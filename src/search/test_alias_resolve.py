"""B4 别名解析 GET /alias/resolve —— alias_resolve 单测。

用临时 sqlite 建 barcode_alias 表，monkeypatch search_server.get_db：
不碰线上 data/wujin.db、不起 HTTP 服务、测真实函数与响应包络。
"""
import os
import sqlite3
import sys
import tempfile
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _ROOT)
sys.path.insert(0, os.path.join(_ROOT, "src"))

from search import search_server as ss  # noqa: E402


class AliasResolveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.conn = sqlite3.connect(os.path.join(self.tmp.name, "wujin.db"))
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(
            "CREATE TABLE barcode_alias (barcode TEXT NOT NULL PRIMARY KEY, "
            "sku TEXT NOT NULL, source TEXT, created_by TEXT, created_at TEXT)")
        self.conn.execute(
            "INSERT INTO barcode_alias VALUES ('TMP-001', 'SKU-9', 'strade', 'tester', '2026-09-08 02:00:00')")
        self.conn.commit()
        self._orig_get_db = ss.get_db
        ss.get_db = lambda: self.conn

    def tearDown(self):
        ss.get_db = self._orig_get_db
        self.conn.close()
        self.tmp.cleanup()

    def test_resolve_hit(self):
        d = ss.alias_resolve("TMP-001")
        self.assertTrue(d["success"])
        self.assertEqual(d["data"]["sku"], "SKU-9")
        self.assertEqual(d["data"]["source"], "strade")
        self.assertEqual(d["data"]["barcode"], "TMP-001")

    def test_resolve_case_insensitive(self):
        d = ss.alias_resolve("tmp-001")
        self.assertTrue(d["success"])
        self.assertEqual(d["data"]["sku"], "SKU-9")

    def test_resolve_miss_returns_null_data(self):
        d = ss.alias_resolve("TMP-404")
        self.assertTrue(d["success"])
        self.assertIsNone(d["data"])

    def test_resolve_empty_barcode_is_validation_error(self):
        d = ss.alias_resolve("  ")
        self.assertFalse(d["success"])
        self.assertEqual(d.get("code"), "VALIDATION")


class AliasUnbindTest(unittest.TestCase):
    """I24 条码解绑（回滚专用）。

    本地先生效 → 后台回写校验没过时，必须能把本地绑定撤回，
    否则本地会长期留一条管家婆不认的码（APP 扫得到、收银端扫不到）。
    ``expect_sku`` 乐观锁保证回滚不会抹掉店员后来的改判。
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.conn = sqlite3.connect(os.path.join(self.tmp.name, "wujin.db"))
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(
            "CREATE TABLE barcode_alias (barcode TEXT NOT NULL PRIMARY KEY, "
            "sku TEXT NOT NULL, source TEXT, created_by TEXT, created_at TEXT)")
        self.conn.execute(
            "CREATE TABLE alias_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, barcode TEXT, "
            "sku TEXT, action TEXT, actor TEXT, created_at TEXT)")
        self.conn.execute(
            "INSERT INTO barcode_alias VALUES ('TMP-001', 'SKU-9', 'app_barcode_write', 'tester', '2026-09-14 10:00:00')")
        self.conn.commit()
        self._orig_get_db = ss.get_db
        ss.get_db = lambda: self.conn

    def tearDown(self):
        ss.get_db = self._orig_get_db
        self.conn.close()
        self.tmp.cleanup()

    def _row(self):
        return self.conn.execute(
            "SELECT sku FROM barcode_alias WHERE barcode='TMP-001'").fetchone()

    def test_unbind_removes_binding(self):
        d = ss.alias_unbind({"barcode": "TMP-001", "actor": "system:rollback",
                             "expect_sku": "SKU-9"})
        self.assertTrue(d["success"], d)
        self.assertTrue(d["data"]["unbound"])
        self.assertIsNone(self._row(), "回滚后本地不该再有这条绑定")

    def test_unbind_writes_audit(self):
        ss.alias_unbind({"barcode": "TMP-001", "actor": "system:rollback",
                         "expect_sku": "SKU-9"})
        row = self.conn.execute(
            "SELECT action, actor FROM alias_audit WHERE barcode='TMP-001'").fetchone()
        self.assertEqual(row["action"], "unbind")
        self.assertEqual(row["actor"], "system:rollback")

    def test_乐观锁_店员已改判则让路(self):
        """回写是异步的；等待期里店员可能已把码改判给别的商品——回滚必须让路。"""
        self.conn.execute("UPDATE barcode_alias SET sku='SKU-OTHER' WHERE barcode='TMP-001'")
        self.conn.commit()
        d = ss.alias_unbind({"barcode": "TMP-001", "expect_sku": "SKU-9"})
        self.assertTrue(d["success"])
        self.assertFalse(d["data"]["unbound"])
        self.assertEqual(d["data"]["reason"], "changed")
        self.assertEqual(self._row()["sku"], "SKU-OTHER", "店员的改判不得被抹掉")

    def test_未绑定时幂等成功(self):
        d = ss.alias_unbind({"barcode": "TMP-404"})
        self.assertTrue(d["success"])
        self.assertFalse(d["data"]["unbound"])
        self.assertEqual(d["data"]["reason"], "not_bound")

    def test_缺条码是校验错误(self):
        d = ss.alias_unbind({"barcode": "  "})
        self.assertFalse(d["success"])
        self.assertEqual(d.get("code"), "VALIDATION")


if __name__ == "__main__":
    unittest.main()
