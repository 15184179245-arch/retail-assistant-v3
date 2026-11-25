#!/usr/bin/env python3
"""B13 结算回执云端匹配器（`xzg_client` → SaleBill/Sale/Query 精确子串匹配）。

契约（B13 手册 Step 2）：
1. 桥接键：cloud billCode == "C-XSCK-18118-" + local BillNumber（精确后缀）；
2. 结算状态：settleStateName == "完全" 才算已结算；
3. 未命中先查 SaleBack/Query（退货单在这里），再看作废；
4. 绝不使用时间/金额的模糊匹配，只按精确单号匹配。
"""
import logging
from typing import Any, Dict, Optional

logger = logging.getLogger("gateway.settlement_scanner")


def match_settlement_from_items(local_bill_number: str, items: list) -> Optional[Dict[str, Any]]:
    """从销售单列表 items 中按本地单号精确后缀匹配已结算单据。"""
    if not local_bill_number:
        return None
    target_suffix = str(local_bill_number).strip()
    for it in items:
        if not isinstance(it, dict):
            continue
        code = str(it.get("billCode") or "").strip()
        if code.endswith(target_suffix):
            settle_state = str(it.get("settleStateName") or "").strip()
            if settle_state != "完全":
                logger.info(f"单据 {code} 状态为 {settle_state}，未完全结算，不上报")
                return None
            bill_id = str(it.get("billId") or "").strip()
            settle_time = str(it.get("settleTime") or it.get("postTime") or "").strip()
            return {
                "bill_code": code,
                "order_no": bill_id,
                "settle_state": settle_state,
                "settle_time": settle_time,
            }
    return None


def scan_cloud_settlement(
    local_bill_number: str,
    date: str,
    client: Optional[Any] = None,
) -> Optional[Dict[str, Any]]:
    """调用管家婆 SaleBill/Sale/Query 接口查询当期单据并匹配。"""
    if not local_bill_number or not date:
        return None
    if client is None:
        try:
            from sync.xzg_client import XzgClient
            client = XzgClient()
        except Exception as e:
            logger.warning(f"无法初始化 XzgClient: {e}")
            return None

    # 1. 查正向销售单
    try:
        resp = client._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/SaleBill/Sale/Query",
            {"startDate": date, "endDate": date, "skipCount": 0, "maxResultCount": 100},
        )
        items = resp.get("result", {}).get("items") or resp.get("items") or []
        matched = match_settlement_from_items(local_bill_number, items)
        if matched:
            return matched
    except Exception as e:
        logger.warning(f"Sale/Query 查询异常: {e}")

    # 2. 查退货单（本地 BillType=2）
    try:
        resp_back = client._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/SaleBill/SaleBack/Query",
            {"startDate": date, "endDate": date, "skipCount": 0, "maxResultCount": 100},
        )
        items_back = resp_back.get("result", {}).get("items") or resp_back.get("items") or []
        matched_back = match_settlement_from_items(local_bill_number, items_back)
        if matched_back:
            return matched_back
    except Exception as e:
        logger.warning(f"SaleBack/Query 查询异常: {e}")

    return None
