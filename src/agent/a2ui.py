# -*- coding: utf-8 -*-
"""src/agent/a2ui.py — 老王生成式 UI 构建层（A2UI v0.9.1 报文，纯标准库）。

红线（design/A2UI-CATALOG.md §0 四条前提在本模块的落点）：
  1. 数据只来自 ops 的返回值，模型不参与生成结构；
  2. 报文里不出现任何样式数值（无 color/fontSize/padding…），外观只从 design/tokens.json 与画布取；
  3. 不生成 confirm/exec 语义 —— 写操作仍只走 pending_actions 票据（I16）；
  4. 行数/列数/格数上限与 APP 侧渲染层同源，全部读自 a2ui_catalog.json。

组件目录与展示配置的唯一真源是 `a2ui_catalog.json`：新增一个 op 的卡片＝往它的 display.ops 里加一行，
不必改本文件。给 agent 的说明书由 scripts/gen_a2ui_llm_instructions.py 从同一份 JSON 生成。
"""
import json
import os

VERSION = "v0.9.1"

with open(os.path.join(os.path.dirname(__file__), "a2ui_catalog.json"), encoding="utf-8") as _f:
    CATALOG = json.load(_f)

CATALOG_ID = CATALOG["catalogId"]
LIMITS = CATALOG["limits"]
MAX_ROWS = LIMITS["maxRows"]          # 与 APP 侧 A2uiRender.MAX_ROWS 同值，由跨语言一致性用例锁住
MAX_CARDS_PER_TURN = 2

DISP = CATALOG["display"]
LABELS = [tuple(x) for x in DISP["labels"]]
LABEL_MAP = dict(LABELS)
MONEY_KEYS = set(DISP["moneyKeys"])
QTY_KEYS = set(DISP["qtyKeys"])
SKIP_KEYS = set(DISP["skipKeys"])
ROWS_KEYS = DISP["rowsKeys"]
OP_CARDS = DISP["ops"]
NO_DISPLAY = set(DISP.get("noDisplay") or [])   # 机器字段：进数据、不上屏
WEIGHT_TOTAL = float(DISP.get("weightTotal") or 10)
# 只有这些 op 不需要参数、纯只读，允许自动补跑一次出卡；search/quick_pricing 等必须带参数，
# 空参重跑会拿回垃圾结果再画成卡 —— 那比不出卡更糟。
SAFE_OPS = set(CATALOG["intent"]["safeOps"])
CARD_OPS = sorted(OP_CARDS)          # 已登记卡片、可被 sx_card 点单的 op

_FORBIDDEN = set(CATALOG["forbiddenEverywhere"])


# ---------------- 报文骨架 ----------------

def _msg(kind, sid, body):
    return {"version": VERSION, kind: dict({"surfaceId": sid}, **body)}


def _surface(sid):
    return _msg("createSurface", sid, {"catalogId": CATALOG_ID})


def _data(sid, path, value):
    return _msg("updateDataModel", sid, {"path": path, "value": value})


def _components(sid, comps):
    return _msg("updateComponents", sid, {"components": comps})


def _col(key, label, weight, align, fmt=None, tone=None):
    c = {"key": key, "label": label, "weight": weight, "align": align}
    if fmt:
        c["format"] = fmt
    if tone:
        c["tone"] = tone
    return c


def _stat(label, path, tone):
    return {"label": label, "value": {"path": path}, "tone": tone}


def _nav(label, route):
    return {"label": label, "event": {"name": "navigate", "context": {"route": route}}}


# ---------------- 形状分析 ----------------

def _num(v):
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def _label(op, key):
    """列名允许被 op 覆盖：debts 的 name 是客户名，不能统一叫「品名」。"""
    return (OP_CARDS.get(op, {}).get("labelOverrides") or {}).get(key) or LABEL_MAP.get(key, key)


def _disp_width(v):
    """显示宽度：中日韩字符按 2 计，其余按 1 计（近似等宽栅格）。"""
    t = "" if v is None else str(v)
    return sum(2 if ord(ch) > 0x2E7F else 1 for ch in t)


def _pick_rows(op, data):
    cfg = OP_CARDS.get(op) or {}
    key = cfg.get("rowsKey")
    if isinstance(data, dict):
        if key and isinstance(data.get(key), list):
            return data[key]
        for k in ROWS_KEYS:
            if isinstance(data.get(k), list):
                return data[k]
        return []
    return data if isinstance(data, list) else []


def _weights(op, keys, rows):
    """列宽＝按实际内容测出来的权重（A2UI 协议只有 weight，没有像素宽度）。

    这是"售价被省略号砍断"的正解：不拍 3:2:1:1:2，而是量出每列最长内容再按比例分配。
    """
    cap = float(DISP.get("measureCapUnits") or 12)
    money_min = float(DISP.get("moneyMinUnits") or 9)
    need = {}
    for k in keys:
        widths = [_disp_width(_label(op, k))] + [_disp_width(r.get(k)) for r in rows if isinstance(r, dict)]
        # 封顶：一列再长也不许吃掉全表；金额：保底够放 "¥6,488.00"，否则又会变成省略号
        need[k] = max(min(max(widths), cap), money_min if k in MONEY_KEYS else 0)
    scale = WEIGHT_TOTAL / (sum(need.values()) or 1)
    return {k: round(max(need[k] * scale, 0.6), 2) for k in keys}  # 金额列下限 2.0：再小就被长文本挤成省略号


def _columns_for(op, rows):
    """只画认识的中文字段，且要求该字段在多数行里有值（脏数据列不占位）。"""
    keys, n = [], max(1, len(rows))
    for k, _lab in LABELS:
        if k in SKIP_KEYS or k in NO_DISPLAY:
            continue
        hit = sum(1 for r in rows if isinstance(r, dict) and r.get(k) not in (None, ""))
        if hit >= max(1, int(n * 0.6)):
            keys.append(k)
        if len(keys) == LIMITS["maxColumns"]:
            break
    return keys


def _flatten_scalars(obj, op=None, prefix="", out=None, depth=0):
    """没有行列表时（status / governance / data_overview）把标量摊平成「项目 / 数值」两列。"""
    out = [] if out is None else out
    if depth > 3 or not isinstance(obj, dict):
        return out
    for k, v in obj.items():
        if k in SKIP_KEYS:
            continue
        if isinstance(v, dict):
            _flatten_scalars(v, op, prefix + _label(op, k) + "·", out, depth + 1)
        elif isinstance(v, bool):
            out.append((prefix + _label(op, k), "是" if v else "否"))
        elif isinstance(v, (int, float, str)) and len(out) < 40:
            out.append((prefix + _label(op, k), v))
    return out


def _stats(op, data, loaded):
    """按 catalog 声明算派生 KPI。from 只支持 total/field/countZero/countEmpty 四种。

    只能在已加载行上算的指标（countZero/countEmpty），标签必须写「本页」，不许冒充全局。
    """
    out, vals = [], {}
    for spec in (OP_CARDS.get(op, {}).get("stats") or []):
        src = spec["from"]
        if src == "total":
            v = data.get("total") if isinstance(data, dict) and isinstance(data.get("total"), int) else len(loaded)
        elif src == "field":
            v = data.get(spec["field"]) if isinstance(data, dict) else None
        elif src == "countZero":
            v = sum(1 for r in loaded if _num(r.get(spec["field"])) == 0)
        elif src == "countEmpty":
            v = sum(1 for r in loaded if not str(r.get(spec["field"]) or "").strip())
        else:
            continue
        if not isinstance(v, (int, float)):
            continue
        key = spec["key"]                      # 语义键，让 /stat 数据模型自描述
        vals[key] = v
        out.append(_stat(spec["label"], "/stat/" + key, spec.get("tone", "primary")))
    return out, vals


# ---------------- 构建 ----------------

def _nav_key(r):
    """行点击导航键：条码优先，无条码回落上游编号（与 APP productDetailNavKey 同口径）。"""
    if not isinstance(r, dict):
        return None
    for k in ("barcode", "sku"):
        v = r.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip()
    return None


def generic_card(op, data, rows_override=None, columns_override=None):
    cfg = OP_CARDS.get(op) or {"title": op}
    all_rows = rows_override if rows_override is not None else _pick_rows(op, data)
    rows = all_rows[:LIMITS["maxRows"]]
    total_key = cfg.get("totalFrom")
    total = data.get(total_key) if total_key and isinstance(data, dict) else None
    if not isinstance(total, int):
        total = len(all_rows) if all_rows else None
    stats, stat_vals = _stats(op, data, all_rows or rows)

    comps = [{"id": "root", "component": "Card", "child": "col"},
             {"id": "title", "component": "Text", "variant": "h3", "text": {"path": "/title"}}]
    kids = ["title"]
    payload = []

    if rows:
        default_columns = cfg.get("columns")
        keys = (
            (columns_override and _checked_columns(op, columns_override))
            or (default_columns and _checked_columns(op, default_columns))
            or _columns_for(op, rows)
        )
        if not keys:
            return None
        w = _weights(op, keys, rows)
        cols = [_col(k, _label(op, k), w[k],
                      "end" if (k in MONEY_KEYS or k in QTY_KEYS) else "start",
                      "money" if k in MONEY_KEYS else ("qty" if k in QTY_KEYS else None),
                      "stock" if k == "stock_qty" else None) for k in keys]
        tbl = {"id": "tbl", "component": "Table", "columns": cols, "rows": {"path": "/rows"}}
        hint = None
        # 行导航键与 APP 的 productDetailNavKey 同口径：条码优先，无条码回落上游编号。
        # 店里近三分之一商品没条码（脏数据只容错不改，铁律 12），旧的"全有码才可行"会废掉整张卡。
        nav_keys = [_nav_key(r) for r in rows]
        if any(nav_keys):
            tbl["rowAction"] = {"event": {"name": "navigate",
                                          "context": {"route": "product", "barcode": {"path": "barcode"}}}}
            hint = {"id": "hint", "component": "Text", "variant": "caption", "maxLines": 2,
                    "text": "点任意一行看该商品详情 · 数据截至提问时"}
        payload = []
        for r, nav in zip(rows, nav_keys):
            row = {k: r.get(k) for k in keys}
            if "barcode" not in row:
                # 无键行留空串：APP 侧空白参数解析不出路由，点击自然不动作
                row["barcode"] = nav or ""
            payload.append(row)
        comps.append(tbl)
        kids.append("tbl")
        if hint:
            comps.append(hint)
            kids.append("hint")
    else:
        pairs = _flatten_scalars(data, op)[:LIMITS["maxRows"]]
        if not pairs:
            return None
        payload = [{"k": k, "v": v} for k, v in pairs]
        comps.append({"id": "tbl", "component": "Table",
                      "columns": [_col("k", "项目", 5, "start"), _col("v", "数值", 4, "end", "text")],
                      "rows": {"path": "/rows"}})
        kids.append("tbl")

    if stats:
        comps.insert(1, {"id": "stats", "component": "StatGrid", "items": stats})
        kids.insert(1, "stats")
    nav = cfg.get("nav")
    if nav:
        comps.append({"id": "nav", "component": "NavCard", "actions": [_nav(nav[0], nav[1])]})
        kids.append("nav")
    comps.insert(1, {"id": "col", "component": "Column", "children": kids})

    # 标题不许出现「共 0 项」这种假数：摊平型数据没有"条数"概念，只报标题
    if total is None or not cfg.get("showTotalInTitle", True):
        title = cfg["title"]
    elif total > len(payload):
        title = "%s · 共 %s 项（卡片显示前 %d 条）" % (cfg["title"], "{:,}".format(total), len(payload))
    else:
        title = "%s · 共 %s 项" % (cfg["title"], "{:,}".format(total))

    sid = (op or "card").replace("-", "_")
    msgs = [_surface(sid), _data(sid, "/title", title)]
    if stat_vals:
        msgs.append(_data(sid, "/stat", stat_vals))
    msgs.append(_data(sid, "/rows", payload))
    msgs.append(_components(sid, comps))
    return msgs


def card_for(op, data, columns=None, sort=None, limit=None):
    """op 的 ops 返回值 → A2UI 消息列表；没东西可画返回 None（宁缺不造）。

    columns/sort/limit 是 agent 的"点单"参数（见 sx_card）：只允许收窄与重排，
    且列名必须已在 catalog 登记 —— 机器字段与成本价点不到，样式永远点不到。
    """
    if op not in OP_CARDS:
        return None
    picked = _pick_rows(op, data)
    if not picked and not _flatten_scalars(data, op):
        return None
    if sort and picked:
        picked = _sorted(picked, sort)
    if limit and picked:
        picked = picked[:min(int(limit), LIMITS["maxRows"])]
    if columns and picked:
        picked = [{k: r.get(k) for k in _checked_columns(op, columns)} for r in picked]
    return _guard(generic_card(op, data, rows_override=picked,
                               columns_override=columns if picked else None))


def _checked_columns(op, columns):
    """点单列名校验：必须登记过、且不是机器字段/成本价。越界直接抛，不做静默降级。"""
    allowed = {k for k, _ in LABELS} - NO_DISPLAY - SKIP_KEYS
    want = [c for c in columns if c in allowed]
    if not want:
        raise ValueError("点单列名全部不可用: %s" % list(columns))
    return want[:LIMITS["maxColumns"]]


def _sorted(rows, spec):
    """spec 形如 "stock_qty" 或 "-sell_price"（前缀 - 为降序）。脏值排最后，绝不抛。"""
    desc = spec.startswith("-")
    key = spec[1:] if desc else spec
    return sorted(rows, key=lambda r: (r.get(key) is None, _num(r.get(key))), reverse=desc)


# ---------------- 触发 ----------------

def op_for_tool(name):
    """serve 侧工具名 → op 名。工具名形如 `sx_low_stock`，也可能被 hermes 加命名空间
    （实测 `mcp__retaildemo__sx_low_stock`）。只认已登记 op 的精确/后缀匹配，认不出返回 None。"""
    n = (name or "").strip()
    for op in OP_CARDS:
        if n in (op, "sx_" + op) or n.endswith("_" + op):
            return op
    return None


def frames_for_tools(tool_names, run):
    return frames_for_ops([o for o in (op_for_tool(t) for t in tool_names or []) if o], run)


def frames_for_ops(ops_ran, run):
    """本轮真跑过的读 op → ui 帧列表。

    `run(op, args)` 由调用方注入（生产传 core.ops.run_op，测试传假函数），本模块因此不 import ops。
    取数失败只跳过这张卡 —— 卡片是增强，回答才是主路，绝不允许把 SSE 流打死。
    """
    frames, seen = [], set()
    for op in ops_ran or []:
        if op in seen or len(seen) >= MAX_CARDS_PER_TURN or op not in SAFE_OPS:
            continue
        seen.add(op)
        try:
            r = run(op, {})
            msgs = None if not isinstance(r, dict) or not r.get("ok") else card_for(op, r.get("data"))
        except Exception:
            continue
        if msgs:
            frames.append({"type": "ui", "messages": msgs})
    return frames


# ---------------- 出网前校验（照 catalog） ----------------

def _guard(msgs):
    """结构、必填、枚举、route 白名单、上限、禁样式 —— 任一不过就抛错。

    抛错＝我们的构建器写坏了（模型不参与生成结构），宁可不出卡也不放半成品下手机。
    """
    for m in msgs:
        for comp in (m.get("updateComponents") or {}).get("components", []):
            _check_component(comp)
    return msgs


def _check_component(comp):
    name = comp.get("component")
    spec = CATALOG["components"].get(name)
    if not spec:
        raise ValueError("catalog 外组件: %s" % name)
    _check_keys(comp, spec["properties"], spec["required"], name)
    _check_enums(comp)

    if name == "Row" and len(comp["children"]) > LIMITS["maxRowChildren"]:
        raise ValueError("Row 子节点超上限: %s" % comp["id"])
    if name == "Table":
        if len(comp["columns"]) > LIMITS["maxColumns"]:
            raise ValueError("Table 列数超上限: %s" % comp["id"])
        sub = CATALOG["subStructures"]["column"]
        for col in comp["columns"]:
            _check_keys(col, sub["properties"], sub["required"], "column")
            _check_enums(col)
        _check_action((comp.get("rowAction") or {}).get("event"), comp["id"])
    if name == "StatGrid":
        if len(comp["items"]) > LIMITS["maxStats"]:
            raise ValueError("StatGrid 格数超上限: %s" % comp["id"])
        sub = CATALOG["subStructures"]["stat"]
        for st in comp["items"]:
            _check_keys(st, sub["properties"], sub["required"], "stat")
            _check_enums(st)
    if name == "NavCard":
        if len(comp["actions"]) > LIMITS["maxActions"]:
            raise ValueError("NavCard 动作数超上限: %s" % comp["id"])
        sub = CATALOG["subStructures"]["navAction"]
        for act in comp["actions"]:
            _check_keys(act, sub["properties"], sub["required"], "navAction")
            _check_action(act.get("event"), comp["id"])


def _check_keys(obj, allowed, required, where):
    extra = set(obj) - set(allowed)
    if extra:
        raise ValueError("%s 带未知字段 %s" % (where, sorted(extra)))
    missing = set(required) - set(obj)
    if missing:
        raise ValueError("%s 缺必填字段 %s" % (where, sorted(missing)))
    forbidden = set(obj) & _FORBIDDEN
    if forbidden:
        raise ValueError("%s 夹带样式字段 %s（外观只从 tokens 取）" % (where, sorted(forbidden)))


def _check_enums(obj):
    for key, kind in (("align", "align"), ("format", "format"), ("tone", "tone"), ("variant", "variant")):
        if key in obj and obj[key] not in CATALOG["enums"][kind]:
            raise ValueError("%s=%r 不在枚举内" % (key, obj[key]))


def _check_action(event, cid):
    if event is None:
        return
    _check_keys(event, CATALOG["action"]["properties"], CATALOG["action"]["required"], "action")
    if event["name"] != "navigate":
        raise ValueError("卡片动作只允许 navigate（%s）" % cid)
    ctx = event["context"]
    route = ctx.get("route")
    if route not in CATALOG["routes"]["allow"]:
        raise ValueError("route %r 未登记白名单（%s）" % (route, cid))
    need = CATALOG["routes"]["args"].get(route, {}).get("required") or []
    missing = [k for k in need if k not in ctx]
    if missing:
        raise ValueError("route %s 缺参数 %s（%s）" % (route, missing, cid))
