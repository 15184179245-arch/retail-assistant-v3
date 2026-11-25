"""熊掌柜 → ``wujin.db`` 差异计算与落库（**默认 dry-run，不写库**）。

设计原则（老板 2026-09-03 定调）
--------------------------------

1. **默认只读**：不传 ``apply=True`` 时只算差异、出报告，一个字节都不写库。
2. **不臆造数据**：上游为空 → 不补值、不推断、不生成兜底条码。
3. **不破坏本地数据**：
   - :data:`LOCAL_OWNED_COLUMNS` 列出的字段（库存 / 货位 / 预警值等）**永不写入**
   - 上游某字段为空而本地非空时，**不清空本地值**，只列入「上游为空」待核对清单
     （条码典型场景：店员拿实物扫进了本地，上游从未录入；清掉就丢了）
4. **脏数据原样落库 + 单独标记**：见 :mod:`xzg_mapper` 的 ``FLAG_DIRTY_*``。
"""
from __future__ import annotations

import os
import re
import sqlite3
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from .xzg_mapper import (
    FLAG_INACTIVE,
    FLAG_MULTI_STANDARD,
    FLAG_NO_BARCODE,
    FLAG_NO_NAME,
    INSERT_DESCRIPTION_DEFAULT,
    INSERT_ONLY_COLUMNS,
    LOCAL_OWNED_COLUMNS,
    REVIEW_FLAGS,
    UPSTREAM_COLUMNS,
    map_product,
)
from utils.pinyintools import full_pinyin, initials  # noqa: E402

if sys.stdout and hasattr(sys.stdout, "reconfigure"):  # pragma: no cover
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

#: 参与差异比对的上游权威列（``sku`` 是主键，不参与比对）。
COMPARE_COLUMNS: Tuple[str, ...] = (
    "barcode",
    "name",
    "spec",
    "model",
    "unit",
    "category",
    "brand",
    "cost_price",
    "sell_price",
    "pinyin",
    "initials",
)

#: 需要参与策略判断的业务列（拼音是派生列，单独处理）。
BUSINESS_COLUMNS: Tuple[str, ...] = (
    "barcode", "name", "spec", "model", "unit", "category", "brand", "cost_price", "sell_price",
)

PRICE_COLUMNS: Tuple[str, ...] = ("cost_price", "sell_price")

#: 高亮差异类别：老板最需要人工核对的几类。
HIGHLIGHT_COLUMNS = ("barcode", "cost_price", "sell_price")

# ---------------------------------------------------------------------------
# 覆盖策略（关键：不同字段风险不同，不能一刀切全量覆盖）
# ---------------------------------------------------------------------------

#: ``always``   — 上游值权威，直接覆盖
#: ``fill``     — 只在本地为空时补录，不覆盖已有值
#: ``preserve`` — 已有行一律不覆盖，只进报告待人工核对
UPDATE_POLICY: Dict[str, str] = {
    # 本地为空才补条码；两边都有值时只报告（条码来自原包装，本地可能是店员实扫的）
    "barcode": "fill",
    # 商品名差异仅 129 条，以上游为准
    "name": "always",
    # 规格差异 7857 条（67%），疑似两套口径，不敢自动覆盖
    "spec": "preserve",
    # 上游「型号」（雷士等填出厂货号 DD.00000001）：2026-09-19 起新收的字段，
    # 上游权威直接覆盖——本地历史从未存过该列，首轮同步即完成全量回填；
    # 上游清空则跟随清空（不臆造、不留旧货号误导搜索）
    "model": "always",
    # 单位本地/上游混用「个」与「Pcs」，双向混乱，覆盖只是换个乱法
    "unit": "preserve",
    # 分类差异 10992 条（94%）= 两套分类体系，覆盖会毁掉 APP 分类筛选
    "category": "preserve",
    # 品牌：本地视为空才补（含占位符"默认品牌"，见 BRAND_PLACEHOLDER）
    "brand": "fill",
    # 老板明确要同步的两个字段
    "cost_price": "always",
    "sell_price": "always",
}


# 拍板 2026-09-04（老板）：本地 brand='默认品牌' 是批量建档的占位符，不是真品牌，
# fill 时与"空"同等对待，允许上游真实品牌补入；空缺的品牌后续靠盘点慢慢补。
BRAND_PLACEHOLDER = "默认品牌"


def is_brand_placeholder(value: Any) -> bool:
    """判断品牌值是否为占位符（'默认品牌'），视为等价于空。"""
    return isinstance(value, str) and value.strip() == BRAND_PLACEHOLDER

#: 价格比较容差（元）。本地历史存 6 位小数、上游 10 位，
#: 13.213333 vs 13.2133333333 这类差 1e-10 的是精度噪声，不是真差异。
PRICE_EPSILON = 0.005

_UNIQUE_INDEX_NAME = "idx_products_sku"


def now_str() -> str:
    """与 products.updated_at 现有格式保持一致：``YYYY-MM-DD HH:MM:SS``。"""
    return time.strftime("%Y-%m-%d %H:%M:%S")


def is_empty(value: Any) -> bool:
    """``None`` / 空串 / 纯空白串都视为空。**注意：0 与 0.0 不是空**。"""
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    return False


def same_value(a: Any, b: Any) -> bool:
    """比较两个字段值是否等价（价格用 :data:`PRICE_EPSILON` 容差，空值互相等价）。"""
    if is_empty(a) and is_empty(b):
        return True
    if is_empty(a) or is_empty(b):
        return False
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(float(a) - float(b)) < PRICE_EPSILON
    return str(a) == str(b)


def is_price_zero(value: Any) -> bool:
    """价格为零（含 0、0.0、'0'、空）视为「未设置」，不是真值。"""
    if is_empty(value):
        return True
    try:
        return float(value) == 0.0
    except (TypeError, ValueError):
        return False


@dataclass
class DiffResult:
    """一次同步的差异结果。"""

    upstream_count: int = 0
    local_count: int = 0
    scanned: int = 0
    truncated: bool = False

    inserts: List[Dict[str, Any]] = field(default_factory=list)
    updates: List[Dict[str, Any]] = field(default_factory=list)
    #: 高争议字段（如 category/spec/unit 两套口径），已有行保守不覆盖，交人工核对
    preserved: List[Dict[str, Any]] = field(default_factory=list)
    #: 上游价格为零但本地非零 → 不覆盖，交人工核对（零通常代表「未设置」而非白送）
    suspicious: List[Dict[str, Any]] = field(default_factory=list)
    #: 上游为空、本地非空 → 保守起见不动本地值，交人工核对
    empties: List[Dict[str, Any]] = field(default_factory=list)

    no_barcode: int = 0
    dirty: List[Dict[str, Any]] = field(default_factory=list)
    multi_standard: List[Dict[str, Any]] = field(default_factory=list)
    inactive: List[Dict[str, Any]] = field(default_factory=list)
    local_only: List[Dict[str, Any]] = field(default_factory=list)
    local_without_sku: int = 0
    invalid: List[Dict[str, Any]] = field(default_factory=list)

    def changes_of(self, column: str) -> List[Dict[str, Any]]:
        return [c for c in self.updates if c["field"] == column]

    def to_dict(self) -> Dict[str, Any]:
        """报告用的可序列化视图（不含完整 insert 明细，避免文件过大）。"""
        return {
            "upstream_count": self.upstream_count,
            "local_count": self.local_count,
            "scanned": self.scanned,
            "truncated": self.truncated,
            "inserts": [
                {"sku": m["sku"], "name": m["name"], "barcode": m["barcode"],
                 "cost_price": m["cost_price"], "sell_price": m["sell_price"]}
                for m in self.inserts
            ],
            "updates": self.updates,
            "preserved": self.preserved,
            "suspicious": self.suspicious,
            "empties": self.empties,
            "no_barcode": self.no_barcode,
            "dirty": self.dirty,
            "multi_standard": self.multi_standard,
            "inactive": self.inactive,
            "local_only": self.local_only,
            "local_without_sku": self.local_without_sku,
            "invalid": self.invalid,
        }


@dataclass
class ApplyResult:
    inserted: int = 0
    updated: int = 0
    skipped_empty: int = 0
    index_created: bool = False
    fts_rebuilt: bool = False


# ---------------------------------------------------------------------------
# 本地库读取
# ---------------------------------------------------------------------------

def load_local_rows(conn: sqlite3.Connection) -> Tuple[Dict[str, Dict[str, Any]], int]:
    """读本地商品行，返回 ``({sku: row}, 无sku行数)``。"""
    conn.row_factory = sqlite3.Row
    rows: Dict[str, Dict[str, Any]] = {}
    without_sku = 0
    # F6: fully static column list (matches COMPARE_COLUMNS)
    for row in conn.execute(
        "SELECT sku, barcode, name, spec, model, unit, category, brand, cost_price, sell_price, pinyin, initials FROM products"
    ):
        sku = row["sku"]
        if is_empty(sku):
            without_sku += 1
            continue
        rows[str(sku).strip()] = {c: row[c] for c in COMPARE_COLUMNS}
    return rows, without_sku


def find_duplicate_skus(conn: sqlite3.Connection) -> List[Tuple[str, int]]:
    """找出重复的 sku——有重复就不能建唯一索引，upsert 也无意义。"""
    return [
        (r[0], r[1])
        for r in conn.execute(
            "SELECT sku, COUNT(*) AS c FROM products "
            "WHERE sku IS NOT NULL AND TRIM(sku) <> '' GROUP BY sku HAVING c > 1"
        )
    ]


def ensure_unique_index(conn: sqlite3.Connection) -> bool:
    """建 ``products.sku`` 唯一索引。返回是否新建（已存在返回 False）。"""
    exists = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND name=?", (_UNIQUE_INDEX_NAME,)
    ).fetchone()
    if exists:
        return False
    conn.execute("CREATE UNIQUE INDEX idx_products_sku ON products(sku)")
    return True


def ensure_model_column(conn: sqlite3.Connection) -> bool:
    """确保 ``products.model``（上游「型号」列）存在。返回是否新建。

    2026-09-19 事故修复：上游型号（雷士等品牌填出厂货号 ``DD.00000001``）此前
    不入映射，店里按货号永远搜不到。幂等：列已存在（含回填过的库）返回 False。
    """
    cols = [r[1] for r in conn.execute("PRAGMA table_info(products)")]
    if "model" in cols:
        return False
    conn.execute("ALTER TABLE products ADD COLUMN model TEXT")
    return True


def rebuild_fts(conn: sqlite3.Connection) -> bool:
    """重建 FTS5 索引（沿用 ``src/search/search_server.py`` 的写法）。

    不重建的话新商品搜不到；这里是外部内容表全量重建，幂等且安全。
    """
    try:
        conn.execute("INSERT INTO products_fts(products_fts) VALUES('rebuild')")
        return True
    except sqlite3.Error:
        return False


# ---------------------------------------------------------------------------
# 差异计算
# ---------------------------------------------------------------------------

def _flag_zero_price(diff: DiffResult, mapped: Dict[str, Any]) -> None:
    """新增行若价格为 0，单独列入 suspicious 供人工核对（0 多半代表「未设置」）。"""
    for col in PRICE_COLUMNS:
        if is_price_zero(mapped.get(col)):
            diff.suspicious.append({
                "sku": mapped["sku"], "name": mapped["name"], "field": col,
                "local": None, "upstream": 0, "reason": "新增商品价格为零",
            })


def diff_products(
    local_rows: Dict[str, Dict[str, Any]],
    items: Iterable[Dict[str, Any]],
    normalize_dirty: bool = False,
    truncated: bool = False,
    upstream_count: Optional[int] = None,
    exclude_cols: Sequence[str] = (),
) -> DiffResult:
    """比较上游商品与本地行，产出 :class:`DiffResult`。**纯计算，不写库**。

    按 :data:`UPDATE_POLICY` 逐列决定：``always`` 直接覆盖、``fill`` 只补空、
    ``preserve`` 高争议字段保守不覆盖只报告。价格会先剔除精度噪声与「上游为零」两类假差异。

    ``exclude_cols``：跳过的列。S7 全维度模式下传 ``("cost_price",)``——
    成本价由「库存状况表」通道（移动加权，权威口径）最后写入，GetAll 通道再用
    最近采购价比对必然产生假差异（970 条/次），还会把报表值刷回旧口径，故让位。
    """
    exclude = frozenset(exclude_cols)
    diff = DiffResult(local_count=len(local_rows), truncated=truncated)
    seen: set = set()

    for raw in items:
        diff.scanned += 1
        mapped = map_product(raw, normalize_dirty=normalize_dirty)
        meta = mapped["_meta"]
        sku = mapped["sku"]
        name = mapped["name"]
        flags = meta["flags"]

        if is_empty(sku) or FLAG_NO_NAME in flags:
            diff.invalid.append({
                "sku": sku, "name": name,
                "reason": "缺少编号" if is_empty(sku) else "缺少商品名称",
            })
            continue

        if FLAG_NO_BARCODE in meta["barcode_flags"]:
            diff.no_barcode += 1

        if REVIEW_FLAGS & set(flags):
            if FLAG_MULTI_STANDARD in flags:
                diff.multi_standard.append({
                    "sku": sku, "name": name, "standard_count": meta["standard_count"],
                })
            dirty_flags = tuple(f for f in flags if f in REVIEW_FLAGS and f != FLAG_MULTI_STANDARD)
            if dirty_flags:
                diff.dirty.append({
                    "sku": sku, "name": name,
                    "barcode": mapped["barcode"],
                    "barcode_raw": meta["barcode_raw"],
                    "flags": list(dirty_flags),
                })

        if FLAG_INACTIVE in flags:
            diff.inactive.append({"sku": sku, "name": name})

        seen.add(sku)
        local_row = local_rows.get(sku)
        if local_row is None:
            _flag_zero_price(diff, mapped)
            diff.inserts.append(mapped)
            continue

        # 已有行：按覆盖策略逐列决策
        planned: Dict[str, Any] = {}
        for col in BUSINESS_COLUMNS:
            if col in exclude:
                continue  # S7：报表通道是权威源，GetAll 不再比对/覆盖该列
            new_val = mapped[col]
            old_val = local_row.get(col)

            if col in PRICE_COLUMNS:
                if same_value(new_val, old_val):
                    continue
                if is_price_zero(new_val) and not is_price_zero(old_val):
                    # 上游价格为零但本地非零：绝不覆盖（零多半是「未设置」），交人工核对
                    diff.suspicious.append({
                        "sku": sku, "name": name, "field": col,
                        "local": old_val, "upstream": 0,
                    })
                    continue
                if is_empty(new_val) and not is_empty(old_val) and not is_price_zero(old_val):
                    # 本地也为 0/空 → 双方都是「未设置」，无意义，直接跳过
                    diff.empties.append({
                        "sku": sku, "name": name, "field": col,
                        "local": old_val, "upstream": None,
                    })
                    continue
                planned[col] = new_val
                diff.updates.append({"sku": sku, "name": name, "field": col,
                                     "old": old_val, "new": new_val})
                continue

            if same_value(new_val, old_val):
                continue
            policy = UPDATE_POLICY.get(col, "preserve")
            if policy == "always":
                planned[col] = new_val
                diff.updates.append({"sku": sku, "name": name, "field": col,
                                     "old": old_val, "new": new_val})
            elif policy == "fill":
                # 本地视为空（真空 或 品牌占位符"默认品牌"）→ 上游有真值才补录；上游也空则双方都是未设置，不动
                old_empty = is_empty(old_val) or (col == "brand" and is_brand_placeholder(old_val))
                if old_empty:
                    if not is_empty(new_val):
                        planned[col] = new_val
                        diff.updates.append({"sku": sku, "name": name, "field": col,
                                             "old": old_val, "new": new_val})
                else:
                    diff.preserved.append({"sku": sku, "name": name, "field": col,
                                          "local": old_val, "upstream": new_val,
                                          "reason": "本地已有值（fill 策略不覆盖）"})
            else:  # preserve：高争议字段，默认不覆盖
                diff.preserved.append({"sku": sku, "name": name, "field": col,
                                      "local": old_val, "upstream": new_val,
                                      "reason": "高争议字段，默认不覆盖"})

        # 拼音/首字母：仅当 name 或 spec 实际写入时才用最终值重算，保持与 spec 一致
        final_name = planned.get("name", local_row.get("name"))
        final_spec = planned.get("spec", local_row.get("spec"))
        src = "%s %s" % (final_name or "", final_spec or "")
        new_py, new_init = full_pinyin(src), initials(src)
        if new_py != (local_row.get("pinyin") or "") or new_init != (local_row.get("initials") or ""):
            planned["pinyin"] = new_py
            planned["initials"] = new_init
            diff.updates.append({"sku": sku, "name": name, "field": "pinyin",
                                 "old": local_row.get("pinyin"), "new": new_py})
            diff.updates.append({"sku": sku, "name": name, "field": "initials",
                                 "old": local_row.get("initials"), "new": new_init})

    if not truncated:
        diff.local_only = [
            {"sku": s, "name": (local_rows[s].get("name") or "")}
            for s in local_rows
            if s not in seen
        ]
    diff.upstream_count = upstream_count if upstream_count is not None else diff.scanned
    return diff


# ---------------------------------------------------------------------------
# 落库
# ---------------------------------------------------------------------------

def _insert_columns(mapped: Dict[str, Any]) -> Tuple[Tuple[str, ...], Tuple[Any, ...]]:
    """构造 INSERT 的列与值。``unit`` 为空时省略，让表默认值 '个' 生效。"""
    values: Dict[str, Any] = {
        "sku": mapped["sku"],
        "barcode": mapped["barcode"],
        "name": mapped["name"],
        "spec": mapped["spec"],
        "model": mapped["model"],
        "unit": mapped["unit"],
        "category": mapped["category"],
        "brand": mapped["brand"],
        "cost_price": mapped["cost_price"],
        "sell_price": mapped["sell_price"],
        "description": mapped["description"] or INSERT_DESCRIPTION_DEFAULT,
        "pinyin": mapped["pinyin"],
        "initials": mapped["initials"],
        "updated_at": now_str(),
    }
    if is_empty(values["unit"]):
        values.pop("unit")
    cols = tuple(values.keys())
    return cols, tuple(values[c] for c in cols)


def apply_diff(
    conn: sqlite3.Connection,
    diff: DiffResult,
    rebuild: bool = True,
) -> ApplyResult:
    """把差异写进 ``wujin.db``。**调用方必须先跑备份**。

    只写 :data:`COMPARE_COLUMNS` + 新增行的 ``description``；
    :data:`LOCAL_OWNED_COLUMNS`（库存 / 货位 / 预警 / jieba_tokens）一律不碰。
    """
    result = ApplyResult()

    for mapped in diff.inserts:
        cols, params = _insert_columns(mapped)
        sql = "INSERT INTO products (%s) VALUES (%s)" % (
            ", ".join(cols), ", ".join("?" * len(cols))
        )
        conn.execute(sql, params)
        result.inserted += 1

    grouped: Dict[str, Dict[str, Any]] = {}
    for change in diff.updates:
        grouped.setdefault(change["sku"], {})[change["field"]] = change["new"]

    for sku, values in grouped.items():
        if not values:
            continue
        cols = tuple(values.keys())
        sql = "UPDATE products SET %s, updated_at=? WHERE sku=?" % ", ".join(
            "%s=?" % c for c in cols
        )
        conn.execute(sql, tuple(values[c] for c in cols) + (now_str(), sku))
        result.updated += 1

    result.skipped_empty = len(diff.empties)

    if rebuild:
        result.fts_rebuilt = rebuild_fts(conn)
    conn.commit()
    return result


def backup_database(root: str) -> Tuple[bool, str]:
    """调用 ``scripts/backup-db.ps1`` 备份运行时数据库。改数据前的强制动作。"""
    script = os.path.join(root, "scripts", "backup-db.ps1")
    if not os.path.exists(script):
        return False, "未找到备份脚本：%s" % script
    try:
        proc = subprocess.run(
            ["powershell", "-ExecutionPolicy", "Bypass", "-File", script, "-Note", "pre-xzg-sync"],
            capture_output=True, text=True, cwd=root, timeout=300,
        )
    except (OSError, subprocess.SubprocessError) as exc:  # pragma: no cover
        return False, "备份脚本执行失败：%s" % exc
    output = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode == 0, output.strip()


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------

def render_report(diff: DiffResult, applied: Optional[ApplyResult] = None) -> str:
    """控制台差异报告。条码差异放在最前面——这是最需要人工核对的一类。"""
    lines: List[str] = []
    add = lines.append

    add("=" * 72)
    add("熊掌柜 → wujin.db 同步报告%s" % ("（已落库）" if applied else "（DRY-RUN，未写库）"))
    add("=" * 72)
    add("本地商品 %d 条 / 上游 %d 条 / 本次扫描 %d 条%s"
        % (diff.local_count, diff.upstream_count, diff.scanned,
           "（限流截断）" if diff.truncated else ""))
    add("")

    add("【1】条码差异 —— 最需要人工核对（编号 / 商品名 / 本地原值 → 上游新值）")
    barcode_changes = diff.changes_of("barcode")
    if barcode_changes:
        for c in barcode_changes[:200]:
            add("   %-16s %-22s %r → %r" % (c["sku"], (c["name"] or "")[:22], c["old"], c["new"]))
        if len(barcode_changes) > 200:
            add("   ... 其余 %d 条见报告文件" % (len(barcode_changes) - 200))
    else:
        add("   无")
    add("   小计：%d 条" % len(barcode_changes))
    add("")

    add("【2】价格差异（成本价 / 售价）")
    for col, label in (("cost_price", "成本价"), ("sell_price", "售价")):
        changes = diff.changes_of(col)
        add("   %s：%d 条" % (label, len(changes)))
        for c in changes[:20]:
            add("      %-16s %-20s %s → %s"
                % (c["sku"], (c["name"] or "")[:20], c["old"], c["new"]))
        if len(changes) > 20:
            add("      ... 其余 %d 条见报告文件" % (len(changes) - 20))
    add("")

    add("【3】脏数据 —— 数据本身可能有问题，请核对")
    add("   脏条码（原样保留未修改）：%d 条" % len(diff.dirty))
    for d in diff.dirty[:20]:
        add("      %-16s %-20s %r  标记=%s"
            % (d["sku"], (d["name"] or "")[:20], d["barcode"], ",".join(d["flags"])))
    if len(diff.dirty) > 20:
        add("      ... 其余 %d 条见报告文件" % (len(diff.dirty) - 20))
    add("   多规格商品：%d 条（上游实测全为单规格，出现即为异常）" % len(diff.multi_standard))
    add("   无条码（散装/非原包装，正常业务状态，非异常）：%d 条" % diff.no_barcode)
    add("   无法落库（缺编号或缺名称）：%d 条" % len(diff.invalid))
    add("")

    add("【4】上游为空但本地有值 —— 保守起见未清空，请核对后手工处理")
    add("   共 %d 条" % len(diff.empties))
    for e in diff.empties[:20]:
        add("      %-16s %-20s %s: 本地=%r 上游=空"
            % (e["sku"], (e["name"] or "")[:20], e["field"], e["local"]))
    if len(diff.empties) > 20:
        add("      ... 其余 %d 条见报告文件" % (len(diff.empties) - 20))
    add("")

    add("【4.5】高争议字段 —— 默认不覆盖，仅报告（category/spec/unit 两套口径）")
    add("   共 %d 条" % len(diff.preserved))
    for p in diff.preserved[:20]:
        add("      %-16s %-20s %s: 本地=%r 上游=%r"
            % (p["sku"], (p["name"] or "")[:20], p["field"], p["local"], p["upstream"]))
    if len(diff.preserved) > 20:
        add("      ... 其余 %d 条见报告文件" % (len(diff.preserved) - 20))
    add("")

    add("【4.6】上游价格为零 —— 未覆盖本地（零多半是「未设置」非真值）")
    add("   共 %d 条" % len(diff.suspicious))
    for s in diff.suspicious[:20]:
        add("      %-16s %-20s %s: 本地=%r 上游=0 [%s]"
            % (s["sku"], (s["name"] or "")[:20], s["field"], s["local"], s.get("reason", "")))
    if len(diff.suspicious) > 20:
        add("      ... 其余 %d 条见报告文件" % (len(diff.suspicious) - 20))
    add("")

    add("【5】新增 / 停用 / 本地独有")
    add("   上游新增（本地没有）：%d 条" % len(diff.inserts))
    for m in diff.inserts[:10]:
        add("      %-16s %-20s 条码=%r 成本=%s 售价=%s"
            % (m["sku"], (m["name"] or "")[:20], m["barcode"], m["cost_price"], m["sell_price"]))
    if len(diff.inserts) > 10:
        add("      ... 其余 %d 条见报告文件" % (len(diff.inserts) - 10))
    add("   上游已停用（isActive=false，本地不删除）：%d 条" % len(diff.inactive))
    add("   本地独有（上游已无，不自动删除）：%d 条" % len(diff.local_only))
    for r in diff.local_only[:20]:
        add("      %-16s %s" % (r["sku"], r["name"]))
    add("")

    add("【6】其他字段差异")
    others = [c for c in diff.updates if c["field"] not in HIGHLIGHT_COLUMNS]
    add("   共 %d 条（name/spec/unit/category/brand/拼音）" % len(others))
    add("")

    if applied:
        add("落库结果：新增 %d 行，更新 %d 行，跳过(上游为空) %d 处，"
            "唯一索引%s，FTS 重建%s"
            % (applied.inserted, applied.updated, applied.skipped_empty,
               "已建" if applied.index_created else "已存在",
               "成功" if applied.fts_rebuilt else "失败"))
    else:
        add("未写库。确认无误后加 --apply 执行落库（会自动先备份）。")
    add("=" * 72)
    return "\n".join(lines)



def write_report(diff: DiffResult, path: str, applied: Optional[ApplyResult] = None) -> None:
    """报告落盘：``.json`` 写完整结构，``.csv`` 只写需要人工核对的清单（Excel 可直接打开）。"""
    import tempfile
    _roots = [os.path.realpath(os.environ.get("retaildemo_PATH_BASE") or os.getcwd()),
              os.path.normcase(tempfile.gettempdir())]
    _pn = os.path.normcase(os.path.realpath(path))
    if not any(_pn == _r or _pn.startswith(_r + os.sep) for _r in _roots):
        raise ValueError("path outside sandbox: %s" % path)  # F6 path guard
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)

    if path.lower().endswith(".json"):
        import json

        payload = diff.to_dict()
        if applied:
            payload["applied"] = {
                "inserted": applied.inserted,
                "updated": applied.updated,
                "skipped_empty": applied.skipped_empty,
                "index_created": applied.index_created,
                "fts_rebuilt": applied.fts_rebuilt,
            }
        path = os.path.realpath(path)  # F6: sink 前规范化
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        return

    import csv

    path = os.path.realpath(path)  # F6: sink 前规范化
    with open(path, "w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["类别", "编号", "商品名", "字段", "本地原值", "上游新值", "标记"])
        for c in diff.changes_of("barcode"):
            writer.writerow(["条码差异", c["sku"], c["name"], "barcode", c["old"], c["new"], ""])
        for c in diff.changes_of("cost_price"):
            writer.writerow(["成本价差异", c["sku"], c["name"], "cost_price", c["old"], c["new"], ""])
        for c in diff.changes_of("sell_price"):
            writer.writerow(["售价差异", c["sku"], c["name"], "sell_price", c["old"], c["new"], ""])
        for d in diff.dirty:
            writer.writerow(["脏条码", d["sku"], d["name"], "barcode", "", d["barcode"], ",".join(d["flags"])])
        for e in diff.empties:
            writer.writerow(["上游为空", e["sku"], e["name"], e["field"], e["local"], "", "未清空"])
        for p in diff.preserved:
            writer.writerow(["高争议不覆盖", p["sku"], p["name"], p["field"], p["local"], p["upstream"], p.get("reason", "")])
        for s in diff.suspicious:
            writer.writerow(["价格为零", s["sku"], s["name"], s["field"], s["local"], 0, s.get("reason", "")])
        for m in diff.multi_standard:
            writer.writerow(["多规格", m["sku"], m["name"], "baseProductStandardList", "", m["standard_count"], FLAG_MULTI_STANDARD])
        for m in diff.inserts:
            writer.writerow(["上游新增", m["sku"], m["name"], "", "", m["barcode"], ""])
        for r in diff.inactive:
            writer.writerow(["上游停用", r["sku"], r["name"], "isActive", "", "false", FLAG_INACTIVE])
        for r in diff.local_only:
            writer.writerow(["本地独有", r["sku"], r["name"], "", "", "", "上游已无"])
