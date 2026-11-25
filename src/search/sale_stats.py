"""商品出库频次（05 查价页右侧「昨天出 3 件」）——**只读聚合，纯函数，可单测**。

为什么不塞进 ``sale_status.py``：
``sale_status`` 回答「这个货还卖不卖得动」（零库存冻结判定，是**离线批处理物化的列**，
由 ``scripts/compute_sale_status.py --apply`` 写库）；本模块回答「最近一次什么时候出的、出了几件」
（搜索接口**按请求只读**展示用）。两者共用同一份账单口径，但一个写库、一个只读——
混在一起迟早会让只读路径误碰写路径。

口径（与 ``sale_status.load_last_sale_days`` 同源，避免两套算法漂移）：
- 只认 ``status == 'paid'``（已出库 = 真实卖过）；``pending``（挂账）/``draft`` **不算销售**。
- 商品键优先 ``sku``，缺则退 ``barcode``（与 ``load_last_sale_days`` / ``BillRepo.kt`` 一致）。
- 件数取 item 的 ``quantity``，缺则退 ``qty``，两者都缺按 **1** 计
  —— 真实库里两套结构并存（2026-09-17 实测 69 : 11），客户端 ``BillRepo.kt:702`` 也是这个兜底顺序。
- 「最近一次出库日」= 该商品出现过的**最大门店本地自然日**；
  件数 = 该自然日内的**合计**件数（同日多单合并）。
- 自然日按**门店时区**切（``WUJIN_SALE_TZ_OFFSET`` 分钟，默认 480 = 东八区）。
  用 UTC 切日会把「北京时间早上 7 点卖出的货」算进前一天，店员看到的「昨天」就整体错一天。

红线：只读 ``bills.db``；不写任何库；不碰 ``products``；**不臆造**无销售记录的商品
（没记录就不产出条目 → UI 整行不显示，而不是显示「0 件」）。
"""
from __future__ import annotations

import json
import os
import sqlite3
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, NamedTuple, Optional

from search.sale_status import parse_paid_at

# 门店时区偏移（分钟）。东八区 = 480。可用 WUJIN_SALE_TZ_OFFSET 覆盖。
DEFAULT_TZ_OFFSET_MINUTES = 480


class SaleStat(NamedTuple):
    """某商品最近一次出库的「本地自然日 + 当日合计件数」。"""

    last_sale_day: str  # 门店本地自然日，YYYY-MM-DD
    qty: float          # 该自然日内合计出库件数


def tz_offset_minutes_from_env(environ: Optional[Dict[str, str]] = None) -> int:
    """读 ``WUJIN_SALE_TZ_OFFSET``（分钟）；缺失/非法一律回默认，不抛。"""
    env = os.environ if environ is None else environ
    raw = str(env.get("WUJIN_SALE_TZ_OFFSET", "") or "").strip()
    if not raw:
        return DEFAULT_TZ_OFFSET_MINUTES
    try:
        return int(float(raw))
    except (TypeError, ValueError):
        return DEFAULT_TZ_OFFSET_MINUTES


def item_key(item: Any) -> str:
    """账单 item 的商品键：优先 sku，缺则退 barcode；都没有返回空串。"""
    if not isinstance(item, dict):
        return ""
    return str(item.get("sku") or "").strip() or str(item.get("barcode") or "").strip()


def item_qty(item: Any) -> float:
    """账单 item 的件数：``quantity`` → ``qty`` → 兜底 1（与客户端读取顺序一致）。"""
    if not isinstance(item, dict):
        return 1.0
    for key in ("quantity", "qty"):
        if key in item:
            try:
                return float(item[key])
            except (TypeError, ValueError):
                continue
    return 1.0


def local_day(dt: datetime, tz_offset_minutes: int = DEFAULT_TZ_OFFSET_MINUTES) -> str:
    """把带时区的时间点折算成门店本地自然日（YYYY-MM-DD）。"""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return (dt.astimezone(timezone.utc) + timedelta(minutes=tz_offset_minutes)).date().isoformat()


def load_sale_stats(
    bills_db_path: str,
    tz_offset_minutes: int = DEFAULT_TZ_OFFSET_MINUTES,
) -> Dict[str, SaleStat]:
    """扫 bills.db，返回 ``{商品键: SaleStat}``。库不在位返回空 dict（不抛）。"""
    if not os.path.exists(bills_db_path):
        return {}

    # key -> {本地自然日: 当日合计件数}
    per_day: Dict[str, Dict[str, float]] = {}
    last_day: Dict[str, str] = {}

    conn = sqlite3.connect(bills_db_path)
    try:
        for (data_json,) in conn.execute("select data_json from bills"):
            try:
                bill = json.loads(data_json)
            except (TypeError, ValueError):
                continue  # 坏 JSON 不崩，跳过
            if not isinstance(bill, dict) or bill.get("status") != "paid":
                continue
            when = parse_paid_at(bill.get("paid_at") or bill.get("updated_at"))
            if when is None:
                continue
            day = local_day(when, tz_offset_minutes)
            items = bill.get("items")
            if not isinstance(items, list):
                continue
            for item in items:
                key = item_key(item)
                if not key:
                    continue
                bucket = per_day.setdefault(key, {})
                bucket[day] = bucket.get(day, 0.0) + item_qty(item)
                if day > last_day.get(key, ""):
                    last_day[key] = day
    finally:
        conn.close()

    return {
        key: SaleStat(last_sale_day=day, qty=per_day[key][day])
        for key, day in last_day.items()
    }
