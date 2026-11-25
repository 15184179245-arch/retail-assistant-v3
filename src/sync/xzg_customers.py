"""熊掌柜客户档案（基础信息）→ ``wujin.db`` customers 表：拉取、映射、差异、落库。

S18（2026-09-11）老板令：「从管家婆上游的基础信息里面提取出客户信息并保存到app内，
可通过同步数据更新」。设计原则与 :mod:`sync.xzg_sync` 完全同口径：

1. **上游绝对只读**：只调 ``Selector/Customer/GetAll``（白名单内只读端点）。
2. **脏数据原样落库**：空名/空电话照存（空名进 suspicious 单列），绝不清洗、不臆造。
   ``phone`` 是 tel→tel2→tel3 三个上游兄弟字段中**第一个非空真值**，不是推断。
3. **永不删除**：上游已无的客户在本地保留，只进报告——删除是人工通道。
4. **幂等**：upsert by ``upstream_id``（上游雪花 id 转 TEXT，避免 JSON 数值精度丢失），
   复跑零差异。
5. **应收/预收余额（total/preTotal）不进同步**：属报表口径，且欠款/信用额度冻结
   （B5 scope_out）。

表是新增的（``customers``，``upstream_id TEXT PRIMARY KEY``），不触碰任何已有表。
"""
from __future__ import annotations

import sqlite3
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

if sys.stdout and hasattr(sys.stdout, "reconfigure"):  # pragma: no cover
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

#: 上游选客器端点（只读，xzg_client 白名单同款）。
CUSTOMER_GETALL_PATH = "/api/services/app/V1/Erp/Selector/Customer/GetAll"

#: 分页大小（61 条实测一页可拿全；分页循环只是防御性通用化）。
CUSTOMER_PAGE_SIZE = 500

#: 落库列（``upstream_id`` 主键不参与差异比对）。
CUSTOMER_COLUMNS: Tuple[str, ...] = (
    "code",
    "name",
    "full_name",
    "pinyin",
    "phone",
    "contacts",
    "address",
    "category",
    "remark",
)


def now_str() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def _s(value: Any) -> str:
    """上游标量 → 字符串。None 归 ""（空值就是空值，不做别的加工）。"""
    if value is None:
        return ""
    return str(value).strip()


def first_nonempty(*values: Any) -> str:
    """兄弟字段取第一个非空真值（全是空 → ""）。"""
    for v in values:
        s = _s(v)
        if s:
            return s
    return ""


def map_customer(item: Dict[str, Any]) -> Tuple[Dict[str, Any], List[str]]:
    """上游客户 JSON → 本地行（dict）。返回 (row, suspicious_flags)。

    纯函数：不落库、不出网。脏数据原样保留，只打标。
    """
    flags: List[str] = []
    upstream_id = str(item.get("id", "")).strip()
    name = _s(item.get("name"))
    full_name = _s(item.get("fullName"))
    if not name and full_name:
        # 两个字段都是上游真值，取 fullName 兜底并在 flags 留痕（非臆造）
        name = full_name
        flags.append("name_from_full_name")
    if not name:
        flags.append("no_name")
    if not first_nonempty(item.get("tel"), item.get("tel2"), item.get("tel3")):
        flags.append("no_phone")
    row = {
        "upstream_id": upstream_id,
        "code": _s(item.get("code")),
        "name": name,
        "full_name": full_name,
        "pinyin": _s(item.get("pinYinCode")),
        "phone": first_nonempty(item.get("tel"), item.get("tel2"), item.get("tel3")),
        "contacts": _s(item.get("contacts")),
        "address": _s(item.get("address")),
        "category": _s(item.get("companyCategoryName")),
        "remark": _s(item.get("remark")),
        "updated_at": now_str(),
    }
    return row, flags


def fetch_customers(
    client: Any,
    page_size: int = CUSTOMER_PAGE_SIZE,
    limit: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """分页拉全量客户（Selector GetAll），返回原始 item 列表。

    任何 HTTP/上游异常向上抛（调用方按「任何阶段失败=整体失败」处理）。
    """
    items: List[Dict[str, Any]] = []
    skip = 0
    total: Optional[int] = None
    while True:
        payload = client._call_with_reauth(
            "POST",
            CUSTOMER_GETALL_PATH,
            {"skipCount": skip, "maxResultCount": page_size, "keyword": ""},
        )
        result = payload.get("result") or {}
        page = result.get("items") or []
        if not page:
            break
        items.extend(page)
        total = result.get("totalCount")
        if limit is not None and len(items) >= limit:
            return items[:limit]
        if total is not None and len(items) >= int(total):
            break
        skip += len(page)
    return items


# ---------------------------------------------------------------------------
# 落库（ensure / diff / apply）
# ---------------------------------------------------------------------------

def ensure_customers_table(conn: sqlite3.Connection) -> None:
    """建 customers 表（不存在才建）。不触碰任何已有表/数据。"""
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS customers (
            upstream_id TEXT PRIMARY KEY,
            code        TEXT,
            name        TEXT,
            full_name   TEXT,
            pinyin      TEXT,
            phone       TEXT,
            contacts    TEXT,
            address     TEXT,
            category    TEXT,
            remark      TEXT,
            updated_at  TEXT
        )
        """
    )


def load_local_customers(conn: sqlite3.Connection) -> Dict[str, Dict[str, Any]]:
    """本地 customers 全量，按 upstream_id 索引。"""
    rows: Dict[str, Dict[str, Any]] = {}
    try:
        cur = conn.execute(
            "SELECT upstream_id, %s FROM customers"
            % ", ".join(CUSTOMER_COLUMNS)
        )
    except sqlite3.OperationalError:
        return rows  # 表尚未建立（首次同步前）
    for r in cur:
        row = dict(zip(("upstream_id",) + CUSTOMER_COLUMNS, r))
        rows[str(row["upstream_id"])] = row
    return rows


@dataclass
class CustomerDiff:
    inserts: List[Dict[str, Any]] = field(default_factory=list)
    updates: List[Dict[str, Any]] = field(default_factory=list)
    suspicious: List[Dict[str, Any]] = field(default_factory=list)
    unchanged: int = 0

    @property
    def changed(self) -> int:
        return len(self.inserts) + len(self.updates)


def diff_customers(local_rows: Dict[str, Dict[str, Any]], rows: List[Dict[str, Any]]) -> CustomerDiff:
    """纯计算差异：新增 / 更新（逐字段比对）/ 不变；脏行进 suspicious 但照常参与落库。"""
    diff = CustomerDiff()
    for row in rows:
        flags: List[str] = []
        if not row.get("name"):
            flags.append("no_name")
        if not row.get("phone"):
            flags.append("no_phone")
        if flags:
            diff.suspicious.append({"upstream_id": row["upstream_id"], "name": row["name"], "flags": flags})
        local = local_rows.get(row["upstream_id"])
        if local is None:
            diff.inserts.append(row)
            continue
        changed_cols = [c for c in CUSTOMER_COLUMNS if _s(local.get(c)) != _s(row.get(c))]
        if changed_cols:
            merged = dict(row)
            merged["_changed_cols"] = changed_cols
            diff.updates.append(merged)
        else:
            diff.unchanged += 1
    return diff


@dataclass
class CustomerApplyResult:
    inserted: int = 0
    updated: int = 0
    suspicious: int = 0


def apply_customers(conn: sqlite3.Connection, diff: CustomerDiff) -> CustomerApplyResult:
    """落库（调用方必须已备份、已 ensure_customers_table）。upsert，永不删除。"""
    res = CustomerApplyResult()
    for row in diff.inserts:
        conn.execute(
            "INSERT OR REPLACE INTO customers (%s) VALUES (%s)"
            % (", ".join(("upstream_id",) + CUSTOMER_COLUMNS),
               ", ".join("?" * (len(CUSTOMER_COLUMNS) + 1))),
            tuple(row[c] for c in ("upstream_id",) + CUSTOMER_COLUMNS),
        )
        res.inserted += 1
    for row in diff.updates:
        conn.execute(
            "INSERT OR REPLACE INTO customers (%s) VALUES (%s)"
            % (", ".join(("upstream_id",) + CUSTOMER_COLUMNS),
               ", ".join("?" * (len(CUSTOMER_COLUMNS) + 1))),
            tuple(row[c] for c in ("upstream_id",) + CUSTOMER_COLUMNS),
        )
        res.updated += 1
    res.suspicious = len(diff.suspicious)
    return res
