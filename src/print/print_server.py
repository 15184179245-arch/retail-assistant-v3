"""Offline extract of the V3 label planner and encoders; no network or printer I/O."""
import re

_LINE_H = 24          # TSS24.BF2 @1x 单行高（dot）
_BORDER_INSET = 4     # 外框内缩（dot），防骑纸边
_BOLD_XMUL = 2        # 名称/价格加粗：横向 2 倍拉伸（占宽/行高同步放大才不溢出）
_ELLIPSIS = "…"
_OFFSET_MAX_MM = 2.0  # 打印偏移容错上限（mm）
_DEAD_ZONE_X = 3      # 边缘死区（dot，水平 ≈0.4mm）：任何元素不得进入，防切边
_DEAD_ZONE_Y = 2      # 边缘死区（dot，纵向 ≈0.25mm）
_OFFSET_OUT_MAX = 16  # 向外（朝纸边）允许的最大平移量（dot，= _OFFSET_MAX_MM 的 2mm）：
                      # 打印头/走纸偏差是**双向**的，UI 既然允许 ±2mm，容错窗口就得给到 2mm；
                      # 出不出纸由后面的死区夹紧兜底，这里不再额外收紧（否则配 2mm 实际只走 0.5mm）


def _disp_w(s: str) -> int:
    """TSS24.BF2 @1x 显示宽：CJK/全角=24dot，ASCII=12dot。"""
    return sum(24 if ord(c) > 0x7F else 12 for c in s)


def _sanitize(s: str) -> str:
    """清理打印机吃不下或会破坏指令流的字符（换行是元素的职责，不是字符的）。"""
    return "".join(c for c in s if c not in "\r\n\t" and ord(c) >= 0x20)


def _fit_lines(text: str, max_dots: int, max_lines: int, mul: int = 1) -> list[str]:
    """按显示宽贪婪断行；装不下时末行省略号收尾（换行代替截断）。max_dots 为该字号下的可用宽度。"""
    clean = _sanitize(text)
    if not clean or max_lines <= 0 or max_dots <= 0:
        return []
    budget = max_dots // max(mul, 1)
    wrapped: list[str] = []
    cur, w = "", 0
    for ch in clean:
        cw = 24 if ord(ch) > 0x7F else 12
        if w + cw > budget and cur:
            wrapped.append(cur)
            cur, w = "", 0
        cur += ch
        w += cw
    if cur:
        wrapped.append(cur)
    if len(wrapped) <= max_lines:
        return wrapped
    kept = wrapped[:max_lines]
    last, lw = "", 0
    for ch in kept[-1]:
        cw = 24 if ord(ch) > 0x7F else 12
        if lw + cw + 24 > budget:
            break
        last += ch
        lw += cw
    kept[-1] = last + _ELLIPSIS
    return kept


def _fits_fully(text: str, max_dots: int, max_lines: int, size: int = 1) -> bool:
    """文本在 size 倍率下是否完整落进 max_lines 行（无省略号收口）。"""
    clean = _sanitize(text)
    if not clean:
        return True
    budget = max_dots // max(size, 1)
    lines, w = 1, 0
    for ch in clean:
        cw = 24 if ord(ch) > 0x7F else 12
        if w + cw > budget:
            lines += 1
            w = 0
        w += cw
    return lines <= max_lines


def _fit_for_box(text: str, max_dots: int, max_lines: int, max_size: int = 1,
                 min_size: int = 1, xmul: int = 1) -> tuple[int, list[str]]:
    """把文本装进「max_lines 行 × max_dots 宽」盒子：先取装得下的最大字号，真放不下才按 min_size 缩号收口。
    [xmul]=横向拉伸倍率（加粗行占宽同步放大）；返回的字号是**倍率前的字号**。"""
    clean = _sanitize(text)
    if not clean or max_lines <= 0 or max_dots <= 0:
        return 1, []
    lo = max(min_size, 1)
    for size in range(max(max_size, lo), lo - 1, -1):
        if _fits_fully(clean, max_dots, max_lines, size * xmul):
            return size, _fit_lines(clean, max_dots, max_lines, size * xmul)
    return lo, _fit_lines(clean, max_dots, max_lines, lo * xmul)


def _fit_scale(text: str, max_dots: int, preferred: int, minimum: int = 1) -> int:
    """在 [minimum, preferred] 里取显示后不超 max_dots 的最大字号倍率。"""
    one = _disp_w(_sanitize(text))
    for m in range(preferred, minimum - 1, -1):
        if one * m <= max_dots:
            return m
    return minimum


def _label_for(label: str, value: str, col_w: int, xmul: int = 1, max_size: int = _BOLD_XMUL) -> str:
    """标签自适应（可读性优先，实物验证后定稿）：

    ① 值在 2 行内完整放下 → 取**最长**标签（最紧凑好看）；
    ② 不行就放宽到 3 行、4 行（仍取最长标签——先保标签可读）；
    ③ 再放不下 → 取 2 字标签（保证值至少显示一段，避开实物上【称】【格】这种残标签）；
    ④ 连 2 字都放不下 → 单字。
    代价明确：窄纸上标签会让位给值（值优先完整），但永不留单字残标签。
    """
    clean = _sanitize(value)
    if not clean:
        return label
    cands = [label] + [label[i:] for i in range(1, len(label))] + [label[-1:]]
    for line_budget in (2, 3, 4):
        for lab in cands:
            if len(lab) < 2:
                continue
            wv = col_w - _disp_w("【%s】" % lab)
            if wv <= 0:
                continue
            for size in range(max_size, 0, -1):
                if _fits_fully(clean, wv, line_budget, size * xmul):
                    return lab
    for lab in cands:
        if len(lab) <= 2:
            return lab
    return cands[-1]


def _label_text(label: str, value: str, col_w: int, xmul: int = 1, max_size: int = _BOLD_XMUL) -> str:
    """行标签的最终渲染文本（含【】）；与占宽计算共用，避免「量宽/打宽」不一致。"""
    return "【%s】" % _label_for(label, value, col_w, xmul, max_size)


def _apply_offset(ops: list, dx_mm: float, dy_mm: float, width_dots: int, height_dots: int) -> list:
    """打印偏移容错：整版平移 (dxMm, dyMm) 并夹紧到边缘死区。

    打印机机械偏差（打印头起始位/走纸/纸在胶辊上横向错位）表现为整版偏移，统一平移即可对齐；
    死区 + 向外限量保证「无论怎么配，外框与文字都不进边缘死区、不越出纸面」。
    (0,0) 时原样返回（老任务/老配置零变化）。
    """
    if not ops or (dx_mm == 0 and dy_mm == 0):
        return ops
    # round 而非 int：int(-0.8)==0 会把「负向偏移」吞掉（-0.5mm×8=-4dot 必须生效）
    raw_dx = round(max(-_OFFSET_MAX_MM, min(_OFFSET_MAX_MM, dx_mm)) * 8)
    raw_dy = round(max(-_OFFSET_MAX_MM, min(_OFFSET_MAX_MM, dy_mm)) * 8)
    if raw_dx == 0 and raw_dy == 0:
        return ops
    boxes = [_op_box(op) for op in ops]
    min_x = min(b[0] for b in boxes)
    max_x = max(b[1] for b in boxes)
    min_y = min(b[2] for b in boxes)
    max_y = max(b[3] for b in boxes)
    out_max_x = min(_OFFSET_OUT_MAX, max(min_x - _DEAD_ZONE_X, 0))
    out_max_y = min(_OFFSET_OUT_MAX, max(min_y - _DEAD_ZONE_Y, 0))
    raw_dx = min(raw_dx, out_max_x) if raw_dx > 0 else max(raw_dx, -out_max_x)
    raw_dy = min(raw_dy, out_max_y) if raw_dy > 0 else max(raw_dy, -out_max_y)
    dx = max(_DEAD_ZONE_X - min_x, min(raw_dx, (width_dots - _DEAD_ZONE_X) - max_x))
    dy = max(_DEAD_ZONE_Y - min_y, min(raw_dy, (height_dots - _DEAD_ZONE_Y) - max_y))
    if dx == 0 and dy == 0:
        return ops
    out = []
    for op in ops:
        if op[0] == "txt":
            base = list(op[:5])
            base[1] += dx
            base[2] += dy
            out.append(tuple(base + list(op[5:])))
        elif op[0] == "ln":
            out.append(("ln", op[1] + dx, op[2] + dy, op[3] + dx, op[4] + dy))
        elif op[0] == "qr":
            out.append(("qr", op[1] + dx, op[2] + dy, op[3], op[4]))
        else:
            out.append(("bar", op[1] + dx, op[2] + dy, op[3], op[4], op[5]))
    return out


def _op_box(op: tuple) -> tuple:
    """元素包围盒 (left, right, top, bottom)（dot）——偏移容错（`_apply_offset`）的判据。

    二维码有两处特殊（测试反馈 2026-09-11 实物反馈）：
    1. **边长按真实模块数 × 码格**（`_qr_modules`），不再硬估 30 模块——短码虚大 1.43 倍
       会把偏移量夹掉，长数据则虚小、偏移后可能出纸；
    2. **向外多算 4 个模块的静默边**，因为静默边是码的组成部分：QR 规范要求四周留
       4 模块白，平移容错若把白边顶掉，码贴到纸边就扫不出来。
    """
    if op[0] == "txt":
        xmul = op[5] if len(op) > 5 else op[3]
        return (op[1], op[1] + _disp_w(op[4]) * xmul, op[2], op[2] + 24 * max(op[3], xmul))
    if op[0] == "ln":
        return (min(op[1], op[3]), max(op[1], op[3]), min(op[2], op[4]), max(op[2], op[4]))
    if op[0] == "qr":
        side = _qr_modules(op[4]) * op[3]
        quiet = 4 * op[3]   # QR 规范 4 模块静默边，与布局侧口径一致
        return (op[1] - quiet, op[1] + side + quiet, op[2] - quiet, op[2] + side + quiet)
    return (op[1], op[1] + 200, op[2], op[2] + op[3])


def _bar_narrow(data: str, col_dots: int) -> int:
    """CODE128 窄条宽：约 11 模块/字符+起止校验，取能塞进列宽的最大值（1~3dot）。"""
    modules = 11 * (min(len(data), 20) + 2)
    if modules * 3 <= col_dots:
        return 3
    if modules * 2 <= col_dots:
        return 2
    return 1


def _row_height(label: str, value: str, col_w: int, max_lines: int, preferred: int,
                min_size: int, scale: int, xmul: int) -> int:
    """该行定稿行高（与渲染同口径）：值宽 = 列宽 − 标签渲染宽（含【】）。

    · 价格：字号=整版 scale（醒目），按整数倍率单行量高；放不下则独占两行。
    · 其余：取仍能完整装下的最大字号（缩到 min_size 仍超则末行省略号）。
    <0=无值不可用（空单位等）。
    """
    if not label or not value or col_w <= 0:
        return -1
    label_w = _disp_w(_label_text(label, value, col_w, xmul))
    if label == "零售价":
        # 值宽 = 列宽 − 真实标签宽（与渲染同口径）；放不下就按两行量高
        wv_price = col_w - label_w
        size = max(scale, max(preferred, 1))
        if wv_price > 0 and _fits_fully(value, wv_price, 1, size):
            return size * _LINE_H * scale + 2
        return 2 * _LINE_H * scale + 2
    line_cap = max(max_lines, 1)
    wv = col_w - label_w
    if wv > 0:
        for size in range(max(preferred, min_size), min_size - 1, -1):
            g = size * xmul
            if _fits_fully(value, wv, line_cap, g):
                n = max(len(_fit_lines(value, wv, line_cap, g)), 1)
                return n * size * _LINE_H * scale + 2
    n = max(len(_fit_lines(value, max(wv, 1), line_cap, min_size * xmul)), 1)
    return n * min_size * _LINE_H * scale + 2


def _fit_rows(rows: list, col_w: int, height_budget: int, scale: int):
    """贪心装行：放得下返回保留的行（行高按 scale 定稿），装不下按 z（大到小）让位；
    protected 行（名称/价格）永不让位；保命行也放不下返回 None。"""
    if height_budget < _LINE_H:
        return None
    kept = list(rows)

    def measure():
        total = 0
        for r in kept:
            hh = _row_height(r["label"], r["value"], col_w, r["max_lines"], r["preferred"], r["min_size"],
                             scale, r["xmul"])
            if hh < 0:
                return None
            r["height"] = hh
            total += hh
        return total

    total = measure()
    while total is not None and total > height_budget:
        drop_at = next((i for i in range(len(kept) - 1, -1, -1) if not kept[i]["protected"]), -1)
        if drop_at < 0:
            return None
        kept.pop(drop_at)
        total = measure()
    return kept if total is not None else None


#: QR 模块数估算表：(数据字符数上限, 模块数)。按 ECC=M、字母数字模式容量取**上界**——
#: 数据再长也只会更小，给码图留位宁多勿少（右对齐的码若超出预留，会向左长出去压到文字）。
_NAME_BRACKET = re.compile(r"[（(]")
_NAME_PACK_TAIL = re.compile(r"[-—/]\s*\d+\s*(枚|只|个|台|件|包|卷|筒|箱|米|对|套|支|把|片)\s*$")
#: 名称里嵌着的规格/型号（保守：必须有数字；型号还要求夹在中文之间，
#: 避免误伤「LED」「PP」这类纯字母品类词）
_NAME_SPEC = re.compile(
    r"φ\s*\d+(?:\.\d+)?(?:\s*[*×xX]\s*\d+(?:\.\d+)?)*"                                 # φ390*108
    r"|(?:\d+(?:\.\d+)?\s*[A-Za-z]{0,3}\s*[*×xX]\s*)+\d+(?:\.\d+)?\s*[A-Za-z]{0,3}"    # 40*50mm / 300X600 / 18mm*30y*38um
    r"|\d+(?:\.\d+)?\s*(?:W|V|A|mA|kg|g|Hz|K)(?![A-Za-z0-9])"                          # 24W、6500K
    r"|\d+(?:\.\d+)?\s*(?:mm|cm|寸|米|匹)"                                              # 1.8米
    r"|(?<=[\u4e00-\u9fff])[A-Za-z]{1,5}-?\d{2,}[A-Za-z]*"                              # 型号 DY-283G、ZG301
)


def _short_name(name: str, max_dots: int = 0):
    """整理商品名（价签/展示专用，**绝不回写数据库**——铁律 12：脏数据只容错、不篡改）。

    上游普遍把「品名 + 型号 + 规格 + 配件说明 + 包装数量」一股脑塞进名称字段
    （如「大艺DY-283G三线LD绿光水平仪/配超黑锂电池/12台/件（只修不退不换）」），
    真正认得出来的是最前面那段。这里按分隔符 + 模式把次要部分摘掉。

    返回 `(整理后的名, 摘出来的规格型号串)`——摘出来的部分不丢，补到规格行去。
    实测（12650 条真实商品）：最长名 64 → 27 半角位，≤24 半角位覆盖率 93.8% → 95.7%。
    """
    raw = (name or "").strip()
    if not raw:
        return "", ""
    s = raw.split("/")[0]                  # 斜杠后面是配件/包装说明
    s = _NAME_BRACKET.split(s)[0]          # 括号里是补充规格
    hits = [m.group(0).strip() for m in _NAME_SPEC.finditer(s)]
    if hits:
        s = _NAME_SPEC.sub("", s)  # 直接删掉，不留空格（否则名称中间会出现断口）
    s = _NAME_PACK_TAIL.sub("", s).strip(" -—/#、,·")
    # 仍超预算：按空格切开，取「中文最多」的那段（比取第一个安全——「LED 直发光平板灯」
    # 的第一段是 "LED"，中间那段才是品名）
    if max_dots > 0 and _disp_w(s) > max_dots:
        segs = [x for x in re.split(r"[\s　]+", s) if x]
        if len(segs) > 1:
            best = max(segs, key=lambda x: (sum(1 for c in x if "\u4e00" <= c <= "\u9fff"), len(x)))
            if _disp_w(best) < _disp_w(s):
                s = best
    return (s or raw), "、".join(dict.fromkeys(hits))


#: QR 模块数估算表：(数据字符数上限, 模块数)。按 ECC=M、字母数字模式容量取**上界**——
#: 数据再长也只会更小，给码图留位宁多勿少（右对齐的码若超出预留，会向左长出去压到文字）。
_QR_CAPACITY = ((20, 21), (38, 25), (61, 29), (90, 33), (122, 37), (154, 41))





def _qr_modules(data: str) -> int:
    """估 QR 码模块数：正方形边长 = 模块数 × 码格（TSPL 的 QRCODE 第 4 参就是码格）。"""
    n = len(data or "")
    for cap, mod in _QR_CAPACITY:
        if n <= cap:
            return mod
    return _QR_CAPACITY[-1][1]


def _price_tag_ops(name: str, price: str, sku: str, spec: str = "", barcode: str = "",
                   width_mm: int = 30, height_mm: int = 40, template: dict = None,
                   unit: str = "") -> list:
    """价签版式计划：左侧「值」若干行 + 右下角码图（测试反馈 2026-09-11 参考实物 + 优化）。

    ★ 测试反馈定调：参考实拍「只是参考，实际还能优化」。相对参考实拍做了三处优化：

    1. **价格放大成大字**（2~3×，在余量里自动取档）——参考实拍五行字一样大，
       价格不突出；价签的核心是「品名 + 价格」，价格行按幅面吃到最大字号。
       价格行因此**不带「零售价:」前缀**（大字数字本身就是价格的通用写法，
       省下的宽度全给字号）。
    2. **无外框、无【】括号、不打字段名**——参考实拍是白底裸版式；
       只有「规格」行带半角冒号前缀（规格值本身不自明，其余一眼能认）。
    3. **整行要么完整要么让位，绝不印半截残字**——测试反馈实物上的
       「｜品名称】」就是残字。1× 下装不进 `max_lines` 的非保护行整行让位
       （如窄纸上的长编号，与其折成「20.01.05.006/3」不如交给二维码承载），
       让位优先级 单位 → 编号 → 规格；名称/价格永不让位。

    顺序固定：商品编号 → 商品名称 → 规格: → 价格（大字，**单位紧跟其后**，不单独成行）。
    **名称与价格是两张主信息大字**：名称能等比例放大就放大（测试反馈 2026-09-11「名称醒目」），
    价格吃最大字号并可为其让位次要行；辅助行（编号/规格）恒 1×。

    op 元组: ("txt",x,y,ymul,text,xmul=ymul) ("ln",x1,y1,x2,y2) ("qr",x,y,cell,data)
             ("bar",x,y,h,narrow,data)
    """
    t = template if isinstance(template, dict) else {}
    store_name = str(t.get("store_name", "") or "")
    show_spec = bool(t.get("show_spec", True))
    show_sku = bool(t.get("show_sku", True))
    code_style = str(t.get("code_style", "qr") or "qr")
    footer = str(t.get("footer", "") or "")
    w = width_mm * 8
    h = height_mm * 8
    margin = 8 if width_mm <= 32 else 10
    inner_x = _BORDER_INSET + margin
    inner_w = w - 2 * inner_x
    inner_y = _BORDER_INSET + margin
    inner_h = h - 2 * inner_y
    off_x = float(t.get("offset_x", 0) or 0)
    off_y = float(t.get("offset_y", 0) or 0)
    code = (sku or barcode) if code_style == "qr" else (barcode or (sku.replace(".", "") if sku else ""))

    # ── 行集：order=渲染顺序，z=让位优先级（大者先让），max_lines=行数预算 ──
    rows: list = []

    def add_row(order, key, value, z, max_lines, protected=False, xmul=1):
        v = _sanitize(str(value or ""))
        if not v:
            return
        rows.append(dict(order=order, key=key, value=v, z=z, max_lines=max_lines,
                         protected=protected, xmul=xmul))

    add_row(1, "sku", sku if show_sku else "", 2, 1)   # 编号**保留**：让位排在规格之后
    # 名称专项（测试反馈 2026-09-11「专项处理超长名」）：正常名**原样打印、零加工**；
    # 只有「2 行预算都装不下」的超长名才摘取型号/规格——摘出来的补到规格行，信息不丢
    if _disp_w(name) > inner_w * 2:
        name_show, name_spec = _short_name(name, inner_w * 2)
    else:
        name_show, name_spec = (name or "").strip(), ""
    spec_show = (spec or "").strip() or name_spec
    # 名称：靠**放大字号**醒目，不做横向拉伸（拉伸会把字体拉变形——测试反馈 2026-09-11
    # 「名称咋还拉伸了」）。TSPL 无真粗体，等比例放大才是正确的「更醒目」手段。
    add_row(2, "name", name_show, 0, 3, True)  # 3 行预算（只有超长名用得到）
    add_row(3, "spec", ("规格:" + spec_show) if (show_spec and spec_show) else "", 3, 2)
    add_row(4, "price", price, 1, 1, True)
    # 单位**不单独成行**——跟在价格右侧（测试反馈 2026-09-11：单位应该在价格旁边）
    rows.sort(key=lambda r: r["order"])
    unit_text = _sanitize(unit)

    has_head = bool(store_name)
    has_foot = bool(footer)
    reserved = (_LINE_H if has_head else 0) + (_LINE_H if has_foot else 0)
    landscape = w >= h
    # 横版整体上提（2026-09-12 测试反馈 40×30 实拍两轮「偏下」，与 APP LabelTemplates 同步）：
    # QR 与价格锚点同步抬升，版式相对关系不变；竖版（30×40）不动。安全上限 24dot：
    # 再大 qrTop<名称行底(72)，名称（7 汉字 168dot > 窄列 162）会被挤折行。
    bottom_lift = 24 if landscape else 0

    # ── 码图先占位 ──
    # ★ 可扫硬指标（测试反馈 2026-09-11「二维码大小要保证清晰可扫」）：
    #   · 码格下限按纸宽定：≥40mm 纸 4 dot（0.5mm/模块，21 模块的短码也有 10.5mm）；
    #     30mm 窄纸退 3 dot（0.375mm/模块）——再大就把整幅宽度吃光，文字没地方站；
    #   · 四周留 ≥12 dot 静默边（QR 规范要 4 模块白边，不够扫码器对不上焦）；
    #   · 码格**不写死**：在「不挤掉任何一行文字」的前提下从大到小取第一个能立的，
    #     40×30 上 5 格会顶掉编号行 → 自动落到 4 格；60×40 上 5 格谁都不影响 → 取 5。
    #   · 留位按 [qr_mod]×码格（真实模块数），不再按固定 30 模块硬估——短码白省 9 个模块宽。
    # ★ 折中（测试反馈 2026-09-11「能不能折中」）：窄纸（<40mm）上把**价格压到纸底、
    # 横贯全幅**，二维码上移让出这一行。原本边距与价格互斥（边距越大价格列越窄），
    # 这样两边都不用让：价格吃大字，二维码保住 4 模块静默边 + 完整偏移余量。
    price_full_width = width_mm < 40
    max_price_scale = 1 if width_mm < 30 else (3 if width_mm >= 60 else 2)
    qr_mod = _qr_modules(code)
    qr_dots = 0
    qr_cell = 0
    bar_h = 0
    min_cell = 4 if width_mm >= 40 else 3
    # 码图四周净空（dot）：QR 规范要 4 模块静默边 + 水平死区——既是扫码白边，
    # 也是偏移容错的滑动余量（留不够就会被夹死、偏移量失效）
    qpad = 0
    if code_style != "qr":
        bar_h = max(20, min(50, inner_h // 4))
        col_w = inner_w
        row_area_h = inner_h - reserved - bar_h
    elif not code:
        col_w = inner_w
        row_area_h = inner_h - reserved
    else:
        # 码格基准：40mm 起 5 格（0.625mm/模块，21 模块 ≈13mm 见方），30mm 窄纸 3 格。
        # 判据只看「**必须**待在窄列里的价格行」——测试反馈 2026-09-11 拍了优先级：
        # **编号可以放弃，名称和二维码必须保住**。既然不必再为编号（最长 16 字符
        # = 192dot）留宽度，省下的宽度全给码：40×30 上码格从 4 升到 5。
        # 码格**按纸型钉死**，不随商品浮动（测试反馈 2026-09-11「价格一个一个位置都不一样，
        # 能不能固定」）：码格一旦随价格宽度浮动，二维码尺寸 / qpad / 价格位置全都会
        # 一张一个样。40mm 起 5 格（0.625mm/模块，21 模块 ≈13mm 见方），30mm 窄纸 3 格。
        # 价格若因此放不下大字号，由下面的价格字号逻辑自动降档（不会溢出）。
        qr_cell = 5 if width_mm >= 40 else 3
        qr_dots = qr_mod * qr_cell
        # + _OFFSET_OUT_MAX：还要给「向外偏移」留滑动空间——否则码一贴死区边界，
        # 正向偏移就会被夹成 0（测试反馈 2026-09-11 实测：右移 2mm 完全不动）
        # 静默边 / 偏移余量按纸宽收紧：小纸（30mm）上二维码在纸角、四周本就是白纸，
        # 2 模块静默边足够；省下的宽度全给价格（测试反馈 2026-09-11「30×20 售价是不是
        # 还可以再大一点」——正是这 14dot 卡着价格上不去 2×）
        # 净空 = 4 模块静默边（QR 规范）+ 死区 + 偏移滑动余量 —— **边距不打折**
        qpad = max(_BORDER_INSET + margin, 4 * qr_cell + _DEAD_ZONE_X + _OFFSET_OUT_MAX)

        if landscape:
            col_w = max(w - qpad - qr_dots - inner_x, 40)
            if price_full_width:
                # 价格压底独占一行（见渲染段）：上方行区到「价格顶边」为止，预算 = 上方行 + 价格
                upper = h - inner_y - max_price_scale * _LINE_H - 6 - inner_y - reserved
                row_area_h = max(upper + max_price_scale * _LINE_H, _LINE_H)
            else:
                # 价格行底对齐钉在二维码下沿（见渲染段），上方几行只能用到那里
                row_area_h = max(h - qpad - inner_y - reserved, _LINE_H)
        else:
            col_w = inner_w
            row_area_h = inner_h - reserved - qr_dots - 6

    def usable(r, sc):
        """该行在 sc 倍率下能否完整落进自己的行数预算（保护行永远保留）。"""
        return r["protected"] or _fits_fully(r["value"], col_w, r["max_lines"], sc)

    def row_height(r, sc):
        # 宽度预算按「字号 × 横向倍率」缩：名称横向 2× 加粗 → 占宽翻倍，折行数会变多
        return max(len(_fit_lines(r["value"], col_w, r["max_lines"], sc * r["xmul"])), 1) * _LINE_H * sc + 2

    # ── 阶段一：1× 下装不下的非保护行整行让位（宁缺勿残），再按 z 让到高度够 ──
    # 让位分性质（测试反馈 2026-09-11 实测真实库后定）：
    #   · 文本行（名称/规格，多行预算）永远留着走换行+省略号——「规格:NJ-YB3060L…」
    #     仍有信息量，砍掉反而什么都没了；
    #   · 单行编码行（编号）在两行预算内都放不下才整行让位——半截编号毫无意义，
    #     况且编号内容就在二维码里，扫一下就有。
    # 判断宽度用 inner_w：长值可能吃到「二维码上方净空区」的整幅宽度，
    # 不该因为窄列放不下就先被砍（真正的取舍交给后面的整行让位）。
    kept = [r for r in rows
            if r["protected"] or r["max_lines"] > 1
            or _fits_fully(r["value"], inner_w, r["max_lines"], 1)]
    while kept and sum(row_height(r, max_price_scale if r["key"] == "price" else 1)
                        for r in kept) > row_area_h:
        drop = next((r for r in sorted(kept, key=lambda x: -x["z"]) if not r["protected"]), None)
        if drop is None:
            break
        kept.remove(drop)

    # 二维码上沿：**它以上的行可以用整幅宽度**（测试反馈 2026-09-11：「二维码上方的空白处做
    # 名称规格的超长冗余」）——那段是净空区，长名称/规格不该被二维码列挤成折行/省略号；
    # 落在二维码同一水平带的行才受窄列限制。（渲染段与名称字号判定都用它）
    qr_top = h - qpad - qr_dots - (_LINE_H if has_foot else 0) - bottom_lift \
        if (code_style == "qr" and qr_cell > 0 and landscape and code) else h
    qr_top = max(qr_top, inner_y)

    # ── 阶段二：价格字号按纸型钉死（见下），名称再吃余量等比例放大 ──
    name_row = next((r for r in kept if r["key"] == "name"), None)
    price_row = next((r for r in kept if r["key"] == "price"), None)
    unit_w = _disp_w(unit_text)
    name_scale = 1

    # ── 阶段三：价格字号**按纸型钉死**（30mm→1×、40~50mm→2×、60mm 起→3×）──
    # 只在这档宽度实在放不下（价格数字太长）时才降档——不随上方行数浮动，一排价签才整齐
    price_w = _disp_w(price_row["value"]) if price_row is not None else 0
    # 30mm 窄纸也给到 2×（放不下由下面的 while 自动降档）——测试反馈 2026-09-11
    # 「30×20 售价是不是还可以再大一点」
    price_scale = 1 if width_mm < 30 else (3 if width_mm >= 60 else 2)
    price_avail = inner_w if price_full_width else col_w
    # 单位先让位（2026-09-12 测试反馈 40×30 实拍「价格空间够用怎么这么小」，与 APP 同步）：
    # 降档判据不含单位——单位挤不进就不画（渲染处 ux+unit_w 守卫），价格字号不因单位缩水
    if price_row is not None:
        while price_scale > 1 and price_w * price_scale + 4 > price_avail:
            price_scale -= 1

    # ── 阶段四（2026-09-12 测试反馈拍板废除自动放大，与 APP 同步）：名称恒定 1×。
    # 「稳可切割片」实拍教训：短名触发 2× 后（240dot）越过 qr_top 线被窄列折成两行巨块，
    # 还把价格挤出标签——名称放大与横版二维码布局天然冲突，砍掉。

    def row_scale(r):
        if r is price_row:
            return price_scale
        if r is name_row:
            return name_scale
        return 1

    total_h = sum(row_height(r, row_scale(r)) for r in kept)
    # 行距上限 6dot：余量不摊给行距，而是留给「二维码上方净空区」——
    # 行排得越靠上，越多行能吃整幅宽度（长名称/规格不折行）
    gap = min(max(row_area_h - total_h, 0) // (len(kept) + 1), 6) if kept else 0

    # ── 行渲染：值从左边距起铺（无字段名、无外框，参考实物白底裸版式） ──

    ops: list = []
    row_top = inner_y
    if has_head:
        head = (_fit_lines(store_name, inner_w, 1) or [""])[0]
        if head:
            ops.append(("txt", inner_x + (inner_w - _disp_w(head)) // 2, row_top, 1, head))
        row_top += _LINE_H

    # ★ 价格行**底对齐**（测试反馈 2026-09-11「价格一个一个位置都不一样，能不能固定」）：
    #   钉在二维码下沿那条水平线上——一排价签贴出去，价格永远落在同一条线；
    #   上方几行（编号/名称/规格）照旧顶对齐，中间余量全是弹性间隔。
    price_h = price_scale * _LINE_H if price_row is not None else 0
    price_y = max((h - inner_y if price_full_width else h - qpad) - price_h - bottom_lift, inner_y) \
        if price_row is not None else 0

    for r in kept:
        if r is price_row:
            continue  # 价格单独在下面画（底对齐）
        sc = row_scale(r)
        # 先试整幅宽度：全宽下装得下、且行底不越过二维码上沿 → 就用全宽（长名称/规格不折行）
        n_full = max(len(_fit_lines(r["value"], inner_w, r["max_lines"], sc)), 1)
        avail = inner_w if row_top + n_full * _LINE_H * sc + 2 <= qr_top else col_w
        lines = _fit_lines(r["value"], avail, r["max_lines"], sc * r["xmul"]) or [r["value"]]
        y = row_top
        for line in lines:
            ops.append(("txt", inner_x, y, sc, line, sc * r["xmul"]))
            y += _LINE_H * sc
        row_top += max(len(lines), 1) * _LINE_H * sc + 2 + gap

    if price_row is not None:
        sc = price_scale
        lines = _fit_lines(price_row["value"], price_avail, 1, sc) or [price_row["value"]]
        y = max(price_y, row_top)  # 兜底：空间实在不够时不与上方压字（宁可牺牲整齐）
        for line in lines:
            ops.append(("txt", inner_x, y, sc, line, sc))
        if unit_text:
            # 单位跟价格同排：1× 小字、底对齐，紧贴价格右侧
            ux = inner_x + _disp_w(lines[0]) * sc + 4
            uy = y + (sc - 1) * _LINE_H
            if ux + unit_w <= inner_x + price_avail:
                ops.append(("txt", ux, uy, 1, unit_text, 1))

    # ── 码图放置：横版右下角 / 竖版底部居中；条码风格底置 ──
    if code_style == "qr":
        if code and qr_cell > 0:
            if landscape:
                qr_x = w - qpad - qr_dots
            else:
                qr_x = inner_x + (inner_w - qr_dots) // 2
            qr_y = (h - qpad - qr_dots - price_h - 6 if price_full_width
                    else h - qpad - qr_dots) - (_LINE_H if has_foot else 0) - bottom_lift
            # 上移后**上方也要留足静默边**，否则码的包围盒会顶到纸边（越界/切边）
            qr_y = max(qr_y, inner_y + 4 * qr_cell)
            ops.append(("qr", qr_x, max(qr_y, inner_y), qr_cell, code))
    elif code:
        y_bar = h - _BORDER_INSET - margin - bar_h
        ops.append(("bar", inner_x, y_bar, bar_h, _bar_narrow(code, inner_w), code[:20]))

    # ── 页脚（可选，一行，放得下才画） ──
    if has_foot:
        foot = (_fit_lines(footer, inner_w, 1) or [""])[0]
        if foot:
            ops.append(("txt", inner_x + (inner_w - _disp_w(foot)) // 2, h - _BORDER_INSET - margin - _LINE_H, 1, foot))

    # ── 打印偏移容错：整版平移（夹紧到边缘死区，平移后仍不出纸） ──
    return _apply_offset(ops, off_x, off_y, w, h)


def _tspl_price_tag(name: str, price: str, sku: str, spec: str = "",
                    width: int = 30, height: int = 0, barcode: str = "",
                    template: dict = None, unit: str = "") -> str:
    """价签 TSPL（v3 参考上游业务系统版式，与 APP LabelTemplates 同参数同几何）。
    template 可选项: store_name/show_spec/show_sku/code_style(qr|barcode)/price_scale(2~4)/footer/width/height。
    height<=0 时按纸宽取默认高度（与 APP LabelTemplates.heightFor 同口径，老配置兼容）。
    QR 内容 = 条码文本（扫描枪扫入上游业务系统），回退 SKU；全部文字走防溢出三层防线（换行/末行省略/缩号）。
    """
    if not height or height <= 0:
        height = {30: 40, 40: 30, 50: 30, 60: 40, 70: 50, 80: 50}.get(width, 40)
    ops = _price_tag_ops(name, price, sku, spec=spec, barcode=barcode,
                         width_mm=width, height_mm=height, template=template, unit=unit)
    lines = [
        "SIZE %d mm, %d mm" % (width, height),
        "GAP 2 mm, 0 mm",
        "CLS",
    ]
    for op in ops:
        if op[0] == "txt":
            _, x, y, mul, text = op[:5]
            xmul = op[5] if len(op) > 5 else mul
            # TEXT 第 5/6 参数=横/纵倍率：横向拉伸即加粗（名称/价格 xmul>mul）
            lines.append('TEXT %d,%d,"TSS24.BF2",0,%d,%d,"%s"' % (x, y, xmul, mul, text))
        elif op[0] == "ln":
            _, x1, y1, x2, y2 = op
            lines.append("LINE %d,%d,%d,%d" % (x1, y1, x2, y2))
        elif op[0] == "qr":
            _, x, y, cell, code = op
            lines.append('QRCODE %d,%d,M,%d,A,0,"%s"' % (x, y, cell, code))
        elif op[0] == "bar":
            _, x, y, bh, narrow, code = op
            lines.append('BARCODE %d,%d,"128",%d,1,0,%d,%d,"%s"' % (x, y, bh, narrow, narrow, code[:20]))
    lines.append("PRINT 1")
    return "\n".join(lines)


def _tspl_shelf_label(zone_name: str, rack_name: str,
                      width: int = 40, height: int = 30) -> str:
    lines = [
        "SIZE %d mm, %d mm" % (width, height),
        "GAP 2 mm, 0 mm",
        "CLS",
        'TEXT 15,5,"TSS24.BF",0,1,1,"%s - %s"' % (zone_name[:8], rack_name[:8]),
        'TEXT 15,35,"TSS24.BF",0,3,3,"%s"' % rack_name[:6],
        'BARCODE 15,75,"128",0,1,0,30,10,"%s-%s"' % (zone_name[:4], rack_name[:4]),
        "PRINT 1",
    ]
    return "\n".join(lines)


def _tspl_cell_label(cell_code: str, product_names: list[str],
                     width: int = 30, height: int = 20) -> str:
    name_line = (product_names[0][:12] + "..") if product_names and len(product_names[0]) > 12 else (product_names[0] if product_names else "")
    lines = [
        "SIZE %d mm, %d mm" % (width, height),
        "GAP 2 mm, 0 mm",
        "CLS",
        'TEXT 15,5,"TSS24.BF",0,1,1,"%s"' % cell_code[:10],
        'TEXT 15,30,"TST24.BF",0,1,1,"%s"' % name_line,
        "PRINT 1",
    ]
    return "\n".join(lines)


def _tspl_barcode_label(barcode: str, name: str,
                        width: int = 25, height: int = 15) -> str:
    name_disp = (name[:10] + "..") if len(name) > 10 else name
    lines = [
        "SIZE %d mm, %d mm" % (width, height),
        "GAP 2 mm, 0 mm",
        "CLS",
        'TEXT 15,5,"TSS24.BF",0,1,1,"%s"' % name_disp,
        'BARCODE 15,25,"128",0,1,0,30,10,"%s"' % barcode[:20],
        "PRINT 1",
    ]
    return "\n".join(lines)


