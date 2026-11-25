#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排雷①（2026-09-10）：product_cache 同步自净——上游已删商品在缓存剔除。

事故口径：cache 只增不清曾膨胀到 31765 行 vs 上游全集 12631，
货位搜索/绑定还能搜到上游已删商品。
"""
import os
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from inventory import inventory_server as inv  # noqa: E402


class CacheSyncPruneTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="sx-cachesync-")
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
        w = sqlite3.connect(cls.wujin)
        w.execute(
            "CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, sku TEXT, barcode TEXT, spec TEXT, "
            "sell_price REAL, cost_price REAL, stock_qty INTEGER, unit TEXT, location TEXT)"
        )
        w.execute("INSERT INTO products VALUES (1,'角阀','S1','B1','',2,1,10,'个','')")
        w.execute("INSERT INTO products VALUES (2,'生料带','S2','B2','',1,0.5,5,'卷','')")
        w.commit()
        w.close()
        inv.init_db()
        db = inv.get_db()
        for row in (
            (1, "角阀", "S1", "B1", 2, 1, 10, "个"),
            (2, "生料带", "S2", "B2", 1, 0.5, 5, "卷"),
            (999, "上游已删的陈旧品", "S999", "B999", 1, 1, 7, "个"),
        ):
            db.execute(
                "INSERT OR REPLACE INTO product_cache (id, name, sku, barcode, spec, sell_price, cost_price, "
                "stock_qty, unit, location, updated_at) VALUES (?, ?, ?, ?, '', ?, ?, ?, ?, '', CURRENT_TIMESTAMP)",
                row,
            )
        db.commit()

    def test_sync_prunes_stale_and_keeps_live(self):
        with mock.patch.dict(os.environ, {"WUJIN_DB_PATH": self.wujin}):
            inv.sync_product_cache()
        db = inv.get_db()
        ids = {r["id"] for r in db.execute("SELECT id FROM product_cache")}
        self.assertEqual(ids, {1, 2}, "上游已删的 999 号必须被剔除")
        row = db.execute("SELECT stock_qty FROM product_cache WHERE id=1").fetchone()
        self.assertEqual(row["stock_qty"], 10, "存活商品照常刷新")

    def test_sync_idempotent(self):
        with mock.patch.dict(os.environ, {"WUJIN_DB_PATH": self.wujin}):
            inv.sync_product_cache()
            inv.sync_product_cache()
        db = inv.get_db()
        n = db.execute("SELECT COUNT(*) c FROM product_cache").fetchone()["c"]
        self.assertEqual(n, 2)

    def test_源库打不开时安全降级且报原始错误(self):
        """I30/A3 回归：源库阶段失败时不得引用未绑定的 db（NameError 不得掩盖原始错误）。"""
        with mock.patch.dict(os.environ, {"WUJIN_DB_PATH": self.tmp}), \
                mock.patch.object(inv.logging, "warning") as warn:
            inv.sync_product_cache()  # 目录不是库 → connect 即失败，函数必须吞下并告警
        self.assertTrue(warn.called, "源库失败必须落告警日志")
        msg = str(warn.call_args)
        self.assertIn("Product sync warning", msg)
        self.assertNotIn("NameError", msg, "原始错误被 NameError 罩住了")


if __name__ == "__main__":
    unittest.main()
