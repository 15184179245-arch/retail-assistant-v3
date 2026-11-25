import json
import logging
import sqlite3
from gateway.router import BILLS_DB_PATH


class BillsLoadError(RuntimeError):
    """读账单库失败。

    M1（2026-09-11）：调用方**必须中止**，绝不可把它当成「空库」继续走读-改-写，
    否则写回时会把整张表覆盖成本次入参的内容。
    """


def _ts_key(value) -> str:
    """时间戳归一化成可字典序比较的字符串。

    库里混着两种格式（本地 `2026-09-10 18:41:58` 与旧 ISO `2026-07-10T06:44:19.544Z`），
    直接比较字符串会因为第 10 位的 ' '(0x20) < 'T'(0x54) 得出错误的新旧判断。
    这里统一成 `YYYY-MM-DD HH:MM:SS` 再比；无法识别的原样返回（不崩）。
    """
    s = str(value or "").strip()
    if not s:
        return ""
    s = s.replace("T", " ").replace("Z", "").replace("z", "")
    if "." in s:
        s = s.split(".", 1)[0]
    return s.strip()


def load_bills(*, raise_on_error: bool = False) -> dict:
    """读全部账单 {id: bill_json}。

    raise_on_error=False 保留历史行为（失败返回 {}），仅供**只读展示**路径使用；
    任何「读-改-写」路径必须传 True（M1）。
    """
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT id, data_json FROM bills ORDER BY updated_at DESC").fetchall()
        return {row["id"]: json.loads(row["data_json"]) for row in rows}
    except Exception as e:
        logging.error(f"bills load failed: {e}")
        if raise_on_error:
            raise BillsLoadError(str(e)) from e
        return {}
    finally:
        # 2026-09-11 bug猎杀：execute/json 解析抛错也要关连接（原实现只在正常路径 close）
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def save_bills(bills: dict):
    """按 id 逐条 upsert，**永不 DELETE 全表**。

    M1（2026-09-11）修复：原实现是 `DELETE FROM bills` + 全量重插，两条路径都会吃掉历史——
    ① 两台设备并发推送，各自「读-改-写」，后写的把先写的那批整表删掉；
    ② 读库失败时 load_bills 静默返回 {}，重插等于把整表清空、只留本次推上来的单。
    改成 ON CONFLICT(id) DO UPDATE 后，写入是 id 粒度，任何时刻都不会误删别人写的单。

    失败一律抛出（不再只记日志）：静默失败会让 APP 误以为同步成功并清掉待推标记，数据一样会丢。
    """
    if not bills:
        return
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH, isolation_level=None, timeout=10)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("BEGIN IMMEDIATE")
        for bid, bill_data in bills.items():
            conn.execute(
                "INSERT INTO bills (id, data_json, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET "
                "data_json = excluded.data_json, updated_at = excluded.updated_at",
                (bid, json.dumps(bill_data, ensure_ascii=False),
                 bill_data.get("updated_at", bill_data.get("created_at", "")))
            )
        conn.execute("COMMIT")
    except Exception as e:
        if conn:
            try:
                conn.execute("ROLLBACK")
            except Exception:
                pass
        logging.error(f"bills save failed: {e}")
        raise
    finally:
        if conn:
            conn.close()


def merge_bills(incoming: list) -> dict:
    """上行合并：按 updatedAt last-writer-wins，只写本次的赢家。

    M1：三处收紧——
    ① 读库失败 `raise_on_error=True` 直接抛，交给 handler 返回错误（原实现当空库→清表）；
    ② `save_bills` 只收本次的赢家 winners，不再把「刚才读到的全库快照」回写一遍，
       否则并发场景下会用陈旧快照覆盖别人刚写的同 id 新数据；
    ③ 时间戳走 `_ts_key` 归一化比较，消除两种格式混存的误判。
    """
    bills = load_bills(raise_on_error=True)
    merged = {"updated": [], "skipped": []}
    winners = {}
    for b in incoming:
        bid = b.get("id", "")
        if not bid:
            continue
        existing = bills.get(bid)
        if existing and _ts_key(existing.get("updated_at", existing.get("created_at", ""))) > \
                _ts_key(b.get("updated_at", b.get("created_at", ""))):
            merged["skipped"].append(bid)
            continue
        winners[bid] = b
        merged["updated"].append(bid)
    save_bills(winners)
    return merged


def delete_bill(bill_id: str) -> dict:
    """物理删除服务端账单及关联回执（APP 端真删除闭环）。"""
    bid = str(bill_id or "").strip()
    if not bid:
        return {"success": False, "error": "MISSING_ID", "user_message": "未提供账单 id"}
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH, isolation_level=None, timeout=10)
        cur = conn.execute("DELETE FROM bills WHERE id = ?", (bid,))
        deleted = cur.rowcount > 0
        conn.execute("DELETE FROM settlement_receipts WHERE app_bill_id = ?", (bid,))
        logging.info(f"delete_bill {bid}: deleted={deleted}")
        return {"success": True, "data": {"id": bid, "deleted": deleted}}
    except Exception as e:
        logging.error(f"delete_bill failed for {bid}: {e}")
        return {"success": False, "error": "DB_ERROR", "user_message": str(e)}
    finally:
        if conn:
            conn.close()


def init_bills_db():
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS bills (
                id TEXT PRIMARY KEY,
                data_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_bills_updated ON bills(updated_at)")
        # B13 结算回执表：只记录收银端上报的结算结果，不直接写 bills（APP 作为数据主人负责改写）
        conn.execute("""
            CREATE TABLE IF NOT EXISTS settlement_receipts (
                app_bill_id TEXT PRIMARY KEY,
                bill_code TEXT NOT NULL,
                order_no TEXT NOT NULL,
                settle_state TEXT NOT NULL,
                settle_time TEXT NOT NULL,
                reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_receipts_reported ON settlement_receipts(reported_at)")
        conn.commit()
        logging.info("bills database initialized")
    except Exception as e:
        logging.error(f"bills init failed: {e}")
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


RECEIPT_ALLOWED_FIELDS = frozenset({"app_bill_id", "bill_code", "order_no", "settle_state", "settle_time"})


def save_settlement_receipt(payload: dict) -> dict:
    """B13 结算回执写入：字段白名单 + 幂等（同一 app_bill_id 仅入库首条，不碰 bills）。"""
    if not isinstance(payload, dict):
        return {"success": False, "error": "BAD_PAYLOAD", "user_message": "请求体必须为 JSON 对象"}

    extra = set(payload.keys()) - RECEIPT_ALLOWED_FIELDS
    if extra:
        return {"success": False, "error": "EXTRA_FIELDS",
                "user_message": f"字段越界：只接受 {sorted(RECEIPT_ALLOWED_FIELDS)}，拒绝字段 {sorted(extra)}"}

    missing = RECEIPT_ALLOWED_FIELDS - set(payload.keys())
    if missing:
        return {"success": False, "error": "MISSING_FIELDS",
                "user_message": f"缺少必要字段：{sorted(missing)}"}

    app_bill_id = str(payload.get("app_bill_id") or "").strip()
    bill_code = str(payload.get("bill_code") or "").strip()
    order_no = str(payload.get("order_no") or "").strip()
    settle_state = str(payload.get("settle_state") or "").strip()
    settle_time = str(payload.get("settle_time") or "").strip()

    if not (app_bill_id and bill_code and order_no and settle_state and settle_time):
        return {"success": False, "error": "EMPTY_FIELD",
                "user_message": "回执字段均不能为空字符串"}

    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH, isolation_level=None, timeout=10)
        cur = conn.execute("""
            INSERT OR IGNORE INTO settlement_receipts
            (app_bill_id, bill_code, order_no, settle_state, settle_time)
            VALUES (?, ?, ?, ?, ?)
        """, (app_bill_id, bill_code, order_no, settle_state, settle_time))
        inserted = cur.rowcount > 0
        return {"success": True, "data": {"app_bill_id": app_bill_id, "inserted": inserted}}
    except Exception as e:
        logging.error(f"save_settlement_receipt failed: {e}")
        return {"success": False, "error": "DB_ERROR", "user_message": str(e)}
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def load_settlement_receipts(since: str = None) -> list:
    """B13 增量拉取回执（按 reported_at 增量过滤）。"""
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.row_factory = sqlite3.Row
        if since and since.strip():
            rows = conn.execute("""
                SELECT app_bill_id, bill_code, order_no, settle_state, settle_time, reported_at
                FROM settlement_receipts
                WHERE reported_at > ?
                ORDER BY reported_at ASC
            """, (since.strip(),)).fetchall()
        else:
            rows = conn.execute("""
                SELECT app_bill_id, bill_code, order_no, settle_state, settle_time, reported_at
                FROM settlement_receipts
                ORDER BY reported_at ASC
            """).fetchall()
        return [dict(r) for r in rows]
    except Exception as e:
        logging.error(f"load_settlement_receipts failed: {e}")
        return []
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass
