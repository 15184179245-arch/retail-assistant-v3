#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""mirror 服务（S13 老王数据全景）：APP Room 孤岛表的单向镜像 + 五域总览聚合。

- POST /push     ：APP MirrorWorker 推送孤岛表快照 → data/mirror.db（UPSERT 幂等；单向，永不回写 APP）
- GET  /overview ：五域聚合（账/客/货/治/行），bills 口径与晨报 laowang_report 一致
- GET  /health

红线（S13 卡）：镜像单向 APP→后端；照片只存文件名不收本体；不碰 bills/wujin/inventory/chat 四库的写。
SQL 逐表内联字面量 + 参数绑定（Mimosa 口径：execute 处必须是语句字面量）。
"""
import json
import logging
import os
import sqlite3
import sys
import threading
import time
from datetime import date
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from urllib.parse import urlparse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, load_env, setup_logging  # noqa: E402
from utils.logging import set_request_id  # noqa: E402

load_env()
setup_logging("mirror")
set_request_id("mirror")

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MIRROR_DB = os.environ.get("MIRROR_DB_PATH") or os.path.join(_ROOT, "data", "mirror.db")
BILLS_DB = os.environ.get("retaildemo_BILLS_DB") or os.environ.get("BILLS_DB_PATH") or os.path.join(_ROOT, "data", "bills.db")
WUJIN_DB = os.environ.get("WUJIN_DB_PATH") or os.path.join(_ROOT, "data", "wujin.db")
CHAT_DB = os.environ.get("CHAT_DB_PATH") or os.path.join(_ROOT, "data", "chat.db")

_LOCK = threading.Lock()

def _f6c(p, base=None):
    """F6 路径守卫：realpath 归一 + 根前缀 + .. 分量显式检查，返回值即落盘路径。"""
    import os as _os
    import tempfile as _tf
    _roots = [(_os.path.realpath(base or _os.environ.get("retaildemo_PATH_BASE") or _os.getcwd())),
              (_os.path.normcase(_tf.gettempdir()))]
    _rp = _os.path.normpath(p)
    if not _os.path.isabs(_rp):
        _rp = _os.path.normpath(_os.path.join(_roots[0], _rp))
    _rp_n = _os.path.normcase(_rp)
    for _root in _roots:
        _rn = _os.path.normcase(_root)
        if _rp_n == _rn or _rp_n.startswith(_rn + _os.sep):
            if ".." not in _rp_n[len(_rn):].split(_os.sep):
                return _rp
    raise ValueError("path outside sandbox: " + _rp)


def init_db(path=MIRROR_DB):
    path = _f6c(path, base=_ROOT)  # F6 path guard
    os.makedirs(os.path.dirname(path), exist_ok=True)
    con = sqlite3.connect(path)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("""CREATE TABLE IF NOT EXISTS temp_products (temp_code TEXT, type TEXT, name TEXT, spec TEXT,
       sell_price TEXT, cost_price TEXT, note TEXT, source_vendor TEXT, strade_price TEXT,
       reimburse_status TEXT, created_at TEXT, PRIMARY KEY (temp_code))""")
    con.execute("""CREATE TABLE IF NOT EXISTS data_marks (id TEXT, sku TEXT, barcode TEXT, name TEXT,
       mark_type TEXT, note TEXT, status TEXT, created_at TEXT, PRIMARY KEY (id))""")
    con.execute("""CREATE TABLE IF NOT EXISTS product_supplements (sku TEXT, name TEXT, brand TEXT, spec TEXT,
       note TEXT, pending_barcode TEXT, updated_at TEXT, PRIMARY KEY (sku))""")
    con.execute("""CREATE TABLE IF NOT EXISTS product_views (barcode TEXT, name TEXT, spec TEXT, sell_price TEXT,
       viewed_at TEXT, PRIMARY KEY (barcode))""")
    con.execute("CREATE TABLE IF NOT EXISTS search_history (keyword TEXT, searched_at TEXT, PRIMARY KEY (keyword))")
    con.execute("CREATE TABLE IF NOT EXISTS bill_draft (id TEXT, customer_name TEXT, items TEXT, saved_at TEXT, PRIMARY KEY (id))")
    con.execute("""CREATE TABLE IF NOT EXISTS customers (id TEXT, name TEXT, phone TEXT, credit_limit TEXT,
       pending_sync TEXT, PRIMARY KEY (id))""")
    con.commit()
    con.close()


def _row_vals(row, keys):
    """dict 行 → 按列序取值的 tuple；照片类字段本就是文件名字符串，不收本体。"""
    return tuple(str(row.get(k)) if row.get(k) is not None else None for k in keys)


def handle_push(payload: dict, path=MIRROR_DB) -> dict:
    """接收 APP 快照并 UPSERT。白名单外的表静默忽略（APP 先于服务端升级时平滑）。"""
    tables = payload.get("tables") or {}
    if not isinstance(tables, dict):
        raise ValueError("tables must be an object")
    written = {}
    con = sqlite3.connect(path)
    cur = con.cursor()
    try:
        with _LOCK:
            for name, rows in tables.items():
                if not isinstance(rows, list):
                    continue
                # 每表内联字面量 SQL（值全部走 ? 绑定），rowcount=写入行数
                if name == "temp_products":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO temp_products VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        [_row_vals(r, ["temp_code", "type", "name", "spec", "sell_price", "cost_price",
                                       "note", "source_vendor", "strade_price", "reimburse_status",
                                       "created_at"]) for r in rows if isinstance(r, dict)]).rowcount
                elif name == "data_marks":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO data_marks VALUES (?,?,?,?,?,?,?,?)",
                        [_row_vals(r, ["id", "sku", "barcode", "name", "mark_type",
                                       "note", "status", "created_at"])
                         for r in rows if isinstance(r, dict)]).rowcount
                elif name == "product_supplements":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO product_supplements VALUES (?,?,?,?,?,?,?)",
                        [_row_vals(r, ["sku", "name", "brand", "spec", "note",
                                       "pending_barcode", "updated_at"])
                         for r in rows if isinstance(r, dict)]).rowcount
                elif name == "product_views":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO product_views VALUES (?,?,?,?,?)",
                        [_row_vals(r, ["barcode", "name", "spec", "sell_price", "viewed_at"])
                         for r in rows if isinstance(r, dict)]).rowcount
                elif name == "search_history":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO search_history VALUES (?,?)",
                        [_row_vals(r, ["keyword", "searched_at"])
                         for r in rows if isinstance(r, dict)]).rowcount
                elif name == "bill_draft":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO bill_draft VALUES (?,?,?,?)",
                        [_row_vals(r, ["id", "customer_name", "items", "saved_at"])
                         for r in rows if isinstance(r, dict)]).rowcount
                elif name == "customers":
                    written[name] = cur.executemany(
                        "INSERT OR REPLACE INTO customers VALUES (?,?,?,?,?)",
                        [_row_vals(r, ["id", "name", "phone", "credit_limit", "pending_sync"])
                         for r in rows if isinstance(r, dict)]).rowcount
            con.commit()
    finally:
        con.close()
    return {"written": written, "device": str(payload.get("device") or "unknown")}


def _day_prefix(d):
    return d.strftime("%Y-%m-%d")


def _ro(path):
    return sqlite3.connect(_f6c(os.path.abspath(path), base=_ROOT))  # F6 path guard


def _agg_bills(bills_path=BILLS_DB):
    """账域聚合。口径与晨报 laowang_report 一致：欠款=pending 单 (total-paid) 按客户聚合。"""
    out = {"today_paid_count": 0, "today_paid_amount": 0.0, "pending_count": 0,
           "debt_total": 0.0, "debt_top": []}
    if not os.path.exists(bills_path):
        return out
    today = _day_prefix(date.today())
    debts = {}
    con = _ro(bills_path)
    try:
        rows = con.execute("SELECT data_json FROM bills").fetchall()
    finally:
        con.close()
    for (raw,) in rows:
        try:
            b = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        status = b.get("status", "")
        try:
            total = float(b.get("total", 0) or 0)
            paid = float(b.get("paidAmount", 0) or 0)
        except (TypeError, ValueError):
            total, paid = 0.0, 0.0
        created = str(b.get("created_at") or "")
        if status == "paid" and created.startswith(today):
            out["today_paid_count"] += 1
            out["today_paid_amount"] += paid or total
        elif status == "pending":
            out["pending_count"] += 1
            name = (b.get("customer_name") or "散客").strip() or "散客"
            debts[name] = debts.get(name, 0.0) + max(total - paid, 0.0)
    out["debt_total"] = round(sum(debts.values()), 2)
    out["debt_top"] = [{"name": n, "amount": round(v, 2)}
                       for n, v in sorted(debts.items(), key=lambda kv: -kv[1])[:3]]
    return out


def _agg_goods(wujin_path=WUJIN_DB):
    """货域：在售/零库存/冻结（wujin.db 只读语义）。"""
    out = {"active": 0, "zero_stock": 0, "frozen": 0}
    if not os.path.exists(wujin_path):
        return out
    con = _ro(wujin_path)
    try:
        out["active"] = con.execute(
            "SELECT COUNT(*) FROM products WHERE sale_status='active'").fetchone()[0]
        out["zero_stock"] = con.execute(
            "SELECT COUNT(*) FROM products WHERE sale_status='active' AND COALESCE(stock_qty,0)<=0").fetchone()[0]
        out["frozen"] = con.execute(
            "SELECT COUNT(*) FROM products WHERE sale_status='frozen'").fetchone()[0]
    except sqlite3.Error:
        pass
    finally:
        con.close()
    return out


def _agg_governance(mirror_path=MIRROR_DB, wujin_path=WUJIN_DB):
    """治域：待审标记/补录/串货未回写（mirror）+ 副码绑定（wujin）。governance op 同口径。"""
    out = {"pending_marks": 0, "supplements": 0, "temp_total": 0,
           "temp_pending_writeback": 0, "barcode_alias": 0}
    if os.path.exists(mirror_path):
        con = _ro(mirror_path)
        try:
            out["pending_marks"] = con.execute(
                "SELECT COUNT(*) FROM data_marks WHERE status='pending'").fetchone()[0]
            out["supplements"] = con.execute(
                "SELECT COUNT(*) FROM product_supplements").fetchone()[0]
            out["temp_total"] = con.execute(
                "SELECT COUNT(*) FROM temp_products").fetchone()[0]
            out["temp_pending_writeback"] = con.execute(
                "SELECT COUNT(*) FROM temp_products WHERE reimburse_status='pending'").fetchone()[0]
        except sqlite3.Error:
            pass
        finally:
            con.close()
    if os.path.exists(wujin_path):
        con = _ro(wujin_path)
        try:
            out["barcode_alias"] = con.execute(
                "SELECT COUNT(*) FROM barcode_alias").fetchone()[0]
        except sqlite3.Error:
            pass
        finally:
            con.close()
    return out


def _agg_behavior(mirror_path=MIRROR_DB, wujin_path=WUJIN_DB):
    """行为域：今日查询数/未命中 Top（usage_stats）+ 最近浏览/高频搜索（mirror）。"""
    out = {"today_searches": 0, "zero_hit_top": [], "recent_views": [], "top_searches": []}
    if os.path.exists(wujin_path):
        con = _ro(wujin_path)
        try:
            today = _day_prefix(date.today())
            out["today_searches"] = con.execute(
                "SELECT COUNT(*) FROM usage_stats WHERE tool_name='search' AND ts LIKE ?",
                (f"{today}%",)).fetchone()[0]
            zr = con.execute(
                "SELECT keyword, COUNT(*) c FROM usage_stats WHERE result_count=0 "
                "GROUP BY keyword ORDER BY c DESC LIMIT 3").fetchall()
            out["zero_hit_top"] = [{"keyword": k, "count": c} for k, c in zr]
        except sqlite3.Error:
            pass
        finally:
            con.close()
    if os.path.exists(mirror_path):
        con = _ro(mirror_path)
        try:
            out["recent_views"] = [r[0] for r in con.execute(
                "SELECT name FROM product_views ORDER BY viewed_at DESC LIMIT 3").fetchall()]
            out["top_searches"] = [r[0] for r in con.execute(
                "SELECT keyword FROM search_history ORDER BY searched_at DESC LIMIT 3").fetchall()]
        except sqlite3.Error:
            pass
        finally:
            con.close()
    return out


def build_overview(mirror_path=MIRROR_DB, bills_path=BILLS_DB, wujin_path=WUJIN_DB) -> dict:
    bills = _agg_bills(bills_path)
    customers = {"total": 0, "temp": 0, "draft_open": 0}
    if os.path.exists(mirror_path):
        con = _ro(mirror_path)
        try:
            customers["total"] = con.execute("SELECT COUNT(*) FROM customers").fetchone()[0]
            customers["temp"] = con.execute(
                "SELECT COUNT(*) FROM customers WHERE LOWER(pending_sync) IN ('1','true')").fetchone()[0]
            customers["draft_open"] = con.execute(
                "SELECT COUNT(*) FROM bill_draft").fetchone()[0]
        except sqlite3.Error:
            pass
        finally:
            con.close()
    return {
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "账": {"今日已出库单数": bills["today_paid_count"], "今日已出库金额": bills["today_paid_amount"],
              "挂账单数": bills["pending_count"], "欠款合计": bills["debt_total"],
              "欠款Top3": bills["debt_top"], "草稿未完成": customers["draft_open"]},
        "客": {"客户总数": customers["total"], "临时客数": customers["temp"]},
        "货": _agg_goods(wujin_path),
        "治": _agg_governance(mirror_path, wujin_path),
        "行": _agg_behavior(mirror_path, wujin_path),
    }


class MirrorHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        logging.info("%s - %s" % (self.address_string(), format % args))

    def _send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        set_request_id(time.strftime("%H%M%S") + "-m")
        path = urlparse(self.path).path
        if path == "/health":
            self._send_json(ok_response({"status": "healthy", "service": "mirror"}))
        elif path in ("/overview", "/laowang/overview"):
            try:
                self._send_json(ok_response(build_overview()))
            except Exception as e:
                self._send_json(err_response(f"overview failed: {e}", code="MIRROR_OVERVIEW_ERR"), status=500)
        else:
            self._send_json(err_response("not found", code="NOT_FOUND"), status=404)

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/push":
            self._send_json(err_response("not found", code="NOT_FOUND"), status=404)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            payload = json.loads(raw.decode("utf-8"))
            init_db()
            self._send_json(ok_response(handle_push(payload)))
        except (ValueError, json.JSONDecodeError) as e:
            self._send_json(err_response(f"bad push payload: {e}", code="MIRROR_PUSH_ERR"), status=400)
        except Exception as e:
            self._send_json(err_response(f"push failed: {e}", code="MIRROR_PUSH_ERR"), status=500)


class ThreadedMirrorHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def run(host=None, port=None):
    host = host or os.environ.get("MIRROR_HOST", "127.0.0.1")
    port = int(port or os.environ.get("MIRROR_PORT", "18606"))
    init_db()
    logging.info("mirror listening on %s:%s (db=%s)", host, port, MIRROR_DB)
    ThreadedMirrorHTTPServer((host, port), MirrorHandler).serve_forever()


if __name__ == "__main__":
    run()
