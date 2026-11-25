"""熊掌柜「库存状况表」→ ``wujin.db`` 全维度同步（S7，**默认 dry-run，不写库**）。

拍板（2026-09-04 老板，原文：成本当然以管家婆为准 / APP 库存也要和上游同步 /
凡是涉及对账相关的信息都要以上游为准）：

- ``cost_price``：报表「成本单价」有值(>0) → 直接覆盖本地（报表=移动加权成本，
  是管家婆权威口径）；报表空/0 → 不动本地并计数留痕。
- ``stock_qty``：一律以报表为准覆盖（行内空按 0）；报表没有的本地 sku 不动。
  ⚠️ 副作用已知：APP 调账/盘点写的数会被下次同步刷回上游——老板知情选择。
- 快照留痕表 ``xzg_stock_snapshot`` 每次 ``--apply`` 整表重建（含时间戳），
  仅用于对账追溯，不承担展示。

红线不变：上游绝对只读（Export/下载均为只读取数）；``location/min_stock/
warehouse/jieba_tokens`` 本地自有列绝不写入。数据源接口契约见
``docs/steps/S7.md`` §0 与 ``docs/tasks.yaml`` S7.notes。
"""
from __future__ import annotations

import sqlite3
import sys
import re
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from io import BytesIO
from typing import Any, Dict, List, Optional, Tuple

if sys.stdout and hasattr(sys.stdout, "reconfigure"):  # pragma: no cover
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

#: 报表期望列（顺序按 2026-09-04 实测；按表头名定位，顺序变化不破坏解析）。
EXPECTED_HEADERS = ("商品条码", "商品编号", "商品名称", "基本单位",
                    "库存数量", "成本单价", "成本金额")

#: 与 xzg_sync.PRICE_EPSILON 同口径：差在容差内视为无变化（精度噪声不算差异）。
STOCK_EPSILON = 0.005

SNAPSHOT_TABLE = "xzg_stock_snapshot"


# ---------------------------------------------------------------------------
# 解析（纯函数：bytes → 行对象）
# ---------------------------------------------------------------------------

@dataclass
class StockRow:
    sku: str
    barcode: Optional[str] = None
    name: Optional[str] = None
    unit: Optional[str] = None
    stock_qty: Optional[float] = None
    cost_unit: Optional[float] = None
    cost_amount: Optional[float] = None


def _cell_text(c) -> str:
    v = c.find(NS + "v")
    if c.get("t") == "inlineStr":
        return "".join(x.text or "" for x in c.iter(NS + "t")).strip()
    return (v.text or "").strip() if v is not None else ""


def _cell_ref(c) -> str:
    return c.get("r") or ""


def _col_index(ref: str) -> int:
    """'BC12' → 列序号（0 起）。"""
    n = 0
    for ch in ref:
        if ch.isalpha():
            n = n * 26 + (ord(ch.upper()) - ord("A") + 1)
        else:
            break
    return n - 1 if n else 0


def fnum(text: str) -> Optional[float]:
    """数字单元格文本 → float；空/非数返回 None。**不推断不补值**。"""
    if text is None:
        return None
    t = str(text).replace(",", "").strip()
    if not t:
        return None
    try:
        return float(t)
    except ValueError:
        return None


def parse_stock_condition(blob: bytes) -> Tuple[List[StockRow], List[str]]:
    """解析「库存状况表」xlsx 字节流。返回 ``(数据行, 异常行文本列表)``。

    跳过：表头行（按列名定位）、编号为空/含「合计」的汇总行。
    不引第三方库（CONVENTIONS §5 纯标准库）。
    """
    zf = zipfile.ZipFile(BytesIO(blob))
    shared: List[str] = []
    if "xl/sharedStrings.xml" in zf.namelist():
        _ss_xml = zf.read("xl/sharedStrings.xml")
        if b"<!DOCTYPE" in _ss_xml[:2048] or b"<!ENTITY" in _ss_xml[:2048]:
            raise ValueError("xml doctype/entity rejected")  # F6 XXE guard
        ss = ET.fromstring(_ss_xml)
        for si in ss:
            shared.append("".join(t.text or "" for t in si.iter(NS + "t")))

    def cell_text(c) -> str:
        if c.get("t") == "s":
            v = c.find(NS + "v")
            return shared[int(v.text)].strip() if v is not None else ""
        return _cell_text(c)

    _sheet_xml = zf.read("xl/worksheets/sheet1.xml")
    if b"<!DOCTYPE" in _sheet_xml[:2048] or b"<!ENTITY" in _sheet_xml[:2048]:
        raise ValueError("xml doctype/entity rejected")  # F6 XXE guard
    sheet = ET.fromstring(_sheet_xml)
    rows = sheet.findall(".//" + NS + "row")
    if not rows:
        raise ValueError("库存状况表为空 xlsx（无 row）")

    def row_cells(row) -> Dict[int, str]:
        out: Dict[int, str] = {}
        for c in row.findall(NS + "c"):
            idx = _col_index(_cell_ref(c)) if _cell_ref(c) else len(out)
            out[idx] = cell_text(c)
        return out

    header = row_cells(rows[0])
    col_of = {name: idx for idx, name in header.items()}
    missing = [h for h in EXPECTED_HEADERS if h not in col_of]
    if missing:
        raise ValueError("库存状况表缺列：%s（表头=%s）" % (missing, list(header.values())))

    data_rows: List[StockRow] = []
    bad_rows: List[str] = []
    for row in rows[1:]:
        cells = row_cells(row)
        g = lambda h: cells.get(col_of[h], "")
        sku = g("商品编号")
        if not sku or "合计" in sku or "小计" in sku:
            if sku:
                bad_rows.append(sku)
            continue
        data_rows.append(StockRow(
            sku=sku,
            barcode=g("商品条码") or None,
            name=g("商品名称") or None,
            unit=g("基本单位") or None,
            stock_qty=fnum(g("库存数量")),
            cost_unit=fnum(g("成本单价")),
            cost_amount=fnum(g("成本金额")),
        ))
    return data_rows, bad_rows


def parse_stock_condition_json(items: List[Dict[str, Any]]) -> Tuple[List[StockRow], List[str]]:
    """解析「库存状况表」**JSON 版**（``Report/Stock/StockCondition/Get``）。

    与 :func:`parse_stock_condition` 产出**同一种** :class:`StockRow`，因此
    :func:`diff_stock` / :func:`apply_stock` 一行不用改——取数方式换了，落库口径不变。

    字段对应（2026-09-13 实测 12670 条）：

    ==================  ============================================
    上游 JSON 字段       本地 StockRow
    ==================  ============================================
    ``productCode``      ``sku``（对齐键，与 xlsx 的「商品编号」同口径）
    ``barCode``          ``barcode``
    ``productName``      ``name``
    ``standardName``     ``unit``（见下注）
    ``stockQty``         ``stock_qty``
    ``costPrice``        ``cost_unit``
    ``costTotal``        ``cost_amount``
    ==================  ============================================

    比 xlsx 版更省事的地方：数字已是 JSON number，无需单元格文本转换；
    顺带还能拿到 ``retailPrice`` 等价格档位（本函数不产出，留给价格通道）。

    **单位口径差异（2026-09-13 实测）**：JSON 版**没有**「基本单位」字段（71 个字段
    里只有 ``standardName``=规格名 与 ``subUnitName``=副单位名，后者对普通商品恒空），
    而 xlsx 有「基本单位」列。故此处以 ``standardName`` 补 ``unit``——
    ``unit`` 只进 ``xzg_stock_snapshot`` 留痕表、不参与 diff、也不写 ``products``，
    对账口径（stock_qty / cost_unit / cost_amount）**逐条零差异**（已实测 12670 条全等）。

    :return: ``(数据行, 异常行文本列表)``；编号为空或含「合计/小计」的行按 xlsx 同规则跳过。
    """
    data_rows: List[StockRow] = []
    bad_rows: List[str] = []
    for item in items or []:
        if not isinstance(item, dict):
            continue
        sku = str(item.get("productCode") or "").strip()
        if not sku or "合计" in sku or "小计" in sku:
            if sku:
                bad_rows.append(sku)
            continue
        data_rows.append(StockRow(
            sku=sku,
            barcode=(str(item.get("barCode")).strip() or None) if item.get("barCode") else None,
            name=(str(item.get("productName")).strip() or None) if item.get("productName") else None,
            unit=(str(item.get("standardName")).strip() or None)
            if item.get("standardName") else None,
            stock_qty=_json_num(item.get("stockQty")),
            cost_unit=_json_num(item.get("costPrice")),
            cost_amount=_json_num(item.get("costTotal")),
        ))
    return data_rows, bad_rows


def _json_num(value: Any) -> Optional[float]:
    """JSON 数字 → float；空/非数返回 None（**不推断不补值**，与 :func:`fnum` 同纪律）。"""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------------------
# 差异计算（纯计算，不写库）
# ---------------------------------------------------------------------------

@dataclass
class StockDiff:
    local_count: int = 0
    report_rows: int = 0
    cost_changes: List[Dict[str, Any]] = field(default_factory=list)    # 将覆盖 cost_price
    stock_changes: List[Dict[str, Any]] = field(default_factory=list)   # 将覆盖 stock_qty
    cost_skipped_zero: int = 0        # 报表成本空/0，本地不动（留痕计数）
    stock_null_as_zero: int = 0       # 报表库存为空按 0 处理
    unmatched_sku: List[str] = field(default_factory=list)  # 报表有、本地无
    bad_rows: List[str] = field(default_factory=list)


def diff_stock(local_rows: Dict[str, Dict[str, Any]],
               rows: List[StockRow]) -> StockDiff:
    """对齐键=sku（**禁止**按条码/名称匹配——脏条码多）。纯计算。

    :param local_rows: sku → {cost_price, stock_qty, name}
    """
    diff = StockDiff(local_count=len(local_rows), report_rows=len(rows))
    report_skus = set()
    for r in rows:
        report_skus.add(r.sku)
        local = local_rows.get(r.sku)
        if local is None:
            diff.unmatched_sku.append(r.sku)
            continue

        # 成本：报表有值(>0) 才覆盖（拍板①）；与 GetAll 的 628 条不一致一并刷掉
        if r.cost_unit is not None and r.cost_unit > 0:
            old = local.get("cost_price")
            if old is None or abs(float(old) - r.cost_unit) > STOCK_EPSILON:
                diff.cost_changes.append({"sku": r.sku, "name": r.name,
                                          "old": old, "new": r.cost_unit})
        else:
            diff.cost_skipped_zero += 1

        # 库存：一律以报表为准（拍板②）；行内空按 0
        new_qty = r.stock_qty if r.stock_qty is not None else 0.0
        if r.stock_qty is None:
            diff.stock_null_as_zero += 1
        old_qty = local.get("stock_qty")
        if old_qty is None or abs(float(old_qty) - new_qty) > STOCK_EPSILON:
            diff.stock_changes.append({"sku": r.sku, "name": r.name,
                                       "old": old_qty, "new": new_qty})

    return diff


# ---------------------------------------------------------------------------
# 落库（apply=True 才写；调用方必须先跑备份）
# ---------------------------------------------------------------------------

@dataclass
class StockApplyResult:
    cost_updated: int = 0
    stock_updated: int = 0
    snapshot_rows: int = 0


def _ensure_snapshot_table(conn: sqlite3.Connection) -> None:
    conn.execute(
        "CREATE TABLE IF NOT EXISTS xzg_stock_snapshot ("
        "sku TEXT PRIMARY KEY, barcode TEXT, name TEXT, unit TEXT,"
        "stock_qty REAL, cost_unit REAL, cost_amount REAL, synced_at TEXT)"
    )


def apply_stock(conn: sqlite3.Connection,
                diff: StockDiff,
                rows: List[StockRow],
                apply: bool = False,
                ts: Optional[str] = None) -> StockApplyResult:
    """dry-run 不写任何字节；apply 时：整表重建快照 + 按 diff 覆盖 products。

    只写 ``cost_price`` / ``stock_qty`` / ``updated_at`` 三列；
    ``location/min_stock/warehouse/jieba_tokens`` 绝不在语句中出现。
    """
    result = StockApplyResult()
    if not apply:
        return result

    ts = ts or time_now()
    _ensure_snapshot_table(conn)
    conn.execute("DELETE FROM xzg_stock_snapshot")
    conn.executemany(
        "INSERT INTO xzg_stock_snapshot (sku, barcode, name, unit, stock_qty, cost_unit, cost_amount, synced_at)"
        " VALUES (?,?,?,?,?,?,?,?)",
        [(r.sku, r.barcode, r.name, r.unit,
          r.stock_qty if r.stock_qty is not None else 0.0,
          r.cost_unit, r.cost_amount, ts) for r in rows],
    )
    result.snapshot_rows = len(rows)

    grouped: Dict[str, Dict[str, float]] = {}
    for c in diff.cost_changes:
        grouped.setdefault(c["sku"], {})["cost_price"] = c["new"]
    for c in diff.stock_changes:
        grouped.setdefault(c["sku"], {})["stock_qty"] = c["new"]

    for sku, values in grouped.items():
        cols = sorted(values)
        params = tuple(values[c] for c in cols) + (ts, sku)
        if cols == ["cost_price"]:
            sql = "UPDATE products SET cost_price=?, updated_at=? WHERE sku=?"
        elif cols == ["stock_qty"]:
            sql = "UPDATE products SET stock_qty=?, updated_at=? WHERE sku=?"
        elif cols == ["cost_price", "stock_qty"]:
            sql = "UPDATE products SET cost_price=?, stock_qty=?, updated_at=? WHERE sku=?"
        else:
            raise ValueError("illegal update columns: " + repr(cols))  # F6: static dispatch
        conn.execute(sql, params)
        result.cost_updated += "cost_price" in values
        result.stock_updated += "stock_qty" in values

    conn.commit()
    return result


def time_now() -> str:
    """与 products.updated_at 现有格式一致：``YYYY-MM-DD HH:MM:SS``。"""
    import time
    return time.strftime("%Y-%m-%d %H:%M:%S")


# ---------------------------------------------------------------------------
# 多规格附表（4Tc 按需拉取；本期不进搜索不进 APP，纯备查）
# ---------------------------------------------------------------------------

STANDARDS_TABLE = "xzg_product_standards"


def apply_standards(conn: sqlite3.Connection, client, products) -> int:
    """对 ``products=[{id, sku}, ...]``（多规格/差异商品）逐个调 4Tc 只读接口，
    整表重建附表。返回写入行数。仅在 ``--apply`` 且显式 ``--standards`` 时调用。"""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS xzg_product_standards ("
        "product_id TEXT, sku TEXT, row_index INTEGER, standard_name TEXT,"
        "buying_price REAL, retail_price REAL, wholesale_price REAL,"
        "member_price REAL, bar_code TEXT, synced_at TEXT)"
    )
    conn.execute("DELETE FROM xzg_product_standards")
    ts = time_now()
    total = 0
    for p in products:
        for std in client.fetch_standards(p["id"]):
            conn.execute(
                "INSERT INTO xzg_product_standards VALUES (?,?,?,?,?,?,?,?,?,?)",
                (p["id"], p["sku"], std.get("rowIndex"), std.get("standardName"),
                 std.get("buyingPrice"), std.get("retailPrice"),
                 std.get("wholesalePrice"), std.get("memberPrice"),
                 std.get("barCode"), ts))
            total += 1
    conn.commit()
    return total


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------

def render_stock_report(diff: StockDiff,
                        applied: Optional[StockApplyResult] = None) -> str:
    lines: List[str] = []
    add = lines.append
    add("")
    add("-" * 72)
    add("库存状况表（全维度）%s" % ("——已落库" if applied else "——DRY-RUN 对账，未写库"))
    add("-" * 72)
    add("报表数据行 %d / 本地商品 %d" % (diff.report_rows, diff.local_count))
    add("  成本价将覆盖：%d 条（报表=移动加权权威；含与 GetAll 进价不一致项）"
        % len(diff.cost_changes))
    for c in diff.cost_changes[:10]:
        add("     %-16s %-18s %s → %s" % (c["sku"], (c["name"] or "")[:18], c["old"], c["new"]))
    if len(diff.cost_changes) > 10:
        add("     ... 其余 %d 条见报告文件" % (len(diff.cost_changes) - 10))
    add("  成本空/0 跳过（本地不动，留痕）：%d 条" % diff.cost_skipped_zero)
    add("  库存将覆盖：%d 条（空按 0：%d 条）"
        % (len(diff.stock_changes), diff.stock_null_as_zero))
    for c in diff.stock_changes[:10]:
        add("     %-16s %-18s %s → %s" % (c["sku"], (c["name"] or "")[:18], c["old"], c["new"]))
    if len(diff.stock_changes) > 10:
        add("     ... 其余 %d 条见报告文件" % (len(diff.stock_changes) - 10))
    add("  报表 sku 本地无（不动，仅列出）：%d 条 %s"
        % (len(diff.unmatched_sku), diff.unmatched_sku[:5]))
    add("  汇总/合计行跳过：%d 条" % len(diff.bad_rows))
    if applied:
        add("落库：成本覆盖 %d / 库存覆盖 %d / 快照表 %s 重建 %d 行"
            % (applied.cost_updated, applied.stock_updated,
               SNAPSHOT_TABLE, applied.snapshot_rows))
    add("-" * 72)
    return "\n".join(lines)


def stock_diff_to_dict(diff: StockDiff) -> Dict[str, Any]:
    return {
        "local_count": diff.local_count,
        "report_rows": diff.report_rows,
        "cost_changes": diff.cost_changes,
        "cost_skipped_zero": diff.cost_skipped_zero,
        "stock_changes": diff.stock_changes,
        "stock_null_as_zero": diff.stock_null_as_zero,
        "unmatched_sku": diff.unmatched_sku,
        "bad_rows": diff.bad_rows,
    }
