#!/usr/bin/env python3
"""M2/M3（2026-09-11）后端回归：人员名册接口 + 账单 GET 失败语义。

M3：`GET /api/roster` 供 APP 首启「这台机器是谁用」下拉。拍板「全员管理员身份」下
名册**不是权限系统**——所有店端设备可读，只列 active，且绝不下发 password_hash。

M2：`GET /api/bills/sync` 读库失败必须返回 **503**，不能返回 `{}` 冒充「服务器没单」。
否则重装后的「从服务器恢复」会显示「已恢复 0 条」，店员以为账真没了（原实现如此）。

运行：python -m unittest gateway.test_roster_and_bills_get -v
"""
import io
import json
import os
import sqlite3
import tempfile
import unittest

from gateway import auth, bills_sync
from gateway import handler as handler_mod
from gateway.handler import GatewayHandler


class _DummyHandler(GatewayHandler):
    def __init__(self, path, method="GET", body=None):
        self.command = method
        self.path = path
        self.headers = {}
        self.client_address = ("127.0.0.1", 12345)
        if body:
            raw = json.dumps(body).encode("utf-8")
            self.headers["Content-Length"] = str(len(raw))
            self.headers["Content-Type"] = "application/json"
            self.rfile = io.BytesIO(raw)
        else:
            self.rfile = io.BytesIO()
        self.wfile = io.BytesIO()
        self.sent_status = 200
        self.sent_headers = {}

    def send_response(self, code, message=None):
        self.sent_status = code

    def send_header(self, keyword, value):
        self.sent_headers[keyword.lower()] = value

    def end_headers(self):
        pass


class _BillsDbCase(unittest.TestCase):
    def setUp(self):
        self._orig = {
            "bills_sync": bills_sync.BILLS_DB_PATH,
            "auth": auth.BILLS_DB_PATH,
            "handler": handler_mod.BILLS_DB_PATH,
            "token": auth.AUTH_TOKEN,
        }
        fd, self.db_path = tempfile.mkstemp(suffix=".db", prefix="m23_")
        os.close(fd)
        for mod in (bills_sync, auth, handler_mod):
            mod.BILLS_DB_PATH = self.db_path
        auth.AUTH_TOKEN = ""          # 私服模式：匿名 = 本地管理员
        bills_sync.init_bills_db()

    def tearDown(self):
        bills_sync.BILLS_DB_PATH = self._orig["bills_sync"]
        auth.BILLS_DB_PATH = self._orig["auth"]
        handler_mod.BILLS_DB_PATH = self._orig["handler"]
        auth.AUTH_TOKEN = self._orig["token"]
        for suffix in ("", "-wal", "-shm"):
            p = self.db_path + suffix
            if os.path.exists(p):
                try:
                    os.remove(p)
                except OSError:
                    pass

    def seed_employees(self, rows):
        con = sqlite3.connect(self.db_path)
        con.execute("""
            CREATE TABLE IF NOT EXISTS employees (
                id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL, display_name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'cashier',
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        for i, (eid, uname, disp, role, status) in enumerate(rows):
            con.execute(
                "INSERT INTO employees (id, username, password_hash, display_name, role, status) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (eid, uname, "scrypt$fake$hash", disp, role, status),
            )
        con.commit()
        con.close()


class RosterTest(_BillsDbCase):
    def test_lists_active_only(self):
        self.seed_employees([
            ("EMP-1", "laowang", "老王", "cashier", "active"),
            ("EMP-2", "xiaoli", "小李", "cashier", "active"),
            ("EMP-3", "lisi", "李四", "cashier", "disabled"),
        ])
        res = auth._get_roster({})
        self.assertTrue(res["success"])
        names = [r["display_name"] for r in res["data"]]
        self.assertEqual(["老王", "小李"], names, "名册必须只含在职人员")

    def test_never_exposes_password_hash(self):
        self.seed_employees([("EMP-1", "laowang", "老王", "cashier", "active")])
        row = auth._get_roster({})["data"][0]
        self.assertEqual({"id", "display_name", "role"}, set(row.keys()),
                         "名册不得下发 password_hash 等敏感列")

    def test_missing_table_degrades_without_crash(self):
        """employees 表不存在（全新库）时给结构化失败，不抛异常炸路由。"""
        res = auth._get_roster({})
        self.assertFalse(res["success"])
        self.assertIn("user_message", res)

    def test_route_returns_ok(self):
        self.seed_employees([("EMP-1", "laowang", "老王", "cashier", "active")])
        h = _DummyHandler("/api/roster", "GET")
        h.do_GET()
        self.assertEqual(200, h.sent_status)
        resp = json.loads(h.wfile.getvalue().decode("utf-8"))
        self.assertTrue(resp["success"])
        self.assertEqual("老王", resp["data"][0]["display_name"])


class BillsGetFailureTest(_BillsDbCase):
    def _bill(self, bid, ts):
        return {"id": bid, "customer_name": "散客", "total": 1.0, "status": "pending",
                "staff": "老王", "created_at": ts, "updated_at": ts, "items": []}

    def test_get_returns_bills_when_readable(self):
        bills_sync.save_bills({"A": self._bill("A", "2026-09-11 09:00:00")})
        h = _DummyHandler("/api/bills/sync", "GET")
        h.do_GET()
        self.assertEqual(200, h.sent_status)
        resp = json.loads(h.wfile.getvalue().decode("utf-8"))
        self.assertIn("A", resp)

    def test_get_returns_503_instead_of_fake_empty(self):
        """核心 M2：读库失败必须 503，不能 200 + {}（会让「恢复」显示 0 条）。"""
        bills_sync.save_bills({"A": self._bill("A", "2026-09-11 09:00:00")})
        # 注意：handler 里的 load_bills 读的是 bills_sync 模块全局，两处都要指坏
        bad = os.path.dirname(self.db_path)
        bills_sync.BILLS_DB_PATH = bad
        handler_mod.BILLS_DB_PATH = bad
        h = _DummyHandler("/api/bills/sync", "GET")
        h.do_GET()
        self.assertEqual(503, h.sent_status, "读库失败被当成「服务器没单」返回了 200")
        resp = json.loads(h.wfile.getvalue().decode("utf-8"))
        self.assertFalse(resp["success"])
        self.assertEqual("BILLS_UNAVAILABLE", resp.get("code"))


class OwnerIdStampTest(_BillsDbCase):
    def test_post_stamps_owner_id(self):
        """M3：POST 上行必须打 owner_id（归属一等字段），改名不断链。"""
        h = _DummyHandler("/api/bills/sync", "POST",
                          body={"bills": [{"id": "N1", "total": 5.0, "status": "pending",
                                           "created_at": "2026-09-11 09:00:00",
                                           "updated_at": "2026-09-11 09:00:00", "items": []}]})
        h.do_POST()
        self.assertEqual(200, h.sent_status)
        stored = bills_sync.load_bills()["N1"]
        self.assertTrue(stored.get("owner_id"), "owner_id 未落库")
        self.assertEqual("local-admin", stored["owner_id"])
        self.assertEqual("本地管理员", stored["created_by_display_name"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
