"""sale_status 判定纯函数单测（S2 Step1/Step9）。"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.sale_status import (  # noqa: E402
    decide_sale_status,
    load_inactive_skus,
    load_last_sale_days,
    parse_paid_at,
)

NOW = datetime(2026, 9, 4, 12, 0, 0, tzinfo=timezone.utc)


class DecideSaleStatusSpec(unittest.TestCase):
    def test_有库存即在售(self):
        self.assertEqual(decide_sale_status(5, None), "active")
        self.assertEqual(decide_sale_status(0.5, 9999, upstream_active=False), "active",
                         "库存>0 优先于一切（哪怕上游停用也先在售）")

    def test_近期有销在售(self):
        self.assertEqual(decide_sale_status(0, 179), "active")

    def test_零库存超期冻结(self):
        self.assertEqual(decide_sale_status(0, 180), "frozen", "阈值边界 >= 归 frozen")
        self.assertEqual(decide_sale_status(0, 400), "frozen")
        self.assertEqual(decide_sale_status(0, None), "frozen", "从未卖过也算超期")

    def test_冻结且上游停用为清理候选(self):
        self.assertEqual(decide_sale_status(0, 400, upstream_active=False), "clearable")
        self.assertEqual(decide_sale_status(0, 400, frozen_days=90, upstream_active=False),
                         "clearable")

    def test_阈值可配(self):
        self.assertEqual(decide_sale_status(0, 100, frozen_days=90), "frozen")
        self.assertEqual(decide_sale_status(0, 100, frozen_days=180), "active")

    def test_脏库存值兜底0(self):
        self.assertEqual(decide_sale_status("", None), "frozen")
        self.assertEqual(decide_sale_status("abc", None), "frozen")
        self.assertEqual(decide_sale_status(None, None), "frozen")


class 账单解析Spec(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "bills.db")
        conn = sqlite3.connect(self.path)
        conn.execute("create table bills (id TEXT, data_json TEXT,"
                     " created_at TIMESTAMP, updated_at TIMESTAMP)")

        def bill(bid, status, days_ago, items, paid_at=None):
            when = (NOW - timedelta(days=days_ago)).isoformat()
            data = {"id": bid, "status": status,
                    "paid_at": paid_at if paid_at is not None else when,
                    "items": items}
            conn.execute("insert into bills values (?,?,?,?)",
                         (bid, json.dumps(data), when, when))

        bill("b1", "paid", 10, [{"sku": "192.0.2.10", "barcode": "X1", "quantity": 1}])
        bill("b2", "paid", 200, [{"sku": "192.0.2.10", "barcode": "", "quantity": 2}])
        bill("b3", "pending", 1, [{"sku": "192.0.2.10", "quantity": 1}])
        bill("b4", "paid", 30, [{"barcode": "ONLY-BARCODE", "quantity": 1}])   # 无 sku 退条码
        bill("b5", "paid", 500, [{"sku": "192.0.2.10", "quantity": 1}])     # 更早的一单，不覆盖
        bill("b6", "draft", 2, [{"sku": "192.0.2.10", "quantity": 1}])
        bill("bad", "paid", 1, "不是列表")                                       # 坏数据不崩
        conn.execute("insert into bills values ('nul', '{坏json', null, null)")
        conn.commit()
        conn.close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_最近销售天数(self):
        days = load_last_sale_days(self.path, now=NOW)
        self.assertAlmostEqual(days["192.0.2.10"], 10, places=1,
                               msg="同 sku 多单取最近")
        self.assertAlmostEqual(days["192.0.2.10"], 200, places=1)
        self.assertAlmostEqual(days["ONLY-BARCODE"], 30, places=1, msg="无 sku 退条码键")
        self.assertNotIn("192.0.2.10", days, "pending 不算销售")
        self.assertNotIn("192.0.2.10", days, "draft 不算销售")

    def test_库不存在返回空(self):
        self.assertEqual(load_last_sale_days(self.path + ".nope", now=NOW), {})

    def test_parse_paid_at容错(self):
        self.assertIsNone(parse_paid_at(None))
        self.assertIsNone(parse_paid_at("乱码"))
        dt = parse_paid_at("2026-07-06T08:44:59.083Z")
        self.assertEqual(dt.year, 2026)
        self.assertIsNotNone(dt.tzinfo)


class 停用清单Spec(unittest.TestCase):
    def test_报告缺位退化(self):
        self.assertEqual(load_inactive_skus(None), set())
        self.assertEqual(load_inactive_skus("不存在的.json"), set())

    def test_读inactive段(self):
        tmp = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        try:
            json.dump({"inactive": [{"sku": "A.1", "name": "x"}, {"sku": " "}, {}]}, tmp)
        finally:
            tmp.close()
        got = load_inactive_skus(tmp.name)
        self.assertEqual(got, {"A.1"})
        os.unlink(tmp.name)


if __name__ == "__main__":
    unittest.main()
