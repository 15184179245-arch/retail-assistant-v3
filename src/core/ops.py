# -*- coding: utf-8 -*-
"""src/core/ops.py — 示例零售 op 注册表（F4 老王工具集底座）。

设计（老板 2026-09-05 追加 F4）：
- 所有操作走网关 :18767（包络 6 字段），纯标准库零依赖
- CLI（sx.py）、MCP server（老王）、未来任何端共用同一注册表
- 写操作（checkout 出库）由调用方负责二次确认——本层只执行

用法：
    from core.ops import OPS, run_op
    result = run_op("search", {"q": "螺钉"})
"""
import json
import os
import re
import sqlite3
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
import urllib.request

_gw_env = os.environ.get("retaildemo_GATEWAY", "")
if not _gw_env.startswith("http://127.0.0.1") and not _gw_env.startswith("http://localhost"):
    _gw_env = "http://127.0.0.1:18767"  # F6: env override confined to local gateway
GATEWAY = _gw_env
STAFF = os.environ.get("retaildemo_STAFF", "unknown")
TIMEOUT = int(os.environ.get("retaildemo_TIMEOUT", "30"))
# M4（2026-09-11）：网关严格模式（设了 GATEWAY_AUTH_TOKEN）下所有请求必须带 X-Auth-Token；
# 私服模式（未设）网关不看这个头，带上也无害——所以无条件透传，本层永远不需要改。
AUTH_TOKEN = os.environ.get("GATEWAY_AUTH_TOKEN", "")

OPS = {}


def op(name, desc, params=None, write=False):
    """注册装饰器。params: {参数名: 说明}（供 MCP schema 与 CLI help 复用）。
    write=True 的 op 经 MCP 调用时会被票据闸门拦截（I16：AI 结构上无法自确认）。"""
    def deco(fn):
        OPS[name] = {"name": name, "desc": desc, "params": params or {},
                     "fn": fn, "write": write}
        return fn
    return deco


def _headers(extra=None):
    h = dict(extra or {})
    if AUTH_TOKEN:
        h["X-Auth-Token"] = AUTH_TOKEN
    return h


def _get(path, timeout=TIMEOUT):
    # F6：对最终请求字符串做 scheme+回环白名单校验（env 覆盖仅限本机网关）
    url = GATEWAY + path
    _gw = urllib.parse.urlparse(url)
    if _gw.scheme not in ("http", "https") or _gw.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("blocked gateway: %r" % (url,))
    req = urllib.request.Request(url, headers=_headers())
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


def _post(path, obj, timeout=TIMEOUT):
    url = GATEWAY + path
    _gw = urllib.parse.urlparse(url)
    if _gw.scheme not in ("http", "https") or _gw.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("blocked gateway: %r" % (url,))
    req = urllib.request.Request(
        url, data=json.dumps(obj).encode(),
        headers=_headers({"Content-Type": "application/json"}))
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


# ---------- ops ----------

@op("status", "六服务健康 + 数据规模概览")
def op_status(args):
    h = _get("/health")
    return {"gateway": h}


@op("search", "商品查价检索（支持 min_qty 库存下限）",
    {"q": "关键词（名称/规格/拼音缩写/条码）", "min_qty": "库存下限，可选"})
def op_search(args):
    q = urllib.parse.quote(args.get("q", ""))
    extra = f"&min_qty={args['min_qty']}" if args.get("min_qty") is not None else ""
    return _get(f"/api/search?q={q}{extra}")


def price_band(rows, top=5):
    """纯函数：把一次检索的行聚成价格档——谁最便宜、区间多少、多少条没定价。

    为什么要它：店员问「3P空开最便宜的是哪档」，模型只能拿到被裁过的 20 行命中（库里
    174 条），于是要么跳 5~9 次挨个搜品牌，要么老实说"再低的我不能保底"。一次聚合
    就把结论算完，回执几十字符，2 跳搞定。

    未定价（sell_price 为 0/空）**不参与最低价**：店里上千条售价 0 是"还没录价"，
    当成 0 元报给店员就是错账。这里不改读出来的任何值，只是不参与统计并如实计数
    （数据铁律第 12 条：脏数据只容错，不篡改）。
    """
    priced, unpriced = [], 0
    for r in rows or []:
        p = r.get("sell_price")
        if isinstance(p, (int, float)) and not isinstance(p, bool) and p > 0:
            priced.append((float(p), r))
        else:
            unpriced += 1
    priced.sort(key=lambda x: (x[0], str(x[1].get("name") or "")))
    prices = [p for p, _ in priced]
    n = len(prices)
    if n % 2:
        median = prices[n // 2]
    elif n:
        median = (prices[n // 2 - 1] + prices[n // 2]) / 2
    else:
        median = None

    def brief(row):
        return {k: row.get(k) for k in ("name", "spec", "sell_price", "stock_qty", "unit", "sku")
                if row.get(k) is not None}

    return {
        "success": True,
        "priced": n,
        "unpriced": unpriced,
        "min": round(prices[0], 2) if n else None,
        "max": round(prices[-1], 2) if n else None,
        "median": round(median, 2) if median is not None else None,
        "cheapest": [brief(r) for _, r in priced[:max(1, top)]],
    }


@op("price_band", "价格档统计：一个关键词命中的商品里最低/最高/中位价与最便宜的几档（只读；"
    "未定价的不参与最低价，避免把「还没录价」报成「0 元」）",
    {"q": "关键词（名称/规格/拼音缩写/条码）", "top": "回最低的几档，默认 5，最多 10"})
def op_price_band(args):
    q = urllib.parse.quote(args.get("q", ""))
    try:
        top = max(1, min(int(args.get("top") or 5), 10))
    except (TypeError, ValueError):
        top = 5
    env = _get(f"/api/search?q={q}") or {}
    rows = env.get("data") or []
    out = price_band(rows, top)
    out["keyword"] = env.get("keyword")
    out["hits"] = env.get("total", len(rows))
    out["scanned"] = len(rows)
    if out["scanned"] < out["hits"]:
        out["note"] = (f"检索接口只回了前 {out['scanned']} 条命中（共 {out['hits']} 条），"
                       "统计只覆盖这批")
    return out


@op("ask", "AI 问答（五金知识/进货建议）", {"message": "问题"})
def op_ask(args):
    msg = args.get("message", "")
    return _post("/api/chat", {"messages": [{"role": "user", "content": msg}]}, timeout=120)


@op("knowledge_search", "知识库检索（本店 5 万+ 条五金知识：怎么选/怎么用/配什么/俗称行话/规格参数/安全注意）。"
                        "知识类问题与识货后的补充说明必用它，优先于模型自己的记忆",
    {"q": "问题或商品名（如「生料带怎么缠」「C25空开带多大功率」「角磨片怎么选」）", "limit": "返回条数，默认 5"})
def op_knowledge_search(args):
    q = urllib.parse.quote(args.get("q", ""))
    return _get(f"/api/knowledge/search?q={q}&limit=5")


@op("web_search", "联网搜索（Google 接地，走本机 antigravity 反代）：只查店外信息——"
                  "库里没有的新品参数/品牌行情/知识库也没答上的题。店内的价格库存一律 sx_search/sx_xzg，别联网",
    {"q": "搜索词"})
def op_web_search(args):
    q = str(args.get("q") or "").strip()
    if not q:
        return {"hint": "需要 q 参数（搜索词）"}
    from core.web_search import search
    return search(q)


@op("web_read", "读指定网页（url_context 接地，谷歌侧抓取）：配合 sx_web_search 用——"
                "搜到相关网页后要细看参数表/详情时，把网页地址给它读。只支持公网 http/https 地址",
    {"url": "完整网页地址（含 https://）", "q": "想从这个页面了解什么（可选）"})
def op_web_read(args):
    url = str(args.get("url") or "").strip()
    if not url:
        return {"hint": "需要 url 参数（完整网页地址，含 https://）"}
    from core.web_search import read_url
    return read_url(url, str(args.get("q") or ""))


@op("checkout", "出库扣库存（写操作！调用方须先经店员确认）",
    {"items": "[{product_id, qty}]", "staff": "店员名", "note": "备注"}, write=True)
def op_checkout(args):
    return _post("/api/inventory/checkout", {
        "items": args.get("items", []),
        "staff": args.get("staff", STAFF),
        "note": args.get("note", ""),
    })


@op("print", "打印价签/货架签（走打印服务，需 printer_id 或 printer.ip）",
    {"items": '[{name,price,sku,spec,barcode}]',
     "printer_id": "已登记打印机 ID，可选",
     "type": "price_tag|shelf_label|barcode|receipt，默认 price_tag"}, write=True)
def op_print(args):
    return _post("/api/print/send", {
        "type": args.get("type", "price_tag"),
        "items": args.get("items", []),
        "printer_id": args.get("printer_id", ""),
        "printer": args.get("printer", {}),
    })


@op("todos", "待办清单", {})
def op_todos(args):
    return _get("/api/todos")


@op("todo_create", "新增待办", {"title": "标题"}, write=True)
def op_todo_create(args):
    return _post("/api/todos/create", {
        "title": args.get("title", ""),
        "created_by": STAFF,
    })


@op("low_stock", "库存预警清单", {})
def op_low_stock(args):
    return _get("/api/inventory/low-stock")


@op("knowledge_add", "知识库新增（进审核队列）",
    {"question": "问题", "answer": "答案", "category": "类目"}, write=True)
def op_knowledge_add(args):
    return _post("/api/knowledge/add", {
        "question": args.get("question", ""),
        "answer": args.get("answer", ""),
        "category": args.get("category", ""),
        "source": "agent",
    })


# ---------- 批量清单报价（quick_pricing，老王3 方法借鉴：确定性并发循环，零 LLM） ----------

@op("quick_pricing", "批量清单报价（一次提交整单，并发逐项匹配，返回单价/小计/合计；价格 0 或未命中自动标 needs_review）",
    {"items": '[{"name": "单86盒", "qty": 18}, ...]', "limit": "每项候选上限，默认 5"})
def op_quick_pricing(args):
    items_in = args.get("items") or []
    limit = int(args.get("limit", 5))

    def price_one(it):
        if isinstance(it, str):
            it = {"name": it, "qty": 1}
        name = (it.get("name") or "").strip()
        try:
            qty = int(it.get("qty", 1) or 1)
        except (TypeError, ValueError):
            qty = 1
        base = {"query": name, "qty": qty}
        if not name:
            return {**base, "needs_review": True, "reason": "空名称"}
        r = _get(f"/api/search?q={urllib.parse.quote(name)}&limit={limit}")
        data = r.get("data") if isinstance(r, dict) else None
        cands = data.get("items") if isinstance(data, dict) else data
        if not isinstance(cands, list):
            cands = []
        toks = [t for t in re.split(r"[\s*×x]+", name) if t]
        denom = max(sum(len(t) for t in toks), len(name.replace(" ", "")), 1)

        def cover_of(pname):
            hit = sum(len(t) for t in toks if t in pname)
            return hit / denom

        ranked = sorted(
            cands,
            key=lambda c: (
                cover_of(c.get("name") or ""),
                1 if (c.get("stock_qty") or 0) > 0 else 0,
                -len(c.get("name") or ""),
            ),
            reverse=True,
        )
        if not ranked:
            return {**base, "needs_review": True, "reason": "未命中"}
        best = ranked[0]
        cov = cover_of(best.get("name") or "")
        price = best.get("sell_price") or 0
        stock = best.get("stock_qty") or 0
        low_conf = cov < 0.5  # 低置信：疑似俗称/规格写法差异，交人工或老王语义复核
        return {**base, "matched_name": best.get("name"), "sku": best.get("sku"),
                "price": price, "subtotal": round(price * qty, 2), "stock": stock,
                "category": best.get("category"), "confidence": round(cov, 2),
                "needs_review": low_conf or price == 0}

    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(price_one, items_in))
    priced = [x for x in results if x.get("sku")]
    grand = round(sum(x.get("subtotal", 0) for x in priced), 2)
    review = [x["query"] for x in results if x.get("needs_review")]
    return {"items": results, "grand_total": grand,
            "hit": len(priced), "needs_review": review}


# ---------- 欠款/治理/推荐/识货（L8 老王工具面补齐，全部只读） ----------

def _data_db_path(env_names, filename):
    """数据 DB 解析：显式 env 优先（须真实存在），否则仓库 data/ 下默认位。"""
    for env in env_names:
        p = os.environ.get(env, "")
        if p and os.path.exists(p):
            return p
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return os.path.join(repo, "data", filename)


def _connect_ro(path):
    return sqlite3.connect("file:" + path.replace(os.sep, "/") + "?mode=ro", uri=True)


def aggregate_debts(bills, top=5):
    """纯函数：账单 dicts → 挂账欠款汇总（pending 口径，欠款=total-paidAmount）。"""
    debts = {}
    pending = 0
    for b in bills:
        if b.get("status") != "pending":
            continue
        pending += 1
        try:
            total = float(b.get("total", 0))
        except (TypeError, ValueError):
            total = 0.0
        try:
            paid = float(b.get("paidAmount", 0) or 0)
        except (TypeError, ValueError):
            paid = 0.0
        name = (b.get("customer_name") or b.get("customerName") or "散客").strip() or "散客"
        debts[name] = debts.get(name, 0.0) + (total - paid)
    ranked = sorted(debts.items(), key=lambda kv: -kv[1])
    return {
        "pending_count": pending,
        "total_outstanding": round(sum(debts.values()), 2),
        "top": [{"name": n, "amount": round(v, 2)} for n, v in ranked[:max(int(top or 5), 1)]],
    }


def _is_priced(cand):
    """sell_price 缺失或 ≤0 视为未定价（2026-09-27 看图复盘：老王曾照报「0元」假价）。"""
    price = cand.get("sell_price")
    return isinstance(price, (int, float)) and price > 0


def pick_candidate(cands):
    """纯函数：候选挑选序 有货有价 > 有货 > 有价 > 第一条；空返回 None。

    2026-09-27 看图复盘：未定价的泛品类卡曾按「有货优先」顶上首位，
    老王照报「0元」假价——挑选序补价格维度。"""
    if not isinstance(cands, list) or not cands:
        return None

    def stocked(c):
        return (c.get("stock_qty") or 0) > 0

    for want_stock, want_price in ((True, True), (True, False), (False, True)):
        for c in cands:
            if stocked(c) == want_stock and _is_priced(c) == want_price:
                return c
    return cands[0]


def dedupe_candidates(items, cap=10):
    """纯函数：按 sku 去重（有货优先已由候选序保证），截前 cap。"""
    seen = set()
    out = []
    for it in items:
        sku = it.get("sku")
        if not sku or sku in seen:
            continue
        seen.add(sku)
        out.append(it)
        if len(out) >= cap:
            break
    return out


@op("debts", "欠款清单（挂账口径，bills.db 只读）", {"top": "返回前 N 名客户，默认 5"})
def op_debts(args):
    p = _data_db_path(("retaildemo_BILLS_DB", "BILLS_DB_PATH"), "bills.db")
    empty = {"pending_count": 0, "total_outstanding": 0.0, "top": []}
    if not os.path.exists(p):
        return {**empty, "hint": f"账单库不存在: {p}"}
    try:
        con = _connect_ro(p)
        rows = con.execute("SELECT data_json FROM bills").fetchall()
        con.close()
    except sqlite3.Error as e:
        return {**empty, "hint": f"账单库只读失败: {e}"}
    bills = []
    for (raw,) in rows:
        try:
            bills.append(json.loads(raw))
        except (json.JSONDecodeError, TypeError):
            continue
    return aggregate_debts(bills, top=args.get("top", 5))


@op("governance", "数据治理进度（无码标记/副码绑定/多码裁决 计数，只读）", {})
def op_governance(args):
    out = {}
    con = _connect_ro(_data_db_path(("retaildemo_WUJIN_DB", "WUJIN_DB_PATH"), "wujin.db"))
    try:
        out["barcode_alias"] = con.execute("SELECT COUNT(*) FROM barcode_alias").fetchone()[0]
        out["alias_audit"] = con.execute("SELECT COUNT(*) FROM alias_audit").fetchone()[0]
    finally:
        con.close()
    marks = {}
    bp = _data_db_path(("retaildemo_BILLS_DB", "BILLS_DB_PATH"), "bills.db")
    if os.path.exists(bp):
        try:
            con = _connect_ro(bp)
            has = con.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='data_marks'").fetchone()
            if has:
                for st, n in con.execute(
                        "SELECT status, COUNT(*) FROM data_marks GROUP BY status"):
                    marks[st or "unknown"] = n
            con.close()
        except sqlite3.Error:
            marks = {"error": "读取失败"}
    out["data_marks"] = marks
    out["hint"] = "temp_products 在 APP 域（Room），服务端不可见；无码货以 data_marks/alias 口径为准"
    return out


@op("data_overview", "老王数据全景（S13：账/客/货/治/行 五域聚合，含 APP 孤岛表镜像，只读）", {})
def op_data_overview(args):
    h = _get("/api/laowang/overview", timeout=15)
    if not h.get("success"):
        return {"error": h.get("error") or h.get("user_message") or "overview failed"}
    return h.get("data") or {}


@op("reco", "连带推荐（搭配规则 + 在售真实商品对照）", {"q": "商品名/关键词（如 角阀）", "limit": "每个推荐项候选数，默认 2"})
def op_reco(args):
    q = (args.get("q") or "").strip()
    if not q:
        return {"rules": [], "items": [], "hint": "需要 q 参数"}
    limit = max(int(args.get("limit", 2) or 2), 1)
    con = _connect_ro(_data_db_path(("retaildemo_WUJIN_DB", "WUJIN_DB_PATH"), "wujin.db"))
    try:
        rows = con.execute(
            "SELECT trigger_product, recommended_products_json, reason, type "
            "FROM recommendation_rules WHERE trigger_product LIKE ? OR keywords_json LIKE ? "
            "ORDER BY priority DESC LIMIT 5", (f"%{q}%", f"%{q}%")).fetchall()
    finally:
        con.close()
    if not rows:
        return {"rules": [], "items": [], "hint": "无搭配规则命中，可用 sx_search 自查"}
    rules, names = [], []
    for trig, recs_json, reason, rtype in rows:
        try:
            recs = json.loads(recs_json or "[]")
        except json.JSONDecodeError:
            recs = []
        rules.append({"trigger": trig, "type": rtype, "reason": reason, "recommended": recs})
        names.extend(r for r in recs if r not in names)

    def lookup(name):
        try:
            r = _get(f"/api/search?q={urllib.parse.quote(name)}&limit={limit}")
        except Exception as e:  # noqa: BLE001
            return {"query": name, "hint": f"检索失败: {type(e).__name__}"}
        data = r.get("data") if isinstance(r, dict) else None
        cands = data.get("items") if isinstance(data, dict) else data
        best = pick_candidate(cands if isinstance(cands, list) else [])
        if not best:
            return {"query": name, "hint": "未命中在售商品"}
        return {"query": name, "matched_name": best.get("name"), "sku": best.get("sku"),
                "price": best.get("sell_price"), "stock": best.get("stock_qty") or 0,
                "unpriced": not _is_priced(best)}

    with ThreadPoolExecutor(max_workers=6) as ex:
        items = list(ex.map(lookup, names[:12]))
    return {"rules": rules, "items": items}


@op("identify", "识货对照（拍照识别候选词 → 商品库真实候选；图像识别本身由 Hermes vision 完成）",
    {"names": '["候选词1","候选词2"] 拍照识别出的名称列表', "limit": "每词候选数，默认 3"})
def op_identify(args):
    names = args.get("names") or []
    if isinstance(names, str):
        try:
            names = json.loads(names)
        except json.JSONDecodeError:
            names = [names]
    names = [str(n).strip() for n in names if str(n).strip()]
    if not names:
        return {"candidates": [], "unmatched": [], "hint": "需要 names 参数（JSON 数组）"}
    limit = max(int(args.get("limit", 3) or 3), 1)

    def lookup(name):
        try:
            r = _get(f"/api/search?q={urllib.parse.quote(name)}&limit={limit}")
        except Exception as e:  # noqa: BLE001
            return [], name
        data = r.get("data") if isinstance(r, dict) else None
        cands = data.get("items") if isinstance(data, dict) else data
        return (cands if isinstance(cands, list) else []), None

    with ThreadPoolExecutor(max_workers=6) as ex:
        pairs = list(ex.map(lookup, names[:8]))
    flat, unmatched = [], []
    for cands, miss in pairs:
        if miss:
            unmatched.append(miss)
            continue
        best = pick_candidate(cands)
        if not best:
            unmatched.append("?")
            continue
        flat.append({"matched_name": best.get("name"), "sku": best.get("sku"),
                     "price": best.get("sell_price"), "stock": best.get("stock_qty") or 0,
                     "category": best.get("category"),
                     "unpriced": not _is_priced(best)})
    out = {"candidates": dedupe_candidates(flat),
           "unmatched": [u for u in unmatched if u != "?"]}
    if any(it.get("unpriced") for it in out["candidates"]):
        # 2026-09-27 看图复盘：0 元＝未定价不是真价格，老王必须照实说
        out["hint"] = "unpriced=true 表示库内未定价：回答必须说「库内未定价」，禁止报「0元」"
    return out


# ---------- 管家婆云实盘查询（2026-09-19 老板令「加入管家婆接口查询」）----------
# 只读（白名单端点见 sync/xzg_client.py）；wujin.db 是同步快照，实时价/库存以此为准。
# 单端点方案：库存状况表 StockCondition/Get + keyword（实测编号/条码精确命中单行），
# 一行 71 字段自带 库存/零售价/进价/会员价/批发价 —— 免 sku→上游雪花 id 映射。
# 凭据只走 XZG_PASSWORD 环境变量（与同步链路同一纪律：不入库、不写日志）。


@op("xzg", "管家婆云实盘查询（只读·价格权威）：按商品编号/条码查实时库存与全档实时价"
          "（零售/进价/会员/批发）。wujin.db 是同步快照，两者冲突时以本查询为准",
    {"keyword": "商品编号或条码（先用 sx_search 获取 sku/条码；上游按关键词精确过滤）",
     "limit": "最多返回行数，默认 5"})
def op_xzg(args):
    args = args or {}
    keyword = str(args.get("keyword") or "").strip()
    if not keyword:
        raise ValueError("keyword 不能为空（先用 sx_search 拿 sku/条码）")
    try:
        limit = max(1, min(int(args.get("limit") or 5), 20))
    except (TypeError, ValueError):
        limit = 5
    from sync.xzg_client import XzgClient  # 纯标准库，惰性导入
    client = XzgClient(verbose=False)
    result = client.fetch_stock_condition_page(0, limit, keyword=keyword)
    rows = []
    for r in result.get("items") or []:
        if not isinstance(r, dict):
            continue
        rows.append({
            "code": r.get("productCode"),
            "name": r.get("productName"),
            "barcode": r.get("barCode") or r.get("productBarCode"),
            "spec": r.get("standardName") or r.get("spec"),
            "stockQty": r.get("stockQty"),
            "retailPrice": r.get("retailPrice"),
            "buyingPrice": r.get("buyingPrice"),
            "memberPrice": r.get("memberPrice"),
            "wholesalePrice": r.get("wholesalePrice"),
            "brand": r.get("brandName"),
        })
    return {"keyword": keyword, "total": result.get("totalCount"), "rows": rows}


# ---------- 执行 ----------

@op("pending_create", "写操作出票（MCP 层调用：操作不执行，出待确认票据）",
    {"op": "op 名", "args": "参数 dict", "session_id": "app 会话", "staff": "店员名"})
def op_pending_create(args):
    from agent.pending_store import WRITE_OPS
    op_name = args.get("op", "")
    if op_name not in WRITE_OPS:
        return {"error": f"非写操作不允许出票: {op_name}"}
    # MCP 子进程 → HTTP 打回网关进程内的票据库（唯一权威）
    return _post("/api/agent/pending", {
        "op": op_name,
        "args": args.get("args") or {},
        "session_id": args.get("session_id", ""),
        "staff": args.get("staff", STAFF),
        "summary": f"写操作待确认：{op_name}",
    })


@op("card", "把某个只读查询以卡片形式发给店员（不返回数据，只登记渲染请求）。"
             "列名/可点 op 白名单见系统提示里的「点单」段；不在名单内的会被丢弃。",
    {"op": "只读 op 名（见卡片白名单）",
     "columns": "要显示的字段名，逗号分隔；只能点目录里登记过的列名（可省，默认自动选）",
     "sort": "排序字段名，前缀 - 为降序，如 -stock_qty（可省）",
     "limit": "行数，1-5 的整数（可省）",
     "session_id": "app 会话"})
def op_card(args):
    from agent.a2ui import CARD_OPS
    op_name = args.get("op", "")
    if op_name not in CARD_OPS:
        return {"error": f"该查询没有卡片配置: {op_name}；可用: {', '.join(CARD_OPS)}"}
    cols = args.get("columns") or []
    if isinstance(cols, str):
        cols = [c.strip() for c in cols.split(",") if c.strip()]
    limit = args.get("limit")
    try:
        limit = int(limit) if limit not in (None, "") else None
    except (TypeError, ValueError):
        return {"error": "limit 必须是 1-5 的整数"}
    # MCP 子进程 → HTTP 打回网关进程内的待渲染队列（与写票据同一套通道）
    return _post("/api/agent/ui", {
        "op": op_name,
        "columns": cols,
        "sort": args.get("sort") or "",
        "limit": limit,
        "session_id": args.get("session_id", ""),
        "staff": STAFF,
    })


def run_op(name, args=None):
    """执行 op，返回统一结果 {ok, data|error}。未知 op 返回 ok=False。"""
    entry = OPS.get(name)
    if entry is None:
        return {"ok": False, "error": f"未知操作: {name}", "available": sorted(OPS)}
    try:
        data = entry["fn"](args or {})
        return {"ok": True, "data": data}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"{type(e).__name__}: {e}"}
