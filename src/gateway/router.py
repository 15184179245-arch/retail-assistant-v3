#!/usr/bin/env python3
"""Gateway route table definitions and service URL constants."""
import json, re, sqlite3, os, time
from datetime import datetime

from utils.logging import get_error_stats

DB_PATH = os.environ.get("WUJIN_DB_PATH", "/data/wujin.db")
SEARCH_SERVICE_URL = os.environ.get("SEARCH_SERVICE_URL", "http://127.0.0.1:18601")
KNOWLEDGE_SERVICE_URL = os.environ.get("KNOWLEDGE_SERVICE_URL", "http://127.0.0.1:18602")
CHAT_SERVICE_URL = os.environ.get("CHAT_SERVICE_URL", "http://127.0.0.1:18603")
INVENTORY_SERVICE_URL = os.environ.get("INVENTORY_SERVICE_URL", "http://127.0.0.1:18604")
PRINT_SERVICE_URL = os.environ.get("PRINT_SERVICE_URL", "http://127.0.0.1:18605")
MIRROR_SERVICE_URL = os.environ.get("MIRROR_SERVICE_URL", "http://127.0.0.1:18606")  # S13 老王数据全景
ASR_SERVICE_URL = os.environ.get("ASR_SERVICE_URL", "http://127.0.0.1:18607")  # I17 语音识别

LISTEN_HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("GATEWAY_PORT", 18767))

BILLS_DB_PATH = os.environ.get("BILLS_DB_PATH", "/data/bills.db")

PRINTER_DEFAULT_PORT = 9100
LABEL_WIDTH_MM = 30
LABEL_HEIGHT_MM = 40

HEALTH_OK = {
    "success": True,
    "service": "retaildemo-gateway",
    "version": "1.0.0",
    "status": "healthy"
}


def _agent_bridge():
    """懒加载 agent 桥（避免网关启动期的导入开销与潜在环）。"""
    from agent import agent_bridge
    return agent_bridge


# I16：进程内 POST 路由（票据式写操作闸门 + Hermes 桥），优先于代理兜底
ROUTES_POST = {
    "/api/agent/run": lambda body: _agent_bridge().agent_run(body),
    "/api/agent/confirm": lambda body: _agent_bridge().agent_confirm(body),
    "/api/agent/pending": lambda body: _agent_bridge().pending_create(body),
    "/api/agent/ui": lambda body: _agent_bridge().ui_create(body),
    # 条码写回管家婆（XZG-API-RO，老板单独授权的最小写口子）
    "/api/product/barcode/check": lambda body: _check_barcode(body),
    "/api/product/barcode/write": lambda body: _write_barcode(body),
    # I29：条码改动建档与随时回滚（老板 2026-09-14 令）
    "/api/product/barcode/rollback": lambda body: _rollback_barcode(body),
    # 商品图片回传管家婆（I26，同一授权写口子内的 imgUrl 用途；只进不出，人工确认才推）
    "/api/product/image/push": lambda body: _push_image(body),
    "/api/product/image/rollback": lambda body: _rollback_image(body),
    # B13：收银台结算回执上报（只写回执表，不碰 bills，由 APP 驱动转已出库）
    "/api/pos/settlement-receipt": lambda body: _post_settlement_receipt(body),
    "/pos/settlement-receipt": lambda body: _post_settlement_receipt(body),
    # 账单真删除：APP 侧删除账单，服务端物理同步删除（杜绝刷新复活）
    "/api/bills/delete": lambda body: _post_delete_bill(body),
    "/bills/delete": lambda body: _post_delete_bill(body),
}


#: 条码上限（上游条码通常 8~13 位；给足余量但挡住超长垃圾）
_BARCODE_MAX_LEN = 32


def _clean_code(value) -> str:
    """入参清洗：转字符串去空白，并挡住超长/控制字符。"""
    text = str(value or "").strip()
    if len(text) > _BARCODE_MAX_LEN:
        return ""
    return "".join(ch for ch in text if ch.isprintable())


def _check_barcode(body):
    """一货一码校验（只读）：这个条码现在归谁？

    APP 在改条码**之前**先调这里做校验，避免出现「一码两货」。

    :return: ``{"success": True, "data": {"barcode": ..., "owner": {...}|None,
              "is_free": bool}}``
    """
    barcode = _clean_code(body.get("barcode"))
    if not barcode:
        return {"success": False, "error": "barcode required",
                "user_message": "请提供条码"}
    try:
        from sync.xzg_client import XzgClient, XzgError
        client = XzgClient(verbose=False)
        if not client._password:
            return {"success": False, "error": "upstream credential missing",
                    "user_message": "本机没配上游凭据，查不了一货一码"}
        rows = client.fetch_all_stock_condition(keyword=barcode, page_size=10)
        owner = None
        for row in rows:
            if (row.get("barCode") or "").strip() == barcode:
                owner = {
                    "product_id": row.get("productId"),
                    "sku": row.get("productCode"),
                    "name": row.get("productName"),
                    "spec": row.get("spec"),
                }
                break
        return {"success": True, "data": {
            "barcode": barcode,
            "owner": owner,
            "is_free": owner is None,   # True=这个码上游没人用，可以绑
        }}
    except Exception as exc:  # noqa: BLE001 - 校验失败只降级，不影响主流程
        return {"success": False, "error": str(exc),
                "user_message": "连不上管家婆，校验不了这个码"}


def _write_barcode(body):
    """提交条码修改 —— **受理**语义（I24，老板 2026-09-14 令「逻辑重写」）。

    旧实现（2026-09-13）在**请求线程里同步**跑完整套上游动作：全量分页扫 12,631 条找 SKU →
    一货一码查询 → 取全量对象 → 三处齐写 → 回读校验。店员点一次确认要等几十秒，
    请求超时即白做；而且**完全不写本地**，写成功了 APP 也扫不到，得等下次全量同步才拉回来。

    新实现（本地优先 + 出站队列，真正的编排在 :mod:`gateway.barcode_outbox`）：

    1. 本地别名立刻落库 → 搜索/扫码**当场命中**（「APP 第一时间覆盖」）；
    2. 写一条出站作业，后台 worker 去做上游写回 + 回读校验；
    3. **立即返回**「已受理」——本端点不再碰上游，毫秒级。

    保留的安全闸（与旧实现同口径，勿删）：参数清洗 + 人工确认 ``confirmed`` + 本地一货一码。
    上游那一层的三层防护（一货一码 / 用途锁 / 回读校验）原样搬进后台，判据不变：
    **只有回读校验通过才算同步成功**，校验没过会自动回滚本地绑定并把原因报给店员。

    :param body: ``{"sku": 商品编号, "barcode": 新条码, "confirmed": true,
                  "actor": 店员标识（可选，进审计）}``
    :return: ``{"success": True, "data": {"job_id", "state": "pending",
              "local_effective": True, ...}}``
    """
    from gateway import barcode_outbox
    return barcode_outbox.submit(body)


def _barcode_write_status(qs):
    """查条码回写作业状态（I24）：APP 据此把「正在回写…」翻成「已同步到管家婆 ✓」。

    入参任选其一：``?job_id=`` / ``?barcode=`` / ``?sku=``。
    """
    from gateway import barcode_outbox
    return barcode_outbox.status({
        "job_id": _qs_param(qs, "job_id"),
        "barcode": _qs_param(qs, "barcode"),
        "sku": _qs_param(qs, "sku"),
    })


def _rollback_barcode(body):
    """I29：条码改动随时人工回滚（老板 2026-09-14 令）。

    溯源最近一次成功同步（synced + readback_ok），提取其 old_barcode 反向写回。
    """
    from gateway import barcode_outbox
    return barcode_outbox.rollback_submit(body)


def _barcode_history(qs):
    """I29：查条码改动建档全量轨迹（聚合活动库与永久归档库）。"""
    from gateway import barcode_outbox
    return barcode_outbox.history({
        "sku": _qs_param(qs, "sku"),
        "barcode": _qs_param(qs, "barcode"),
    })


def _push_image(body):
    """提交商品图片回传（I26）——**受理**语义，与条码回写同一套出站队列范式。

    真正的编排在 :mod:`gateway.image_outbox`：压缩 → 上传 UpLoadImg → 合并写回
    imgUrl（用途锁）→ 回读校验（唯一成功判据）。上游图片存储**只进不出**
    （无删除端点，传废了就是孤儿文件），所以确认闸（``confirmed``）在这里是硬闸，
    不确认直接拒绝受理。

    :param body: ``{"sku", "product_id", "image_ids": [可选], "confirmed": true}``
    """
    from gateway import image_outbox
    return image_outbox.submit(body)


def _rollback_image(body):
    """图片回滚（I26）：把 imgUrl 写回推送前快照（引用级回滚，撤不掉已传文件）。"""
    from gateway import image_outbox
    return image_outbox.rollback_submit(body)


def _image_push_status(qs):
    """查图片回传作业状态（I26）：APP 轮询把「正在回传…」翻成「已同步 ✓」。

    入参任选其一：``?job_id=`` / ``?sku=`` / ``?product_id=``。
    """
    from gateway import image_outbox
    return image_outbox.status({
        "job_id": _qs_param(qs, "job_id"),
        "sku": _qs_param(qs, "sku"),
        "product_id": _qs_param(qs, "product_id"),
    })


def _get_stats():
    try:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        total = conn.execute("SELECT COUNT(*) as c FROM products").fetchone()["c"]
        cats = conn.execute("SELECT category, COUNT(*) as cnt FROM products WHERE category IS NOT NULL GROUP BY category ORDER BY cnt DESC").fetchall()
        low_stock = conn.execute("SELECT COUNT(*) as c FROM products WHERE stock_qty < 5 AND stock_qty >= 0").fetchone()["c"]
        conn.close()
        return {
            "success": True,
            "data": {
                "productCount": total,
                "locationCount": len(cats),
                "billCount": 0,
                "syncCount": 0,
            },
        }
    except Exception as e:
        return {"success": False, "data": None, "hint": str(e)}


def _get_all_knowledge():
    try:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT id, category, keyword, question, answer FROM knowledge_base ORDER BY category, id").fetchall()
        conn.close()
        return {"success": True, "data": [dict(r) for r in rows], "total": len(rows)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def _get_all_products(since=None):
    try:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        if since is not None and since > 0:
            since_dt = datetime.fromtimestamp(since / 1000).strftime("%Y-%m-%d %H:%M:%S")
            rows = conn.execute(
                "SELECT * FROM products WHERE updated_at > ? ORDER BY id",
                (since_dt,)
            ).fetchall()
        else:
            rows = conn.execute("SELECT * FROM products ORDER BY id").fetchall()
        conn.close()
        data = [dict(r) for r in rows]
        # S17：server_time_ms 供 APP 做增量回拉水位（用服务器时间，规避手机时钟差）
        return {"success": True, "data": data, "version": "1.0", "total": len(data),
                "server_time_ms": int(time.time() * 1000)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def _get_all_customers(since=None):
    """S18：客户档案（管家婆基础信息只读同步落库）。表未建（从未同步过）返回空表而非 500。"""
    try:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        try:
            if since is not None and since > 0:
                since_dt = datetime.fromtimestamp(since / 1000).strftime("%Y-%m-%d %H:%M:%S")
                rows = conn.execute(
                    "SELECT * FROM customers WHERE updated_at > ? ORDER BY upstream_id",
                    (since_dt,)
                ).fetchall()
            else:
                rows = conn.execute("SELECT * FROM customers ORDER BY upstream_id").fetchall()
        except sqlite3.OperationalError:
            rows = []
        finally:
            conn.close()
        data = [dict(r) for r in rows]
        return {"success": True, "data": data, "version": "1.0", "total": len(data),
                "server_time_ms": int(time.time() * 1000)}
    except Exception as e:
        return {"success": False, "error": str(e)}


#: 收银台插件领单接口的日期参数格式（挡住注入与垃圾输入）
_POS_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _pos_num(value, default=0.0) -> float:
    """行项数值容错：库里 price/quantity 可能是 None、字符串或脏值（数据铁律：只容错不篡改）。"""
    try:
        if value is None or value == "":
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def _pos_pending_bills(qs):
    """P-IMPORT：收银台插件用的「待出库单」只读视图（B12 两态口径）。

    存在的理由：店里结账仍走管家婆收银端，但逐条扫码+改价+改量太慢。插件列出 APP 的
    待出库单，选中后把 商品/数量/成交价 灌进它自己的购物车，
    收银员核对完再点结算——**记账动作永远由人触发**。

    B12（2026-09-26 老板令）把旧口径「当天 + 挂账(pending)」改成「**待出库(stage=ready)
    + 挂账(pending)**」，并**取消当天限制**——昨天没出完的今天更要能领。
    挂账（stage=draft）个人单不进本视图、不同步插件；补全三件标了待出库才出现。

    因此本端点刻意只做三件事：读、按 stage 与状态筛、整形。
      * 只读：不改 bills 任何一行，也不写 needsSync/recorded 之类的标记；
      * 不碰上游：数据全部来自 APP 自己同步上来的本地库，不调管家婆任何接口；
      * 不给内部 id：插件按 `sku`/`barcode` 走收银端自己的搜索框（它接受
        条码/编码/拼音码/名称/规格/型号），我们不复刻它的商品主键。
    """
    date = _qs_param(qs, "date") or datetime.now().strftime("%Y-%m-%d")
    if not _POS_DATE_RE.match(date):
        return {"success": False, "data": None, "error": "BAD_DATE",
                "user_message": "日期格式应为 YYYY-MM-DD"}

    from gateway.bills_sync import load_bills
    bills = load_bills()          # 只读展示路径：失败返回 {}，不抛（与账单页同口径）

    out = []
    for b in bills.values():
        if not isinstance(b, dict):
            continue
        # B12 两态：只给「待出库(stage=ready) 且挂账未出库(pending) 且未进回收站」的单。
        # stage 缺键/脏值一律按 draft 处理（与 APP 侧 normalizeStage 同口径）——
        # 老数据没有 stage 键，批量猜测属越界，靠人逐单补全。
        # 保留 recycled 过滤：APP 回收站「一键恢复」会清 recycled（BillRepo.restore），
        # 所以这里不开口子——先恢复，再来领。
        if (b.get("status") or "") != "pending" or b.get("recycled"):
            continue
        if str(b.get("stage") or "") != "ready":
            continue

        lines, bill_total = [], 0.0
        for it in (b.get("items") or []):
            if not isinstance(it, dict):
                continue
            qty = _pos_num(it.get("quantity"))
            price = _pos_num(it.get("price"))
            line_total = round(qty * price, 2)
            bill_total += line_total
            sku = str(it.get("sku") or "").strip()
            barcode = str(it.get("barcode") or "").strip()
            lines.append({
                "sku": sku,
                "barcode": barcode,
                "name": it.get("productName") or it.get("name") or "",
                "spec": it.get("spec") or "",
                "unit": it.get("unit") or "",
                "qty": qty,
                "price": price,
                "line_total": line_total,
                # 无码货（自定义/串货 TMP-）在收银端搜不到，插件必须显式提示人工处理
                "importable": bool(sku or barcode),
                # TMP- 临时码原样透出：插件在收银端建档成功后，用它调 /api/alias/bind
                # 把这个码绑到新品编码上（人发起、留痕、可 unbind 回退，铁律 12 的人工通道）。
                # 刻意不改 importable 的判据——TMP- 码在管家婆里搜不到，仍然不可直接导入。
                "temp_code": str(it.get("temp_code") or "").strip(),
            })
        out.append({
            "id": b.get("id"),
            "customer_name": b.get("customer_name") or "",
            "staff": b.get("staff") or "",
            "created_at": b.get("created_at") or "",
            "note": b.get("note") or "",
            "total": round(bill_total, 2),
            "item_count": len(lines),
            "items": lines,
        })

    out.sort(key=lambda x: str(x["created_at"]))
    return {"success": True,
            "data": {"date": date,
                     "bill_count": len(out),
                     "total_amount": round(sum(x["total"] for x in out), 2),
                     "bills": out,
                     "server_time_ms": int(time.time() * 1000)}}


def _post_settlement_receipt(body):
    """B13：收银台结算回执上报处理器。"""
    from gateway.bills_sync import save_settlement_receipt
    try:
        payload = json.loads(body) if isinstance(body, (str, bytes)) else body
    except Exception:
        return {"success": False, "error": "BAD_JSON", "user_message": "请求体不是合法 JSON"}
    return save_settlement_receipt(payload)


def _get_settlement_receipts(qs):
    """B13：增量拉取收银端已结算回执。"""
    from gateway.bills_sync import load_settlement_receipts
    since = _qs_param(qs, "since")
    rows = load_settlement_receipts(since)
    return {"success": True, "data": rows}


def _post_delete_bill(body):
    """账单真删除：从服务端 bills 表物理删除该单（及关联回执）。"""
    from gateway.bills_sync import delete_bill
    try:
        payload = json.loads(body) if isinstance(body, (str, bytes)) else body
    except Exception:
        return {"success": False, "error": "BAD_JSON", "user_message": "请求体不是合法 JSON"}
    if not isinstance(payload, dict):
        return {"success": False, "error": "BAD_PAYLOAD", "user_message": "请求体必须为 JSON 对象"}
    bid = payload.get("id") or payload.get("billId") or payload.get("bill_id")
    return delete_bill(bid)


def _get_error_stats_ok():
    """网关进程内错误统计（含前端上报），给「更多→错误查看」用。"""
    return {"success": True, "data": get_error_stats()}


ROUTES_GET = {
    "/health": lambda qs, body: HEALTH_OK,
    "/api/health": lambda qs, body: HEALTH_OK,
    "/api/agent/health": lambda qs, body: _agent_bridge().agent_health(),
    "/stats": lambda qs, body: _get_stats(),
    "/api/stats": lambda qs, body: _get_stats(),
    "/knowledge/all": lambda qs, body: _get_all_knowledge(),
    "/api/knowledge/all": lambda qs, body: _get_all_knowledge(),
    "/logs/errors": lambda qs, body: _get_error_stats_ok(),
    "/api/logs/errors": lambda qs, body: _get_error_stats_ok(),
    "/api/shelves/state": lambda qs, body: _shelf_state(),
}


def _shelf_state():
    """I22 货架同步自检：行数/水位/日志大小（排障用，与 /api/logs/recent 同口径需 token）。"""
    from gateway.shelf_store import state as shelf_state
    return shelf_state()

ROUTES_GET_WITH_QS = {
    "/sync": (lambda qs: _get_all_products(since=_get_since(qs))),
    "/api/sync": (lambda qs: _get_all_products(since=_get_since(qs))),
    "/customers": (lambda qs: _get_all_customers(since=_get_since(qs))),
    "/api/customers": (lambda qs: _get_all_customers(since=_get_since(qs))),
    "/stock/live": (lambda qs: _get_live_stock(qs)),
    "/api/stock/live": (lambda qs: _get_live_stock(qs)),
    # I24：条码回写作业状态（本地先生效 → 后台回写 → 校验通过才算同步成功）
    "/api/product/barcode/sync-status": (lambda qs: _barcode_write_status(qs)),
    # I29：条码改动建档历史（聚合活动作业与永久归档，随时可查可回滚）
    "/api/product/barcode/history": (lambda qs: _barcode_history(qs)),
    # I26：图片回传作业状态（受理后压缩上传 → 合并写回 imgUrl → 回读校验才算同步成功）
    "/api/product/image/sync-status": (lambda qs: _image_push_status(qs)),
    # P-IMPORT：收银台插件领当天挂账单（只读视图，不改任何状态、不碰上游）
    "/api/pos/pending-bills": (lambda qs: _pos_pending_bills(qs)),
    "/pos/pending-bills": (lambda qs: _pos_pending_bills(qs)),
    # B13：增量拉取收银端结算回执
    "/api/pos/settlement-receipts": (lambda qs: _get_settlement_receipts(qs)),
    "/pos/settlement-receipts": (lambda qs: _get_settlement_receipts(qs)),
}


#: 单件实时库存查询的上游超时（实测约 0.5s；给足余量，超时即降级返回失败）
_LIVE_STOCK_TIMEOUT_SEC = 10.0


def _qs_param(qs, name):
    """从 query string 取一个参数值（URL 解码，找不到返回 None）。"""
    if not qs:
        return None
    from urllib.parse import unquote
    for part in qs.split("&"):
        if part.startswith(name + "="):
            return unquote(part.split("=", 1)[1]) or None
    return None


def _get_live_stock(qs):
    """XZG-API-RO：单个商品**实时**库存/成本（上游直查，约 0.5 秒）。

    用途：本地库存是同步快照、可能过期；店员扫码/查价时按需问一次上游拿当场值。
    **只读**：调 `Report/Stock/StockCondition/Get?keyword=<编号或条码>`（白名单内）。

    失败一律走 ``success=false`` 降级（凭据缺失/上游超时/无此商品），
    调用方（APP）继续显示本地快照即可——实时查询是增强，不是依赖。
    """
    keyword = _qs_param(qs, "sku") or _qs_param(qs, "barcode") or _qs_param(qs, "keyword")
    if not keyword:
        return {"success": False, "error": "缺少 sku/barcode 参数",
                "user_message": "没给商品编号或条码"}

    try:
        from sync.xzg_client import XzgClient, XzgError
        client = XzgClient(verbose=False)
        if not client._password:
            return {"success": False, "error": "upstream credential missing",
                    "user_message": "本机没配上游凭据，先用本地库存"}

        box = {}

        def _fetch():
            try:
                box["items"] = client.fetch_all_stock_condition(keyword=keyword, page_size=5)
            except Exception as exc:  # noqa: BLE001 - 任何上游异常都降级，不打断 APP
                box["error"] = str(exc)

        import threading
        worker = threading.Thread(target=_fetch, daemon=True)
        worker.start()
        worker.join(timeout=_LIVE_STOCK_TIMEOUT_SEC)
        if worker.is_alive():
            return {"success": False, "error": "upstream timeout",
                    "user_message": "上游响应慢，先用本地库存"}
        if "error" in box:
            return {"success": False, "error": box["error"],
                    "user_message": "没连上管家婆，先用本地库存"}

        items = box.get("items") or []
        if not items:
            return {"success": False, "error": "not found upstream",
                    "user_message": "管家婆里没这个商品"}
        row = items[0]
        return {"success": True, "data": {
            "sku": row.get("productCode"),
            "barcode": row.get("barCode"),
            "name": row.get("productName"),
            "stock_qty": row.get("stockQty"),
            "cost_price": row.get("costPrice"),
            "sell_price": row.get("retailPrice"),
            "spec": row.get("spec"),
            "category": row.get("productCategoryName"),
            "brand": row.get("brandName"),
            "live": True,
        }}
    except Exception as exc:  # noqa: BLE001 - 网关绝不能因这个增强接口 500
        return {"success": False, "error": str(exc),
                "user_message": "实时库存查询失败，先用本地库存"}


def _get_since(qs):
    if not qs:
        return None
    for part in qs.split("&"):
        if part.startswith("since="):
            try:
                return int(part.split("=", 1)[1])
            except (ValueError, IndexError):
                return None
    return None


ROUTES_PROXY_GET = {
    "/search": ("/search", SEARCH_SERVICE_URL),
    "/api/search": ("/search", SEARCH_SERVICE_URL),
    "/alias/resolve": ("/alias/resolve", SEARCH_SERVICE_URL),
    "/api/alias/resolve": ("/alias/resolve", SEARCH_SERVICE_URL),
    "/recommendations": ("/recommendations", SEARCH_SERVICE_URL),
    "/api/recommendations": ("/recommendations", SEARCH_SERVICE_URL),
    "/products/unpriced": ("/unpriced", SEARCH_SERVICE_URL),
    "/api/products/unpriced": ("/unpriced", SEARCH_SERVICE_URL),
    "/knowledge": ("/search", KNOWLEDGE_SERVICE_URL),
    "/api/knowledge": ("/search", KNOWLEDGE_SERVICE_URL),
    "/api/knowledge/search": ("/search", KNOWLEDGE_SERVICE_URL),
    "/scenario": ("/scenario", KNOWLEDGE_SERVICE_URL),
    "/api/scenario": ("/scenario", KNOWLEDGE_SERVICE_URL),
    "/memory": ("/memory", CHAT_SERVICE_URL),
    "/api/memory": ("/memory", CHAT_SERVICE_URL),
    "/memories": ("/memories", CHAT_SERVICE_URL),
    "/api/memories": ("/memories", CHAT_SERVICE_URL),
    "/messages": ("/messages", CHAT_SERVICE_URL),
    "/api/messages": ("/messages", CHAT_SERVICE_URL),
    "/inventory/tree": ("/tree", INVENTORY_SERVICE_URL),
    "/api/inventory/tree": ("/tree", INVENTORY_SERVICE_URL),
    "/todos": ("/todos", INVENTORY_SERVICE_URL),
    "/api/todos": ("/todos", INVENTORY_SERVICE_URL),
    "/todos/pending": ("/todos/pending", INVENTORY_SERVICE_URL),
    "/api/todos/pending": ("/todos/pending", INVENTORY_SERVICE_URL),
    "/inventory/floors": ("/floors", INVENTORY_SERVICE_URL),
    "/api/inventory/floors": ("/floors", INVENTORY_SERVICE_URL),
    "/inventory/zones": ("/zones", INVENTORY_SERVICE_URL),
    "/api/inventory/zones": ("/zones", INVENTORY_SERVICE_URL),
    "/inventory/racks": ("/racks", INVENTORY_SERVICE_URL),
    "/api/inventory/racks": ("/racks", INVENTORY_SERVICE_URL),
    "/inventory/levels": ("/levels", INVENTORY_SERVICE_URL),
    "/api/inventory/levels": ("/levels", INVENTORY_SERVICE_URL),
    "/inventory/cells": ("/cells", INVENTORY_SERVICE_URL),
    "/api/inventory/cells": ("/cells", INVENTORY_SERVICE_URL),
    "/inventory/custom-locations": ("/custom-locations", INVENTORY_SERVICE_URL),
    "/api/inventory/custom-locations": ("/custom-locations", INVENTORY_SERVICE_URL),
    "/inventory/cell": ("", INVENTORY_SERVICE_URL),
    "/api/inventory/cell": ("", INVENTORY_SERVICE_URL),
    "/inventory/search-products": ("/search-products", INVENTORY_SERVICE_URL),
    "/api/inventory/search-products": ("/search-products", INVENTORY_SERVICE_URL),
    "/inventory/low-stock": ("/low-stock", INVENTORY_SERVICE_URL),
    "/api/inventory/low-stock": ("/low-stock", INVENTORY_SERVICE_URL),
    "/inventory/movements": ("/movements", INVENTORY_SERVICE_URL),
    "/api/inventory/movements": ("/movements", INVENTORY_SERVICE_URL),
    "/inventory/product/images": ("/product/images", INVENTORY_SERVICE_URL),
    "/api/inventory/product/images": ("/product/images", INVENTORY_SERVICE_URL),
    "/inventory/products-with-images": ("/products-with-images", INVENTORY_SERVICE_URL),
    "/api/inventory/products-with-images": ("/products-with-images", INVENTORY_SERVICE_URL),
    "/inventory/product/locations": ("/product/locations", INVENTORY_SERVICE_URL),
    "/api/inventory/product/locations": ("/product/locations", INVENTORY_SERVICE_URL),
    "/api/print/templates": ("/api/print/templates", PRINT_SERVICE_URL),
    "/api/print/printers": ("/api/print/printers", PRINT_SERVICE_URL),
    "/api/print/jobs": ("/api/print/jobs", PRINT_SERVICE_URL),
    "/api/print/stats": ("/stats", PRINT_SERVICE_URL),
    "/api/print/queue": ("/print/queue", PRINT_SERVICE_URL),
    "/laowang/overview": ("/overview", MIRROR_SERVICE_URL),
    "/api/laowang/overview": ("/overview", MIRROR_SERVICE_URL),
    "/mirror/health": ("/health", MIRROR_SERVICE_URL),
    "/api/mirror/health": ("/health", MIRROR_SERVICE_URL),
    "/asr/health": ("/health", ASR_SERVICE_URL),
    "/api/asr/health": ("/health", ASR_SERVICE_URL),
}

ROUTES_PROXY_POST = {
    "/mirror/push": ("/push", MIRROR_SERVICE_URL, 15),
    "/api/mirror/push": ("/push", MIRROR_SERVICE_URL, 15),
    "/search": ("/search", SEARCH_SERVICE_URL, 10),
    "/api/search": ("/search", SEARCH_SERVICE_URL, 10),
    "/recommendations": ("/recommendations", SEARCH_SERVICE_URL, 10),
    "/api/recommendations": ("/recommendations", SEARCH_SERVICE_URL, 10),
    "/knowledge": ("/search", KNOWLEDGE_SERVICE_URL, 10),
    "/api/knowledge": ("/search", KNOWLEDGE_SERVICE_URL, 10),
    "/scenario": ("/scenario", KNOWLEDGE_SERVICE_URL, 10),
    "/api/scenario": ("/scenario", KNOWLEDGE_SERVICE_URL, 10),
    "/knowledge/add": ("/add", KNOWLEDGE_SERVICE_URL, 10),
    "/api/knowledge/add": ("/add", KNOWLEDGE_SERVICE_URL, 10),
    "/chat": ("/chat", CHAT_SERVICE_URL, 180),
    "/api/chat": ("/chat", CHAT_SERVICE_URL, 180),
    "/agent": ("/chat", CHAT_SERVICE_URL, 180),
    "/api/agent": ("/chat", CHAT_SERVICE_URL, 180),
    "/memory": ("/memory", CHAT_SERVICE_URL, 10),
    "/api/memory": ("/memory", CHAT_SERVICE_URL, 10),
    "/memory/delete": ("/memory/delete", CHAT_SERVICE_URL, 10),
    "/api/memory/delete": ("/memory/delete", CHAT_SERVICE_URL, 10),
    "/todos/create": ("/todos/create", INVENTORY_SERVICE_URL, 10),
    "/api/todos/create": ("/todos/create", INVENTORY_SERVICE_URL, 10),
    "/todos/update": ("/todos/update", INVENTORY_SERVICE_URL, 10),
    "/api/todos/update": ("/todos/update", INVENTORY_SERVICE_URL, 10),
    "/todos/toggle": ("/todos/toggle", INVENTORY_SERVICE_URL, 10),
    "/api/todos/toggle": ("/todos/toggle", INVENTORY_SERVICE_URL, 10),
    "/todos/delete": ("/todos/delete", INVENTORY_SERVICE_URL, 10),
    "/api/todos/delete": ("/todos/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/floor/create": ("/floor/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/floor/create": ("/floor/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/floor/delete": ("/floor/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/floor/delete": ("/floor/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/zone/create": ("/zone/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/zone/create": ("/zone/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/zone/delete": ("/zone/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/zone/delete": ("/zone/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/rack/create": ("/rack/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/rack/create": ("/rack/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/rack/delete": ("/rack/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/rack/delete": ("/rack/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/level/create": ("/level/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/level/create": ("/level/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/level/delete": ("/level/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/level/delete": ("/level/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/cell/create": ("/cell/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/cell/create": ("/cell/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/cell/delete": ("/cell/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/cell/delete": ("/cell/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/custom-location/create": ("/custom-location/create", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/custom-location/create": ("/custom-location/create", INVENTORY_SERVICE_URL, 10),
    "/inventory/custom-location/delete": ("/custom-location/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/custom-location/delete": ("/custom-location/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/product/bind": ("/product/bind", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/product/bind": ("/product/bind", INVENTORY_SERVICE_URL, 10),
    "/inventory/product/unbind": ("/product/unbind", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/product/unbind": ("/product/unbind", INVENTORY_SERVICE_URL, 10),
    "/inventory/adjust": ("/adjust", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/adjust": ("/adjust", INVENTORY_SERVICE_URL, 10),
    "/inventory/product/image/upload": ("/product/image/upload", INVENTORY_SERVICE_URL, 30),
    "/api/inventory/product/image/upload": ("/product/image/upload", INVENTORY_SERVICE_URL, 30),
    "/inventory/product/image/delete": ("/product/image/delete", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/product/image/delete": ("/product/image/delete", INVENTORY_SERVICE_URL, 10),
    "/inventory/product/location/update": ("/product/location/update", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/product/location/update": ("/product/location/update", INVENTORY_SERVICE_URL, 10),
    "/batch-price": ("/batch-price", SEARCH_SERVICE_URL, 15),
    "/alias/bind": ("/alias/bind", SEARCH_SERVICE_URL, 10),
    "/api/alias/bind": ("/alias/bind", SEARCH_SERVICE_URL, 10),
    "/alias/rebind": ("/alias/rebind", SEARCH_SERVICE_URL, 10),
    "/api/alias/rebind": ("/alias/rebind", SEARCH_SERVICE_URL, 10),
    # I24：条码解绑（回滚专用）——本地先生效的绑定在上游校验没过时要能撤回
    "/alias/unbind": ("/alias/unbind", SEARCH_SERVICE_URL, 10),
    "/api/alias/unbind": ("/alias/unbind", SEARCH_SERVICE_URL, 10),
    "/inventory/checkout": ("/api/inventory/checkout", INVENTORY_SERVICE_URL, 15),
    "/api/inventory/checkout": ("/api/inventory/checkout", INVENTORY_SERVICE_URL, 15),
    "/api/batch-price": ("/batch-price", SEARCH_SERVICE_URL, 15),
    "/inventory/low-stock": ("/low-stock", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/low-stock": ("/low-stock", INVENTORY_SERVICE_URL, 10),
    "/inventory/zero-stock": ("/zero-stock", INVENTORY_SERVICE_URL, 10),
    "/api/inventory/zero-stock": ("/zero-stock", INVENTORY_SERVICE_URL, 10),
    "/api/print/printers": ("/api/print/printers", PRINT_SERVICE_URL, 10),
    "/api/print/printers/delete": ("/api/print/printers/delete", PRINT_SERVICE_URL, 10),
    "/api/print/ping": ("/api/print/ping", PRINT_SERVICE_URL, 10),
    "/api/print/job": ("/api/print/job", PRINT_SERVICE_URL, 15),
    "/api/print/jobs/clear": ("/api/print/jobs/clear", PRINT_SERVICE_URL, 10),
    "/api/print/send": ("/print/send", PRINT_SERVICE_URL, 15),
    "/api/print/print": ("/print", PRINT_SERVICE_URL, 15),
    "/api/print/discover": ("/print/discover", PRINT_SERVICE_URL, 30),
    "/api/print/test": ("/print/test", PRINT_SERVICE_URL, 15),
    "/api/print/queue": ("/print/queue", PRINT_SERVICE_URL, 10),
    "/api/print/queue/cancel": ("/print/queue/cancel", PRINT_SERVICE_URL, 10),
    "/api/print/templates": ("/api/print/templates", PRINT_SERVICE_URL, 10),
    "/api/print/scan": ("/print/discover", PRINT_SERVICE_URL, 30),
    "/asr/recognize": ("/recognize", ASR_SERVICE_URL, 30),
    "/api/asr/recognize": ("/recognize", ASR_SERVICE_URL, 30),
}

ROUTES_PROXY_DELETE = {
    "/api/print/printers": ("/api/print/printers", PRINT_SERVICE_URL, 10),
}
