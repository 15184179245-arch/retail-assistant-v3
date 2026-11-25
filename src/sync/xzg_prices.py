"""熊掌柜商品价格档位提取（**纯函数**，不碰网络、不碰数据库）。

背景（XZG-API-RO，2026-09-13 老板令「定死只读然后成功反哺项目」）
------------------------------------------------------------------

客户端逆向 + 真账只读实测确认：``BaseProduct/GetAll`` 返回的每条商品里内嵌
``baseProductStandardList``，每个规格携带**完整价格位**：

- 采购：``buyingPrice`` ``maxBuyingPrice`` ``buyingPrice2`` ~ ``buyingPrice5``
- 零售：``retailPrice`` ``retailPrice2`` ~ ``retailPrice5``
- 会员：``memberPrice`` ``memberPrice2`` ~ ``memberPrice5``
- 批发：``wholesalePrice`` ``minWholesalePrice`` ``wholesalePrice2`` ~ ``wholesalePrice5``
- 配送：``deliveryPrice`` ``deliveryPrice2`` ~ ``deliveryPrice5``

实测样本（「按钮」条码 1001010001）：``buyingPrice=13.2133333333`` ``retailPrice=25.0``，
其余档位为 ``None``（未启用）。本地 ``products`` 表此前只有 ``cost_price`` / ``sell_price``
两个口径，本模块把这套档位**如实**提取出来，供查价 / 开单 / 后续上架使用。

分层约定
--------

本模块只做「上游实体 → 结构化价格行」的**纯转换**，不做持久化：
落库由同步层（``scripts/sync-xzg.py`` 的写库通道）负责，保持与
:mod:`xzg_mapper` / :mod:`xzg_stock` 一致的可测性——纯函数可离线喂快照验证。

数据纪律（与 :mod:`xzg_mapper` 同源，不可违背）
-----------------------------------------------

- 档位为空 → 映射为 ``None``，落库即 NULL。**不臆造、不推断、不补默认值**
- 价格原样浮点，**不四舍五入**（上游 ``13.2133333333`` 就存 ``13.2133333333``）
- 只读上游，本模块不做任何上游写操作
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

#: 价格档位字段名（与上游字段同名，作为快照表列名与 CSV 表头）。
PRICE_FIELDS: Tuple[str, ...] = (
    "buyingPrice", "maxBuyingPrice", "buyingPrice2", "buyingPrice3",
    "buyingPrice4", "buyingPrice5",
    "retailPrice", "retailPrice2", "retailPrice3", "retailPrice4", "retailPrice5",
    "memberPrice", "memberPrice2", "memberPrice3", "memberPrice4", "memberPrice5",
    "wholesalePrice", "minWholesalePrice", "wholesalePrice2", "wholesalePrice3",
    "wholesalePrice4", "wholesalePrice5",
    "deliveryPrice", "deliveryPrice2", "deliveryPrice3", "deliveryPrice4",
    "deliveryPrice5",
)

#: 价格行的标识列（商品/规格维度），在 :data:`PRICE_FIELDS` 之前排列。
PRICE_ROW_KEYS: Tuple[str, ...] = (
    "product_id", "product_code", "bar_code", "product_name",
    "standard_id", "standard_name", "standard_relation", "row_index",
)

#: 快照表的完整列顺序（标识列 + 价格档位）。
PRICE_ROW_COLUMNS: Tuple[str, ...] = PRICE_ROW_KEYS + PRICE_FIELDS


def _as_float(value: Any) -> Optional[float]:
    """上游价格 → float；空值一律 None（绝不臆造 0）。"""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def extract_prices(product: Dict[str, Any]) -> List[Dict[str, Any]]:
    """从一条商品档案提取**全部规格**的价格档位行。

    :param product: ``BaseProduct/GetAll`` 返回的单条商品实体。
    :return: 规格级价格行列表（键见 :data:`PRICE_ROW_COLUMNS`）；
        商品无 id 或无规格子表时返回空列表——宁可无数据，也不产半成品行。
    """
    if not isinstance(product, dict):
        return []
    product_id = product.get("id")
    if product_id in (None, ""):
        return []

    standards = product.get("baseProductStandardList")
    if not isinstance(standards, list):
        return []

    rows: List[Dict[str, Any]] = []
    for std in standards:
        if not isinstance(std, dict):
            continue
        row: Dict[str, Any] = {
            "product_id": str(product_id),
            "product_code": product.get("code"),
            "bar_code": product.get("barCode"),
            "product_name": product.get("name"),
            "standard_id": std.get("id"),
            "standard_name": std.get("standardName"),
            "standard_relation": _as_float(std.get("standardRelation")),
            "row_index": std.get("rowIndex"),
        }
        for field in PRICE_FIELDS:
            row[field] = _as_float(std.get(field))
        rows.append(row)
    return rows


def extract_prices_many(products: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """批量提取（顺序与入参一致；透传 :func:`extract_prices` 的全部纪律）。"""
    rows: List[Dict[str, Any]] = []
    for product in products:
        rows.extend(extract_prices(product))
    return rows


def summarize_prices(rows: List[Dict[str, Any]]) -> Dict[str, int]:
    """统计口径：有多少商品、多少规格、以及各档位实际有值的条数。

    用于同步报告的「拿回了什么」一栏——空档位不计数，避免把「字段存在」
    误读成「价格可用」。
    """
    products = {r.get("product_id") for r in rows}
    summary: Dict[str, int] = {
        "goods": len(products),
        "standards": len(rows),
    }
    for field in PRICE_FIELDS:
        summary["filled_" + field] = sum(1 for r in rows if r.get(field) is not None)
    return summary
