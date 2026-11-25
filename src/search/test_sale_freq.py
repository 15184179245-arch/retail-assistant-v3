"""05 出库频次挂载单测（/search 收口增强 _attach_sale_freq / _sale_stats_cached）。

只测挂载与缓存这两层（口径本身在 test_sale_stats.py 里测）：
- data 是 list 要挂、是一码多货的 multi 候选包也要挂；
- 没卖过的商品**不加字段**（而不是加 0）；
- sku 不中要退条码（真实库键格式很杂，只认一路必 miss）；
- 失败/异常响应一律原样返回，绝不因为频次把检索打断；
- TTL 内只扫一次账单库。
"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import search.search_server as ss  # noqa: E402


class 挂载Spec(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "bills.db")
        conn = sqlite3.connect(self.db)
        conn.execute("create table bills (id TEXT, data_json TEXT,"
                     " created_at TIMESTAMP, updated_at TIMESTAMP)")
        data = {"id": "b1", "status": "paid", "paid_at": "2026-09-16T02:00:00Z",
                "items": [{"sku": "SKU-A", "quantity": 3}, {"barcode": "BC-B", "qty": 2}]}
        conn.execute("insert into bills values ('b1', ?, null, null)", (json.dumps(data),))
        conn.commit()
        conn.close()

        self._orig_path = ss._bills_db_path
        ss._bills_db_path = lambda: self.db
        self._reset_cache()

    def tearDown(self):
        ss._bills_db_path = self._orig_path
        self._reset_cache()
        self.tmp.cleanup()

    def _reset_cache(self):
        ss._sale_stats_cache["at"] = 0.0
        ss._sale_stats_cache["data"] = {}

    # ---- 挂载 ----

    def test_列表结果挂载频次(self):
        res = {"success": True, "data": [{"sku": "SKU-A", "name": "弯头"}]}
        out = ss._attach_sale_freq(res)
        p = out["data"][0]
        self.assertEqual(p["last_sale_qty"], 3.0)
        self.assertEqual(p["last_sale_day"], "2026-09-16")

    def test_一码多货候选包也挂载(self):
        res = {"success": True, "data": {"multi": True, "candidates": [{"barcode": "BC-B"}]}}
        out = ss._attach_sale_freq(res)
        self.assertEqual(out["data"]["candidates"][0]["last_sale_qty"], 2.0)

    def test_没卖过不加字段(self):
        res = {"success": True, "data": [{"sku": "从未卖过", "barcode": "也没卖过"}]}
        p = ss._attach_sale_freq(res)["data"][0]
        self.assertNotIn("last_sale_qty", p, "无记录不加字段，而不是加 0")
        self.assertNotIn("last_sale_day", p)

    def test_sku不中退条码(self):
        res = {"success": True, "data": [{"sku": "别的编码", "barcode": "BC-B"}]}
        p = ss._attach_sale_freq(res)["data"][0]
        self.assertEqual(p["last_sale_qty"], 2.0, "账单键是条码时，靠商品条码也能命中")

    def test_空结果与脏数据不崩(self):
        self.assertEqual(ss._attach_sale_freq({"success": True, "data": []}),
                         {"success": True, "data": []})
        self.assertEqual(ss._attach_sale_freq({"success": True, "data": "字符串"}),
                         {"success": True, "data": "字符串"})
        res = {"success": True, "data": [None, {"sku": None}, {"sku": "  "}, "不是字典"]}
        ss._attach_sale_freq(res)  # 不抛即可

    # ---- 失败降级 ----

    def test_失败响应原样返回(self):
        res = {"success": False, "data": [{"sku": "SKU-A"}]}
        out = ss._attach_sale_freq(res)
        self.assertNotIn("last_sale_qty", out["data"][0], "失败响应不该被动过")

    def test_算不出来沿用旧快照且不外抛(self):
        ss._attach_sale_freq({"success": True, "data": [{"sku": "SKU-A"}]})  # 先暖一份快照
        old = ss._bills_db_path
        ss._bills_db_path = lambda: (_ for _ in ()).throw(RuntimeError("模拟账单库炸了"))
        try:
            ss._sale_stats_cache["at"] = 0.0  # 让快照过期，强制重算
            res = {"success": True, "data": [{"sku": "SKU-A"}]}
            out = ss._attach_sale_freq(res)  # 不该抛
            self.assertEqual(out["data"][0]["last_sale_qty"], 3.0, "失败时沿用旧快照")
        finally:
            ss._bills_db_path = old

    # ---- 缓存 ----

    def test_TTL内只扫一次账单库(self):
        calls = {"n": 0}
        real = ss.load_sale_stats

        def counting(*a, **kw):
            calls["n"] += 1
            return real(*a, **kw)

        ss.load_sale_stats = counting
        try:
            for _ in range(5):
                ss._attach_sale_freq({"success": True, "data": [{"sku": "SKU-A"}]})
        finally:
            ss.load_sale_stats = real
        self.assertEqual(calls["n"], 1, "5 次检索只应扫 1 次账单库")


if __name__ == "__main__":
    unittest.main()
