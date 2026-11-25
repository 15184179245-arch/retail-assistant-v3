#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排雷 B1/B2：checkout 失败如实分列 + adjust_stock 的 wujin.db 同步失败显式暴露。

旧缺陷（2026-09-09 排雷前）：
- B2 checkout：adjust 失败项的 data=None 也被 append 进 results 且计入 total，
  整体恒 success=True——缺货/错 ID 永远不会在对账时暴露。
- B1 adjust_stock：wujin.db 3 次同步全败仍按成功返回，两库静默分叉。
"""
import os
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from inventory import inventory_server as inv  # noqa: E402


def _make_wujin(path):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, stock_qty INTEGER, updated_at TEXT)")
    con.execute("INSERT OR REPLACE INTO products (id, stock_qty) VALUES (1, 100)")
    con.commit()
    con.close()


class InventoryCheckoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="sx-inv-test-")
        inv.DB_PATH = os.path.join(cls.tmp, "inventory.db")
        # 同进程多个测试类共用线程本地连接——换库必须重置缓存连接
        old = getattr(inv._local, "conn", None)
        if old is not None:
            try:
                old.close()
            except Exception:
                pass
        inv._local.conn = None
        cls.wujin = os.path.join(cls.tmp, "wujin.db")
        _make_wujin(cls.wujin)
        inv.init_db()
        db = inv.get_db()
        db.execute(
            "INSERT OR REPLACE INTO product_cache (id, name, sku, barcode, spec, sell_price, cost_price, stock_qty, unit, location, updated_at) "
            "VALUES (1, '测试品', 'SKU1', 'BC1', '', 10, 5, 100, '个', '', CURRENT_TIMESTAMP)"
        )
        db.commit()

    def _patch_env(self):
        return mock.patch.dict(os.environ, {"WUJIN_DB_PATH": self.wujin})

    def test_adjust_success_sync_ok_and_wujin_updated(self):
        with self._patch_env():
            r = inv.adjust_stock(1, -3, "sale", "", "tester")
        self.assertTrue(r["success"])
        self.assertEqual(r["data"]["new_stock"], 97)
        self.assertEqual(r["data"]["wujin_sync"], "ok")
        w = sqlite3.connect(self.wujin)
        try:
            self.assertEqual(w.execute("SELECT stock_qty FROM products WHERE id=1").fetchone()[0], 97)
        finally:
            w.close()

    def test_adjust_sync_failure_is_exposed(self):
        bad = os.path.join(self.tmp, "no-such-dir", "wujin.db")
        with mock.patch.dict(os.environ, {"WUJIN_DB_PATH": bad}):
            r = inv.adjust_stock(1, -1, "sale", "", "tester")
        self.assertTrue(r["success"], "本地扣减已入账，整体仍算成功")
        self.assertEqual(r["data"]["wujin_sync"], "failed")
        self.assertTrue(r["data"]["wujin_sync_error"])
        self.assertIn("同步失败", r["message"])

    def test_checkout_all_ok(self):
        with self._patch_env():
            r = inv.checkout_items([{"product_id": 1, "qty": 2}], "tester", "")
        self.assertTrue(r["success"])
        self.assertEqual(r["total"], 1)
        self.assertEqual(r["failed_count"], 0)
        self.assertEqual(r["code"], "OK")

    def test_checkout_mixed_reports_failures(self):
        with self._patch_env():
            r = inv.checkout_items(
                [
                    {"product_id": 1, "qty": 1},          # 成功
                    {"product_id": 99999, "qty": 1},      # 商品不存在
                    {"product_id": None, "qty": 3},       # 无效项
                    {"product_id": 1, "qty": 0},          # 数量无效
                ],
                "tester", "",
            )
        self.assertFalse(r["success"], "旧实现这里恒 success=True（雷点）")
        self.assertEqual(r["succeeded"], 1)
        self.assertEqual(r["failed_count"], 3)
        self.assertEqual(r["code"], "PARTIAL_CHECKOUT")
        self.assertTrue(all(f.get("error") for f in r["failed"]))
        self.assertNotIn(None, r["data"], "失败项不得以 None 混进成功清单")

    def test_checkout_all_failed(self):
        with self._patch_env():
            r = inv.checkout_items([{"product_id": 424242, "qty": 5}], "tester", "")
        self.assertFalse(r["success"])
        self.assertEqual(r["code"], "CHECKOUT_FAILED")
        self.assertEqual(r["total"], 0)


if __name__ == "__main__":
    unittest.main()
