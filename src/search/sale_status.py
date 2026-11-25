"""商品销售状态（S2 零库存冻结）判定——**纯函数，不碰数据库写路径**。

业务语义（master-plan §9 / docs/p0-data-foundation-design.md §1）：

- ``active``    在售：有库存，或阈值天数内有销售记录
- ``frozen``    冻结：零库存 **且** 超过阈值天数无销售（默认从检索消失，可「全量检索」找回）
- ``clearable`` 清理候选：冻结 **且** 上游已停用（进导出清单，管家婆侧人工处理）

阈值 ``frozen_days`` 由环境变量 ``WUJIN_FROZEN_DAYS`` 配置（默认 180）。
边界：``last_sale_days >= frozen_days`` 归 frozen（含恰好相等）。

红线：只算状态标记，**不删数据**；不写上游；不碰 products 的其它列。

bills.db 的 ``bills.data_json`` 是 JSON blob（无规范化 status 列，SQL join 不了），
最近销售日期必须 Python 解析：取 ``status=='paid'`` 账单的 ``paid_at``，
按 item 的 sku（优先）/ barcode 归组取最大值。
"""
from __future__ import annotations

import json
import os
import sqlite3
from datetime import datetime, timezone
from typing import Any, Dict, Optional


def decide_sale_status(
    stock_qty: Any,
    last_sale_days: Optional[float],
    frozen_days: int = 180,
    upstream_active: bool = True,
) -> str:
    """三态判定（见模块 docstring）。纯函数，可单测。"""
    try:
        qty = float(stock_qty or 0)
    except (TypeError, ValueError):
        qty = 0.0
    if qty > 0:
        return "active"
    no_recent_sale = last_sale_days is None or last_sale_days >= frozen_days
    if no_recent_sale:
        return "frozen" if upstream_active else "clearable"
    return "active"


def parse_paid_at(value: Any) -> Optional[datetime]:
    """解析账单时间字段（ISO 8601，容忍 Z 后缀）；失败返回 None。"""
    if not value:
        return None
    try:
        text = str(value).strip()
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except ValueError:
        return None


def load_last_sale_days(bills_db_path: str,
                        now: Optional[datetime] = None) -> Dict[str, float]:
    """扫 bills.db 全部账单，返回 ``{sku: 最近销售距今天数}``。

    口径：``status=='paid'``（已出库=真实卖过）；商品键优先 ``sku``，
    无 sku 的 item 退回 ``barcode``。draft/pending 不算销售。
    """
    if not os.path.exists(bills_db_path):
        return {}
    now = now or datetime.now(timezone.utc)
    latest: Dict[str, datetime] = {}
    conn = sqlite3.connect(bills_db_path)
    try:
        rows = conn.execute("select data_json from bills")
        for (data_json,) in rows:
            try:
                bill = json.loads(data_json)
            except (TypeError, ValueError):
                continue
            if not isinstance(bill, dict) or bill.get("status") != "paid":
                continue
            when = parse_paid_at(bill.get("paid_at") or bill.get("updated_at"))
            if when is None:
                continue
            for item in bill.get("items") or []:
                if not isinstance(item, dict):
                    continue
                key = str(item.get("sku") or "").strip() or str(item.get("barcode") or "").strip()
                if not key:
                    continue
                old = latest.get(key)
                if old is None or when > old:
                    latest[key] = when
    finally:
        conn.close()
    return {sku: (now - when).total_seconds() / 86400.0
            for sku, when in latest.items()}


def load_inactive_skus(report_path: Optional[str]) -> set:
    """从 --apply 落库报告 JSON 读上游停用（isActive=false）sku 清单。

    报告不在位/无该段时返回空集（clearable 判定退化为全 frozen，不臆造）。
    """
    if not report_path or not os.path.exists(report_path):
        return set()
    try:
        with open(report_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return set()
    entries = data.get("inactive") or []
    out = set()
    for e in entries:
        if not isinstance(e, dict):
            continue
        sku = str(e.get("sku") or "").strip()
        if sku:
            out.add(sku)
    return out
