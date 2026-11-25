#!/usr/bin/env python3
"""示例零售 - 打印服务 (port 18605)
打印标签、货架标签、货位标签、条码标签、小票
"""
import json, logging, os, re, signal, socket, sys, threading, time, uuid
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs
from typing import Any

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, setup_logging
from utils.guards import safe_lan_host, safe_lan_subnet, safe_port
from utils.validate import parse_json_body, validate_request
from utils.logging import set_request_id

# 2026-09-09 审计：print 只服务网关（本机回环），默认不再暴露 0.0.0.0。
LISTEN_HOST = os.environ.get("PRINT_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("PRINT_PORT", 18605))
START_TIME = time.time()

PRINTER_DEFAULT_PORT = 9100

STORE_NAME = "示例零售全能助手"
STORE_ADDRESS = ""
STORE_PHONE = ""

PRINTER_CONFIGS = {}

_print_lock = threading.Lock()
_print_stats = {"total": 0, "success": 0, "failed": 0}
_queue: list[dict] = []
_queue_lock = threading.Lock()
_queue_max_retries = 3

try:
    import escpos.printer as escpos_printer
    HAS_ESCPOS = True
except ImportError:
    HAS_ESCPOS = False

# ─── TSPL Templates ──────────────────────────────────────────

# ─── 价签版式引擎 v3（参考管家婆价签：左五行 + 右码图 + 外框；与 APP LabelTemplates.kt 同参数同几何） ──

_LINE_H = 24          # TSS24.BF2 @1x 单行高（dot）
_BORDER_INSET = 4     # 外框内缩（dot），防骑纸边
_BOLD_XMUL = 2        # 名称/价格加粗：横向 2 倍拉伸（占宽/行高同步放大才不溢出）
_ELLIPSIS = "…"
_OFFSET_MAX_MM = 10.0  # 打印偏移容错上限（mm，2026-10-22 老板令「不再做限制」：2.0 → 4.0 → 10.0）
_DEAD_ZONE_X = 3      # 边缘死区（dot，水平 ≈0.4mm）：任何元素不得进入，防切边
_DEAD_ZONE_Y = 2      # 边缘死区（dot，纵向 ≈0.25mm）
_OFFSET_OUT_MAX = 16  # 向外（朝纸边）允许的最大平移量（dot，2mm）：
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
    # 2026-10-22 老板令「不再做限制」：只按 _OFFSET_MAX_MM 限幅，不再按纸边死区夹紧。
    # 打印机走纸起点比标签低 2~3mm，必须允许整版越过逻辑纸边（负坐标），由打印机在
    # 物理标签上裁掉——这才是补偿机械偏移的正确姿势。
    dx, dy = raw_dx, raw_dy
    out = []
    for op in ops:
        if op[0] == "txt":
            base = list(op)
            base[1] += dx
            base[2] += dy
            out.append(tuple(base))
        elif op[0] == "ln":
            out.append(("ln", op[1] + dx, op[2] + dy, op[3] + dx, op[4] + dy))
        elif op[0] == "qr":
            out.append(("qr", op[1] + dx, op[2] + dy, op[3], op[4]))
        else:
            out.append(("bar", op[1] + dx, op[2] + dy, op[3], op[4], op[5]))
    return out


def _op_box(op: tuple) -> tuple:
    """元素包围盒 (left, right, top, bottom)（dot）——偏移容错（`_apply_offset`）的判据。

    二维码有两处特殊（老板 2026-09-11 实物反馈）：
    1. **边长按真实模块数 × 码格**（`_qr_modules`），不再硬估 30 模块——短码虚大 1.43 倍
       会把偏移量夹掉，长数据则虚小、偏移后可能出纸；
    2. **向外多算 4 个模块的静默边**，因为静默边是码的组成部分：QR 规范要求四周留
       4 模块白，平移容错若把白边顶掉，码贴到纸边就扫不出来。
    """
    if op[0] == "txt":
        xmul = op[5] if len(op) > 5 else op[3]
        font = op[6] if len(op) > 6 else _LINE_H
        w = _disp_w(op[4]) if font == _LINE_H else (_disp_w(op[4]) * font + _LINE_H - 1) // _LINE_H
        return (op[1], op[1] + w * xmul, op[2], op[2] + font * max(op[3], xmul))
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
    """价签版式计划：左侧「值」若干行 + 右下角码图（老板 2026-09-11 参考实物 + 优化）。

    ★ 老板定调：参考实拍「只是参考，实际还能优化」。相对参考实拍做了三处优化：

    1. **价格放大成大字**（2~3×，在余量里自动取档）——参考实拍五行字一样大，
       价格不突出；价签的核心是「品名 + 价格」，价格行按幅面吃到最大字号。
       价格行因此**不带「零售价:」前缀**（大字数字本身就是价格的通用写法，
       省下的宽度全给字号）。
    2. **无外框、无【】括号、不打字段名**——参考实拍是白底裸版式；
       只有「规格」行带半角冒号前缀（规格值本身不自明，其余一眼能认）。
    3. **整行要么完整要么让位，绝不印半截残字**——老板实物上的
       「｜品名称】」就是残字。1× 下装不进 `max_lines` 的非保护行整行让位
       （如窄纸上的长编号，与其折成「20.01.05.006/3」不如交给二维码承载），
       让位优先级 单位 → 编号 → 规格；名称/价格永不让位。

    顺序固定：商品编号 → 商品名称 → 规格: → 价格（大字，**单位紧跟其后**，不单独成行）。
    **名称与价格是两张主信息大字**：名称能等比例放大就放大（老板 2026-09-11「名称醒目」），
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
    # 2026-10-22 老板令：小横版文字再向内缩进约 1.2mm（与 APP 同口径）
    _landscape0 = w >= h
    _compact0 = _landscape0 and width_mm >= 40 and height_mm <= 20
    _inset0 = 10 if _compact0 else 0
    inner_x = _BORDER_INSET + margin + _inset0
    inner_w = w - 2 * inner_x
    inner_y = _BORDER_INSET + margin + _inset0
    inner_h = h - 2 * inner_y
    off_x = float(t.get("offset_x", 0) or 0)
    off_y = float(t.get("offset_y", 0) or 0)
    code = (sku or barcode) if code_style == "qr" else (barcode or (sku.replace(".", "") if sku else ""))

    landscape = w >= h
    # 小横版（40×20 一类：宽 ≥40mm、高只有 20mm）——与 APP LabelTemplates 同口径。
    # 纵向只有 160dot，装不下 40×30 那套「码格 5 + 底部抬升 24」：5 格码 = 105dot
    # 占掉 65% 纸高，文字只能挤在 20mm 窄列里折三行。这里按紧凑口径重算
    # （码格 4 / 净空按窄纸收 / 不抬版 / 名称限 2 行），换出宽度与高度给名称和价格。
    compact = landscape and width_mm >= 40 and height_mm <= 20
    has_head = bool(store_name)
    has_foot = bool(footer)
    # 小横版（20mm 高）只够「编号 / 名称 / 规格 / 价格 / 码」：店名与页脚各占 24dot，
    # 一起开就把规格整行挤掉（老板 2026-10-20「规格型号呢」）。取舍：**小横版上店名、页脚
    # 都让位**，空间全给商品信息（价格 > 名称 > 规格 > 编号）。其他纸规照旧。
    show_head = has_head and not compact
    show_foot = has_foot and not compact
    # 小横版底部基准线：纸内边距（不走 qpad 的 19dot 净空——qpad 是右侧净空，
    # 拿它当纵向基准会把行预算算少 5dot，规格正好卡在预算外）。码底沿与价格基线共用。
    # 2026-10-22：40×20 上整版仍偏下 1.0mm（价格/码底被标签缝切）——底部安全边距
    # 14dot(1.75mm) → 22dot(2.75mm)，行间 +2dot 呼吸缝在小横版上收紧为 0，与 APP 同口径。
    compact_bottom = 10 if compact else 0  # 10-22：底部留 3mm（给大字号名称腾一行）
    compact_base = h - _BORDER_INSET - margin - compact_bottom - (_LINE_H if show_foot else 0)

    # ── 行集：order=渲染顺序，z=让位优先级（大者先让），max_lines=行数预算 ──
    rows: list = []

    def add_row(order, key, value, z, max_lines, protected=False, xmul=1, font=_LINE_H):
        v = _sanitize(str(value or ""))
        if not v:
            return
        rows.append(dict(order=order, key=key, value=v, z=z, max_lines=max_lines,
                         protected=protected, xmul=xmul, font=font))

    # 小横版上「编号 vs 规格」打架时**规格优先**：规格型号是认件的唯一依据，
    # 编号内容本来就在二维码里（扫一下就有）。所以小横版把编号的让位优先级提到最高（z=4）。
    # 阅读顺序也随纸型调整：品名在最上（吃整幅宽度）→ 规格 → 编号 → 价格。
    # 2026-10-22 老板令「整体字体再缩小一个字号」：小横版正文（品名/规格/编号）用 TSS16（2mm 字），
    # 同样宽度多放 1/3 的字、纵向每行省 8dot——规格不再被省略号截断（与 APP 同口径）。
    # 2026-10-22 二轮：正文回到 24dot（16 太小），价格降一档到 2×16=32dot 腾空间
    body_font = _LINE_H
    price_font = 16 if compact else _LINE_H
    sku_order = 3 if compact else 1
    name_order = 1 if compact else 2
    spec_order = 2 if compact else 3
    add_row(sku_order, "sku", sku if show_sku else "", 4 if compact else 2, 1, font=body_font)   # 编号：常规纸规最后让位，小横版最先让位
    # 名称专项（老板 2026-09-11「专项处理超长名」）：正常名**原样打印、零加工**；
    # 只有「2 行预算都装不下」的超长名才摘取型号/规格——摘出来的补到规格行，信息不丢
    if _disp_w(name) > inner_w * 2:
        name_show, name_spec = _short_name(name, inner_w * 2)
    else:
        name_show, name_spec = (name or "").strip(), ""
    spec_show = (spec or "").strip() or name_spec
    # 名称：靠**放大字号**醒目，不做横向拉伸（拉伸会把字体拉变形——老板 2026-09-11
    # 「名称咋还拉伸了」）。TSPL 无真粗体，等比例放大才是正确的「更醒目」手段。
    # 小横版名称行数：20mm 纸最多两行（店名/页脚已让位），常规纸规 3 行
    name_lines = 2 if compact else 3
    # 名称能上大字就上大字（32dot/4mm 撑满二维码上方整幅宽）；放不下才退回 24dot
    name_font = 32 if (compact and _disp_w(name_show) * 32 <= inner_w * _LINE_H) else body_font
    add_row(name_order, "name", name_show, 0, 1 if name_font == 32 else name_lines, True, font=name_font)
    # 小横版规格：只给 1 行（2 行会把自己撑出预算），且**不带「规格:」前缀**——
    # 20mm 纸上这 60dot 能多放 2 个字，型号本身一看就知道是规格
    if show_spec and spec_show:
        spec_text = spec_show if compact else ("规格:" + spec_show)
    else:
        spec_text = ""
    add_row(spec_order, "spec", spec_text, 3, 1 if compact else 2, font=body_font)
    add_row(4, "price", price, 1, 1, True, font=price_font)
    # 单位**不单独成行**——跟在价格右侧（老板 2026-09-11：单位应该在价格旁边）
    rows.sort(key=lambda r: r["order"])
    unit_text = _sanitize(unit)

    reserved = (_LINE_H if show_head else 0) + (_LINE_H if show_foot else 0)
    # 横版整体上提（2026-09-12 老板 40×30 实拍两轮「偏下」，与 APP LabelTemplates 同步）：
    # QR 与价格锚点同步抬升，版式相对关系不变；竖版（30×40）不动。安全上限 24dot：
    # 再大 qrTop<名称行底(72)，名称（7 汉字 168dot > 窄列 162）会被挤折行。
    # 小横版不抬：20mm 纸抬 24dot（3mm）会把码顶到纸中、下方留一条空带。
    bottom_lift = 24 if (landscape and not compact) else 0

    # ── 码图先占位 ──
    # ★ 可扫硬指标（老板 2026-09-11「二维码大小要保证清晰可扫」）：
    #   · 码格下限按纸宽定：≥40mm 纸 4 dot（0.5mm/模块，21 模块的短码也有 10.5mm）；
    #     30mm 窄纸退 3 dot（0.375mm/模块）——再大就把整幅宽度吃光，文字没地方站；
    #   · 四周留 ≥12 dot 静默边（QR 规范要 4 模块白边，不够扫码器对不上焦）；
    #   · 码格**不写死**：在「不挤掉任何一行文字」的前提下从大到小取第一个能立的，
    #     40×30 上 5 格会顶掉编号行 → 自动落到 4 格；60×40 上 5 格谁都不影响 → 取 5。
    #   · 留位按 [qr_mod]×码格（真实模块数），不再按固定 30 模块硬估——短码白省 9 个模块宽。
    # ★ 折中（老板 2026-09-11「能不能折中」）：窄纸（<40mm）上把**价格压到纸底、
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
        # 小横版：条码压到 28dot（3.5mm）——20mm 高的签要留出「品名 + 规格 + 价格」
        bar_h = max(20, min(28 if compact else 50, inner_h // 4))
        col_w = inner_w
        row_area_h = inner_h - reserved - bar_h
    elif not code:
        col_w = inner_w
        row_area_h = inner_h - reserved
    else:
        # 码格基准：40mm 起 5 格（0.625mm/模块，21 模块 ≈13mm 见方），30mm 窄纸 3 格。
        # 判据只看「**必须**待在窄列里的价格行」——老板 2026-09-11 拍了优先级：
        # **编号可以放弃，名称和二维码必须保住**。既然不必再为编号（最长 16 字符
        # = 192dot）留宽度，省下的宽度全给码：40×30 上码格从 4 升到 5。
        # 码格**按纸型钉死**，不随商品浮动（老板 2026-09-11「价格一个一个位置都不一样，
        # 能不能固定」）：码格一旦随价格宽度浮动，二维码尺寸 / qpad / 价格位置全都会
        # 一张一个样。小横版 4 格（0.5mm/模块，比 30mm 纸的 0.375 还宽裕），
        # 40mm 起 5 格（0.625mm/模块，21 模块 ≈13mm 见方），30mm 窄纸 3 格。
        # 价格若因此放不下大字号，由下面的价格字号逻辑自动降档（不会溢出）。
        # 2026-10-22 二轮：正文回 24dot 后窄列放不下规格 → 小横版码格 4→3（与 APP 同口径）
        qr_cell = 3 if compact else (5 if width_mm >= 40 else 3)
        qr_dots = qr_mod * qr_cell
        # + _OFFSET_OUT_MAX：还要给「向外偏移」留滑动空间——否则码一贴死区边界，
        # 正向偏移就会被夹成 0（老板 2026-09-11 实测：右移 2mm 完全不动）
        # 静默边 / 偏移余量按纸宽收紧：小纸（30mm）上二维码在纸角、四周本就是白纸，
        # 2 模块静默边足够；省下的宽度全给价格（老板 2026-09-11「30×20 售价是不是
        # 还可以再大一点」——正是这 14dot 卡着价格上不去 2×）
        # 小横版同理（4 模块静默边 + 16dot 偏移余量会把 20mm 纸上的码挤成小方块）
        # 净空 = 4 模块静默边（QR 规范）+ 死区 + 偏移滑动余量 —— **边距不打折**
        quiet_mods = 2 if (compact or width_mm < 40) else 4
        out_slack = 8 if (compact or width_mm < 40) else _OFFSET_OUT_MAX
        qpad = max(_BORDER_INSET + margin + (10 if compact else 0), quiet_mods * qr_cell + _DEAD_ZONE_X + out_slack)

        if landscape:
            col_w = max(w - qpad - qr_dots - inner_x, 40)
            if price_full_width:
                # 价格压底独占一行（见渲染段）：上方行区到「价格顶边」为止，预算 = 上方行 + 价格
                upper = h - inner_y - max_price_scale * _LINE_H - 6 - inner_y - reserved
                row_area_h = max(upper + max_price_scale * _LINE_H, _LINE_H)
            else:
                # 价格行底对齐钉在二维码下沿（见渲染段），上方几行只能用到那里。
                # 小横版的可用高度按**纸内边距**算，不用 qpad：qpad 是右侧净空（19dot），
                # 比内边距多 5dot——40×20 上正好把「规格」卡在预算外（128 > 127，差 1dot
                # 就整行让位）。码/价都以 compact_base 作底基准，行预算必须同源。
                row_base = (h - _BORDER_INSET - margin) if compact else (h - qpad)
                row_area_h = max(row_base - inner_y - reserved, _LINE_H)
        else:
            col_w = inner_w
            row_area_h = inner_h - reserved - qr_dots - 6

    def usable(r, sc):
        """该行在 sc 倍率下能否完整落进自己的行数预算（保护行永远保留）。"""
        return r["protected"] or _fits_fully(r["value"], col_w, r["max_lines"], sc)

    def row_height(r, sc):
        # 宽度预算按「字号 × 横向倍率」缩：名称横向 2× 加粗 → 占宽翻倍，折行数会变多。
        # 小横版的品名排第一行、恒在「码上方净空区」→ 折行宽度是整幅（与渲染同源）；
        # 仍按窄列估会把它多算一行，白掉一个编号行。
        w = inner_w if (compact and r["key"] == "name") else col_w
        pad = 0 if compact else 2
        f = r.get("font", _LINE_H)
        budget = w * _LINE_H // f
        return max(len(_fit_lines(r["value"], budget, r["max_lines"], sc * r["xmul"])), 1) * f * sc + pad

    # ── 阶段一：1× 下装不下的非保护行整行让位（宁缺勿残），再按 z 让到高度够 ──
    # 让位分性质（老板 2026-09-11 实测真实库后定）：
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

    # 二维码上沿：**它以上的行可以用整幅宽度**（老板 2026-09-11：「二维码上方的空白处做
    # 名称规格的超长冗余」）——那段是净空区，长名称/规格不该被二维码列挤成折行/省略号；
    # 落在二维码同一水平带的行才受窄列限制。（渲染段与名称字号判定都用它）
    qr_top = (compact_base - qr_dots if compact else
              h - qpad - qr_dots - (_LINE_H if show_foot else 0) - bottom_lift) \
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
    # 30mm 窄纸也给到 2×（放不下由下面的 while 自动降档）——老板 2026-09-11
    # 「30×20 售价是不是还可以再大一点」
    price_scale = 1 if width_mm < 30 else (3 if width_mm >= 60 else 2)
    price_avail = inner_w if price_full_width else col_w
    # 单位先让位（2026-09-12 老板 40×30 实拍「价格空间够用怎么这么小」，与 APP 同步）：
    # 降档判据不含单位——单位挤不进就不画（渲染处 ux+unit_w 守卫），价格字号不因单位缩水
    if price_row is not None:
        while price_scale > 1 and price_w * price_scale + 4 > price_avail:
            price_scale -= 1

    # ── 阶段四（2026-09-12 老板拍板废除自动放大，与 APP 同步）：名称恒定 1×。
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
    gap = 0 if compact else (min(max(row_area_h - total_h, 0) // (len(kept) + 1), 6) if kept else 0)

    # ── 行渲染：值从左边距起铺（无字段名、无外框，参考实物白底裸版式） ──

    ops: list = []
    row_top = inner_y
    if show_head:
        head = (_fit_lines(store_name, inner_w, 1) or [""])[0]
        if head:
            ops.append(("txt", inner_x + (inner_w - _disp_w(head)) // 2, row_top, 1, head))
        row_top += _LINE_H

    # ★ 价格行**底对齐**（老板 2026-09-11「价格一个一个位置都不一样，能不能固定」）：
    #   钉在二维码下沿那条水平线上——一排价签贴出去，价格永远落在同一条线；
    #   上方几行（编号/名称/规格）照旧顶对齐，中间余量全是弹性间隔。
    price_h = price_scale * price_row["font"] if price_row is not None else 0
    # 条码样式没有二维码，基准线改取**条码上沿**（留 2dot 呼吸缝）——
    # 原先按 qpad(=0) 算，价格会压在条码上
    price_base = (h - _BORDER_INSET - margin - bar_h - 2) if code_style != "qr" \
        else (compact_base if compact else (h - inner_y if price_full_width else h - qpad))
    price_y = max(price_base - price_h - bottom_lift, inner_y) if price_row is not None else 0

    for r in kept:
        if r is price_row:
            continue  # 价格单独在下面画（底对齐）
        sc = row_scale(r)
        f = r.get("font", _LINE_H)
        # 先试整幅宽度：全宽下装得下、且行底不越过二维码上沿 → 就用全宽（长名称/规格不折行）
        n_full = max(len(_fit_lines(r["value"], inner_w * _LINE_H // f, r["max_lines"], sc)), 1)
        avail = inner_w if row_top + n_full * f * sc + 2 <= qr_top else col_w
        lines = _fit_lines(r["value"], avail * _LINE_H // f, r["max_lines"], sc * r["xmul"]) or [r["value"]]
        y = row_top
        for line in lines:
            ops.append(("txt", inner_x, y, sc, line, sc * r["xmul"], f))
            y += f * sc
        row_top += max(len(lines), 1) * f * sc + (0 if compact else 2) + gap

    if price_row is not None:
        sc = price_scale
        pf = price_row.get("font", _LINE_H)
        lines = _fit_lines(price_row["value"], price_avail * _LINE_H // pf, 1, sc) or [price_row["value"]]
        y = max(price_y, row_top)  # 兜底：空间实在不够时不与上方压字（宁可牺牲整齐）
        for line in lines:
            ops.append(("txt", inner_x, y, sc, line, sc, pf))
        if unit_text:
            # 单位跟价格同排：1× 小字、底对齐，紧贴价格右侧
            uw = _disp_w(lines[0]) * pf // _LINE_H
            ux = inner_x + uw * sc + 4
            uy = y + (sc - 1) * pf
            unit_w_pf = (_disp_w(unit_text) * pf + _LINE_H - 1) // _LINE_H
            if ux + unit_w_pf <= inner_x + price_avail:
                ops.append(("txt", ux, uy, 1, unit_text, 1, pf))

    # ── 码图放置：横版右下角 / 竖版底部居中；条码风格底置 ──
    if code_style == "qr":
        if code and qr_cell > 0:
            if landscape:
                qr_x = w - qpad - qr_dots
            else:
                qr_x = inner_x + (inner_w - qr_dots) // 2
            qr_y = (compact_base - qr_dots) if compact else \
                ((h - qpad - qr_dots - price_h - 6 if price_full_width
                  else h - qpad - qr_dots) - (_LINE_H if has_foot else 0) - bottom_lift)
            # 上移后**上方也要留足静默边**，否则码的包围盒会顶到纸边（越界/切边）
            qr_y = max(qr_y, inner_y + 4 * qr_cell)
            ops.append(("qr", qr_x, max(qr_y, inner_y), qr_cell, code))
    elif code:
        y_bar = h - _BORDER_INSET - margin - bar_h
        ops.append(("bar", inner_x, y_bar, bar_h, _bar_narrow(code, inner_w), code[:20]))

    # ── 页脚（可选，一行，放得下才画；小横版有店名时让位，见 show_foot） ──
    if show_foot:
        foot = (_fit_lines(footer, inner_w, 1) or [""])[0]
        if foot:
            ops.append(("txt", inner_x + (inner_w - _disp_w(foot)) // 2, h - _BORDER_INSET - margin - _LINE_H, 1, foot))

    # ── 打印偏移容错：整版平移（夹紧到边缘死区，平移后仍不出纸） ──
    return _apply_offset(ops, off_x, off_y, w, h)


def _tspl_price_tag(name: str, price: str, sku: str, spec: str = "",
                    width: int = 30, height: int = 0, barcode: str = "",
                    template: dict = None, unit: str = "") -> str:
    """价签 TSPL（v3 参考管家婆版式，与 APP LabelTemplates 同参数同几何）。
    template 可选项: store_name/show_spec/show_sku/code_style(qr|barcode)/price_scale(2~4)/footer/width/height。
    height<=0 时按纸宽取默认高度（与 APP LabelTemplates.heightFor 同口径，老配置兼容）。
    QR 内容 = 条码文本（扫描枪扫入管家婆），回退 SKU；全部文字走防溢出三层防线（换行/末行省略/缩号）。
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
            font = op[6] if len(op) > 6 else _LINE_H
            # TEXT 第 5/6 参数=横/纵倍率：横向拉伸即加粗（名称/价格 xmul>mul）
            lines.append('TEXT %d,%d,"TSS%d.BF2",0,%d,%d,"%s"' % (x, y, font, xmul, mul, text))
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


def _tspl_receipt_header(width: int = 58) -> str:
    address = STORE_ADDRESS or "五金一条街88号"
    phone = STORE_PHONE or "00000000000"
    return "\n".join([
        "SIZE %d mm, 100 mm" % width,
        "GAP 2 mm, 0 mm",
        "CLS",
        'TEXT 15,5,"TSS24.BF",0,2,2,"%s"' % STORE_NAME,
        'TEXT 15,40,"TSS24.BF",0,1,1,"地址: %s"' % address,
        'TEXT 15,55,"TSS24.BF",0,1,1,"电话: %s"' % phone,
        'LINE 10,70,400,70,2',
    ])


# ─── ESC/POS Templates ───────────────────────────────────────

def _escpos_init() -> bytes:
    return b"\x1b\x40"


def _escpos_text(text: str, align: str = "left", bold: bool = False,
                 size: int = 1) -> bytes:
    out = b""
    align_map = {"left": b"\x1b\x61\x00", "center": b"\x1b\x61\x01", "right": b"\x1b\x61\x02"}
    out += align_map.get(align, b"\x1b\x61\x00")
    if bold:
        out += b"\x1b\x45\x01"
    else:
        out += b"\x1b\x45\x00"
    if size >= 2:
        out += b"\x1d\x21\x11"
    else:
        out += b"\x1d\x21\x00"
    out += text.encode("gbk", errors="replace") + b"\x0a"
    return out


def _escpos_barcode(data: str, barcode_type: str = "128") -> bytes:
    out = b""
    out += b"\x1d\x48\x02"
    out += b"\x1d\x77\x02"
    out += b"\x1d\x68\x50"
    type_map = {"128": b"\x49", "EAN13": b"\x43", "CODE39": b"\x45"}
    out += b"\x1d\x6b" + type_map.get(barcode_type, b"\x49")
    data_bytes = data.encode("ascii", errors="replace")
    out += bytes([len(data_bytes)]) + data_bytes
    return out


def _escpos_qrcode(data: str) -> bytes:
    out = b""
    out += b"\x1d\x28\x6b\x03\x00\x31\x43\x08"
    out += b"\x1d\x28\x6b\x03\x00\x31\x45\x30"
    data_bytes = data.encode("gbk", errors="replace")
    pL = len(data_bytes) & 0xFF
    pH = (len(data_bytes) >> 8) & 0xFF
    out += b"\x1d\x28\x6b" + bytes([pL, pH, 0x31, 0x50, 0x30]) + data_bytes
    out += b"\x1d\x28\x6b\x03\x00\x31\x51\x30"
    return out


def _escpos_cut() -> bytes:
    return b"\x1d\x56\x00"


def _escpos_receipt(receipt_data: dict) -> bytes:
    out = _escpos_init()
    out += _escpos_text(STORE_NAME, align="center", bold=True, size=2)
    out += _escpos_text("=" * 24, align="center")
    out += _escpos_text("单号: %s" % receipt_data.get("order_no", ""), align="left")
    out += _escpos_text("时间: %s" % receipt_data.get("time", ""), align="left")
    out += _escpos_text("-" * 24, align="center")
    items = receipt_data.get("items", [])
    for item in items:
        name = item.get("name", "")[:10]
        qty = item.get("qty", 1)
        price = item.get("price", 0)
        total = qty * price
        out += _escpos_text("%s x%d  %.2f" % (name, qty, total), align="left")
    out += _escpos_text("-" * 24, align="center")
    out += _escpos_text("合计: ¥%.2f" % receipt_data.get("total", 0), align="right", bold=True, size=2)
    out += _escpos_text("收款: ¥%.2f" % receipt_data.get("paid", 0), align="right")
    out += _escpos_text("找零: ¥%.2f" % receipt_data.get("change", 0), align="right")
    out += _escpos_text("=" * 24, align="center")
    out += _escpos_text("谢谢惠顾！", align="center")
    out += _escpos_cut()
    return out


def _escpos_price_tag(item: dict) -> bytes:
    out = _escpos_init()
    out += _escpos_text(STORE_NAME, align="center", bold=True, size=2)
    out += _escpos_text(item.get("name", "")[:12], align="center", bold=True)
    spec = item.get("spec", "")
    if spec:
        out += _escpos_text(spec[:12], align="center")
    out += _escpos_text("¥%s" % item.get("price", "0.00"), align="center", bold=True, size=2)
    out += _escpos_barcode(item.get("sku", "000000"), "128")
    out += _escpos_cut()
    return out


def _escpos_shelf_label(data: dict) -> bytes:
    """Generate ESC/POS commands for shelf label"""
    out = _escpos_init()
    out += _escpos_text(data.get("zone_name", ""), align="center", bold=True, size=2)
    out += _escpos_text(data.get("rack_name", ""), align="center", bold=True, size=2)
    out += _escpos_text("-" * 16, align="center")
    out += _escpos_text(data.get("level_name", ""), align="center", size=1)
    sku = data.get("sku", "")
    if sku:
        out += _escpos_barcode(sku, "128")
    out += _escpos_cut()
    return out


def _escpos_label(item: dict, template_type: str) -> bytes:
    """Generic ESC/POS label printer for various label types"""
    out = _escpos_init()
    out += _escpos_text(STORE_NAME, align="center", bold=True, size=2)
    name = item.get("name", "")[:16]
    if name:
        out += _escpos_text(name, align="center", bold=True, size=1)
    spec = item.get("spec", "")[:16]
    if spec:
        out += _escpos_text(spec, align="center", size=1)
    if template_type in ("price_tag", "price_tag_30x40", "price_tag_40x60"):
        price = item.get("price", "0.00")
        out += _escpos_text("¥%s" % price, align="center", bold=True, size=2)
    elif template_type in ("shelf_label", "shelf_label_40x30", "shelf_label_40x20"):
        zone = item.get("zone_name", "")
        rack = item.get("rack_name", "")
        out += _escpos_text("%s-%s" % (zone, rack), align="center", bold=True, size=2)
    sku = item.get("sku") or item.get("barcode") or ""
    if sku:
        out += _escpos_barcode(sku, "128")
    out += _escpos_cut()
    return out


# ─── Network Printer Communication ───────────────────────────

def _send_tcp(ip: str, port: int, data) -> dict:
    # 2026-09-09 排雷 B3：本函数是打印服务唯一的 TCP 出站汇点（打印/测试/探测/
    # 队列全走这里），一律先做内网地址校验，禁止被当作任意公网地址与端口的跳板。
    try:
        ip = safe_lan_host(ip)
        port = safe_port(port)
    except ValueError as e:
        logging.warning("blocked non-LAN print target %r:%r (%s)", ip, port, e)
        return {"success": False, "error": "目标不是内网打印机地址，已拦截"}
    sock = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((ip, port))
        if isinstance(data, str):
            # TSPL/ESC-POS 文本指令按打印机 GBK 字库编码（2026-09-10 真机排障：
            # 旧 UTF-8 编码下中文全是乱码字节——测试打印恰好是纯 ASCII 才显得"能打"）
            sock.sendall(data.encode("gbk", errors="replace"))
        else:
            sock.sendall(data)
        sock.shutdown(socket.SHUT_WR)
        try:
            resp = sock.recv(4096)
        except Exception:
            # 无回读是常态（多数机不出纸前不回 ACK）：超时/对端关闭都当空响应
            resp = b""
        sent = len(data) if isinstance(data, bytes) else len(data.encode("gbk", errors="replace"))
        return {"success": True, "sent": sent, "response": resp.decode("utf-8", errors="replace")[:200]}
    except Exception as e:
        return {"success": False, "error": str(e)}
    finally:
        # 2026-09-11 bug猎杀：原实现只在正常路径 close，connect/sendall 抛异常即漏一个
        # fd——打印服务常驻，打印机离线反复重试会耗尽 fd。close 幂等，正常路径二次关无害。
        if sock is not None:
            try:
                sock.close()
            except Exception:
                pass


def _send_tspl(ip: str, port: int, data: str) -> dict:
    return _send_tcp(ip, port, data)


def _ping_printer(ip: str, port: int) -> dict:
    result = _send_tcp(ip, port, b"\x1b\x40")
    if result["success"]:
        return {"success": True, "status": "online", "ip": ip, "port": port}
    return {"success": False, "status": "offline", "ip": ip, "port": port, "error": result.get("error", "")}


# ─── USB Printer Communication ───────────────────────────────

def _print_usb(vendor_id: str, product_id: str, data: bytes) -> dict:
    if not HAS_ESCPOS:
        return {"success": False, "error": "python-escpos not available, use TCP mode"}
    try:
        printer = escpos_printer.Usb(int(vendor_id, 16), int(product_id, 16))
        printer._raw(data)
        printer.close()
        return {"success": True, "sent": len(data)}
    except Exception as e:
        return {"success": False, "error": str(e)}


# ─── Printer Discovery ───────────────────────────────────────

def _discover_printers(subnet="192.168.1"):
    """Scan subnet for printers on port 9100"""
    subnet = safe_lan_subnet(subnet)  # 排雷 B3：只允许扫描私网 /24 网段
    import concurrent.futures
    results = []

    def check_ip(ip):
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.5)
            result = sock.connect_ex((ip, 9100))
            if result == 0:
                return {"ip": ip, "port": 9100, "status": "online"}
        except Exception:
            pass  # 单 IP 探测失败属预期（不可达/网络栈异常），扫描继续
        finally:
            # 2026-09-11 bug猎杀：connect_ex 抛错路径原先不 close（254 线程并发探测漏 fd）
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass
        return None

    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as executor:
        ips = ["%s.%d" % (subnet, i) for i in range(1, 255)]
        futures = {executor.submit(check_ip, ip): ip for ip in ips}
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result:
                results.append(result)

    return results


# ─── Print Queue ─────────────────────────────────────────────

def _process_queue():
    while True:
        task = None
        with _queue_lock:
            if _queue:
                task = _queue[0]
                if task.get("status") == "pending":
                    task["status"] = "processing"
                    task["retries"] = task.get("retries", 0) + 1
                elif task.get("status") == "failed" and task.get("retries", 0) < _queue_max_retries:
                    task["status"] = "processing"
                    task["retries"] = task.get("retries", 0) + 1
                else:
                    _queue.pop(0)
                    task = None
        if task:
            try:
                logging.info("Processing print task %s (attempt %d)" % (task.get('id', 'unknown'), task.get('retries', 1)))
                result = _do_print(task)
                if result.get("success"):
                    _inc_success()
                    with _queue_lock:
                        if _queue and _queue[0].get("id") == task.get("id"):
                            _queue.pop(0)
                else:
                    _inc_failed()
                    with _queue_lock:
                        if _queue and _queue[0].get("id") == task.get("id"):
                            if task.get("retries", 0) >= _queue_max_retries:
                                _queue.pop(0)
                                logging.warning("Print task %s failed after %d attempts" % (task.get('id'), _queue_max_retries))
                            else:
                                _queue[0]["status"] = "failed"
                                logging.warning("Print task %s failed, will retry" % task.get('id'))
            except Exception as e:
                _inc_failed()
                logging.error("Queue print exception: %s" % e)
                with _queue_lock:
                    if _queue and _queue[0].get("id") == task.get("id"):
                        task["status"] = "failed"
                        task["error"] = str(e)
        time.sleep(0.5)


def _do_print(task: dict) -> dict:
    printer = task.get("printer", {})
    printer_id = task.get("printer_id", "")
    if printer_id and printer_id in PRINTER_CONFIGS:
        cfg = PRINTER_CONFIGS[printer_id]
        printer.setdefault("type", cfg.get("type", "network"))
        printer.setdefault("ip", cfg.get("ip", ""))
        printer.setdefault("port", cfg.get("port", PRINTER_DEFAULT_PORT))
        printer.setdefault("vendor_id", cfg.get("vendor_id", ""))
        printer.setdefault("product_id", cfg.get("product_id", ""))
    ptype = printer.get("type", "network")
    ip = printer.get("ip", "")
    port = int(printer.get("port", PRINTER_DEFAULT_PORT))
    vendor_id = printer.get("vendor_id", "")
    product_id = printer.get("product_id", "")
    print_type = task.get("type", "")
    template = task.get("template", {})
    items = task.get("items", [task])

    # Normalize template to print_type
    if not print_type and isinstance(template, str):
        template_str = template
        if template_str in ("price_tag", "price_tag_30x40", "price_tag_40x60"):
            print_type = "price_tag"
        elif template_str in ("shelf_label", "shelf_label_40x30", "shelf_label_40x20"):
            print_type = "shelf_label"
        elif template_str in ("cell_label",):
            print_type = "cell_label"
        elif template_str in ("barcode",):
            print_type = "barcode"
        elif template_str in ("receipt",):
            print_type = "receipt"
        elif template_str in ("escpos_price_tag",):
            print_type = "escpos_price_tag"
        elif template_str in ("escpos_shelf_label",):
            print_type = "escpos_shelf_label"
        else:
            print_type = "price_tag"

    if not print_type:
        print_type = task.get("type", "price_tag")

    results = []
    for item in items:
        if print_type == "receipt":
            data = _escpos_receipt(item)
            if ptype == "usb" and vendor_id and product_id:
                r = _print_usb(vendor_id, product_id, data)
            else:
                r = _send_tcp(ip, port, data)
        elif print_type == "escpos_price_tag":
            data = _escpos_price_tag(item)
            if ptype == "usb" and vendor_id and product_id:
                r = _print_usb(vendor_id, product_id, data)
            else:
                r = _send_tcp(ip, port, data)
        elif print_type == "escpos_shelf_label":
            data = _escpos_shelf_label(item)
            if ptype == "usb" and vendor_id and product_id:
                r = _print_usb(vendor_id, product_id, data)
            else:
                r = _send_tcp(ip, port, data)
        elif print_type in ("price_tag", "price_tag_30x40", "price_tag_40x60", "shelf_label", "shelf_label_40x30", "shelf_label_40x20"):
            if ptype in ("usb",):
                data = _escpos_label(item, print_type)
                if vendor_id and product_id:
                    r = _print_usb(vendor_id, product_id, data)
                else:
                    r = _send_tcp(ip, port, data)
            else:
                name = item.get("name", "")
                price = str(item.get("price", "0.00"))
                sku = item.get("sku", "")
                spec = item.get("spec", "")
                barcode = item.get("barcode", "")
                if print_type in ("shelf_label", "shelf_label_40x30", "shelf_label_40x20"):
                    tspl = _tspl_shelf_label(
                        item.get("zone_name", ""),
                        item.get("rack_name", ""),
                        template.get("width", 40) if isinstance(template, dict) else 40,
                        template.get("height", 30) if isinstance(template, dict) else 30)
                else:
                    tw = template.get("width", 30) if isinstance(template, dict) else 30
                    th = template.get("height", 40) if isinstance(template, dict) else 40
                    tspl = _tspl_price_tag(name, price, sku, spec, tw, th, barcode,
                                           template if isinstance(template, dict) else None,
                                           unit=item.get("unit", ""))
                r = _send_tspl(ip, port, tspl)
        elif print_type == "cell_label":
            tspl = _tspl_cell_label(
                item.get("cell_code", ""),
                item.get("product_names", []),
                template.get("width", 30) if isinstance(template, dict) else 30,
                template.get("height", 20) if isinstance(template, dict) else 20)
            r = _send_tspl(ip, port, tspl)
        elif print_type == "barcode":
            tspl = _tspl_barcode_label(
                item.get("barcode") or item.get("sku", ""), item.get("name", ""),
                template.get("width", 25) if isinstance(template, dict) else 25,
                template.get("height", 15) if isinstance(template, dict) else 15)
            r = _send_tspl(ip, port, tspl)
        else:
            name = item.get("name", "")
            price = str(item.get("price", "0.00"))
            sku = item.get("sku", "")
            spec = item.get("spec", "")
            tw = template.get("width", 30) if isinstance(template, dict) else 30
            th = template.get("height", 40) if isinstance(template, dict) else 40
            tspl = _tspl_price_tag(name, price, sku, spec, tw, th, item.get("barcode", ""),
                                   template if isinstance(template, dict) else None,
                                   unit=item.get("unit", ""))
            r = _send_tspl(ip, port, tspl)
        results.append(r)
    success_count = sum(1 for r in results if r.get("success"))
    return {"success": success_count == len(results), "printed": len(results), "success_count": success_count, "results": results}


# ─── Metrics ─────────────────────────────────────────────────

def _inc_total():
    with _print_lock:
        _print_stats["total"] += 1

def _inc_success():
    with _print_lock:
        _print_stats["success"] += 1

def _inc_failed():
    with _print_lock:
        _print_stats["failed"] += 1


# ─── HTTP Handler ────────────────────────────────────────────

class PrintHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        logging.info(f"{self.client_address[0]} - {format % args}")

    def _send_json(self, data: dict, status: int = 200, request_id: str = ""):
        if request_id and isinstance(data, dict):
            data["_request_id"] = request_id
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_OPTIONS(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        self._send_json({}, request_id=self.request_id)

    def do_GET(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path.rstrip("/")

        # Health check（2026-09-10 排雷②：/print/ping、/api/print/ping 移出 health——
        # 此前被这里截胡永远返回 healthy，打印机离线也显示在线；现改回真实探测）
        if path in ("/status", "/health"):
            return self._send_json(ok_response(data={
                "service": "retaildemo-print",
                "uptime": round(time.time() - START_TIME, 2),
                "status": "healthy",
            }), request_id=self.request_id)

        # Stats
        if path == "/stats":
            with _print_lock:
                stats = dict(_print_stats)
            return self._send_json(ok_response(data=stats), request_id=self.request_id)

        # List printers
        if path == "/printers":
            return self._send_json(ok_response(data=list(PRINTER_CONFIGS.values())), request_id=self.request_id)

        # Default printer
        if path == "/printers/default":
            for pid, cfg in PRINTER_CONFIGS.items():
                if cfg.get("is_default"):
                    return self._send_json(ok_response(data={"id": pid, **cfg}), request_id=self.request_id)
            return self._send_json(err_response("未设置默认打印机"), 404, request_id=self.request_id)

        # Printer detail
        if path.startswith("/printers/"):
            pid = path[len("/printers/"):]
            if pid and pid in PRINTER_CONFIGS:
                return self._send_json(ok_response(data={"id": pid, **PRINTER_CONFIGS[pid]}), request_id=self.request_id)
            return self._send_json(err_response("打印机不存在"), 404, request_id=self.request_id)

        # Queue status
        if path in ("/print/queue", "/api/print/queue"):
            with _queue_lock:
                queue_data = [{"id": t.get("id", ""), "type": t.get("type", ""), "status": "pending"} for t in _queue]
            return self._send_json(ok_response(data={"queue": queue_data, "count": len(queue_data)}), request_id=self.request_id)

        # Ping printer — 2026-09-10 排雷②：恢复真实探测（旧分支被 health 截胡成死代码），
        # 走 _ping_printer → _send_tcp（内网防护）
        if path in ("/print/ping", "/api/print/ping"):
            ip = (qs.get("ip") or [""])[0]
            port_raw = (qs.get("port") or [str(PRINTER_DEFAULT_PORT)])[0]
            if not ip:
                return self._send_json(err_response("缺少 ip 参数", "BAD_REQUEST", "请提供打印机 IP 地址"), 400, request_id=self.request_id)
            try:
                port = int(port_raw)
            except (TypeError, ValueError):
                return self._send_json(err_response("端口不合法", "BAD_REQUEST", "打印机端口必须是数字"), 400, request_id=self.request_id)
            result = _ping_printer(ip, port)
            return self._send_json(ok_response(data=result), request_id=self.request_id)

        # List jobs
        if path in ("/print/jobs", "/api/print/jobs"):
            with _queue_lock:
                jobs = [{"id": j.get("id"), "type": j.get("type"), "status": j.get("status", "pending"), "retries": j.get("retries", 0), "error": j.get("error", "")} for j in _queue]
            return self._send_json(ok_response(data={"jobs": jobs, "count": len(jobs)}), request_id=self.request_id)

        self._send_json(err_response(f"未知路径: {path}"), 404, request_id=self.request_id)

    def do_POST(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))) if int(self.headers.get("Content-Length", 0)) else b"{}"
        data, err = parse_json_body(body)
        if err:
            return self._send_json(err, 400, request_id=self.request_id)

        # Save printer config
        if path == "/printers":
            data, err = validate_request(body, {
                "name": {"required": True, "type": str, "message": "请填写打印机名称"},
                "id": {"default": uuid.uuid4().hex[:12]},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            pid = data["id"]
            PRINTER_CONFIGS[pid] = {k: v for k, v in data.items() if k != "id"}
            logging.info(f"Printer saved: {pid} ({data['name']})")
            return self._send_json(ok_response(data={"id": pid, **PRINTER_CONFIGS[pid]}), request_id=self.request_id)

        # Print
        if path in ("/print", "/api/print"):
            result = _do_print(data)
            _inc_total()
            if result.get("success"):
                _inc_success()
            else:
                _inc_failed()
            status = 200 if result.get("success") else 500
            return self._send_json(ok_response(data=result) if result.get("success") else err_response(result.get("error", "打印失败")), status, request_id=self.request_id)

        # Print send (called from usePrint.js)
        if path in ("/print/send", "/api/print/send"):
            result = _do_print(data)
            _inc_total()
            if result.get("success"):
                _inc_success()
            else:
                _inc_failed()
            status = 200 if result.get("success") else 500
            return self._send_json(ok_response(data=result) if result.get("success") else err_response(result.get("error", "打印失败")), status, request_id=self.request_id)

        # Test print
        if path == "/print/test":
            data, err = validate_request(body, {
                "printer": {"required": True, "type": dict, "message": "请提供打印机信息"},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            printer = data["printer"]
            ip = printer.get("ip", "")
            port = int(printer.get("port", PRINTER_DEFAULT_PORT))
            if not ip:
                return self._send_json(err_response("缺少打印机IP"), 400, request_id=self.request_id)
            test_data = "SIZE 30 mm, 40 mm\nGAP 2 mm, 0 mm\nCLS\nTEXT 15,5,\"TSS24.BF\",0,1,1,\"测试页\"\nTEXT 15,35,\"TSS24.BF\",0,2,2,\"%s\"\nTEXT 15,75,\"TSS24.BF\",0,1,1,\"打印服务正常\"\nPRINT 1\n" % STORE_NAME
            result = _send_tspl(ip, port, test_data)
            _inc_total()
            if result.get("success"):
                _inc_success()
            else:
                _inc_failed()
            status = 200 if result.get("success") else 500
            return self._send_json(ok_response(data=result) if result.get("success") else err_response("测试打印失败"), status, request_id=self.request_id)

        # Queue print
        if path in ("/print/queue", "/api/print/queue"):
            task_id = uuid.uuid4().hex[:12]
            data["id"] = task_id
            data.setdefault("status", "pending")
            with _queue_lock:
                _queue.append(data)
            logging.info(f"Print queued: {task_id} ({data.get('type', 'unknown')})")
            return self._send_json(ok_response(data={"id": task_id, "status": "queued"}), request_id=self.request_id)

        # Cancel queue
        if path in ("/print/queue/cancel", "/api/print/queue/cancel"):
            with _queue_lock:
                count = len(_queue)
                _queue.clear()
            logging.info(f"Queue cancelled: {count} tasks")
            return self._send_json(ok_response(data={"cancelled": count}), request_id=self.request_id)

        # Discover printers
        if path in ("/print/discover", "/api/print/discover"):
            subnet = data.get("subnet", "192.168.1")
            try:
                printers = _discover_printers(subnet)
            except ValueError:
                return self._send_json(err_response("仅允许扫描私网网段（形如 192.168.1）", "FORBIDDEN_SUBNET", "网段不合法，请填写店内局域网网段"), 400, request_id=self.request_id)
            return self._send_json(ok_response(data={"printers": printers, "count": len(printers)}), request_id=self.request_id)

        # Clear jobs
        if path in ("/print/jobs/clear", "/api/print/jobs/clear"):
            with _queue_lock:
                _queue.clear()
            return self._send_json(ok_response(data={"cleared": True}), request_id=self.request_id)

        # List jobs (POST variant)
        if path in ("/print/jobs", "/api/print/jobs"):
            with _queue_lock:
                jobs = [{"id": j.get("id"), "type": j.get("type"), "status": j.get("status", "pending"), "retries": j.get("retries", 0), "error": j.get("error", "")} for j in _queue]
            return self._send_json(ok_response(data={"jobs": jobs, "count": len(jobs)}), request_id=self.request_id)

        self._send_json(err_response(f"未知路径: {path}"), 404, request_id=self.request_id)

    def do_DELETE(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path.startswith("/printers/"):
            pid = path[len("/printers/"):]
            if pid and pid in PRINTER_CONFIGS:
                cfg = PRINTER_CONFIGS.pop(pid)
                logging.info(f"Printer deleted: {pid} ({cfg.get('name', '')})")
                return self._send_json(ok_response(data={"deleted": pid}), request_id=self.request_id)
            return self._send_json(err_response("打印机不存在"), 404, request_id=self.request_id)

        self._send_json(err_response(f"未知路径: {path}"), 404, request_id=self.request_id)


class ThreadedPrintHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def _run_print_server():
    setup_logging("print")
    queue_thread = threading.Thread(target=_process_queue, daemon=True)
    queue_thread.start()
    server = ThreadedPrintHTTPServer((LISTEN_HOST, LISTEN_PORT), PrintHandler)
    logging.info(f"Print service listening on {LISTEN_HOST}:{LISTEN_PORT}")
    signal.signal(signal.SIGTERM, lambda s, f: server.shutdown())
    server.socket.settimeout(0.5)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Shutting down...")
    finally:
        server.server_close()


if __name__ == "__main__":
    _run_print_server()
