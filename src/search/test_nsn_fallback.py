#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""I30/A4 回归：never_say_no 零命中兜底链行为锁定。

历史缺陷：函数自称「7 层兜底」，但 L1-5 命中即 return，L6 热门推荐/L7 可调货
永远不可达（守卫到达时恒真）。2026-09-15 移除死代码，行为与 docstring、S16c
反误导口径（零命中不喷无关商品）对齐：五层全空 → 如实 NOT_FOUND。

不碰线上 data/wujin.db（monkeypatch ss.get_db，同 test_search_pool 口径）。
"""
import os
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _ROOT)
sys.path.insert(0, os.path.join(_ROOT, "src"))

from search import search_server as ss  # noqa: E402


def _make_db(path):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute(
        "CREATE TABLE products ("
        "id INTEGER PRIMARY KEY, barcode TEXT, sku TEXT, name TEXT, spec TEXT, model TEXT, "
        "category TEXT, brand TEXT, description TEXT, unit TEXT, sell_price REAL, "
        "cost_price REAL, stock_qty INTEGER, warehouse TEXT, pinyin TEXT, "
        "initials TEXT, location TEXT, jieba_tokens TEXT, "
        "sale_status TEXT DEFAULT 'active')")
    conn.execute(
        "INSERT INTO products (id, barcode, sku, name, spec, category, brand, "
        "description, unit, sell_price, cost_price, stock_qty, warehouse, pinyin, "
        "initials, location, jieba_tokens, sale_status) "
        "VALUES (1, NULL, NULL, '测试类目热销品', NULL, '测试类目', NULL, NULL, "
        "'个', 3.0, 1.8, 99, NULL, NULL, NULL, NULL, NULL, 'active')")
    conn.commit()
    return conn


class NeverSayNoFallbackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.conn = _make_db(os.path.join(self.tmp.name, "wujin.db"))
        self._patch = mock.patch.object(ss, "get_db", return_value=self.conn)
        self._patch.start()

    def tearDown(self):
        self._patch.stop()
        self.conn.close()
        self.tmp.cleanup()

    def test_五层全空时如实NOT_FOUND不再虚构推荐(self):
        """I30/A4：原 L6 热门推荐/L7 可调货为不可达死代码；全空必须如实 NOT_FOUND。"""
        r = ss.never_say_no("呱唧呱唧呜呜奇QC")
        self.assertFalse(r.get("success"), f"零命中不应返回商品: {r}")
        self.assertEqual(r.get("code"), "NOT_FOUND")

    def test_分类推断命中时L5分类推荐可达(self):
        with mock.patch.dict(ss.CATEGORY_KEYWORDS, {"测试类目": ["神秘词"]}):
            r = ss.never_say_no("神秘词")
        self.assertTrue(r.get("success"), f"分类推荐应命中: {r}")
        data = r["data"]
        self.assertEqual(data["layer"], 5)
        self.assertEqual(data["products"][0]["name"], "测试类目热销品")

    def test_L1精确名仍最优先(self):
        self.conn.execute(
            "INSERT INTO products (id, name, unit, sell_price, cost_price, stock_qty, "
            "sale_status, category) VALUES (2, '神秘词', '个', 1, 0.5, 3, 'active', '测试类目')")
        self.conn.commit()
        with mock.patch.dict(ss.CATEGORY_KEYWORDS, {"测试类目": ["神秘词"]}):
            r = ss.never_say_no("神秘词")
        self.assertTrue(r.get("success"))
        self.assertEqual(r["data"]["layer"], 1, "精确名命中应走 L1 而非分类推荐")


if __name__ == "__main__":
    unittest.main()
