# -*- coding: utf-8 -*-
"""S13 mirror 服务单测：push 幂等/白名单 + 五域聚合口径（账与晨报一致、客/货/治/行 对账）。"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest
import datetime

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mirror import mirror_server as ms  # noqa: E402


def _make_bills(path, bills):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE bills (id TEXT, data_json TEXT, created_at TEXT, updated_at TEXT)")
    for bid, b in bills:
        con.execute("INSERT INTO bills VALUES (?,?,?,?)",
                    (bid, json.dumps(b), b["created_at"], b["created_at"]))
    con.commit()
    con.close()


def _make_wujin(path, rows):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, stock_qty INTEGER, sale_status TEXT)")
    con.execute("CREATE TABLE barcode_alias (barcode TEXT PRIMARY KEY, sku TEXT)")
    con.executemany("INSERT INTO products VALUES (?,?,?,?)", rows)
    con.execute("INSERT INTO barcode_alias VALUES ('6901','A1')")
    con.commit()
    con.close()


class MirrorPushTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.mdb = os.path.join(self._tmp.name, "mirror.db")
        ms.init_db(self.mdb)

    def tearDown(self):
        self._tmp.cleanup()

    def test_push写入与未知表白名单忽略(self):
        payload = {
            "device": "test-phone",
            "tables": {
                "temp_products": [{"temp_code": "TMP-1", "type": "strade", "name": "无码货A",
                                   "sell_price": 10.5, "reimburse_status": "pending"}],
                "product_views": [{"barcode": "6901001", "name": "生料带", "viewed_at": 111}],
                "hack_table": [{"evil": "1"}],
            },
        }
        out = ms.handle_push(payload, self.mdb)
        self.assertEqual(out["written"].get("temp_products"), 1)
        self.assertEqual(out["written"].get("product_views"), 1)
        self.assertNotIn("hack_table", out["written"])

    def test_push幂等_重推不翻倍(self):
        payload = {"tables": {"search_history": [{"keyword": "fsjb", "searched_at": 1},
                                                 {"keyword": "sld", "searched_at": 2}]}}
        ms.handle_push(payload, self.mdb)
        out = ms.handle_push(payload, self.mdb)
        self.assertEqual(out["written"]["search_history"], 2)  # REPLACE 仍 2 行
        con = sqlite3.connect(self.mdb)
        n = con.execute("SELECT COUNT(*) FROM search_history").fetchone()[0]
        con.close()
        self.assertEqual(n, 2)

    def test_push坏载荷拒收(self):
        with self.assertRaises(ValueError):
            ms.handle_push({"tables": "not-a-dict"}, self.mdb)


class OverviewAggTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = self._tmp.name
        self.mdb = os.path.join(root, "mirror.db")
        self.bdb = os.path.join(root, "bills.db")
        self.wdb = os.path.join(root, "wujin.db")
        ms.init_db(self.mdb)
        today = ms._day_prefix(datetime.date.today())
        _make_bills(self.bdb, [
            ("b1", {"status": "paid", "total": 100, "paidAmount": 90, "created_at": today + " 10:00:00"}),
            ("b2", {"status": "pending", "total": 50, "paidAmount": 20, "customer_name": "老李",
                    "created_at": today + " 11:00:00"}),
            ("b3", {"status": "pending", "total": 30, "paidAmount": 0, "customer_name": "老李",
                    "created_at": "2020-01-01 09:00:00"}),
        ])
        _make_wujin(self.wdb, [
            (1, "生料带", 5, "active"),
            (2, "弯头", 0, "active"),
            (3, "老库存", 9, "frozen"),
        ])
        ms.handle_push({"tables": {
            "customers": [{"id": "c1", "name": "老李", "pending_sync": 1},
                          {"id": "c2", "name": "散客", "pending_sync": 0}],
            "temp_products": [{"temp_code": "TMP-1", "reimburse_status": "pending", "name": "无码货"}],
            "data_marks": [{"id": "1", "status": "pending", "name": "脏条码"}],
            "product_views": [{"barcode": "b", "name": "生料带", "viewed_at": 2},
                              {"barcode": "a", "name": "弯头", "viewed_at": 9}],
            "bill_draft": [{"id": "1", "customer_name": "老李", "items": "[]", "saved_at": 1}],
        }}, self.mdb)

    def tearDown(self):
        self._tmp.cleanup()

    def test_五域聚合_对账口径(self):
        ov = ms.build_overview(mirror_path=self.mdb, bills_path=self.bdb, wujin_path=self.wdb)
        self.assertEqual(ov["账"]["今日已出库单数"], 1)
        self.assertEqual(ov["账"]["今日已出库金额"], 90)
        self.assertEqual(ov["账"]["挂账单数"], 2)
        # 欠款 = pending 单 (total-paid)：老李 30 + 30 = 60
        self.assertEqual(ov["账"]["欠款合计"], 60)
        self.assertEqual(ov["账"]["欠款Top3"][0]["name"], "老李")
        self.assertEqual(ov["客"]["客户总数"], 2)
        self.assertEqual(ov["客"]["临时客数"], 1)
        self.assertEqual(ov["货"]["active"], 2)
        self.assertEqual(ov["货"]["zero_stock"], 1)
        self.assertEqual(ov["货"]["frozen"], 1)
        self.assertEqual(ov["治"]["pending_marks"], 1)
        self.assertEqual(ov["治"]["temp_pending_writeback"], 1)
        self.assertEqual(ov["治"]["barcode_alias"], 1)
        self.assertEqual(ov["行"]["recent_views"][0], "弯头")  # viewed_at 9 更近
        self.assertEqual(ov["账"]["草稿未完成"], 1)


if __name__ == "__main__":
    unittest.main()
