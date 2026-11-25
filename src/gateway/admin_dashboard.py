#!/usr/bin/env python3
"""示例零售全能助手 - 现代化经营与管理中枢 (Samsung Hardware Control Center)
纯 Python 标准库实现，零外部依赖。
包含：
1. 经营罗盘与多维大盘 (7天/30天趋势图、时段营业热度柱状图、品类占比环形图、店员天梯榜)
2. 全息商品档案检索与查价台 (12,631+ 商品组合筛选、货位下钻、屏幕大二维码/条码直出)
3. 账单流水与对账中心 (全量账单、超时滞留红黄牌预警、一键核销出库)
4. 库存货架与资产预警 (5级货架可视化树、急需补货清单、资金沉淀 TOP 榜)
5. 往来客户与欠款对账单 (欠款排行榜、微信对账单生成)
6. 现场巡架与数据治理 (问题标记队列、网页直接核准闭环、搜空热词分析、Excel BOM CSV 导出)
7. AI 智囊与五金施工知识库 (桌面端问答调试台、16,000+ 知识库检索)
8. 微服务拓扑与上游同步监控 (6大微服务健康探测、管家婆熊掌柜同步看板)
"""
import csv
from datetime import datetime, timedelta
import html
import io
import json
import logging
import os
import sqlite3
import sys
import time
import urllib.parse
import socket
import urllib.request

from gateway.router import BILLS_DB_PATH, DB_PATH

CHAT_DB_PATH = os.environ.get("CHAT_DB_PATH", os.path.join(os.path.dirname(DB_PATH), "chat.db"))

_ASSETS_DIR = os.path.join(os.path.dirname(__file__), "assets")
_QRCODE_JS_PATH = os.path.join(_ASSETS_DIR, "qrcode.min.js")


def _load_qrcode_js() -> str:
    """Load standard QR code JS library from assets."""
    candidate_paths = [_QRCODE_JS_PATH]
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        candidate_paths.insert(0, os.path.join(sys._MEIPASS, "gateway", "assets", "qrcode.min.js"))
        candidate_paths.insert(0, os.path.join(sys._MEIPASS, "assets", "qrcode.min.js"))
    for p in candidate_paths:
        if os.path.exists(p):
            try:
                with open(p, "r", encoding="utf-8") as f:
                    return f.read()
            except Exception as e:
                logging.warning(f"Could not load {p}: {e}")
    return ""


def _safe_connect(db_path: str):
    """Safe read-only sqlite connection."""
    if not os.path.exists(db_path):
        return None
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        return conn
    except Exception:
        try:
            conn = sqlite3.connect(db_path)
            conn.row_factory = sqlite3.Row
            return conn
        except Exception as e:
            logging.warning(f"Could not connect to {db_path}: {e}")
            return None


def get_dashboard_data() -> dict:
    """Aggregate comprehensive dashboard metrics across bills.db, wujin.db, and chat.db."""
    today_str = datetime.now().strftime("%Y-%m-%d")
    now_ts = time.time()

    # 30-day date range initialization for trend line
    dates_30d = [(datetime.now() - timedelta(days=i)).strftime("%Y-%m-%d") for i in range(29, -1, -1)]
    trend_30d_map = {d: {"date": d, "revenue": 0.0, "profit": 0.0, "orders": 0} for d in dates_30d}

    # 13 hourly slots (08:00 to 20:00)
    hourly_map = {f"{h:02d}:00": {"hour": f"{h:02d}:00", "revenue": 0.0, "orders": 0} for h in range(8, 21)}

    data = {
        "today_str": today_str,
        "now_formatted": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        # 今日大盘核心 KPI
        "today_revenue": 0.0,
        "today_orders": 0,
        "today_profit": 0.0,
        "today_cost": 0.0,
        "margin_pct": 0.0,
        "avg_ticket": 0.0,
        "pending_count": 0,
        "pending_total_amount": 0.0,
        "pending_warning_count": 0,  # 30m~120m
        "pending_danger_count": 0,   # >120m 或隔夜
        # 店员业绩天梯榜
        "clerks": {},
        # 热销品
        "hot_items": [],
        # 库底积压与资金占用
        "stagnant_items": [],
        # 未定价与商品总库容
        "unpriced_count": 0,
        "total_products": 0,
        # 搜空分析 (问了没货)
        "unhit_queries": [],
        # 数据标记
        "marks_open": 0,
        "marks_exported": 0,
        "marks_resolved": 0,
        "recent_marks": [],
        # 上游同步状态
        "sync_info": {"synced_at": "未知", "snapshot_count": 0, "total_products": 0},
        # 扩展图表数据
        "trend_30d": [],
        "hourly_distribution": [],
        "category_distribution": [],
    }

    # 1. 聚合 bills.db
    bills_conn = _safe_connect(BILLS_DB_PATH)
    category_sales_counter = {}
    product_sales_counter = {}

    if bills_conn:
        try:
            # 检查是否有 data_marks 表
            has_marks_tbl = bills_conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='data_marks'"
            ).fetchone()
            if has_marks_tbl:
                m_rows = bills_conn.execute(
                    "SELECT id, sku, barcode, product_name, mark_type, note, status, created_at FROM data_marks ORDER BY id DESC"
                ).fetchall()
                for mr in m_rows:
                    status = mr["status"]
                    if status == "open":
                        data["marks_open"] += 1
                    elif status == "exported":
                        data["marks_exported"] += 1
                    elif status == "resolved":
                        data["marks_resolved"] += 1
                    if len(data["recent_marks"]) < 20:
                        data["recent_marks"].append(dict(mr))

            # 聚合账单
            has_bills_tbl = bills_conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='bills'"
            ).fetchone()
            if has_bills_tbl:
                rows = bills_conn.execute("SELECT id, data_json FROM bills ORDER BY updated_at DESC").fetchall()

                for row in rows:
                    try:
                        bill = json.loads(row["data_json"])
                    except Exception:
                        continue

                    status = bill.get("status", "")
                    created = str(bill.get("createdAt") or bill.get("created_at") or "")
                    created_date = created[:10]
                    created_hour = created[11:13] + ":00" if len(created) >= 13 else ""
                    staff_name = str(bill.get("staff") or bill.get("created_by_display_name") or "未分配")
                    if not staff_name.strip():
                        staff_name = "未分配"

                    if staff_name not in data["clerks"]:
                        data["clerks"][staff_name] = {
                            "name": staff_name,
                            "paid_orders": 0,
                            "paid_revenue": 0.0,
                            "pending_orders": 0,
                            "avg_ticket": 0.0,
                        }

                    is_today = created.startswith(today_str)
                    items = bill.get("items") or []
                    paid_amt = float(bill.get("paidAmount") or bill.get("total") or 0.0)

                    # 30-day trend accumulation (for paid bills)
                    if status == "paid":
                        line_cost_total = 0.0
                        for it in items:
                            qty = float(it.get("quantity") or 1.0)
                            cost = float(it.get("cost_price") or 0.0)
                            line_cost_total += cost * qty

                        line_profit = paid_amt - line_cost_total
                        if created_date in trend_30d_map:
                            trend_30d_map[created_date]["revenue"] += paid_amt
                            trend_30d_map[created_date]["profit"] += line_profit
                            trend_30d_map[created_date]["orders"] += 1

                        data["clerks"][staff_name]["paid_orders"] += 1
                        data["clerks"][staff_name]["paid_revenue"] += paid_amt

                        if is_today:
                            data["today_revenue"] += paid_amt
                            data["today_orders"] += 1
                            data["today_cost"] += line_cost_total
                            data["today_profit"] += line_profit

                            if created_hour in hourly_map:
                                hourly_map[created_hour]["revenue"] += paid_amt
                                hourly_map[created_hour]["orders"] += 1

                        # 热销累计与品类累计
                        for it in items:
                            name = str(it.get("productName") or it.get("name") or "未知商品")
                            sku = str(it.get("sku") or "")
                            qty = float(it.get("quantity") or 1.0)
                            price = float(it.get("price") or 0.0)
                            category = str(it.get("category") or "五金杂件")

                            key = f"{name}___{sku}"
                            if key not in product_sales_counter:
                                product_sales_counter[key] = {"name": name, "sku": sku, "qty": 0.0, "amount": 0.0}
                            product_sales_counter[key]["qty"] += qty
                            product_sales_counter[key]["amount"] += price * qty

                            if category not in category_sales_counter:
                                category_sales_counter[category] = {"category": category, "revenue": 0.0, "count": 0}
                            category_sales_counter[category]["revenue"] += price * qty
                            category_sales_counter[category]["count"] += int(qty)

                    elif status == "pending":
                        data["pending_count"] += 1
                        data["pending_total_amount"] += float(bill.get("total") or 0.0)
                        data["clerks"][staff_name]["pending_orders"] += 1

                        # 计算滞留时间
                        try:
                            ts = None
                            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M"):
                                try:
                                    ts = datetime.strptime(created[:19], fmt).timestamp()
                                    break
                                except Exception:
                                    pass
                            if ts:
                                elapsed_mins = (now_ts - ts) / 60.0
                                if not created.startswith(today_str) or elapsed_mins >= 120:
                                    data["pending_danger_count"] += 1
                                elif elapsed_mins >= 30:
                                    data["pending_warning_count"] += 1
                            else:
                                if not created.startswith(today_str):
                                    data["pending_danger_count"] += 1
                        except Exception:
                            pass

                # 计算综合毛利率与客单价
                if data["today_revenue"] > 0:
                    data["margin_pct"] = round((data["today_profit"] / data["today_revenue"]) * 100, 1)
                if data["today_orders"] > 0:
                    data["avg_ticket"] = round(data["today_revenue"] / data["today_orders"], 2)

                # 计算店员均单价
                for c in data["clerks"].values():
                    if c["paid_orders"] > 0:
                        c["avg_ticket"] = round(c["paid_revenue"] / c["paid_orders"], 2)

                # 热销 TOP 10
                sorted_hot = sorted(product_sales_counter.values(), key=lambda x: x["qty"], reverse=True)
                data["hot_items"] = sorted_hot[:10]

                # 品类销售占比
                total_cat_rev = sum(c["revenue"] for c in category_sales_counter.values())
                sorted_cats = sorted(category_sales_counter.values(), key=lambda x: x["revenue"], reverse=True)
                for cat in sorted_cats:
                    cat["percent"] = round((cat["revenue"] / total_cat_rev) * 100, 1) if total_cat_rev > 0 else 0.0
                data["category_distribution"] = sorted_cats[:6]

                # 趋势与时段列表格式化
                data["trend_30d"] = [trend_30d_map[d] for d in dates_30d]
                data["hourly_distribution"] = [hourly_map[f"{h:02d}:00"] for h in range(8, 21)]

        except Exception as e:
            logging.error(f"Error aggregating bills: {e}")
        finally:
            bills_conn.close()

    # 2. 聚合 wujin.db
    wujin_conn = _safe_connect(DB_PATH)
    if wujin_conn:
        try:
            cnt_row = wujin_conn.execute(
                "SELECT COUNT(*) as total, SUM(CASE WHEN sell_price <= 0 THEN 1 ELSE 0 END) as unpriced FROM products"
            ).fetchone()
            if cnt_row:
                data["sync_info"]["total_products"] = cnt_row["total"]
                data["total_products"] = cnt_row["total"]
                data["unpriced_count"] = cnt_row["unpriced"] or 0

            cols = {c[1] for c in wujin_conn.execute("PRAGMA table_info(products)").fetchall()}
            loc_col = "location" if "location" in cols else "'' as location"
            stag_rows = wujin_conn.execute(
                f"""SELECT id, sku, barcode, name, spec, unit, stock_qty, cost_price, sell_price, {loc_col},
                          ROUND(stock_qty * cost_price, 2) as capital_tied
                   FROM products
                   WHERE stock_qty > 10 AND cost_price > 0
                   ORDER BY capital_tied DESC
                   LIMIT 10"""
            ).fetchall()
            data["stagnant_items"] = [dict(r) for r in stag_rows]

            has_snapshot = wujin_conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='xzg_stock_snapshot'"
            ).fetchone()
            if has_snapshot:
                snap = wujin_conn.execute(
                    "SELECT COUNT(*) as cnt, MAX(synced_at) as last_sync FROM xzg_stock_snapshot"
                ).fetchone()
                if snap:
                    data["sync_info"]["snapshot_count"] = snap["cnt"]
                    data["sync_info"]["synced_at"] = snap["last_sync"] or "暂无记录"

            has_usage = wujin_conn.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='usage_stats'"
            ).fetchone()
            if has_usage:
                unhits = wujin_conn.execute(
                    """SELECT keyword, COUNT(*) as unhit_count, MAX(timestamp) as last_time
                       FROM usage_stats
                       WHERE result_count = 0 AND keyword IS NOT NULL AND TRIM(keyword) != ''
                       GROUP BY keyword
                       ORDER BY unhit_count DESC, last_time DESC
                       LIMIT 15"""
                ).fetchall()
                data["unhit_queries"] = [dict(r) for r in unhits]

        except Exception as e:
            logging.error(f"Error aggregating wujin: {e}")
        finally:
            wujin_conn.close()

    # 3. 聚合 chat.db (未匹配问题)
    if not data["unhit_queries"] and os.path.exists(CHAT_DB_PATH):
        chat_conn = _safe_connect(CHAT_DB_PATH)
        if chat_conn:
            try:
                has_unm = chat_conn.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='unmatched_queries'"
                ).fetchone()
                if has_unm:
                    cq = chat_conn.execute(
                        "SELECT query as keyword, 1 as unhit_count, created_at as last_time FROM unmatched_queries ORDER BY id DESC LIMIT 15"
                    ).fetchall()
                    data["unhit_queries"] = [dict(r) for r in cq]
            except Exception as e:
                logging.error(f"Error reading chat unmatches: {e}")
            finally:
                chat_conn.close()

    return data


def get_admin_products(
    keyword: str = "",
    category: str = "",
    brand: str = "",
    stock_status: str = "all",
    price_status: str = "all",
    page: int = 1,
    page_size: int = 20,
    sort_by: str = "id",
    sort_order: str = "desc",
    filter_type: str = "all",
) -> dict:
    """Paginated product list with search and multi-dimensional filters."""
    if filter_type == "in_stock":
        stock_status = "in_stock"
    elif filter_type == "out_stock":
        stock_status = "out_stock"
    elif filter_type == "unpriced":
        price_status = "unpriced"
    elif filter_type == "priced":
        price_status = "priced"

    conn = _safe_connect(DB_PATH)
    if not conn:
        return {"items": [], "total": 0, "page": page, "page_size": page_size, "total_pages": 0}
    try:
        cols = {c[1] for c in conn.execute("PRAGMA table_info(products)").fetchall()}
        where_clauses = []
        params = []
        if keyword:
            kw = f"%{keyword.strip()}%"
            kw_or = ["name LIKE ?"]
            params.append(kw)
            for optional_col in ("spec", "brand", "barcode", "sku", "initials", "pinyin"):
                if optional_col in cols:
                    kw_or.append(f"{optional_col} LIKE ?")
                    params.append(kw)
            where_clauses.append("(" + " OR ".join(kw_or) + ")")
        if category:
            where_clauses.append("category = ?")
            params.append(category)
        if brand:
            where_clauses.append("brand = ?")
            params.append(brand)
        if stock_status == "in_stock":
            where_clauses.append("stock_qty > 0")
        elif stock_status == "out_stock":
            where_clauses.append("stock_qty <= 0")
        elif stock_status == "negative":
            where_clauses.append("stock_qty < 0")
        if price_status == "unpriced":
            where_clauses.append("sell_price <= 0")
        elif price_status == "priced":
            where_clauses.append("sell_price > 0")

        where_sql = ("WHERE " + " AND ".join(where_clauses)) if where_clauses else ""
        count_sql = f"SELECT COUNT(*) FROM products {where_sql}"
        total = conn.execute(count_sql, params).fetchone()[0]

        valid_sort_cols = {"id", "name", "sell_price", "cost_price", "stock_qty", "updated_at"}
        col = sort_by if sort_by in valid_sort_cols else "id"
        order = "DESC" if sort_order.lower() == "desc" else "ASC"

        limit = max(1, min(page_size, 100))
        offset = (max(1, page) - 1) * limit

        cols = {c[1] for c in conn.execute("PRAGMA table_info(products)").fetchall()}
        loc_col = "location" if "location" in cols else "'' as location"
        min_col = "min_stock" if "min_stock" in cols else "0 as min_stock"
        wh_col = "warehouse" if "warehouse" in cols else "'' as warehouse"
        stat_col = "sale_status" if "sale_status" in cols else "'' as sale_status"
        up_col = "updated_at" if "updated_at" in cols else "'' as updated_at"
        cat_col = "category" if "category" in cols else "'' as category"
        br_col = "brand" if "brand" in cols else "'' as brand"

        query_sql = f"""
            SELECT id, sku, barcode, name, spec, unit, {cat_col}, {br_col},
                   cost_price, sell_price, stock_qty, {min_col}, {wh_col}, {loc_col}, {stat_col}, {up_col}
            FROM products
            {where_sql}
            ORDER BY {col} {order}
            LIMIT ? OFFSET ?
        """
        rows = conn.execute(query_sql, params + [limit, offset]).fetchall()
        items = []
        for r in rows:
            it = dict(r)
            sp = float(it.get("sell_price") or 0.0)
            cp = float(it.get("cost_price") or 0.0)
            it["margin_pct"] = round(((sp - cp) / sp) * 100, 1) if sp > 0 else 0.0
            items.append(it)

        total_pages = (total + limit - 1) // limit if total > 0 else 1
        return {
            "items": items,
            "total": total,
            "page": page,
            "page_size": limit,
            "total_pages": total_pages,
        }
    except Exception as e:
        logging.error(f"Error querying admin products: {e}")
        return {"items": [], "total": 0, "page": page, "page_size": page_size, "total_pages": 0, "error": str(e)}
    finally:
        conn.close()


def get_admin_product_detail(product_id: int) -> dict:
    """Get complete product profile, shelf bindings, and recommendations."""
    conn = _safe_connect(DB_PATH)
    if not conn:
        return {"error": "Database unavailable"}
    try:
        row = conn.execute("SELECT * FROM products WHERE id = ?", (product_id,)).fetchone()
        if not row:
            return {"error": "Product not found"}
        prod = dict(row)
        sp = float(prod.get("sell_price") or 0.0)
        cp = float(prod.get("cost_price") or 0.0)
        prod["margin_pct"] = round(((sp - cp) / sp) * 100, 1) if sp > 0 else 0.0

        # Location details
        has_pl = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='product_locations'").fetchone()
        if has_pl:
            loc_rows = conn.execute("SELECT * FROM product_locations WHERE product_id = ?", (product_id,)).fetchall()
            prod["locations_list"] = [dict(r) for r in loc_rows]
        else:
            prod["locations_list"] = []

        # Substitutes & cross-sell
        substitutes = []
        if prod.get("category"):
            cols = {c[1] for c in conn.execute("PRAGMA table_info(products)").fetchall()}
            loc_col = "location" if "location" in cols else "'' as location"
            sub_rows = conn.execute(
                f"""SELECT id, sku, barcode, name, spec, unit, sell_price, stock_qty, {loc_col}
                   FROM products
                   WHERE category = ? AND id != ? AND stock_qty > 0
                   LIMIT 6""",
                (prod["category"], product_id)
            ).fetchall()
            substitutes = [dict(r) for r in sub_rows]

        return {
            "found": True,
            "product": prod,
            "substitutes": substitutes,
        }
    finally:
        conn.close()


def get_admin_bills(
    status: str = "all",
    staff: str = "",
    customer: str = "",
    date_start: str = "",
    date_end: str = "",
    page: int = 1,
    page_size: int = 20,
    keyword: str = "",
) -> dict:
    """Paginated bill stream with full metadata, timeout alerts, and profit analysis."""
    conn = _safe_connect(BILLS_DB_PATH)
    if not conn:
        return {"bills": [], "total": 0, "page": page, "page_size": page_size, "total_pages": 0, "summary": {}}
    try:
        has_tbl = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='bills'").fetchone()
        if not has_tbl:
            return {"bills": [], "total": 0, "page": page, "page_size": page_size, "total_pages": 0, "summary": {}}

        rows = conn.execute("SELECT id, data_json, updated_at FROM bills ORDER BY updated_at DESC").fetchall()
        now_ts = time.time()
        filtered = []
        sum_revenue = 0.0
        sum_profit = 0.0
        sum_pending = 0.0

        for r in rows:
            try:
                b = json.loads(r["data_json"])
            except Exception:
                continue

            b_id = str(b.get("id") or r["id"])
            b_status = b.get("status", "pending")
            b_staff = str(b.get("staff") or b.get("created_by_display_name") or "未分配")
            b_cust = str(b.get("customer_name") or b.get("customerName") or "散客")
            b_created = str(b.get("created_at") or b.get("createdAt") or r["updated_at"] or "")
            b_total = float(b.get("actual_amount") or b.get("paidAmount") or b.get("total") or b.get("total_amount") or b.get("amount") or 0.0)
            items = b.get("items") or []

            b_profit = 0.0
            for it in items:
                qty = float(it.get("quantity") or 1.0)
                price = float(it.get("price") or 0.0)
                cost = float(it.get("cost_price") or 0.0)
                b_profit += (price - cost) * qty

            if status != "all" and b_status != status:
                continue
            if staff and staff.lower() not in b_staff.lower():
                continue
            if customer and customer.lower() not in b_cust.lower():
                continue
            if keyword:
                kw_lower = keyword.lower()
                match_kw = (
                    kw_lower in b_id.lower() or
                    kw_lower in b_cust.lower() or
                    kw_lower in b_staff.lower() or
                    any(kw_lower in str(it.get("name", "")).lower() for it in items)
                )
                if not match_kw:
                    continue
            if date_start and b_created[:10] < date_start:
                continue
            if date_end and b_created[:10] > date_end:
                continue

            if b_status == "paid":
                sum_revenue += b_total
                sum_profit += b_profit
            elif b_status == "pending":
                sum_pending += b_total

            elapsed_mins = 0
            is_warning = False
            is_danger = False
            if b_status == "pending" and b_created:
                try:
                    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M"):
                        try:
                            ts = datetime.strptime(b_created[:19], fmt).timestamp()
                            elapsed_mins = int((now_ts - ts) / 60)
                            break
                        except Exception:
                            pass
                except Exception:
                    pass
                if elapsed_mins >= 120 or not b_created.startswith(datetime.now().strftime("%Y-%m-%d")):
                    is_danger = True
                elif elapsed_mins >= 30:
                    is_warning = True

            filtered.append({
                "id": b_id,
                "status": b_status,
                "customer_name": b_cust,
                "staff": b_staff,
                "created_at": b_created,
                "total": round(b_total, 2),
                "profit": round(b_profit, 2),
                "items_count": len(items),
                "items": items,
                "pay_method": b.get("pay_method") or b.get("payMethod") or "cash",
                "note": b.get("note", ""),
                "elapsed_mins": elapsed_mins,
                "is_warning": is_warning,
                "is_danger": is_danger,
            })

        total = len(filtered)
        limit = max(1, min(page_size, 100))
        offset = (max(1, page) - 1) * limit
        paged_bills = filtered[offset:offset + limit]
        total_pages = (total + limit - 1) // limit if total > 0 else 1

        return {
            "bills": paged_bills,
            "total": total,
            "page": page,
            "page_size": limit,
            "total_pages": total_pages,
            "summary": {
                "total_revenue": round(sum_revenue, 2),
                "paid_amount": round(sum_revenue, 2),
                "total_profit": round(sum_profit, 2),
                "pending_amount": round(sum_pending, 2),
                "paid_count": sum(1 for b in filtered if b["status"] == "paid"),
                "pending_count": sum(1 for b in filtered if b["status"] == "pending"),
            }
        }
    except Exception as e:
        logging.error(f"Error querying admin bills: {e}")
        return {"bills": [], "total": 0, "page": page, "page_size": page_size, "total_pages": 0, "summary": {}, "error": str(e)}
    finally:
        conn.close()


def update_bill_status(bill_id: str, new_status: str, staff: str = "") -> dict:
    """Update bill status (pending <-> paid) and close out Guanjiaopo sync."""
    if new_status not in ("pending", "paid"):
        return {"success": False, "error": "无效的账单状态，仅限 pending 或 paid"}
    conn = sqlite3.connect(BILLS_DB_PATH)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        row = conn.execute("SELECT data_json FROM bills WHERE id = ?", (bill_id,)).fetchone()
        if not row:
            return {"success": False, "error": f"未找到账单 {bill_id}"}
        b = json.loads(row[0])
        b["status"] = new_status
        b["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        if new_status == "paid" and not b.get("paidAmount"):
            b["paidAmount"] = b.get("total", 0.0)
        conn.execute("UPDATE bills SET data_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", (json.dumps(b, ensure_ascii=False), bill_id))
        conn.commit()
        return {"success": True, "bill_id": bill_id, "status": new_status, "new_status": new_status}
    except Exception as e:
        conn.rollback()
        return {"success": False, "error": str(e)}
    finally:
        conn.close()


def get_admin_customers() -> dict:
    """Customer accounts, receivable debts, and transaction histories."""
    conn = _safe_connect(BILLS_DB_PATH)
    if not conn:
        return {"customers": []}
    try:
        has_tbl = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='bills'").fetchone()
        if not has_tbl:
            return {"customers": []}
        rows = conn.execute("SELECT id, data_json, updated_at FROM bills ORDER BY updated_at DESC").fetchall()
        cust_map = {}
        for r in rows:
            try:
                b = json.loads(r["data_json"])
            except Exception:
                continue
            cust = str(b.get("customer_name") or b.get("customerName") or "散客").strip()
            if not cust:
                cust = "散客"
            status = b.get("status", "pending")
            total = float(b.get("actual_amount") or b.get("total") or b.get("total_amount") or b.get("paidAmount") or b.get("amount") or 0.0)
            created = str(b.get("created_at") or b.get("createdAt") or r["updated_at"] or "")

            if cust not in cust_map:
                cust_map[cust] = {
                    "customer_name": cust,
                    "total_orders": 0,
                    "total_spent": 0.0,
                    "pending_debt": 0.0,
                    "last_order_at": created,
                    "bills": [],
                }
            cust_map[cust]["total_orders"] += 1
            if status == "paid":
                cust_map[cust]["total_spent"] += total
            elif status == "pending":
                cust_map[cust]["pending_debt"] += total
            if created > cust_map[cust]["last_order_at"]:
                cust_map[cust]["last_order_at"] = created
            if len(cust_map[cust]["bills"]) < 10:
                cust_map[cust]["bills"].append({
                    "id": b.get("id") or r["id"],
                    "total": total,
                    "status": status,
                    "created_at": created,
                    "items_count": len(b.get("items") or []),
                })

        sorted_custs = sorted(cust_map.values(), key=lambda x: (x["pending_debt"], x["total_spent"]), reverse=True)
        total_debt = round(sum(c["pending_debt"] for c in sorted_custs), 2)
        return {
            "customers": sorted_custs,
            "total_debt": total_debt,
            "total_customers": len(sorted_custs),
        }
    except Exception as e:
        return {"customers": [], "total_debt": 0.0, "total_customers": 0, "error": str(e)}
    finally:
        conn.close()


def get_admin_inventory_overview() -> dict:
    """5-level shelf structure, urgent stockouts, and capital tied items."""
    conn = _safe_connect(DB_PATH)
    if not conn:
        return {"tree": [], "urgent_replenish": [], "stagnant_items": []}
    try:
        zones = [dict(r) for r in conn.execute("SELECT * FROM inventory_zones ORDER BY sort_order, id").fetchall()]
        racks = [dict(r) for r in conn.execute("SELECT * FROM inventory_racks ORDER BY sort_order, id").fetchall()]
        levels = [dict(r) for r in conn.execute("SELECT * FROM inventory_levels ORDER BY sort_order, id").fetchall()]
        cells = [dict(r) for r in conn.execute("SELECT * FROM inventory_cells ORDER BY sort_order, id").fetchall()]

        tree = []
        for z in zones:
            z_racks = []
            for rk in [r for r in racks if r.get("zone_id") == z["id"]]:
                rk_levels = []
                for lv in [l for l in levels if l.get("rack_id") == rk["id"]]:
                    lv_cells = [c for c in cells if c.get("level_id") == lv["id"]]
                    rk_levels.append({**lv, "cells": lv_cells, "cell_count": len(lv_cells)})
                z_racks.append({**rk, "levels": rk_levels, "cell_count": sum(len(l["cells"]) for l in rk_levels)})
            tree.append({**z, "racks": z_racks, "rack_count": len(z_racks)})

        cols = {c[1] for c in conn.execute("PRAGMA table_info(products)").fetchall()}

        # F6: fully static SQL (identifiers never concatenated); legacy DB falls back below
        if {"category", "brand", "min_stock", "location"} <= cols:
            urgent = conn.execute("""
                SELECT id, sku, barcode, name, spec, unit, category, brand, cost_price, sell_price, stock_qty, min_stock, location
                FROM products
                WHERE stock_qty <= 0 OR (min_stock > 0 AND stock_qty <= min_stock)
                ORDER BY stock_qty ASC, id DESC
                LIMIT 50
            """).fetchall()
        else:
            urgent = conn.execute(
                "SELECT * FROM products WHERE stock_qty <= 0 ORDER BY stock_qty ASC, id DESC LIMIT 50"
            ).fetchall()

        if {"category", "brand", "min_stock", "location"} <= cols:
            stagnant = conn.execute("""
                SELECT id, sku, barcode, name, spec, unit, category, brand, cost_price, sell_price, stock_qty, location,
                       ROUND(stock_qty * cost_price, 2) as capital_tied
                FROM products
                WHERE stock_qty > 10 AND cost_price > 0
                ORDER BY capital_tied DESC
                LIMIT 20
            """).fetchall()
        else:
            stagnant = conn.execute(
                "SELECT * FROM products WHERE stock_qty > 10 AND cost_price > 0 "
                "ORDER BY stock_qty * cost_price DESC LIMIT 20"
            ).fetchall()

        return {
            "tree": tree,
            "urgent_replenish": [dict(r) for r in urgent],
            "stagnant_items": [dict(r) for r in stagnant],
        }
    except Exception as e:
        return {"tree": [], "urgent_replenish": [], "stagnant_items": [], "error": str(e)}
    finally:
        conn.close()


def resolve_data_mark(mark_id: int, note: str = "") -> dict:
    """Close out data mark in bills.db with resolution timestamp and note."""
    conn = sqlite3.connect(BILLS_DB_PATH)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        row = conn.execute("SELECT note FROM data_marks WHERE id = ?", (mark_id,)).fetchone()
        if not row:
            return {"success": False, "error": f"未找到标记 #{mark_id}"}
        orig_note = row[0] or ""
        updated_note = (orig_note + f" [核准备注: {note}]").strip() if note else orig_note
        conn.execute(
            "UPDATE data_marks SET status = 'resolved', resolved_at = CURRENT_TIMESTAMP, note = ? WHERE id = ?",
            (updated_note, mark_id)
        )
        conn.commit()
        return {"success": True, "mark_id": mark_id, "status": "resolved"}
    except Exception as e:
        conn.rollback()
        return {"success": False, "error": str(e)}
    finally:
        conn.close()


def get_admin_services_health() -> dict:
    """Ping all 6 backend services and return live latencies and statuses."""
    services = [
        {"name": "API 网关 (Gateway)", "port": 18767, "url": "http://127.0.0.1:18767/health"},
        {"name": "检索服务 (Search FTS5)", "port": 18601, "url": "http://127.0.0.1:18601/health"},
        {"name": "知识库服务 (Knowledge)", "port": 18602, "url": "http://127.0.0.1:18602/health"},
        {"name": "智能问答 (Chat LLM)", "port": 18603, "url": "http://127.0.0.1:18603/health"},
        {"name": "货架库存 (Inventory)", "port": 18604, "url": "http://127.0.0.1:18604/health"},
        {"name": "价签打印 (Print TSPL)", "port": 18605, "url": "http://127.0.0.1:18605/health"},
    ]
    results = []
    for s in services:
        t0 = time.time()
        status = "offline"
        details = {}
        try:
            _u = urllib.parse.urlparse(s["url"])
            _ip = socket.gethostbyname(_u.hostname) if _u.hostname else ""
            if _u.scheme != "http" or _ip != "127.0.0.1":
                raise ValueError("blocked service url")  # F6: scheme+解析后IP边界
            req = urllib.request.Request(s["url"], headers={"User-Agent": "retaildemoAdmin/1.0"})
            if _u.scheme != "http" or _u.hostname != "127.0.0.1":
                raise ValueError("blocked service url")  # F6 SSRF
            with urllib.request.urlopen(req, timeout=0.5) as resp:
                latency = round((time.time() - t0) * 1000, 1)
                if resp.status == 200:
                    status = "online"
                    try:
                        details = json.loads(resp.read().decode("utf-8"))
                    except Exception:
                        pass
        except Exception:
            latency = round((time.time() - t0) * 1000, 1)
        results.append({
            "name": s["name"],
            "port": s["port"],
            "status": status,
            "latency_ms": latency,
            "details": details,
        })
    return {"services": results}


def admin_ai_chat(query: str) -> dict:
    """Console chat with local AI LLM and Hardware Knowledge base."""
    if not query:
        return {"reply": "请输入您的问题。"}
    # 1. 尝试直连 :18603/chat
    try:
        req_data = json.dumps({"message": query, "user_id": "admin"}).encode("utf-8")
        from utils.guards import safe_url
        _chat_url = safe_url("http://127.0.0.1:18603/chat", allow_private=True)  # F6
        _cu = urllib.parse.urlparse(_chat_url)
        req = urllib.request.Request(_chat_url, data=req_data, headers={"Content-Type": "application/json"})
        if _cu.scheme != "http" or _cu.hostname != "127.0.0.1":
            raise ValueError("blocked chat url")  # F6 SSRF
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            if resp.status == 200:
                res = json.loads(resp.read().decode("utf-8"))
                reply = res.get("response") or res.get("reply") or res.get("data", {}).get("reply")
                if reply:
                    return {"reply": reply, "source": "llm"}
    except Exception:
        pass
    # 2. 降级查询 :18602/search 专家知识库
    try:
        from utils.guards import safe_url
        _kb_url = safe_url(f"http://127.0.0.1:18602/search?q={urllib.request.quote(query)}", allow_private=True)  # F6
        _ku = urllib.parse.urlparse(_kb_url)
        req = urllib.request.Request(_kb_url)
        if _ku.scheme != "http" or _ku.hostname != "127.0.0.1":
            raise ValueError("blocked knowledge url")  # F6 SSRF
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            if resp.status == 200:
                res = json.loads(resp.read().decode("utf-8"))
                items = res.get("data") or []
                if items:
                    top = items[0]
                    return {
                        "reply": f"【施工专家知识库匹配】\\n问：{top.get('question', '')}\\n答：{top.get('answer', '')}",
                        "source": "knowledge",
                        "items": items[:3]
                    }
    except Exception:
        pass
    return {
        "reply": f"已收到关于「{query}」的咨询。当前店内智能模型未连接外网或正处于离线微调中，建议查阅右侧施工知识库，或在商品中心搜索规格货位。",
        "source": "fallback"
    }


def export_marks_csv() -> str:
    """Export data_marks to CSV string with UTF-8 BOM for Excel compatibility."""
    output = io.StringIO()
    output.write("\ufeff")
    writer = csv.writer(output)
    writer.writerow(["标记ID", "商品编码(SKU)", "商品条码", "品名", "标记问题类型", "说明备注", "处理状态", "登记时间", "解决时间"])

    conn = _safe_connect(BILLS_DB_PATH)
    if conn:
        try:
            has_tbl = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='data_marks'").fetchone()
            if has_tbl:
                rows = conn.execute(
                    "SELECT id, sku, barcode, product_name, mark_type, note, status, created_at, resolved_at FROM data_marks ORDER BY id DESC"
                ).fetchall()
                for r in rows:
                    type_zh = {
                        "no_price": "无售价",
                        "wrong_price": "价格不准",
                        "stock_mismatch": "账实不符",
                        "bind_barcode": "待绑条码",
                        "missing_barcode": "无码商品",
                    }.get(r["mark_type"], r["mark_type"] or "其他")

                    status_zh = {
                        "open": "待调整",
                        "exported": "已导出",
                        "resolved": "已核准闭环",
                    }.get(r["status"], r["status"])

                    writer.writerow([
                        r["id"],
                        r["sku"] or "",
                        r["barcode"] or "",
                        r["product_name"] or "",
                        type_zh,
                        r["note"] or "",
                        status_zh,
                        r["created_at"] or "",
                        r["resolved_at"] or "",
                    ])
        except Exception as e:
            logging.error(f"Error exporting marks CSV: {e}")
        finally:
            conn.close()

    return output.getvalue()


def sync_mark_items(items: list) -> dict:
    """Save or sync mark items into bills.db data_marks table."""
    if not items:
        return {"success": True, "count": 0}

    conn = sqlite3.connect(BILLS_DB_PATH)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS data_marks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sku TEXT,
                barcode TEXT,
                product_name TEXT,
                mark_type TEXT,
                note TEXT,
                photo_uri TEXT,
                status TEXT DEFAULT 'open',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                resolved_at TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_marks_status ON data_marks(status)")

        saved = 0
        for item in items:
            sku = item.get("sku", "")
            barcode = item.get("barcode", "")
            name = item.get("productName", item.get("product_name", ""))
            mtype = item.get("markType", item.get("mark_type", "wrong_price"))
            note = item.get("note", "")
            photo = item.get("photoUri", item.get("photo_uri", ""))
            status = item.get("status", "open")
            created_at = item.get("created_at", datetime.now().strftime("%Y-%m-%d %H:%M:%S"))

            conn.execute(
                """INSERT INTO data_marks (sku, barcode, product_name, mark_type, note, photo_uri, status, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                (sku, barcode, name, mtype, note, photo, status, created_at)
            )
            saved += 1

        conn.commit()
        return {"success": True, "count": saved}
    except Exception as e:
        conn.rollback()
        logging.error(f"Failed to sync marks: {e}")
        return {"success": False, "error": str(e)}
    finally:
        conn.close()


def render_dashboard_html(data: dict = None) -> str:
    """Render modern high-aesthetic Single Page Application (SPA) HTML/CSS/JS dashboard."""
    if data is None:
        data = get_dashboard_data()

    initial_json = json.dumps(data, ensure_ascii=False)

    template = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>示例零售 · 经营与数据管理大盘</title>
    <style>
        :root {
            --bg-body: #080B10;
            --bg-card: #111622;
            --bg-card-elevated: #182030;
            --sidebar-bg: #0B0F17;
            --sidebar-hover: #182030;
            --sidebar-active: #FF6A00;
            --sidebar-text: #94A3B8;
            --sidebar-text-active: #FFFFFF;
            --border-color: #222C3E;
            --border-light: #182030;
            --border-highlight: #3B4B68;
            --text-main: #F8FAFC;
            --text-secondary: #94A3B8;
            --text-muted: #64748B;
            --primary: #FF6A00;
            --primary-hover: #FF7D1A;
            --primary-light: rgba(255, 106, 0, 0.15);
            --cyan: #00F2FE;
            --cyan-light: rgba(0, 242, 254, 0.15);
            --success: #00E599;
            --success-light: rgba(0, 229, 153, 0.15);
            --warning: #FFB800;
            --warning-light: rgba(255, 184, 0, 0.15);
            --danger: #FF2E4D;
            --danger-light: rgba(255, 46, 77, 0.15);
            --radius-md: 8px;
            --radius-lg: 12px;
            --shadow-sm: 0 2px 8px 0 rgba(0,0,0,0.4);
            --shadow-md: 0 8px 24px -2px rgba(0,0,0,0.6);
            --shadow-lg: 0 16px 36px -4px rgba(0,0,0,0.8);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
            background-color: var(--bg-body);
            color: var(--text-main);
            line-height: 1.5;
            display: flex;
            min-height: 100vh;
            overflow-x: hidden;
        }
        /* Sidebar */
        .sidebar {
            width: 240px;
            background: var(--sidebar-bg);
            color: var(--sidebar-text);
            display: flex;
            flex-direction: column;
            flex-shrink: 0;
            position: sticky;
            top: 0;
            height: 100vh;
            border-right: 1px solid rgba(255,255,255,0.08);
            z-index: 100;
        }
        .sidebar-brand {
            padding: 20px 20px 16px;
            border-bottom: 1px solid rgba(255,255,255,0.08);
        }
        .brand-logo {
            font-size: 17px;
            font-weight: 800;
            color: #FFFFFF;
            letter-spacing: -0.3px;
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .brand-sub {
            font-size: 11px;
            color: #64748B;
            margin-top: 4px;
            font-weight: 500;
        }
        .sidebar-menu {
            padding: 16px 12px;
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .nav-item {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 10px 14px;
            border-radius: var(--radius-md);
            color: var(--sidebar-text);
            text-decoration: none;
            font-size: 13.5px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.15s ease;
        }
        .nav-item:hover {
            background: var(--sidebar-hover);
            color: #FFFFFF;
        }
        .nav-item.active {
            background: var(--sidebar-active);
            color: #FFFFFF;
            box-shadow: 0 4px 12px rgba(37,99,235,0.3);
        }
        .nav-item-left {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .nav-badge {
            font-size: 11px;
            padding: 2px 7px;
            border-radius: 12px;
            background: rgba(255,255,255,0.15);
            font-weight: 700;
        }
        .nav-badge-warn {
            background: var(--danger);
            color: #FFFFFF;
        }
        .sidebar-footer {
            padding: 16px 20px;
            border-top: 1px solid rgba(255,255,255,0.08);
            font-size: 11px;
            color: #64748B;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .status-indicator {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            color: #10B981;
            font-weight: 600;
        }
        .status-dot {
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background: #10B981;
            box-shadow: 0 0 8px #10B981;
        }
        /* Main Layout */
        .main-wrapper {
            flex: 1;
            display: flex;
            flex-direction: column;
            min-width: 0;
            height: 100vh;
            overflow-y: auto;
        }
        .topbar {
            background: var(--bg-card);
            border-bottom: 1px solid var(--border-color);
            padding: 14px 28px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            position: sticky;
            top: 0;
            z-index: 90;
            box-shadow: var(--shadow-sm);
        }
        .topbar-left {
            display: flex;
            align-items: center;
            gap: 16px;
        }
        .page-title {
            font-size: 17px;
            font-weight: 800;
            color: var(--text-main);
        }
        .topbar-right {
            display: flex;
            align-items: center;
            gap: 14px;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 7px 14px;
            border-radius: var(--radius-md);
            font-size: 13px;
            font-weight: 600;
            border: 1px solid transparent;
            cursor: pointer;
            text-decoration: none;
            transition: all 0.15s;
        }
        .btn-primary { background: var(--primary); color: #FFFFFF; }
        .btn-primary:hover { background: var(--primary-hover); }
        .btn-secondary { background: var(--bg-card-elevated); color: var(--text-main); border-color: var(--border-color); }
        .btn-secondary:hover { background: #222C40; border-color: var(--border-highlight); }
        .btn-success { background: var(--success); color: #FFFFFF; }
        .btn-success:hover { opacity: 0.9; }
        .btn-danger { background: var(--danger); color: #FFFFFF; }
        .btn-danger:hover { opacity: 0.9; }
        .btn-sm { padding: 4px 10px; font-size: 12px; }
        .content-container {
            padding: 24px 28px 40px;
            max-width: 1440px;
            width: 100%;
            margin: 0 auto;
        }
        /* Views */
        .tab-view { display: none; }
        .tab-view.active { display: block; }
        /* Cards */
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
            gap: 16px;
            margin-bottom: 24px;
        }
        .stat-card {
            background: var(--bg-card);
            border-radius: var(--radius-lg);
            padding: 18px 20px;
            border: 1px solid var(--border-color);
            box-shadow: var(--shadow-sm);
            position: relative;
            overflow: hidden;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .stat-card:hover {
            transform: translateY(-2px);
            box-shadow: var(--shadow-md);
        }
        .stat-card::before {
            content: "";
            position: absolute;
            left: 0; top: 0; bottom: 0;
            width: 4px;
            background: var(--primary);
        }
        .stat-card.stat-success::before { background: var(--success); }
        .stat-card.stat-warning::before { background: var(--warning); }
        .stat-card.stat-danger::before { background: var(--danger); }
        .stat-label {
            font-size: 12.5px;
            font-weight: 600;
            color: var(--text-muted);
            margin-bottom: 8px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .stat-val {
            font-size: 26px;
            font-weight: 800;
            color: var(--text-main);
            line-height: 1.1;
        }
        .stat-foot {
            font-size: 11.5px;
            color: var(--text-muted);
            margin-top: 8px;
        }
        /* Charts Grid */
        .charts-row {
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 20px;
            margin-bottom: 24px;
        }
        @media (max-width: 1024px) {
            .charts-row { grid-template-columns: 1fr; }
        }
        .panel {
            background: var(--bg-card);
            border-radius: var(--radius-lg);
            border: 1px solid var(--border-color);
            box-shadow: var(--shadow-sm);
            overflow: hidden;
            margin-bottom: 24px;
        }
        .panel-head {
            padding: 16px 20px;
            border-bottom: 1px solid var(--border-color);
            display: flex;
            justify-content: space-between;
            align-items: center;
            background: var(--bg-card);
        }
        .panel-title {
            font-size: 15px;
            font-weight: 700;
            color: var(--text-main);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .panel-body { padding: 20px; }
        /* Tables */
        .table-responsive { overflow-x: auto; }
        table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
        th {
            background: #141A24;
            color: var(--text-muted);
            font-weight: 700;
            padding: 10px 12px;
            border-bottom: 1px solid var(--border-color);
            white-space: nowrap;
        }
        td {
            padding: 12px;
            border-bottom: 1px solid var(--border-light);
            vertical-align: middle;
            color: var(--text-secondary);
        }
        tr:hover td { background-color: #182030; }
        .text-right { text-align: right; }
        .text-center { text-align: center; }
        .font-mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
        .font-bold { font-weight: 700; }
        .badge {
            display: inline-block;
            font-size: 11px;
            padding: 2px 8px;
            border-radius: 4px;
            font-weight: 700;
        }
        .badge-success { background: var(--success-light); color: var(--success); }
        .badge-warning { background: var(--warning-light); color: var(--warning); }
        .badge-danger { background: var(--danger-light); color: var(--danger); }
        .badge-primary { background: var(--primary-light); color: var(--primary); }
        .badge-secondary { background: var(--bg-card-elevated); color: var(--text-muted); border: 1px solid var(--border-color); }
        /* Search & Filter Bar */
        .filter-bar {
            display: flex;
            flex-wrap: wrap;
            gap: 12px;
            margin-bottom: 16px;
            align-items: center;
        }
        .input-text, .select-box {
            padding: 8px 12px;
            border: 1px solid var(--border-color);
            border-radius: var(--radius-md);
            font-size: 13px;
            outline: none;
            background: var(--bg-card-elevated);
            color: var(--text-main);
            transition: border-color 0.15s;
        }
        .input-text:focus, .select-box:focus { border-color: var(--primary); }
        .filter-chips {
            display: flex;
            gap: 6px;
        }
        .chip {
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 600;
            background: var(--bg-card-elevated);
            color: var(--text-secondary);
            border: 1px solid transparent;
            cursor: pointer;
            transition: all 0.15s;
        }
        .chip.active {
            background: var(--primary-light);
            color: var(--primary);
            border-color: var(--primary);
        }
        /* Pagination */
        .pagination-wrap {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 16px 0 0;
            font-size: 12.5px;
            color: var(--text-muted);
        }
        /* Modal & Drawer */
        .modal-mask {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(15, 23, 42, 0.6);
            backdrop-filter: blur(4px);
            display: none;
            justify-content: center;
            align-items: center;
            z-index: 1000;
        }
        .modal-mask.open { display: flex; }
        .modal-box {
            background: var(--bg-card);
            border: 1px solid var(--border-highlight);
            color: var(--text-main);
            border-radius: var(--radius-lg);
            width: 90%;
            max-width: 520px;
            box-shadow: var(--shadow-lg);
            overflow: hidden;
            animation: modalIn 0.2s ease-out;
        }
        .drawer-mask {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(15, 23, 42, 0.4);
            display: none;
            justify-content: flex-end;
            z-index: 999;
        }
        .drawer-mask.open { display: flex; }
        .drawer-box {
            background: #FFFFFF;
            width: 100%;
            max-width: 600px;
            height: 100%;
            overflow-y: auto;
            box-shadow: var(--shadow-lg);
            padding: 24px;
            display: flex;
            flex-direction: column;
            animation: drawerIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
        }
        @keyframes modalIn { from { opacity: 0; transform: scale(0.95); } to { opacity: 1; transform: scale(1); } }
        @keyframes drawerIn { from { transform: translateX(100%); } to { transform: translateX(0); } }
        /* Toast */
        .toast-box {
            position: fixed;
            bottom: 24px;
            right: 24px;
            z-index: 2000;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .toast {
            padding: 10px 16px;
            background: #0F172A;
            color: #FFFFFF;
            border-radius: var(--radius-md);
            font-size: 13px;
            font-weight: 600;
            box-shadow: var(--shadow-lg);
            display: flex;
            align-items: center;
            gap: 8px;
            animation: toastIn 0.2s ease-out;
        }
        .toast-success { background: #059669; }
        .toast-error { background: #E11D48; }
        @keyframes toastIn { from { transform: translateY(20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
    </style>
</head>
<body>
    <!-- Sidebar -->
    <aside class="sidebar">
        <div class="sidebar-brand">
            <div class="brand-logo">
                <span>🔧</span>
                <span>示例零售全能助手</span>
            </div>
            <div class="brand-sub">网页管理中枢 · 现役 v3</div>
        </div>
        <nav class="sidebar-menu">
            <a class="nav-item active" data-tab="tab-overview" href="#overview" onclick="navTo('overview'); return false;">
                <div class="nav-item-left"><span>📊</span><span>经营大盘</span></div>
            </a>
            <a class="nav-item" data-tab="tab-products" href="#products" onclick="navTo('products'); return false;">
                <div class="nav-item-left"><span>🏷️</span><span>商品物料</span></div>
                <span class="nav-badge" id="nav-prod-count">12.6k</span>
            </a>
            <a class="nav-item" data-tab="tab-bills" href="#bills" onclick="navTo('bills'); return false;">
                <div class="nav-item-left"><span>🧾</span><span>账单流水</span></div>
                <span class="nav-badge nav-badge-warn" id="nav-pending-count" style="display:none;">0</span>
            </a>
            <a class="nav-item" data-tab="tab-inventory" href="#inventory" onclick="navTo('inventory'); return false;">
                <div class="nav-item-left"><span>📦</span><span>库存货架</span></div>
            </a>
            <a class="nav-item" data-tab="tab-customers" href="#customers" onclick="navTo('customers'); return false;">
                <div class="nav-item-left"><span>👥</span><span>往来对账</span></div>
            </a>
            <a class="nav-item" data-tab="tab-governance" href="#governance" onclick="navTo('governance'); return false;">
                <div class="nav-item-left"><span>📋</span><span>数据治理</span></div>
                <span class="nav-badge" id="nav-marks-count">0</span>
            </a>
            <a class="nav-item" data-tab="tab-ai" href="#ai" onclick="navTo('ai'); return false;">
                <div class="nav-item-left"><span>🤖</span><span>AI 智囊</span></div>
            </a>
            <a class="nav-item" data-tab="tab-system" href="#system" onclick="navTo('system'); return false;">
                <div class="nav-item-left"><span>⚙️</span><span>系统运维</span></div>
            </a>
        </nav>
        <div class="sidebar-footer">
            <div class="status-indicator">
                <span class="status-dot"></span>
                <span>服务正常运行</span>
            </div>
            <span>v3.0.0</span>
        </div>
    </aside>

    <!-- Main Wrapper -->
    <div class="main-wrapper">
        <header class="topbar">
            <div class="topbar-left">
                <h1 class="page-title" id="page-title">📊 综合经营大盘</h1>
            </div>
            <div class="topbar-right">
                <span style="font-size:12px; color:var(--text-muted);" id="live-clock">2026-09-07 00:00:00</span>
                <select class="select-box" style="padding:4px 8px; font-size:12px;" id="auto-refresh-select" onchange="changeAutoRefresh(this.value)">
                    <option value="0">自动刷新: 关</option>
                    <option value="30">自动刷新: 30秒</option>
                    <option value="60">自动刷新: 60秒</option>
                </select>
                <a href="/api/admin/export-marks" class="btn btn-secondary btn-sm" onclick="event.preventDefault(); sxAuthDownload('/api/admin/export-marks','data_marks.csv')">📥 导出管家婆清单</a>
                <a href="#system" class="btn btn-success btn-sm">📲 APP 内部推送已启用</a>
                <button class="btn btn-secondary btn-sm" onclick="refreshCurrentView()">🔄 刷新</button>
            </div>
        </header>

        <div class="content-container">
            <!-- 1. Overview Tab -->
            <div id="tab-overview" class="tab-view active">
                <div class="stats-grid">
                    <div class="stat-card stat-success">
                        <div class="stat-label">
                            <span>今日出库销售额 (管家婆实收)</span>
                            <span class="badge badge-success">已出库</span>
                        </div>
                        <div class="stat-val" style="color:var(--success);" id="kpi-rev">¥0.00</div>
                        <div class="stat-foot">完成订单: <strong id="kpi-orders">0</strong> 笔 · 客单价: <strong id="kpi-avg">¥0.00</strong></div>
                    </div>
                    <div class="stat-card stat-warning">
                        <div class="stat-label">
                            <span>当前挂账待录</span>
                            <span class="badge badge-warning" id="kpi-pending-badge">待扫码入库</span>
                        </div>
                        <div class="stat-val" style="color:var(--warning);" id="kpi-pending">0</div>
                        <div class="stat-foot">挂账待收沉淀额: <strong id="kpi-pending-amt">¥0.00</strong></div>
                    </div>
                    <div class="stat-card stat-primary">
                        <div class="stat-label">
                            <span>综合随单毛利率</span>
                            <span class="badge badge-primary">透明成本</span>
                        </div>
                        <div class="stat-val" style="color:var(--primary);" id="kpi-margin">0.0%</div>
                        <div class="stat-foot">今日毛利额: <strong id="kpi-profit">¥0.00</strong></div>
                    </div>
                    <div class="stat-card stat-danger">
                        <div class="stat-label">
                            <span>数据治理待办</span>
                            <span class="badge badge-danger" id="kpi-marks-badge">待处理</span>
                        </div>
                        <div class="stat-val" style="color:var(--danger);" id="kpi-marks">0</div>
                        <div class="stat-foot">未定价商品: <strong id="kpi-unpriced">0</strong> 款</div>
                    </div>
                </div>

                <div class="charts-row">
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">📈 营业额与随单毛利趋势 (近 30 天)</div>
                            <span style="font-size:11.5px; color:var(--text-muted);">出库销售实收 · 纯原生矢量图表</span>
                        </div>
                        <div class="panel-body">
                            <div id="chart-trend" style="width:100%; height:260px; position:relative;"></div>
                        </div>
                    </div>
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">⏰ 今日时段营业热度</div>
                            <span style="font-size:11.5px; color:var(--text-muted);">8:00 - 20:00 出库分布</span>
                        </div>
                        <div class="panel-body">
                            <div id="chart-hourly" style="width:100%; height:260px; position:relative;"></div>
                        </div>
                    </div>
                </div>

                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:20px; margin-bottom:24px;">
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">👥 今日店员开单排行榜</div>
                            <span style="font-size:11.5px; color:var(--text-muted);">单单留痕·实时统计</span>
                        </div>
                        <div class="table-responsive">
                            <table id="tbl-clerks">
                                <thead>
                                    <tr>
                                        <th>店员姓名</th>
                                        <th class="text-right">出库实收</th>
                                        <th class="text-right">客单均价</th>
                                        <th class="text-right">出库笔数</th>
                                        <th class="text-right">挂账待录</th>
                                    </tr>
                                </thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">🔥 热销商品排行 TOP 10</div>
                            <span style="font-size:11.5px; color:var(--text-muted);">近期累计出库销量</span>
                        </div>
                        <div class="table-responsive">
                            <table id="tbl-hot">
                                <thead>
                                    <tr>
                                        <th style="width:40px;" class="text-center">#</th>
                                        <th>商品名称</th>
                                        <th class="text-right">销量</th>
                                        <th class="text-right">销售额</th>
                                    </tr>
                                </thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 2. Products Tab -->
            <div id="tab-products" class="tab-view">
                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">🏷️ 全库商品全息检索中心 (12,631+ 款)</div>
                        <span style="font-size:12px; color:var(--text-muted);" id="prod-total-hint">共 0 款商品</span>
                    </div>
                    <div class="panel-body" style="border-bottom:1px solid var(--border-color); padding-bottom:16px;">
                        <div class="filter-bar">
                            <input type="text" class="input-text" id="prod-search-input" placeholder="输入名称 / 拼音缩写 / 规格 / 品牌 / 条码 / SKU..." style="width:340px;" oninput="onProductSearchInput(this.value)">
                            <div class="filter-chips">
                                <span class="chip active" onclick="setProductStockFilter('all', this)">全部库存</span>
                                <span class="chip" onclick="setProductStockFilter('in_stock', this)">仅现货</span>
                                <span class="chip" onclick="setProductStockFilter('out_stock', this)">缺货/0库存</span>
                                <span class="chip" onclick="setProductPriceFilter('unpriced', this)">未定价(¥0)</span>
                            </div>
                            <select class="select-box" id="prod-sort-select" onchange="onProductSortChange(this.value)">
                                <option value="id_desc">排序: 默认最新</option>
                                <option value="stock_qty_desc">库存: 从多到少</option>
                                <option value="stock_qty_asc">库存: 从少到多</option>
                                <option value="sell_price_desc">售价: 从高到低</option>
                                <option value="sell_price_asc">售价: 从低到高</option>
                            </select>
                        </div>
                    </div>
                    <div class="table-responsive">
                        <table id="tbl-products">
                            <thead>
                                <tr>
                                    <th>编码(SKU)</th>
                                    <th>商品档案</th>
                                    <th>分类</th>
                                    <th class="text-right">进价底价</th>
                                    <th class="text-right">开单售价</th>
                                    <th class="text-right">毛利率</th>
                                    <th class="text-right">库存现货</th>
                                    <th>货架货位</th>
                                    <th class="text-center">快捷操作</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-products">
                                <tr><td colspan="9" class="text-center" style="padding:40px; color:var(--text-muted);">正在加载商品档案...</td></tr>
                            </tbody>
                        </table>
                    </div>
                    <div class="panel-body pagination-wrap">
                        <div id="prod-pagination-info">第 1 页 / 共 1 页</div>
                        <div style="display:flex; gap:8px;">
                            <button class="btn btn-secondary btn-sm" id="btn-prod-prev" onclick="changeProductPage(-1)">上一页</button>
                            <button class="btn btn-secondary btn-sm" id="btn-prod-next" onclick="changeProductPage(1)">下一页</button>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 3. Bills Tab -->
            <div id="tab-bills" class="tab-view">
                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">🧾 账单开单与出库流水</div>
                        <div id="bills-summary-chips" style="display:flex; gap:10px;"></div>
                    </div>
                    <div class="panel-body" style="border-bottom:1px solid var(--border-color); padding-bottom:16px;">
                        <div class="filter-bar">
                            <div class="filter-chips">
                                <span class="chip active" onclick="setBillStatusFilter('all', this)">全部账单</span>
                                <span class="chip" onclick="setBillStatusFilter('pending', this)">挂账待录</span>
                                <span class="chip" onclick="setBillStatusFilter('paid', this)">已出库(已结)</span>
                            </div>
                            <input type="text" class="input-text" id="bill-search-staff" placeholder="店员姓名..." style="width:140px;" oninput="onBillSearchChange()">
                            <input type="text" class="input-text" id="bill-search-cust" placeholder="客户名称..." style="width:160px;" oninput="onBillSearchChange()">
                            <input type="date" class="input-text" id="bill-date-start" onchange="onBillSearchChange()">
                            <span style="color:var(--text-muted);">-</span>
                            <input type="date" class="input-text" id="bill-date-end" onchange="onBillSearchChange()">
                        </div>
                    </div>
                    <div class="table-responsive">
                        <table>
                            <thead>
                                <tr>
                                    <th>账单编号</th>
                                    <th>开单时间</th>
                                    <th>客户</th>
                                    <th>店员</th>
                                    <th class="text-right">品项数</th>
                                    <th class="text-right">账单金额</th>
                                    <th class="text-right">随单毛利</th>
                                    <th>收款方式</th>
                                    <th>状态 / 滞留</th>
                                    <th class="text-center">操作</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-bills">
                                <tr><td colspan="10" class="text-center" style="padding:40px; color:var(--text-muted);">正在加载账单流水...</td></tr>
                            </tbody>
                        </table>
                    </div>
                    <div class="panel-body pagination-wrap">
                        <div id="bill-pagination-info">第 1 页 / 共 1 页</div>
                        <div style="display:flex; gap:8px;">
                            <button class="btn btn-secondary btn-sm" id="btn-bill-prev" onclick="changeBillPage(-1)">上一页</button>
                            <button class="btn btn-secondary btn-sm" id="btn-bill-next" onclick="changeBillPage(1)">下一页</button>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 4. Inventory Tab -->
            <div id="tab-inventory" class="tab-view">
                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:20px; margin-bottom:24px;">
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">🚨 急需补货与缺货清单 (TOP 30)</div>
                            <button class="btn btn-secondary btn-sm" onclick="exportUrgentList()">📥 导出采购单</button>
                        </div>
                        <div class="table-responsive" style="max-height:420px;">
                            <table>
                                <thead>
                                    <tr>
                                        <th>商品名称</th>
                                        <th>规格</th>
                                        <th class="text-right">现存库存</th>
                                        <th class="text-right">参考进价</th>
                                        <th>货位</th>
                                    </tr>
                                </thead>
                                <tbody id="tbody-urgent"></tbody>
                            </table>
                        </div>
                    </div>
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">💰 库底积压与高资金占用预警 (TOP 20)</div>
                            <span style="font-size:11.5px; color:var(--text-muted);">占用资金 = 库存 × 成本进价</span>
                        </div>
                        <div class="table-responsive" style="max-height:420px;">
                            <table>
                                <thead>
                                    <tr>
                                        <th>商品名称</th>
                                        <th class="text-right">账面库存</th>
                                        <th class="text-right">进价底价</th>
                                        <th class="text-right">沉淀资金占用</th>
                                    </tr>
                                </thead>
                                <tbody id="tbody-stagnant"></tbody>
                            </table>
                        </div>
                    </div>
                </div>

                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">🏗️ 五级货架货位可视化树</div>
                        <span style="font-size:12px; color:var(--text-muted);">区域 (Zone) $\to$ 货架 (Rack) $\to$ 货位 (Cell)</span>
                    </div>
                    <div class="panel-body" id="shelf-tree-container">
                        <div style="color:var(--text-muted);">正在加载货架拓扑...</div>
                    </div>
                </div>
            </div>

            <!-- 5. Customers Tab -->
            <div id="tab-customers" class="tab-view">
                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">👥 往来客户账本与欠款对账</div>
                        <span style="font-size:12px; color:var(--text-muted);">支持一键生成客户微信对账单</span>
                    </div>
                    <div class="table-responsive">
                        <table>
                            <thead>
                                <tr>
                                    <th>客户名称</th>
                                    <th class="text-right">累计交易笔数</th>
                                    <th class="text-right">累计消费总额</th>
                                    <th class="text-right">当前挂账欠款</th>
                                    <th>最近交易时间</th>
                                    <th class="text-center">操作</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-customers"></tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- 6. Governance Tab -->
            <div id="tab-governance" class="tab-view">
                <div class="panel">
                    <div class="panel-head">
                    <div class="panel-title">📋 数据治理与管家婆调整 (现场巡架打标)</div>
                    <div>
                        <a href="/api/admin/export-marks" class="btn btn-primary btn-sm" onclick="event.preventDefault(); sxAuthDownload('/api/admin/export-marks','data_marks.csv')">📥 导出 Excel CSV</a>
                        <a href="/admin/gov" class="btn btn-sm" style="background:#fff;border:1px solid var(--border-color);margin-left:6px;">🧭 治理扩展页（同义词/查询日志/串货/待补全）</a>
                    </div>
                </div>
                    <div class="panel-body" style="font-size:12px; background:#F8FAFC; border-bottom:1px solid var(--border-color);">
                        💡 <strong>数据治理闭环</strong>：店员在手机端巡架发现价格有误、无售价、无条码打标后直通此队列。管理员核准后可直接点击「已核准」完成闭环。
                    </div>
                    <div class="table-responsive">
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:60px;">序号</th>
                                    <th>商品档案</th>
                                    <th>问题类型</th>
                                    <th>店员备注说明</th>
                                    <th>登记时间</th>
                                    <th>当前状态</th>
                                    <th class="text-center">闭环操作</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-marks"></tbody>
                        </table>
                    </div>
                </div>

                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">🔍 问了没货与搜空热词 (客户需求漏查)</div>
                        <span style="font-size:12px; color:var(--text-muted);">反映顾客常问但店里未录入或零结果的搜索词</span>
                    </div>
                    <div class="table-responsive">
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:40px;" class="text-center">#</th>
                                    <th>搜索关键词</th>
                                    <th class="text-right">未命中次数</th>
                                    <th class="text-right">最近查询时间</th>
                                    <th class="text-center">建档指引</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-unhit"></tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- 7. AI Tab -->
            <div id="tab-ai" class="tab-view">
                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:20px;">
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">🤖 智能店长助手问答台</div>
                            <span class="badge badge-success">微服务 :18603 在线</span>
                        </div>
                        <div class="panel-body" style="display:flex; flex-direction:column; height:500px;">
                            <div id="ai-chat-history" style="flex:1; overflow-y:auto; padding-bottom:12px; display:flex; flex-direction:column; gap:12px;">
                                <div style="background:#F1F5F9; padding:12px 16px; border-radius:8px; font-size:13px; max-width:85%;">
                                    您好！我是示例零售智能店长。您可以向我询问五金管件规格、水电施工要求、替代配件建议等专业知识。
                                </div>
                            </div>
                            <div style="display:flex; gap:8px; margin-bottom:8px;">
                                <button class="btn btn-secondary btn-sm" onclick="sendQuickAi('PPR水管热熔注意事项')">PPR水管热熔</button>
                                <button class="btn btn-secondary btn-sm" onclick="sendQuickAi('角阀漏水如何排查')">角阀漏水排查</button>
                                <button class="btn btn-secondary btn-sm" onclick="sendQuickAi('日丰管和联塑管能混接吗')">日丰与联塑混接</button>
                            </div>
                            <div style="display:flex; gap:8px;">
                                <input type="text" class="input-text" id="ai-input" placeholder="输入您的施工或五金配件问题..." style="flex:1;" onkeydown="if(event.key==='Enter') sendAiMessage()">
                                <button class="btn btn-primary" onclick="sendAiMessage()">发送咨询</button>
                            </div>
                        </div>
                    </div>
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">📚 施工常识与五金知识库 (16,117 条)</div>
                            <span class="badge badge-primary">微服务 :18602</span>
                        </div>
                        <div class="panel-body" style="padding-bottom:10px;">
                            <input type="text" class="input-text" id="kb-search-input" placeholder="输入关键词检索施工专家知识库..." style="width:100%;" oninput="searchKnowledge(this.value)">
                        </div>
                        <div class="table-responsive" style="max-height:430px; overflow-y:auto;">
                            <table id="tbl-kb">
                                <thead>
                                    <tr>
                                        <th>分类</th>
                                        <th>施工/常识问题</th>
                                        <th>专家解决方案与标准</th>
                                    </tr>
                                </thead>
                                <tbody id="tbody-kb">
                                    <tr><td colspan="3" class="text-center" style="padding:40px; color:var(--text-muted);">请输入关键词查询知识库</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>

            <!-- 8. System Tab -->
            <div id="tab-system" class="tab-view">
                <div class="panel">
                    <div class="panel-head">
                        <div class="panel-title">🖥️ 6 大微服务运行矩阵与实时探测</div>
                        <button class="btn btn-secondary btn-sm" onclick="loadServicesStatus()">🔄 实时探测</button>
                    </div>
                    <div class="table-responsive">
                        <table id="tbl-services">
                            <thead>
                                <tr>
                                    <th>服务名称</th>
                                    <th>监听端口</th>
                                    <th>网络健康状态</th>
                                    <th class="text-right">响应延迟</th>
                                    <th>职责说明</th>
                                </tr>
                            </thead>
                            <tbody id="tbody-services"></tbody>
                        </table>
                    </div>
                </div>

                <div style="display:grid; grid-template-columns: 1fr 1fr; gap:20px;">
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">🔄 管家婆熊掌柜同步看板</div>
                        </div>
                        <div class="panel-body">
                            <p style="margin-bottom:8px;">上游快照覆盖记录: <strong id="sys-snap-count">0</strong> 条</p>
                            <p style="margin-bottom:8px;">全库商品总数: <strong id="sys-total-prod">0</strong> 款</p>
                            <p style="margin-bottom:8px;">最近全维度同步时间: <strong id="sys-sync-time">未知</strong></p>
                            <p style="margin-bottom:8px;">未定价(¥0)商品数: <strong id="sys-unpriced-count">0</strong> 款</p>
                            <div style="margin-top:16px; padding:12px; background:#F1F5F9; border-radius:8px; font-size:12px; color:var(--text-muted);">
                                📌 <strong>标准维护命令</strong>：同步前先 <code>scripts/start-backend.ps1 stop</code> 释放写锁，随后运行 <code>powershell -File scripts/sync-xiongzhanggui.ps1 --apply --full-dim</code>
                            </div>
                        </div>
                    </div>
                    <div class="panel">
                        <div class="panel-head">
                            <div class="panel-title">📲 移动端 APP 内部版本推送中心</div>
                        </div>
                        <div class="panel-body" style="display:flex; gap:20px; align-items:center;">
                            <div id="apk-qr-container" style="width:140px; height:140px; border:1px solid var(--border-color); border-radius:8px; display:flex; justify-content:center; align-items:center; background:#F8FAFC;">
                                <canvas id="apk-qr-canvas" width="128" height="128"></canvas>
                            </div>
                            <div style="flex:1;">
                                <div style="display:flex; align-items:center; gap:8px; margin-bottom:6px;">
                                    <h3 style="font-size:15px; margin:0;" id="app-push-version-title">示例零售全能助手 (内部推送已就绪)</h3>
                                    <span class="badge badge-success">自动检测与推送</span>
                                </div>
                                <p style="font-size:12px; color:var(--text-muted); margin-bottom:10px;">
                                    <strong>内部推送机制</strong>：店员手机打开 APP 自动向服务端查询版本，检测到新版本时在 APP 内部直接弹出更新对话框并显示特性日志，点击「立即更新」即可在应用内极速下载安装，彻底告别浏览器手动下载。
                                </p>
                                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                                    <button class="btn btn-success btn-sm" onclick="triggerAppPush()">📢 触发全员内部推送</button>
                                    <a href="/retaildemo-v2.apk" class="btn btn-secondary btn-sm" download>⬇️ 备用手动下载安装包</a>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- Modals & Drawers -->
    <!-- Universal Barcode / QR Modal (Single Item & Bill Carousel) -->
    <div class="modal-mask" id="modal-qr" onclick="closeQrModal()">
        <div class="modal-box" style="max-width:540px;" onclick="event.stopPropagation()">
            <div class="panel-head" style="background:#0F172A; color:#FFFFFF;">
                <div class="panel-title" id="modal-qr-header-title">📱 调出管家婆扫码窗口</div>
                <button class="btn btn-secondary btn-sm" style="background:rgba(255,255,255,0.2); color:#FFF; border:none;" onclick="closeQrModal()">✕</button>
            </div>

            <!-- 连续扫码轮播进度栏 (仅在对账连扫时展示) -->
            <div id="modal-qr-carousel-bar" style="display:none; background:#F8FAFC; border-bottom:1px solid var(--border-color); padding:10px 20px; font-size:12.5px; justify-content:space-between; align-items:center;">
                <div style="font-weight:700; color:var(--primary);" id="modal-qr-carousel-step">第 1 / 5 件商品</div>
                <div style="color:var(--text-muted); font-size:11.5px;">按 <strong>空格</strong> 或 <strong>→</strong> 切换下一件</div>
            </div>

            <div class="panel-body" id="modal-qr-body-container" style="text-align:center; padding:22px 20px; transition: opacity 0.15s ease, transform 0.15s ease;">
                <div id="modal-qr-itemname" style="font-size:17px; font-weight:800; margin-bottom:4px; color:var(--text-main);">商品名称</div>
                <div id="modal-qr-spec" style="font-size:13px; color:var(--text-muted); margin-bottom:14px;">规格型号 · 售价</div>

                <!-- 码型切换 Tabs (二维码 / 一维条形码) -->
                <div style="display:flex; justify-content:center; gap:8px; margin-bottom:14px;">
                    <button type="button" class="chip active" id="btn-show-qr" onclick="switchBarcodeType('qr')">二维大码 (QR Code)</button>
                    <button type="button" class="chip" id="btn-show-barcode" onclick="switchBarcodeType('barcode')">一维条码 (Code 128)</button>
                </div>

                <!-- QR Canvas 容器 -->
                <div id="wrap-qr-canvas" style="background:#FFFFFF; padding:16px; border:2px solid var(--border-color); border-radius:12px; display:inline-block; margin-bottom:10px; box-shadow:0 4px 12px rgba(0,0,0,0.05);">
                    <canvas id="modal-qr-canvas" width="240" height="240"></canvas>
                </div>

                <!-- Barcode Canvas 容器 -->
                <div id="wrap-barcode-canvas" style="display:none; background:#FFFFFF; padding:16px; border:2px solid var(--border-color); border-radius:12px; margin-bottom:10px; box-shadow:0 4px 12px rgba(0,0,0,0.05);">
                    <canvas id="modal-barcode-canvas" width="380" height="70" style="max-width:100%;"></canvas>
                </div>

                <div id="modal-barcode-text" class="font-mono" style="font-size:20px; font-weight:800; letter-spacing:1.5px; margin-bottom:6px; color:var(--primary);">0000000000000</div>
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:16px;">
                    🎯 请拿起<strong>管家婆扫描枪</strong>直接对准屏幕扫入收银端
                </div>

                <!-- 轮播模式前后切换控制栏 -->
                <div id="modal-qr-carousel-controls" style="display:none; justify-content:space-between; align-items:center; margin-bottom:14px; gap:8px;">
                    <button type="button" class="btn btn-secondary" id="btn-carousel-prev" onclick="prevCarouselItem()">⬅ 上一件 (←)</button>
                    <button type="button" class="btn btn-primary" id="btn-carousel-next" onclick="nextCarouselItem()">下一件 (→ / 空格) ➡</button>
                </div>

                <div style="display:flex; justify-content:center; gap:10px;">
                    <button type="button" class="btn btn-secondary btn-sm" onclick="copyBarcodeText()">📋 复制条码/SKU</button>
                    <button type="button" class="btn btn-secondary btn-sm" onclick="closeQrModal()">关闭窗口</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Customer Statement Modal -->
    <div class="modal-mask" id="modal-statement" onclick="closeStatementModal()">
        <div class="modal-box" style="max-width:640px;" onclick="event.stopPropagation()">
            <div class="panel-head">
                <div class="panel-title">🧾 往来客户对账单</div>
                <button class="btn btn-secondary btn-sm" onclick="closeStatementModal()">✕</button>
            </div>
            <div class="panel-body">
                <textarea id="statement-text" class="font-mono" style="width:100%; height:280px; padding:12px; border:1px solid var(--border-color); border-radius:8px; font-size:12px; resize:none; line-height:1.6;" readonly></textarea>
                <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:16px;">
                    <button class="btn btn-primary" onclick="copyStatementText()">📋 一键复制微信对账单</button>
                    <button class="btn btn-secondary" onclick="closeStatementModal()">关闭</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Mark Resolution Modal -->
    <div class="modal-mask" id="modal-resolve-mark" onclick="closeResolveModal()">
        <div class="modal-box" onclick="event.stopPropagation()">
            <div class="panel-head">
                <div class="panel-title">✅ 核准并闭环数据标记</div>
                <button class="btn btn-secondary btn-sm" onclick="closeResolveModal()">✕</button>
            </div>
            <div class="panel-body">
                <p style="font-size:13px; margin-bottom:12px;" id="resolve-target-desc">正在处理标记...</p>
                <input type="text" class="input-text" id="resolve-note-input" placeholder="输入核准结果说明（例如：已在管家婆修改售价为5元）" style="width:100%; margin-bottom:16px;">
                <div style="display:flex; justify-content:flex-end; gap:10px;">
                    <button class="btn btn-secondary" onclick="closeResolveModal()">取消</button>
                    <button class="btn btn-success" onclick="submitResolveMark()">确认核准闭环</button>
                </div>
            </div>
        </div>
    </div>

    <!-- Slide-over Drawer for Details -->
    <div class="drawer-mask" id="drawer-mask" onclick="closeDrawer()">
        <div class="drawer-box" onclick="event.stopPropagation()">
            <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border-color); padding-bottom:16px; margin-bottom:20px;">
                <h2 style="font-size:17px; font-weight:800;" id="drawer-title">明细详情</h2>
                <button class="btn btn-secondary btn-sm" onclick="closeDrawer()">✕ 关闭</button>
            </div>
            <div id="drawer-content" style="flex:1;">
                正在加载详情...
            </div>
        </div>
    </div>

    <!-- Toast Notifications Container -->
    <div class="toast-box" id="toast-container"></div>

    <script>
        // ---- Gateway auth (2026-09-09 audit) ----
        // 私服模式（服务端未设 GATEWAY_AUTH_TOKEN）完全不受影响；严格模式下所有
        // 请求自动携带 X-Auth-Token（localStorage 记忆），遇 401 提示输入一次并重试。
        (function(){
            var TOK_KEY = 'sx_gateway_token';
            var origFetch = window.fetch.bind(window);
            function withTok(init) {
                init = init || {};
                var h = new Headers(init.headers || {});
                var t = localStorage.getItem(TOK_KEY);
                if (t) h.set('X-Auth-Token', t);
                init.headers = h;
                return init;
            }
            window.fetch = function(url, init) {
                return origFetch(url, withTok(init)).then(function(resp) {
                    if (resp.status === 401 && !String(url).startsWith('/api/auth/login')) {
                        var t = prompt('网关已开启鉴权，请输入 GATEWAY_AUTH_TOKEN：');
                        if (t) {
                            localStorage.setItem(TOK_KEY, t);
                            return origFetch(url, withTok(init));
                        }
                    }
                    return resp;
                });
            };
            // 严格模式下 <a href> 直链下载无法带头，统一走 fetch-blob。
            window.sxAuthDownload = function(url, filename) {
                fetch(url).then(function(r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.blob();
                }).then(function(b) {
                    var a = document.createElement('a');
                    a.href = URL.createObjectURL(b);
                    a.download = filename || url.split('/').pop();
                    document.body.appendChild(a); a.click(); a.remove();
                }).catch(function(e) { alert('下载失败: ' + e.message); });
            };
        })();

        /*__QRCODE_LIB__*/

        // Embed initial server data
        var g_data = __INITIAL_DATA__;
        var g_currentTab = 'overview';
        var g_autoRefreshSec = 0;
        var g_autoRefreshTimer = null;

        // Barcode / QR State
        var g_carouselItems = [];
        var g_carouselIdx = 0;
        var g_currentBarcodeType = 'qr';
        var g_activeCodeText = '';

        // Data caches
        var g_productsItems = [];
        var g_billsItems = [];
        var g_customersItems = [];
        var g_activeProductDetail = null;
        var g_activeBillDetail = null;

        // Products state
        var g_prodKeyword = '';
        var g_prodStockStatus = 'all';
        var g_prodPriceStatus = 'all';
        var g_prodSortBy = 'id';
        var g_prodSortOrder = 'desc';
        var g_prodPage = 1;
        var g_prodDebounce = null;

        // Bills state
        var g_billStatus = 'all';
        var g_billPage = 1;
        var g_billDebounce = null;

        var g_activeResolveMarkId = null;

        // --- Core Router ---
        function navTo(tabName) {
            if (window.location.hash !== '#' + tabName) {
                window.location.hash = tabName;
            }
            switchTab(tabName);
        }

        function handleHash() {
            var hash = window.location.hash.replace('#', '') || 'overview';
            switchTab(hash);
        }

        function switchTab(tabId) {
            g_currentTab = tabId;
            document.querySelectorAll('.tab-view').forEach(function(el) { el.classList.remove('active'); });
            document.querySelectorAll('.sidebar-menu .nav-item').forEach(function(el) { el.classList.remove('active'); });

            var view = document.getElementById('tab-' + tabId);
            if (view) view.classList.add('active');

            var navBtn = document.querySelector('[data-tab="tab-' + tabId + '"]');
            if (navBtn) navBtn.classList.add('active');

            var titles = {
                'overview': '📊 综合经营大盘',
                'products': '🏷️ 全库商品全息检索中心',
                'bills': '🧾 账单流水与对账中心',
                'inventory': '📦 库存货架与资产预警',
                'customers': '👥 往来客户与欠款对账',
                'governance': '📋 现场巡架与数据治理闭环',
                'ai': '🤖 智能店长与五金施工知识库',
                'system': '⚙️ 微服务矩阵与系统运维'
            };
            document.getElementById('page-title').innerText = titles[tabId] || '示例零售管理中枢';

            // Lazy load tab contents
            if (tabId === 'products') loadProductsData();
            if (tabId === 'bills') loadBillsData();
            if (tabId === 'inventory') loadInventoryData();
            if (tabId === 'customers') loadCustomersData();
            if (tabId === 'system') loadServicesStatus();
            if (tabId === 'overview') renderOverviewCharts();
        }

        window.addEventListener('hashchange', handleHash);

        // --- Live Clock ---
        function updateClock() {
            var now = new Date();
            var y = now.getFullYear();
            var m = String(now.getMonth() + 1).padStart(2, '0');
            var d = String(now.getDate()).padStart(2, '0');
            var hh = String(now.getHours()).padStart(2, '0');
            var mm = String(now.getMinutes()).padStart(2, '0');
            var ss = String(now.getSeconds()).padStart(2, '0');
            document.getElementById('live-clock').innerText = y + '-' + m + '-' + d + ' ' + hh + ':' + mm + ':' + ss;
        }
        setInterval(updateClock, 1000);
        updateClock();

        // --- Auto Refresh ---
        function changeAutoRefresh(val) {
            g_autoRefreshSec = parseInt(val) || 0;
            if (g_autoRefreshTimer) clearInterval(g_autoRefreshTimer);
            if (g_autoRefreshSec > 0) {
                g_autoRefreshTimer = setInterval(function() {
                    refreshCurrentView(true);
                }, g_autoRefreshSec * 1000);
                showToast('已开启自动刷新 (' + g_autoRefreshSec + '秒)');
            } else {
                showToast('已关闭自动刷新');
            }
        }

        function refreshCurrentView(silent) {
            fetch('/api/admin/overview')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        g_data = res.data;
                        renderOverviewData();
                        if (g_currentTab === 'products') loadProductsData();
                        if (g_currentTab === 'bills') loadBillsData();
                        if (g_currentTab === 'inventory') loadInventoryData();
                        if (g_currentTab === 'customers') loadCustomersData();
                        if (g_currentTab === 'governance') renderGovernanceData();
                        if (!silent) showToast('数据已更新');
                    }
                })
                .catch(function(err) {
                    if (!silent) showToast('刷新失败: ' + err.message, 'error');
                });
        }

        // --- Toast System ---
        function showToast(msg, type) {
            var container = document.getElementById('toast-container');
            var el = document.createElement('div');
            el.className = 'toast ' + (type === 'error' ? 'toast-error' : (type === 'success' ? 'toast-success' : ''));
            el.innerText = msg;
            container.appendChild(el);
            setTimeout(function() { el.remove(); }, 3000);
        }

        // --- Overview Rendering ---
        function renderOverviewData() {
            var d = g_data;
            document.getElementById('kpi-rev').innerText = '¥' + d.today_revenue.toLocaleString('zh-CN', {minimumFractionDigits: 2});
            document.getElementById('kpi-orders').innerText = d.today_orders;
            document.getElementById('kpi-avg').innerText = '¥' + (d.avg_ticket || 0).toFixed(2);
            document.getElementById('kpi-profit').innerText = '¥' + d.today_profit.toLocaleString('zh-CN', {minimumFractionDigits: 2});
            document.getElementById('kpi-margin').innerText = (d.margin_pct || 0).toFixed(1) + '%';
            document.getElementById('kpi-pending').innerText = d.pending_count;
            document.getElementById('kpi-pending-amt').innerText = '¥' + (d.pending_total_amount || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2});
            document.getElementById('kpi-marks').innerText = d.marks_open;
            document.getElementById('kpi-unpriced').innerText = d.unpriced_count;

            document.getElementById('nav-prod-count').innerText = Math.round(d.total_products / 1000) + 'k';
            var pBadge = document.getElementById('nav-pending-count');
            if (d.pending_count > 0) {
                pBadge.style.display = 'inline-block';
                pBadge.innerText = d.pending_count;
            } else {
                pBadge.style.display = 'none';
            }
            document.getElementById('nav-marks-count').innerText = d.marks_open;

            // Clerks table
            var clerksHtml = '';
            var clerkList = Object.values(d.clerks || {}).sort(function(a, b) { return b.paid_revenue - a.paid_revenue; });
            clerkList.forEach(function(c) {
                clerksHtml += '<tr>' +
                    '<td class="font-bold">' + escapeHtml(c.name) + '</td>' +
                    '<td class="text-right font-bold" style="color:var(--success);">¥' + c.paid_revenue.toLocaleString('zh-CN', {minimumFractionDigits: 2}) + '</td>' +
                    '<td class="text-right">¥' + (c.avg_ticket || 0).toFixed(2) + '</td>' +
                    '<td class="text-right">' + c.paid_orders + ' 单</td>' +
                    '<td class="text-right">' + (c.pending_orders > 0 ? '<span class="badge badge-warning">' + c.pending_orders + ' 笔</span>' : '0 笔') + '</td>' +
                    '</tr>';
            });
            document.querySelector('#tbl-clerks tbody').innerHTML = clerksHtml || '<tr><td colspan="5" class="text-center" style="padding:24px; color:var(--text-muted);">今日暂无店员开单</td></tr>';

            // Hot items table
            var hotHtml = '';
            (d.hot_items || []).forEach(function(item, idx) {
                hotHtml += '<tr>' +
                    '<td class="text-center font-mono font-bold" style="color:var(--text-muted);">' + (idx + 1) + '</td>' +
                    '<td><div class="font-bold">' + escapeHtml(item.name) + '</div><div style="font-size:11px; color:var(--text-muted);">' + escapeHtml(item.sku || '-') + '</div></td>' +
                    '<td class="text-right font-bold">' + item.qty + '</td>' +
                    '<td class="text-right font-bold" style="color:var(--primary);">¥' + (item.amount || 0).toFixed(2) + '</td>' +
                    '</tr>';
            });
            document.querySelector('#tbl-hot tbody').innerHTML = hotHtml || '<tr><td colspan="4" class="text-center" style="padding:24px; color:var(--text-muted);">暂无出库销量</td></tr>';

            renderOverviewCharts();
            renderGovernanceData();
        }

        // --- Native SVG Chart Engine ---
        function renderOverviewCharts() {
            var trend = g_data.trend_30d || [];
            if (trend.length > 0) {
                var dates = trend.map(function(t) { return t.date.slice(5); });
                var revs = trend.map(function(t) { return t.revenue; });
                var profs = trend.map(function(t) { return t.profit; });
                drawAreaChart(document.getElementById('chart-trend'), dates, revs, profs);
            }
            var hourly = g_data.hourly_distribution || [];
            if (hourly.length > 0) {
                var hours = hourly.map(function(h) { return h.hour; });
                var hRevs = hourly.map(function(h) { return h.revenue; });
                var hOrders = hourly.map(function(h) { return h.orders; });
                drawBarChart(document.getElementById('chart-hourly'), hours, hRevs, hOrders);
            }
        }

        function drawAreaChart(container, labels, revs, profs) {
            if (!container) return;
            var w = container.clientWidth || 600, h = 260;
            var padL = 60, padR = 20, padT = 20, padB = 35;
            var plotW = w - padL - padR, plotH = h - padT - padB;
            var maxVal = Math.max.apply(null, revs.concat(profs, [100])) * 1.15;

            var svg = '<svg width="100%" height="100%" viewBox="0 0 ' + w + ' ' + h + '" style="overflow:visible;">';
            svg += '<defs>';
            svg += '<linearGradient id="gradRev" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#2563EB" stop-opacity="0.35"/><stop offset="100%" stop-color="#2563EB" stop-opacity="0.0"/></linearGradient>';
            svg += '<linearGradient id="gradProf" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#059669" stop-opacity="0.25"/><stop offset="100%" stop-color="#059669" stop-opacity="0.0"/></linearGradient>';
            svg += '</defs>';

            // Gridlines
            for (var i = 0; i <= 4; i++) {
                var y = padT + (plotH / 4) * i;
                var val = Math.round(maxVal * (1 - i / 4));
                svg += '<line x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '" stroke="#E2E8F0" stroke-dasharray="3,3"/>';
                svg += '<text x="' + (padL - 10) + '" y="' + (y + 4) + '" fill="#94A3B8" font-size="11" text-anchor="end">¥' + val + '</text>';
            }

            var ptsRev = [], ptsProf = [];
            var step = plotW / Math.max(1, labels.length - 1);
            for (var j = 0; j < labels.length; j++) {
                var x = padL + j * step;
                var yRev = padT + plotH - (revs[j] / maxVal) * plotH;
                var yProf = padT + plotH - (profs[j] / maxVal) * plotH;
                ptsRev.push({x: x, y: yRev, val: revs[j], label: labels[j]});
                ptsProf.push({x: x, y: yProf, val: profs[j], label: labels[j]});
            }

            function makePath(pts) {
                var d = 'M ' + pts[0].x + ' ' + pts[0].y;
                for (var k = 1; k < pts.length; k++) {
                    var mx = (pts[k-1].x + pts[k].x) / 2;
                    d += ' C ' + mx + ' ' + pts[k-1].y + ', ' + mx + ' ' + pts[k].y + ', ' + pts[k].x + ' ' + pts[k].y;
                }
                return d;
            }
            var lineRev = makePath(ptsRev);
            var areaRev = lineRev + ' L ' + ptsRev[ptsRev.length-1].x + ' ' + (padT + plotH) + ' L ' + ptsRev[0].x + ' ' + (padT + plotH) + ' Z';
            var lineProf = makePath(ptsProf);
            var areaProf = lineProf + ' L ' + ptsProf[ptsProf.length-1].x + ' ' + (padT + plotH) + ' L ' + ptsProf[0].x + ' ' + (padT + plotH) + ' Z';

            svg += '<path d="' + areaRev + '" fill="url(#gradRev)"/>';
            svg += '<path d="' + lineRev + '" fill="none" stroke="#2563EB" stroke-width="2.5"/>';
            svg += '<path d="' + areaProf + '" fill="url(#gradProf)"/>';
            svg += '<path d="' + lineProf + '" fill="none" stroke="#059669" stroke-width="2"/>';

            var xStep = Math.ceil(labels.length / 7);
            for (var m = 0; m < labels.length; m += xStep) {
                svg += '<text x="' + ptsRev[m].x + '" y="' + (h - 10) + '" fill="#94A3B8" font-size="11" text-anchor="middle">' + labels[m] + '</text>';
            }
            svg += '</svg>';
            container.innerHTML = svg;
        }

        function drawBarChart(container, labels, values, counts) {
            if (!container) return;
            var w = container.clientWidth || 300, h = 260;
            var padL = 40, padR = 10, padT = 20, padB = 35;
            var plotW = w - padL - padR, plotH = h - padT - padB;
            var maxVal = Math.max.apply(null, values.concat([50])) * 1.15;

            var svg = '<svg width="100%" height="100%" viewBox="0 0 ' + w + ' ' + h + '" style="overflow:visible;">';
            var barW = Math.max(8, (plotW / labels.length) - 6);

            for (var i = 0; i <= 3; i++) {
                var y = padT + (plotH / 3) * i;
                svg += '<line x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '" stroke="#E2E8F0" stroke-dasharray="2,2"/>';
            }

            for (var j = 0; j < labels.length; j++) {
                var x = padL + j * (plotW / labels.length) + 3;
                var barH = (values[j] / maxVal) * plotH;
                var barY = padT + plotH - barH;
                var fill = values[j] > 0 ? '#2563EB' : '#E2E8F0';
                svg += '<rect x="' + x + '" y="' + barY + '" width="' + barW + '" height="' + Math.max(2, barH) + '" rx="3" fill="' + fill + '"/>';
                if (j % 2 === 0) {
                    svg += '<text x="' + (x + barW/2) + '" y="' + (h - 10) + '" fill="#94A3B8" font-size="10" text-anchor="middle">' + labels[j].slice(0,2) + '</text>';
                }
            }
            svg += '</svg>';
            container.innerHTML = svg;
        }

        // --- Products Tab Operations ---
        function loadProductsData() {
            var url = '/api/admin/products?page=' + g_prodPage +
                '&keyword=' + encodeURIComponent(g_prodKeyword) +
                '&stock_status=' + g_prodStockStatus +
                '&price_status=' + g_prodPriceStatus +
                '&sort_by=' + g_prodSortBy +
                '&sort_order=' + g_prodSortOrder;

            fetch(url)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        renderProductsTable(res.data);
                    }
                });
        }

        function renderProductsTable(data) {
            g_productsItems = data.items || [];
            document.getElementById('prod-total-hint').innerText = '共 ' + data.total.toLocaleString() + ' 款商品';
            document.getElementById('prod-pagination-info').innerText = '第 ' + data.page + ' / ' + data.total_pages + ' 页 (共 ' + data.total.toLocaleString() + ' 款)';
            document.getElementById('btn-prod-prev').disabled = data.page <= 1;
            document.getElementById('btn-prod-next').disabled = data.page >= data.total_pages;

            var html = '';
            g_productsItems.forEach(function(item, idx) {
                var sp = item.sell_price || 0;
                var cp = item.cost_price || 0;
                var marginBadge = sp > 0 ? '<span class="badge badge-success">' + item.margin_pct + '%</span>' : '<span class="badge badge-danger">¥0 未定价</span>';
                var stockBadge = item.stock_qty > 0 ? '<span class="font-bold">' + item.stock_qty + '</span>' : '<span class="badge badge-danger">0 缺货</span>';

                html += '<tr>' +
                    '<td class="font-mono" style="font-size:12px;">' + escapeHtml(item.sku || '-') + '</td>' +
                    '<td><div class="font-bold">' + escapeHtml(item.name) + '</div><div style="font-size:11px; color:var(--text-muted);">' + escapeHtml(item.spec || '-') + ' · ' + escapeHtml(item.brand || '默认') + '</div></td>' +
                    '<td>' + escapeHtml(item.category || '-') + '</td>' +
                    '<td class="text-right font-mono">¥' + cp.toFixed(2) + '</td>' +
                    '<td class="text-right font-mono font-bold" style="color:var(--primary);">¥' + sp.toFixed(2) + '</td>' +
                    '<td class="text-right">' + marginBadge + '</td>' +
                    '<td class="text-right">' + stockBadge + '</td>' +
                    '<td>' + (item.location ? '<span class="badge badge-primary">' + escapeHtml(item.location) + '</span>' : '<span style="color:var(--text-muted);">-</span>') + '</td>' +
                    '<td class="text-center" style="white-space:nowrap;">' +
                    '<button class="btn btn-secondary btn-sm" onclick="openProductByIndex(' + idx + ')">🔍 详情</button> ' +
                    '<button class="btn btn-primary btn-sm" onclick="openQrByIndex(' + idx + ')">📱 出码</button>' +
                    '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-products').innerHTML = html || '<tr><td colspan="9" class="text-center" style="padding:40px; color:var(--text-muted);">未找到符合条件的商品</td></tr>';
        }

        function openProductByIndex(idx) {
            var item = g_productsItems[idx];
            if (item) openProductDetail(item.id);
        }

        function openQrByIndex(idx) {
            var item = g_productsItems[idx];
            if (item) openQrModal(item);
        }

        function onProductSearchInput(val) {
            g_prodKeyword = val;
            g_prodPage = 1;
            if (g_prodDebounce) clearTimeout(g_prodDebounce);
            g_prodDebounce = setTimeout(loadProductsData, 300);
        }

        function setProductStockFilter(status, btn) {
            g_prodStockStatus = status;
            g_prodPage = 1;
            btn.parentElement.querySelectorAll('.chip').forEach(function(c) { c.classList.remove('active'); });
            btn.classList.add('active');
            loadProductsData();
        }

        function setProductPriceFilter(status, btn) {
            g_prodPriceStatus = (g_prodPriceStatus === status) ? 'all' : status;
            g_prodPage = 1;
            btn.classList.toggle('active', g_prodPriceStatus === status);
            loadProductsData();
        }

        function onProductSortChange(val) {
            var parts = val.split('_');
            g_prodSortOrder = parts.pop();
            g_prodSortBy = parts.join('_');
            g_prodPage = 1;
            loadProductsData();
        }

        function changeProductPage(delta) {
            g_prodPage = Math.max(1, g_prodPage + delta);
            loadProductsData();
        }

        // --- Bills Tab Operations ---
        function loadBillsData() {
            var url = '/api/admin/bills?page=' + g_billPage +
                '&status=' + g_billStatus +
                '&staff=' + encodeURIComponent(document.getElementById('bill-search-staff').value) +
                '&customer=' + encodeURIComponent(document.getElementById('bill-search-cust').value) +
                '&date_start=' + document.getElementById('bill-date-start').value +
                '&date_end=' + document.getElementById('bill-date-end').value;

            fetch(url)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        renderBillsTable(res.data);
                    }
                });
        }

        function renderBillsTable(data) {
            g_billsItems = data.bills || [];
            var sum = data.summary || {};
            document.getElementById('bills-summary-chips').innerHTML =
                '<span class="badge badge-success">出库实收: ¥' + (sum.total_revenue || 0).toLocaleString('zh-CN', {minimumFractionDigits:2}) + ' (' + (sum.paid_count || 0) + '单)</span>' +
                '<span class="badge badge-warning">当前挂账: ¥' + (sum.pending_amount || 0).toLocaleString('zh-CN', {minimumFractionDigits:2}) + ' (' + (sum.pending_count || 0) + '单)</span>';

            document.getElementById('bill-pagination-info').innerText = '第 ' + data.page + ' / ' + data.total_pages + ' 页 (共 ' + data.total + ' 笔账单)';
            document.getElementById('btn-bill-prev').disabled = data.page <= 1;
            document.getElementById('btn-bill-next').disabled = data.page >= data.total_pages;

            var html = '';
            g_billsItems.forEach(function(bill, idx) {
                var statusBadge = bill.status === 'paid'
                    ? '<span class="badge badge-success">已出库</span>'
                    : '<span class="badge badge-warning">挂账待录</span>';

                var delayBadge = '';
                if (bill.is_danger) delayBadge = '<span class="badge badge-danger">超2小时/隔夜</span>';
                else if (bill.is_warning) delayBadge = '<span class="badge badge-warning">滞留超30分</span>';

                var payBadge = '<span class="badge" style="background:#f1f5f9; color:#475569; border:1px solid #cbd5e1;">现金</span>';
                if (bill.pay_method === 'pos') {
                    payBadge = '<span class="badge" style="background:#e0f2fe; color:#0369a1; border:1px solid #bae6fd;">POS</span>';
                } else if (bill.pay_method === 'other') {
                    var noteTip = bill.note ? escapeHtml(bill.note) : '例外情况';
                    payBadge = '<span class="badge" style="background:#fef3c7; color:#b45309; border:1px solid #fde68a;" title="' + noteTip + '">备注' + (bill.note ? ': ' + noteTip : '') + '</span>';
                } else if (bill.pay_method === 'wechat') {
                    payBadge = '<span class="badge" style="background:#dcfce7; color:#15803d; border:1px solid #bbf7d0;">微信</span>';
                } else if (bill.pay_method === 'alipay') {
                    payBadge = '<span class="badge" style="background:#e0e7ff; color:#4338ca; border:1px solid #c7d2fe;">支付宝</span>';
                }

                html += '<tr>' +
                    '<td class="font-mono font-bold">' + escapeHtml(bill.id) + '</td>' +
                    '<td style="font-size:12px; color:var(--text-muted);">' + escapeHtml(bill.created_at) + '</td>' +
                    '<td class="font-bold">' + escapeHtml(bill.customer_name) + '</td>' +
                    '<td>' + escapeHtml(bill.staff) + '</td>' +
                    '<td class="text-right">' + bill.items_count + ' 件</td>' +
                    '<td class="text-right font-mono font-bold" style="color:var(--primary);">¥' + bill.total.toFixed(2) + '</td>' +
                    '<td class="text-right font-mono text-success">¥' + bill.profit.toFixed(2) + '</td>' +
                    '<td>' + payBadge + '</td>' +
                    '<td>' + statusBadge + ' ' + delayBadge + '</td>' +
                    '<td class="text-center" style="white-space:nowrap;">' +
                    '<button class="btn btn-secondary btn-sm" onclick="openBillByIndex(' + idx + ')">📋 明细</button> ' +
                    (bill.status === 'pending' ? '<button class="btn btn-success btn-sm" onclick="markBillPaidByIndex(' + idx + ')">✅ 出库核销</button>' : '') +
                    '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-bills').innerHTML = html || '<tr><td colspan="10" class="text-center" style="padding:40px; color:var(--text-muted);">暂无符合条件的账单流水</td></tr>';
        }

        function openBillByIndex(idx) {
            var bill = g_billsItems[idx];
            if (bill) openBillDetail(bill);
        }

        function markBillPaidByIndex(idx) {
            var bill = g_billsItems[idx];
            if (bill) markBillPaid(bill.id);
        }

        function setBillStatusFilter(status, btn) {
            g_billStatus = status;
            g_billPage = 1;
            btn.parentElement.querySelectorAll('.chip').forEach(function(c) { c.classList.remove('active'); });
            btn.classList.add('active');
            loadBillsData();
        }

        function onBillSearchChange() {
            g_billPage = 1;
            if (g_billDebounce) clearTimeout(g_billDebounce);
            g_billDebounce = setTimeout(loadBillsData, 300);
        }

        function changeBillPage(delta) {
            g_billPage = Math.max(1, g_billPage + delta);
            loadBillsData();
        }

        function markBillPaid(billId) {
            if (!confirm('确认将账单 ' + billId + ' 标记为「已录入管家婆(出库)」？')) return;
            fetch('/api/admin/bills/status', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({bill_id: billId, status: 'paid'})
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.success) {
                    showToast('账单已核销出库！', 'success');
                    loadBillsData();
                    refreshCurrentView(true);
                } else {
                    showToast('核销失败: ' + (res.error || '未知错误'), 'error');
                }
            });
        }

        // --- Inventory & Shelves ---
        function loadInventoryData() {
            fetch('/api/admin/inventory/overview')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        renderInventoryView(res.data);
                    }
                });
        }

        function renderInventoryView(data) {
            var urgentHtml = '';
            (data.urgent_replenish || []).forEach(function(item) {
                urgentHtml += '<tr>' +
                    '<td class="font-bold">' + escapeHtml(item.name) + '</td>' +
                    '<td>' + escapeHtml(item.spec || '-') + '</td>' +
                    '<td class="text-right font-bold text-danger">' + item.stock_qty + ' ' + escapeHtml(item.unit || '') + '</td>' +
                    '<td class="text-right font-mono">¥' + (item.cost_price || 0).toFixed(2) + '</td>' +
                    '<td><span class="badge badge-primary">' + escapeHtml(item.location || '无货位') + '</span></td>' +
                    '</tr>';
            });
            document.getElementById('tbody-urgent').innerHTML = urgentHtml || '<tr><td colspan="5" class="text-center" style="padding:20px; color:var(--text-muted);">暂无缺货商品</td></tr>';

            var stagHtml = '';
            (data.stagnant_items || []).forEach(function(item) {
                stagHtml += '<tr>' +
                    '<td class="font-bold">' + escapeHtml(item.name) + '</td>' +
                    '<td class="text-right font-bold">' + item.stock_qty + '</td>' +
                    '<td class="text-right font-mono">¥' + (item.cost_price || 0).toFixed(2) + '</td>' +
                    '<td class="text-right font-mono font-bold" style="color:var(--warning);">¥' + (item.capital_tied || 0).toLocaleString('zh-CN', {minimumFractionDigits:2}) + '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-stagnant').innerHTML = stagHtml || '<tr><td colspan="4" class="text-center" style="padding:20px; color:var(--text-muted);">暂无积压商品</td></tr>';

            // Tree hierarchy
            var treeHtml = '<div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:16px;">';
            (data.tree || []).forEach(function(zone) {
                treeHtml += '<div style="border:1px solid var(--border-color); border-radius:8px; padding:16px; background:#F8FAFC;">' +
                    '<div style="font-weight:800; font-size:14px; margin-bottom:8px;">📍 ' + escapeHtml(zone.name) + ' (' + zone.rack_count + '组货架)</div>';
                (zone.racks || []).forEach(function(rack) {
                    treeHtml += '<div style="margin-left:12px; margin-top:6px; font-size:12.5px;">' +
                        '<strong>🔹 ' + escapeHtml(rack.name) + '</strong> (' + rack.cell_count + '货位): ';
                    var cellPills = [];
                    (rack.levels || []).forEach(function(lv) {
                        (lv.cells || []).forEach(function(c) {
                            cellPills.push('<span class="badge badge-secondary" style="margin:2px;">' + escapeHtml(c.name || c.code) + '</span>');
                        });
                    });
                    treeHtml += cellPills.slice(0, 6).join(' ') + (cellPills.length > 6 ? ' <span style="color:var(--text-muted);">+' + (cellPills.length - 6) + '</span>' : '') + '</div>';
                });
                treeHtml += '</div>';
            });
            treeHtml += '</div>';
            document.getElementById('shelf-tree-container').innerHTML = treeHtml;
        }

        // --- Customers Tab Operations ---
        function loadCustomersData() {
            fetch('/api/admin/customers')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        renderCustomersTable(res.data.customers || []);
                    }
                });
        }

        function renderCustomersTable(customers) {
            g_customersItems = customers || [];
            var html = '';
            g_customersItems.forEach(function(c, idx) {
                html += '<tr>' +
                    '<td class="font-bold" style="font-size:14px;">' + escapeHtml(c.customer_name) + '</td>' +
                    '<td class="text-right">' + c.total_orders + ' 单</td>' +
                    '<td class="text-right font-mono font-bold">¥' + c.total_spent.toLocaleString('zh-CN', {minimumFractionDigits:2}) + '</td>' +
                    '<td class="text-right font-mono font-bold" style="color:' + (c.pending_debt > 0 ? 'var(--danger)' : 'var(--text-muted)') + ';">¥' + c.pending_debt.toLocaleString('zh-CN', {minimumFractionDigits:2}) + '</td>' +
                    '<td style="font-size:12px; color:var(--text-muted);">' + escapeHtml(c.last_order_at || '-') + '</td>' +
                    '<td class="text-center">' +
                    '<button class="btn btn-secondary btn-sm" onclick="openCustomerByIndex(' + idx + ')">🧾 对账单</button>' +
                    '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-customers').innerHTML = html || '<tr><td colspan="6" class="text-center" style="padding:40px; color:var(--text-muted);">暂无客户往来记录</td></tr>';
        }

        function openCustomerByIndex(idx) {
            var c = g_customersItems[idx];
            if (c) openCustomerStatement(c);
        }

        function openCustomerStatement(cust) {
            var txt = '========================================\\n';
            txt += '【示例零售全能助手 · 客户往来对账单】\\n';
            txt += '客户名称: ' + cust.customer_name + '\\n';
            txt += '生成时间: ' + new Date().toLocaleString() + '\\n';
            txt += '----------------------------------------\\n';
            txt += '累计交易订单数: ' + cust.total_orders + ' 笔\\n';
            txt += '累计出库交易额: ¥' + cust.total_spent.toFixed(2) + '\\n';
            txt += '当前挂账待付欠款: ¥' + cust.pending_debt.toFixed(2) + '\\n';
            txt += '----------------------------------------\\n';
            txt += '【近期订单明细】\\n';
            (cust.bills || []).forEach(function(b, idx) {
                txt += (idx + 1) + '. ' + b.id + ' | ' + b.created_at + ' | 金额: ¥' + b.total.toFixed(2) + ' | 状态: ' + (b.status === 'paid' ? '已结' : '挂账') + '\\n';
            });
            txt += '========================================\\n';
            txt += '请核对上述往来账目。如有疑问请及时联系门店核对。';

            document.getElementById('statement-text').value = txt;
            document.getElementById('modal-statement').classList.add('open');
        }

        function closeStatementModal() {
            document.getElementById('modal-statement').classList.remove('open');
        }

        function copyStatementText() {
            var txt = document.getElementById('statement-text').value;
            navigator.clipboard.writeText(txt).then(function() {
                showToast('对账单已复制到剪贴板，可直接发微信！', 'success');
            });
        }

        // --- Data Governance ---
        function renderGovernanceData() {
            var d = g_data;
            var marksHtml = '';
            (d.recent_marks || []).forEach(function(m, idx) {
                var typeZh = {'no_price': '无售价', 'wrong_price': '价格不准', 'stock_mismatch': '账实不符', 'bind_barcode': '待绑条码'}[m.mark_type] || '问题标记';
                var statusBadge = m.status === 'resolved'
                    ? '<span class="badge badge-success">已核准闭环</span>'
                    : (m.status === 'exported' ? '<span class="badge badge-warning">已导出</span>' : '<span class="badge badge-danger">待处理</span>');

                marksHtml += '<tr>' +
                    '<td>#' + m.id + '</td>' +
                    '<td><div class="font-bold">' + escapeHtml(m.product_name) + '</div><div style="font-size:11px; color:var(--text-muted);">' + escapeHtml(m.sku || '-') + ' · ' + escapeHtml(m.barcode || '-') + '</div></td>' +
                    '<td><span class="badge badge-secondary">' + typeZh + '</span></td>' +
                    '<td>' + escapeHtml(m.note || '-') + '</td>' +
                    '<td style="font-size:12px; color:var(--text-muted);">' + escapeHtml(m.created_at) + '</td>' +
                    '<td>' + statusBadge + '</td>' +
                    '<td class="text-center">' +
                    (m.status !== 'resolved' ? '<button class="btn btn-success btn-sm" onclick="openResolveModalByIndex(' + idx + ')">核准解决</button>' : '<span style="color:var(--text-muted); font-size:12px;">已完成</span>') +
                    '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-marks').innerHTML = marksHtml || '<tr><td colspan="7" class="text-center" style="padding:30px; color:var(--text-muted);">暂无现场巡架标记</td></tr>';

            var unhitHtml = '';
            (d.unhit_queries || []).forEach(function(u, idx) {
                unhitHtml += '<tr>' +
                    '<td class="text-center font-mono font-bold" style="color:var(--text-muted);">' + (idx + 1) + '</td>' +
                    '<td class="font-bold text-danger">' + escapeHtml(u.keyword) + '</td>' +
                    '<td class="text-right font-bold">' + u.unhit_count + ' 次</td>' +
                    '<td class="text-right" style="font-size:12px; color:var(--text-muted);">' + escapeHtml(String(u.last_time || '')) + '</td>' +
                    '<td class="text-center"><button class="btn btn-secondary btn-sm" onclick="searchUnhitByIndex(' + idx + ')">去补录/别名</button></td>' +
                    '</tr>';
            });
            document.getElementById('tbody-unhit').innerHTML = unhitHtml || '<tr><td colspan="5" class="text-center" style="padding:30px; color:var(--text-muted);">暂无搜空记录</td></tr>';
        }

        function openResolveModalByIndex(idx) {
            var m = (g_data && g_data.recent_marks) ? g_data.recent_marks[idx] : null;
            if (m) openResolveModal(m.id, m.product_name);
        }

        function searchUnhitByIndex(idx) {
            var u = (g_data && g_data.unhit_queries) ? g_data.unhit_queries[idx] : null;
            if (u) {
                navTo('products');
                var inp = document.getElementById('prod-search-input');
                if (inp) inp.value = u.keyword;
                onProductSearchInput(u.keyword);
            }
        }

        function openResolveModal(markId, name) {
            g_activeResolveMarkId = markId;
            document.getElementById('resolve-target-desc').innerText = '正在核准处理标记 #' + markId + ' (' + name + ')';
            document.getElementById('resolve-note-input').value = '';
            document.getElementById('modal-resolve-mark').classList.add('open');
        }

        function closeResolveModal() {
            document.getElementById('modal-resolve-mark').classList.remove('open');
        }

        function submitResolveMark() {
            if (!g_activeResolveMarkId) return;
            var note = document.getElementById('resolve-note-input').value.trim();
            fetch('/api/admin/marks/resolve', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({mark_id: g_activeResolveMarkId, note: note})
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.success) {
                    showToast('标记已闭环解决！', 'success');
                    closeResolveModal();
                    refreshCurrentView(true);
                } else {
                    showToast('操作失败: ' + (res.error || '未知错误'), 'error');
                }
            });
        }

        // --- AI Assistant & Knowledge Base ---
        function sendQuickAi(q) {
            document.getElementById('ai-input').value = q;
            sendAiMessage();
        }

        function sendAiMessage() {
            var inp = document.getElementById('ai-input');
            var msg = inp.value.trim();
            if (!msg) return;
            inp.value = '';

            var hist = document.getElementById('ai-chat-history');
            var uDiv = document.createElement('div');
            uDiv.style.cssText = 'background:#2563EB; color:#FFF; padding:10px 14px; border-radius:8px; font-size:13px; align-self:flex-end; max-width:85%;';
            uDiv.innerText = msg;
            hist.appendChild(uDiv);

            var aDiv = document.createElement('div');
            aDiv.style.cssText = 'background:#F1F5F9; padding:10px 14px; border-radius:8px; font-size:13px; max-width:85%;';
            aDiv.innerText = '正在思考与查询专家知识库...';
            hist.appendChild(aDiv);
            hist.scrollTop = hist.scrollHeight;

            fetch('/api/admin/ai/chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({message: msg})
            })
            .then(function(r) { return r.json(); })
            .then(function(res) {
                if (res && res.data && res.data.reply) {
                    aDiv.innerText = res.data.reply;
                } else {
                    aDiv.innerText = '暂未查询到对应解答，请重试。';
                }
                hist.scrollTop = hist.scrollHeight;
            })
            .catch(function(err) {
                aDiv.innerText = '请求失败: ' + err.message;
            });
        }

        function searchKnowledge(kw) {
            if (!kw.trim()) return;
            fetch('/api/knowledge?q=' + encodeURIComponent(kw))
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    var items = res.data || [];
                    var html = '';
                    items.forEach(function(it) {
                        html += '<tr>' +
                            '<td><span class="badge badge-secondary">' + escapeHtml(it.category || '常识') + '</span></td>' +
                            '<td class="font-bold">' + escapeHtml(it.question) + '</td>' +
                            '<td style="font-size:12.5px; line-height:1.6;">' + escapeHtml(it.answer) + '</td>' +
                            '</tr>';
                    });
                    document.getElementById('tbody-kb').innerHTML = html || '<tr><td colspan="3" class="text-center" style="padding:30px; color:var(--text-muted);">知识库中未找到匹配条目</td></tr>';
                });
        }

        // --- System Ops & Health ---
        function loadServicesStatus() {
            fetch('/api/admin/services/status')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (res && res.data) {
                        renderServicesTable(res.data.services || []);
                    }
                });

            // Sync info
            var sync = g_data.sync_info || {};
            document.getElementById('sys-snap-count').innerText = (sync.snapshot_count || 0).toLocaleString();
            document.getElementById('sys-total-prod').innerText = (g_data.total_products || 0).toLocaleString();
            document.getElementById('sys-sync-time').innerText = sync.synced_at || '暂无';
            document.getElementById('sys-unpriced-count').innerText = g_data.unpriced_count || 0;
            drawApkQr();
        }

        function renderServicesTable(services) {
            var descs = {
                18767: '路由转发、JWT认证、Web大盘与多服务聚合中枢',
                18601: 'FTS5 全文索引、拼音缩写、五金规格保护、推荐召回',
                18602: '16,117 条五金施工常识、材料搭配与工艺知识库',
                18603: '大模型问答、多轮上下文与施工方案生成',
                18604: '5级货架货位下钻、调账盘点与出入库记录',
                18605: '网络与蓝牙双路价签打印、TSPL 指令解析'
            };
            var html = '';
            services.forEach(function(s) {
                var isUp = s.status === 'online';
                var statusBadge = isUp ? '<span class="badge badge-success">正常运行 UP</span>' : '<span class="badge badge-danger">离线 DOWN</span>';
                var lat = isUp ? (s.latency_ms + ' ms') : '-';
                html += '<tr>' +
                    '<td class="font-bold">' + escapeHtml(s.name) + '</td>' +
                    '<td class="font-mono">:' + s.port + '</td>' +
                    '<td>' + statusBadge + '</td>' +
                    '<td class="text-right font-mono" style="color:' + (s.latency_ms < 20 ? 'var(--success)' : 'var(--warning)') + ';">' + lat + '</td>' +
                    '<td style="font-size:12px; color:var(--text-muted);">' + (descs[s.port] || '') + '</td>' +
                    '</tr>';
            });
            document.getElementById('tbody-services').innerHTML = html;
        }

        // --- Modals & Drawers ---
        function switchBarcodeType(type) {
            g_currentBarcodeType = type;
            var btnQr = document.getElementById('btn-show-qr');
            var btnBc = document.getElementById('btn-show-barcode');
            var wrapQr = document.getElementById('wrap-qr-canvas');
            var wrapBc = document.getElementById('wrap-barcode-canvas');
            if (btnQr) btnQr.classList.toggle('active', type === 'qr');
            if (btnBc) btnBc.classList.toggle('active', type === 'barcode');
            if (wrapQr) wrapQr.style.display = (type === 'qr') ? 'inline-block' : 'none';
            if (wrapBc) wrapBc.style.display = (type === 'barcode') ? 'inline-block' : 'none';
            renderCurrentBarcode();
        }

        function renderCurrentBarcode() {
            var item = (g_carouselItems && g_carouselItems.length > 0) ? g_carouselItems[g_carouselIdx] : null;
            if (item) {
                var qrText = (item.sku && String(item.sku).trim()) ? String(item.sku).trim() : ((item.barcode && String(item.barcode).trim()) ? String(item.barcode).trim() : '000000');
                var barcodeText = (item.barcode && String(item.barcode).trim()) ? String(item.barcode).trim() : (item.sku ? String(item.sku).split('.').join('') : '000000');
                g_activeCodeText = (g_currentBarcodeType === 'qr') ? qrText : barcodeText;
                document.getElementById('modal-barcode-text').innerText = g_activeCodeText;
                if (g_currentBarcodeType === 'qr') {
                    drawQrOnCanvas(document.getElementById('modal-qr-canvas'), qrText, {size: 240});
                } else {
                    drawBarcodeOnCanvas(document.getElementById('modal-barcode-canvas'), barcodeText, {height: 70});
                }
            } else if (g_activeCodeText) {
                if (g_currentBarcodeType === 'qr') {
                    drawQrOnCanvas(document.getElementById('modal-qr-canvas'), g_activeCodeText, {size: 240});
                } else {
                    drawBarcodeOnCanvas(document.getElementById('modal-barcode-canvas'), g_activeCodeText, {height: 70});
                }
            }
        }

        function openQrModal(item) {
            g_carouselItems = [];
            document.getElementById('modal-qr-header-title').innerText = '📱 商品管家婆大码直出';
            document.getElementById('modal-qr-carousel-bar').style.display = 'none';
            document.getElementById('modal-qr-carousel-controls').style.display = 'none';

            displaySingleItemBarcode(item);
            document.getElementById('modal-qr').classList.add('open');
        }

        function displaySingleItemBarcode(item) {
            document.getElementById('modal-qr-itemname').innerText = item.name || item.productName || '商品';
            var price = (item.sell_price !== undefined ? item.sell_price : item.price) || 0;
            var spec = (item.spec || '-') + ' · 售价: ¥' + Number(price).toFixed(2);
            if (item.quantity) spec += ' × ' + item.quantity + (item.unit || '');
            document.getElementById('modal-qr-spec').innerText = spec;

            // 二维码全面采用管家婆原始 SKU 编号（如 192.0.2.10），一维条码采用条码或去点 SKU
            var qrText = (item.sku && String(item.sku).trim()) ? String(item.sku).trim() : ((item.barcode && String(item.barcode).trim()) ? String(item.barcode).trim() : '000000');
            var barcodeText = (item.barcode && String(item.barcode).trim()) ? String(item.barcode).trim() : (item.sku ? String(item.sku).split('.').join('') : '000000');
            g_activeCodeText = (g_currentBarcodeType === 'qr') ? qrText : barcodeText;
            document.getElementById('modal-barcode-text').innerText = g_activeCodeText;

            // 预渲染双码
            drawQrOnCanvas(document.getElementById('modal-qr-canvas'), qrText, {size: 240});
            drawBarcodeOnCanvas(document.getElementById('modal-barcode-canvas'), barcodeText, {height: 70});
            switchBarcodeType(g_currentBarcodeType);
        }

        function startBillCarouselQr() {
            if (!g_activeBillDetail || !g_activeBillDetail.items || g_activeBillDetail.items.length === 0) {
                showToast('该账单没有销售明细可出码', 'error');
                return;
            }
            g_carouselItems = g_activeBillDetail.items;
            g_carouselIdx = 0;

            document.getElementById('modal-qr-header-title').innerText = '📱 账单逐条出码录入管家婆 #' + g_activeBillDetail.id;
            document.getElementById('modal-qr-carousel-bar').style.display = 'flex';
            document.getElementById('modal-qr-carousel-controls').style.display = 'flex';

            updateCarouselDisplay();
            document.getElementById('modal-qr').classList.add('open');
        }

        function updateCarouselDisplay() {
            if (g_carouselItems.length === 0) return;
            var item = g_carouselItems[g_carouselIdx];
            document.getElementById('modal-qr-carousel-step').innerText = '第 ' + (g_carouselIdx + 1) + ' / ' + g_carouselItems.length + ' 件商品';
            var prevBtn = document.getElementById('btn-carousel-prev');
            var nextBtn = document.getElementById('btn-carousel-next');
            if (prevBtn) prevBtn.disabled = (g_carouselIdx <= 0);
            if (nextBtn) nextBtn.disabled = (g_carouselIdx >= g_carouselItems.length - 1);

            // 平滑切换过渡动画
            var body = document.getElementById('modal-qr-body-container');
            if (body) {
                body.style.opacity = '0.35';
                body.style.transform = 'translateY(6px)';
                setTimeout(function() {
                    displaySingleItemBarcode(item);
                    body.style.opacity = '1';
                    body.style.transform = 'translateY(0)';
                }, 80);
            } else {
                displaySingleItemBarcode(item);
            }
        }

        function prevCarouselItem() {
            if (g_carouselIdx > 0) {
                g_carouselIdx--;
                updateCarouselDisplay();
            }
        }

        function nextCarouselItem() {
            if (g_carouselIdx < g_carouselItems.length - 1) {
                g_carouselIdx++;
                updateCarouselDisplay();
            } else {
                showToast('已是账单最后一件商品');
            }
        }

        function openQrForBillItem(idx) {
            if (!g_activeBillDetail || !g_activeBillDetail.items) return;
            var it = g_activeBillDetail.items[idx];
            if (it) {
                g_carouselItems = g_activeBillDetail.items;
                g_carouselIdx = idx;
                document.getElementById('modal-qr-header-title').innerText = '📱 账单逐条出码录入管家婆 #' + g_activeBillDetail.id;
                document.getElementById('modal-qr-carousel-bar').style.display = 'flex';
                document.getElementById('modal-qr-carousel-controls').style.display = 'flex';
                updateCarouselDisplay();
                document.getElementById('modal-qr').classList.add('open');
            }
        }

        function closeQrModal() {
            document.getElementById('modal-qr').classList.remove('open');
        }

        function copyBarcodeText() {
            var b = document.getElementById('modal-barcode-text').innerText;
            navigator.clipboard.writeText(b).then(function() {
                showToast('条码已复制: ' + b, 'success');
            });
        }

        function openProductDetail(productId) {
            document.getElementById('drawer-title').innerText = '商品全息档案详情';
            document.getElementById('drawer-content').innerHTML = '<div style="padding:40px; text-align:center; color:var(--text-muted);">正在加载商品档案...</div>';
            document.getElementById('drawer-mask').classList.add('open');

            fetch('/api/admin/products/' + productId)
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    if (!res || !res.data || !res.data.product) {
                        document.getElementById('drawer-content').innerHTML = '加载失败';
                        return;
                    }
                    var p = res.data.product;
                    g_activeProductDetail = p;
                    var subs = res.data.substitutes || [];
                    var h = '';
                    h += '<div style="margin-bottom:20px;">';
                    h += '<h3 style="font-size:18px; font-weight:800;">' + escapeHtml(p.name) + '</h3>';
                    h += '<div style="font-size:13px; color:var(--text-muted); margin-top:4px;">' + escapeHtml(p.spec || '-') + ' · ' + escapeHtml(p.unit || '件') + '</div>';
                    h += '</div>';

                    h += '<div style="display:grid; grid-template-columns: 1fr 1fr; gap:12px; margin-bottom:20px;">';
                    h += '<div style="background:#F8FAFC; padding:12px; border-radius:8px;">';
                    h += '<div style="font-size:11px; color:var(--text-muted);">商品编码 (SKU)</div>';
                    h += '<div class="font-mono font-bold" style="font-size:14px;">' + escapeHtml(p.sku || '-') + '</div>';
                    h += '</div>';
                    h += '<div style="background:#F8FAFC; padding:12px; border-radius:8px;">';
                    h += '<div style="font-size:11px; color:var(--text-muted);">商品条形码 (Barcode)</div>';
                    h += '<div class="font-mono font-bold" style="font-size:14px;">' + escapeHtml(p.barcode || '-') + '</div>';
                    h += '</div>';
                    h += '<div style="background:#F8FAFC; padding:12px; border-radius:8px;">';
                    h += '<div style="font-size:11px; color:var(--text-muted);">进价底价 (移动加权)</div>';
                    h += '<div class="font-mono font-bold" style="font-size:16px;">¥' + (p.cost_price || 0).toFixed(2) + '</div>';
                    h += '</div>';
                    h += '<div style="background:#F8FAFC; padding:12px; border-radius:8px;">';
                    h += '<div style="font-size:11px; color:var(--text-muted);">开单售价 (零售价)</div>';
                    h += '<div class="font-mono font-bold" style="font-size:16px; color:var(--primary);">¥' + (p.sell_price || 0).toFixed(2) + '</div>';
                    h += '</div>';
                    h += '</div>';

                    h += '<div style="margin-bottom:20px; padding:14px; border:1px solid var(--border-color); border-radius:8px;">';
                    h += '<div style="font-weight:700; font-size:13px; margin-bottom:6px;">📍 仓储货架货位定位</div>';
                    h += '<div style="font-size:14px; font-weight:800; color:var(--primary);">' + (p.location ? '货位编号: ' + escapeHtml(p.location) : '未绑定货位') + '</div>';
                    h += '<div style="font-size:12px; color:var(--text-muted); margin-top:4px;">现存库存: <strong>' + p.stock_qty + '</strong> ' + escapeHtml(p.unit || '') + ' · 最低安全库存: ' + (p.min_stock || 0) + '</div>';
                    h += '</div>';

                    if (subs.length > 0) {
                        h += '<div style="margin-bottom:20px;">';
                        h += '<div style="font-weight:700; font-size:13px; margin-bottom:8px;">🔄 同分类在售替代品 (断货可替代)</div>';
                        h += '<div style="display:flex; flex-direction:column; gap:8px;">';
                        subs.forEach(function(s) {
                            h += '<div style="padding:10px; border:1px solid var(--border-color); border-radius:8px; display:flex; justify-content:space-between; align-items:center;">';
                            h += '<div><div class="font-bold" style="font-size:13px;">' + escapeHtml(s.name) + '</div><div style="font-size:11px; color:var(--text-muted);">' + escapeHtml(s.spec || '') + ' · 库存:' + s.stock_qty + '</div></div>';
                            h += '<div class="font-mono font-bold" style="color:var(--primary);">¥' + (s.sell_price || 0).toFixed(2) + '</div>';
                            h += '</div>';
                        });
                        h += '</div></div>';
                    }

                    h += '<button class="btn btn-primary" style="width:100%; justify-content:center;" onclick="openQrForActiveProduct()">📱 调出管家婆大二维码/条码</button>';
                    document.getElementById('drawer-content').innerHTML = h;
                });
        }

        function openQrForActiveProduct() {
            if (g_activeProductDetail) openQrModal(g_activeProductDetail);
        }

        function openBillDetail(bill) {
            g_activeBillDetail = bill;
            document.getElementById('drawer-title').innerText = '账单明细 #' + bill.id;
            document.getElementById('drawer-mask').classList.add('open');

            var payLabel = '现金';
            if (bill.pay_method === 'pos') payLabel = 'POS机';
            else if (bill.pay_method === 'other') payLabel = '备注例外' + (bill.note ? ' (' + escapeHtml(bill.note) + ')' : '');
            else if (bill.pay_method === 'wechat') payLabel = '微信支付';
            else if (bill.pay_method === 'alipay') payLabel = '支付宝';

            var h = '';
            h += '<div style="display:flex; justify-content:space-between; margin-bottom:16px; background:#F8FAFC; padding:14px; border-radius:8px;">';
            h += '<div><div style="font-size:11px; color:var(--text-muted);">客户 / 店员</div><div class="font-bold">' + escapeHtml(bill.customer_name) + ' (' + escapeHtml(bill.staff) + ')</div><div style="font-size:12px; color:var(--text-muted); margin-top:4px;">收款方式: <strong style="color:var(--text-main);">' + payLabel + '</strong></div></div>';
            h += '<div class="text-right"><div style="font-size:11px; color:var(--text-muted);">账单总金额</div><div class="font-mono font-bold" style="font-size:18px; color:var(--primary);">¥' + bill.total.toFixed(2) + '</div></div>';
            h += '</div>';

            h += '<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">';
            h += '<div style="font-weight:700; font-size:13px;">📦 销售商品项清单 (' + bill.items_count + ' 项):</div>';
            h += '<button type="button" class="btn btn-primary btn-sm" onclick="startBillCarouselQr()">📱 逐条全屏对账出码 (扫码枪连扫)</button>';
            h += '</div>';

            h += '<div style="display:flex; flex-direction:column; gap:8px; margin-bottom:20px;">';
            (bill.items || []).forEach(function(it, idx) {
                var name = it.productName || it.name || '商品';
                var qty = it.quantity || 1;
                var pr = it.price || 0;
                var cp = it.cost_price || 0;
                var profit = (pr - cp) * qty;

                h += '<div style="padding:12px; border:1px solid var(--border-color); border-radius:8px; display:flex; justify-content:space-between; align-items:center; gap:12px;">';
                h += '<div style="flex:1;">';
                h += '<div class="font-bold" style="font-size:13.5px;">' + (idx + 1) + '. ' + escapeHtml(name) + '</div>';
                h += '<div style="font-size:11.5px; color:var(--text-muted);">' + escapeHtml(it.spec || '') + ' · 编码: ' + escapeHtml(it.sku || it.barcode || '-') + '</div>';
                h += '</div>';
                h += '<div class="text-right" style="margin-right:8px;">';
                h += '<div class="font-mono font-bold" style="font-size:14px;">' + qty + ' × ¥' + pr.toFixed(2) + ' = ¥' + (qty * pr).toFixed(2) + '</div>';
                h += '<div class="font-mono" style="font-size:11px; color:var(--success);">毛利: ¥' + profit.toFixed(2) + '</div>';
                h += '</div>';
                h += '<button type="button" class="btn btn-secondary btn-sm" style="white-space:nowrap;" onclick="openQrForBillItem(' + idx + ')">📱 出码</button>';
                h += '</div>';
            });
            h += '</div>';

            if (bill.status === 'pending') {
                h += '<button class="btn btn-success" style="width:100%; justify-content:center; padding:12px;" onclick="markActiveBillPaid()">✅ 标记已录入管家婆 (出库核销)</button>';
            }
            document.getElementById('drawer-content').innerHTML = h;
        }

        function markActiveBillPaid() {
            if (g_activeBillDetail) markBillPaid(g_activeBillDetail.id);
        }

        function closeDrawer() {
            document.getElementById('drawer-mask').classList.remove('open');
        }

        // --- Canvas QR Code & Barcode Engine (Standard ISO/IEC 18004 & Code 128) ---
        var CODE128_PATTERNS = [
            [2,1,2,2,2,2], [2,2,2,1,2,2], [2,2,2,2,2,1], [1,2,1,2,2,3], [1,2,1,3,2,2],
            [1,3,1,2,2,2], [1,2,2,2,1,3], [1,2,2,3,1,2], [1,3,2,2,1,2], [2,2,1,2,1,3],
            [2,2,1,3,1,2], [2,3,1,2,1,2], [1,1,2,2,3,2], [1,2,2,1,3,2], [1,2,2,2,3,1],
            [1,1,3,2,2,2], [1,2,3,1,2,2], [1,2,3,2,2,1], [2,2,3,2,1,1], [2,2,1,1,3,2],
            [2,2,1,2,3,1], [2,1,3,2,1,2], [2,2,3,1,1,2], [3,1,2,1,3,1], [3,1,1,2,2,2],
            [3,2,1,1,2,2], [3,2,1,2,2,1], [3,1,2,2,1,2], [3,2,2,1,1,2], [3,2,2,2,1,1],
            [2,1,2,1,2,3], [2,1,2,3,2,1], [2,3,2,1,2,1], [1,1,1,3,2,3], [1,3,1,1,2,3],
            [1,3,1,3,2,1], [1,1,2,3,1,3], [1,3,2,1,1,3], [1,3,2,3,1,1], [2,1,1,3,1,3],
            [2,3,1,1,1,3], [2,3,1,3,1,1], [1,1,2,1,3,3], [1,1,2,3,3,1], [1,3,2,1,3,1],
            [1,1,3,1,2,3], [1,1,3,3,2,1], [1,3,3,1,2,1], [3,1,3,1,2,1], [2,1,1,3,3,1],
            [2,3,1,1,3,1], [2,1,3,1,1,3], [2,1,3,3,1,1], [2,1,3,1,3,1], [3,1,1,1,2,3],
            [3,1,1,3,2,1], [3,3,1,1,2,1], [3,1,2,1,1,3], [3,1,2,3,1,1], [3,3,2,1,1,1],
            [3,1,4,1,1,1], [2,2,1,4,1,1], [4,3,1,1,1,1], [1,1,1,2,2,4], [1,1,1,4,2,2],
            [1,2,1,1,2,4], [1,2,1,4,2,1], [1,4,1,1,2,2], [1,4,1,2,2,1], [1,1,2,2,1,4],
            [1,1,2,4,1,2], [1,2,2,1,1,4], [1,2,2,4,1,1], [1,4,2,1,1,2], [1,4,2,2,1,1],
            [2,4,1,2,1,1], [2,2,1,1,1,4], [4,1,3,1,1,1], [2,4,1,1,1,2], [1,3,4,1,1,1],
            [1,1,1,2,4,2], [1,2,1,1,4,2], [1,2,1,2,4,1], [1,1,4,2,1,2], [1,2,4,1,1,2],
            [1,2,4,2,1,1], [4,1,1,2,1,2], [4,2,1,1,1,2], [4,2,1,2,1,1], [2,1,2,1,4,1],
            [2,1,4,1,2,1], [4,1,2,1,2,1], [1,1,1,1,4,3], [1,1,1,3,4,1], [1,3,1,1,4,1],
            [1,1,4,1,1,3], [1,1,4,3,1,1], [4,1,1,1,1,3], [4,1,1,3,1,1], [1,1,3,1,4,1],
            [1,1,4,1,3,1], [3,1,1,1,4,1], [4,1,1,1,3,1], [2,1,1,4,1,2], [2,1,1,2,1,4],
            [2,1,1,2,3,2], [2,3,3,1,1,1,2]
        ];

        function drawQrOnCanvas(canvas, text, opts) {
            if (!canvas || !text) return;
            opts = opts || {};
            var size = opts.size || canvas.width || 240;
            canvas.width = size;
            canvas.height = size;
            var ctx = canvas.getContext('2d');
            ctx.fillStyle = '#FFFFFF';
            ctx.fillRect(0, 0, size, size);

            try {
                if (typeof qrcode !== 'function') {
                    console.error('qrcode generator not ready');
                    return;
                }
                var qr = qrcode(0, 'M');
                qr.addData(String(text), 'Byte');
                qr.make();

                var modCount = qr.getModuleCount();
                var margin = 4; // ISO standard 4-module quiet zone
                var totalMods = modCount + margin * 2;
                var modSize = Math.floor(size / totalMods);
                if (modSize < 1) modSize = 1;
                var actualQrSize = modSize * totalMods;
                var offset = Math.floor((size - actualQrSize) / 2) + modSize * margin;

                ctx.fillStyle = '#000000';
                for (var r = 0; r < modCount; r++) {
                    for (var c = 0; c < modCount; c++) {
                        if (qr.isDark(r, c)) {
                            ctx.fillRect(offset + c * modSize, offset + r * modSize, modSize, modSize);
                        }
                    }
                }
            } catch (err) {
                console.error('QR code rendering failed:', err);
            }
        }

        function drawBarcodeOnCanvas(canvas, text, opts) {
            if (!canvas || !text) return;
            opts = opts || {};
            var clean = String(text).trim();
            if (!clean) return;

            var codes = [104]; // Start B
            var checksum = 104;
            for (var i = 0; i < clean.length; i++) {
                var codePoint = clean.charCodeAt(i);
                var val = codePoint - 32;
                if (val < 0 || val > 95) continue;
                codes.push(val);
                checksum += val * (i + 1);
            }
            checksum = checksum % 103;
            codes.push(checksum);
            codes.push(106); // Stop

            var modules = [];
            for (var q = 0; q < 10; q++) modules.push(0);
            codes.forEach(function(c) {
                var pat = CODE128_PATTERNS[c];
                if (!pat) return;
                for (var j = 0; j < pat.length; j++) {
                    var w = pat[j];
                    var isBar = (j % 2 === 0);
                    for (var k = 0; k < w; k++) modules.push(isBar ? 1 : 0);
                }
            });
            for (var q2 = 0; q2 < 10; q2++) modules.push(0);

            var h = opts.height || 70;
            var minWidth = modules.length * 2;
            var w = Math.max(minWidth, canvas.parentElement ? (canvas.parentElement.clientWidth - 32) : 340);
            canvas.width = w;
            canvas.height = h;

            var ctx = canvas.getContext('2d');
            ctx.fillStyle = '#FFFFFF';
            ctx.fillRect(0, 0, w, h);

            var modScale = Math.floor(w / modules.length);
            if (modScale < 1) modScale = 1;
            var actualWidth = modules.length * modScale;
            var offsetX = Math.floor((w - actualWidth) / 2);

            ctx.fillStyle = '#000000';
            for (var m = 0; m < modules.length; m++) {
                if (modules[m] === 1) {
                    ctx.fillRect(offsetX + m * modScale, 0, modScale, h);
                }
            }
        }

        function drawApkQr() {
            var canvas = document.getElementById('apk-qr-canvas');
            if (canvas) {
                var url = window.location.origin + '/retaildemo-v2.apk';
                drawQrOnCanvas(canvas, url, {size: 128});
            }
        }

        function triggerAppPush() {
            showToast("正在触发全员版本推送...", "info");
            fetch("/api/admin/app/push_update", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    title: "店员助手新版本推送",
                    force_update: false
                })
            })
            .then(function(res) { return res.json(); })
            .then(function(data) {
                if (data.success) {
                    showToast("全员版本推送已触发！店内手机打开 APP 即见更新提示。", "success");
                } else {
                    showToast("推送失败: " + (data.error || "未知错误"), "error");
                }
            })
            .catch(function(err) {
                showToast("网络请求异常: " + err.message, "error");
            });
        }
        window.triggerAppPush = triggerAppPush;

        // Global keyboard shortcut for Bill Carousel (Next/Prev/Close)
        window.addEventListener('keydown', function(e) {
            var modal = document.getElementById('modal-qr');
            if (!modal || !modal.classList.contains('open')) return;
            if (e.key === 'ArrowRight' || e.key === ' ' || e.code === 'Space') {
                if (g_carouselItems && g_carouselItems.length > 0) {
                    e.preventDefault();
                    nextCarouselItem();
                }
            } else if (e.key === 'ArrowLeft') {
                if (g_carouselItems && g_carouselItems.length > 0) {
                    e.preventDefault();
                    prevCarouselItem();
                }
            } else if (e.key === 'Escape') {
                closeQrModal();
            }
        });

        // --- Helpers ---
        function escapeHtml(str) {
            if (!str) return '';
            return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }
        function escapeAttr(obj) {
            return obj;
        }

        // Explicitly expose functions to window
        window.navTo = navTo;
        window.switchTab = switchTab;
        window.openProductByIndex = openProductByIndex;
        window.openQrByIndex = openQrByIndex;
        window.openBillByIndex = openBillByIndex;
        window.markBillPaidByIndex = markBillPaidByIndex;
        window.openCustomerByIndex = openCustomerByIndex;
        window.openResolveModalByIndex = openResolveModalByIndex;
        window.searchUnhitByIndex = searchUnhitByIndex;
        window.openQrForActiveProduct = openQrForActiveProduct;
        window.markActiveBillPaid = markActiveBillPaid;
        window.openProductDetail = openProductDetail;
        window.openQrModal = openQrModal;
        window.closeQrModal = closeQrModal;
        window.openBillDetail = openBillDetail;
        window.markBillPaid = markBillPaid;
        window.closeDrawer = closeDrawer;
        window.openCustomerStatement = openCustomerStatement;
        window.closeStatementModal = closeStatementModal;
        window.copyStatementText = copyStatementText;
        window.openResolveModal = openResolveModal;
        window.closeResolveModal = closeResolveModal;
        window.submitResolveMark = submitResolveMark;
        window.sendQuickAi = sendQuickAi;
        window.sendAiMessage = sendAiMessage;
        window.searchKnowledge = searchKnowledge;
        window.copyBarcodeText = copyBarcodeText;
        window.changeAutoRefresh = changeAutoRefresh;
        window.refreshCurrentView = refreshCurrentView;
        window.onProductSearchInput = onProductSearchInput;
        window.setProductStockFilter = setProductStockFilter;
        window.setProductPriceFilter = setProductPriceFilter;
        window.onProductSortChange = onProductSortChange;
        window.changeProductPage = changeProductPage;
        window.setBillStatusFilter = setBillStatusFilter;
        window.onBillSearchChange = onBillSearchChange;
        window.changeBillPage = changeBillPage;
        window.switchBarcodeType = switchBarcodeType;
        window.startBillCarouselQr = startBillCarouselQr;
        window.openQrForBillItem = openQrForBillItem;
        window.prevCarouselItem = prevCarouselItem;
        window.nextCarouselItem = nextCarouselItem;
        window.displaySingleItemBarcode = displaySingleItemBarcode;

        // --- Initialization ---
        renderOverviewData();
        handleHash();
        drawApkQr();
    </script>
</body>
</html>"""

    qr_lib = _load_qrcode_js()
    rendered = template.replace("__INITIAL_DATA__", initial_json)
    return rendered.replace("/*__QRCODE_LIB__*/", qr_lib)
