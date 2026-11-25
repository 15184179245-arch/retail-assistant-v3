# -*- coding: utf-8 -*-
"""S14 层 7.5 模糊拼音兜底：口音查询命中 + 严格层回归 + 短串守卫（临时库跑 _search_single）。"""
import os
import sqlite3
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import search.search_server as ss  # noqa: E402


# (barcode, sku, name, category, brand, pinyin, initials)
_ROWS = [
    ("69010001", "10001", "水钻机", "电动工具", "博深", "shui zuan ji", "szj"),
    ("69010002", "10002", "南龙扎带", "电工电料", "南龙", "nan long zha dai", "nlzd"),
]


def _insert_values(i, barcode, sku, name, category, brand, pinyin, inits):
    return (i, barcode, sku, name, "100型", None, category, brand, "", "个",
            25.5, 18.0, 6, "A-1", pinyin, inits, "A-1-2-3", "水 钻机", "active")


class FuzzyPinyinLayerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        dbpath = os.path.join(cls._tmp.name, "fuzzy.db")
        conn = sqlite3.connect(dbpath)
        conn.execute(
            "CREATE TABLE products (id, barcode, sku, name, spec, model, category, brand,"
            " description, unit, sell_price, cost_price, stock_qty, warehouse,"
            " pinyin, initials, location, jieba_tokens, sale_status)"
        )
        conn.executemany(
            "INSERT INTO products VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            [_insert_values(i, *r) for i, r in enumerate(_ROWS, 1)],
        )
        conn.commit()
        conn.close()
        cls._orig_db_path = ss.DB_PATH
        ss.DB_PATH = dbpath
        ss._local = threading.local()  # 丢弃可能指向其他库的线程连接
        ss._search_cache.clear()
        ss._RECURSION_GUARD.clear()

    @classmethod
    def tearDownClass(cls):
        conn = getattr(ss._local, "conn", None)  # Windows 下先断开连接才能删临时库
        if conn is not None:
            conn.close()
        ss.DB_PATH = cls._orig_db_path
        ss._local = threading.local()
        ss._search_cache.clear()
        cls._tmp.cleanup()

    def _search(self, kw):
        return ss._search_single(kw, 10)

    def test_口音丢h_全拼连写命中(self):
        # "suizuanji" 不含 h，严格层 6/7 全漏，只能由 7.5 折叠层命中
        r = self._search("suizuanji")
        self.assertTrue(r and any(x["name"] == "水钻机" for x in r), msg=str(r))

    def test_口音nl不分_命中(self):
        # clerk n/l 不分把 南龙打成 兰/蓝音 → "lanlongzhadai"
        r = self._search("lanlongzhadai")
        self.assertTrue(r and any(x["name"] == "南龙扎带" for x in r), msg=str(r))

    def test_严格全拼回归_层6照常(self):
        # 标准串走既有层 6 严格匹配，不依赖 7.5（回归保护）
        r = self._search("shuizuanji")
        self.assertTrue(r and any(x["name"] == "水钻机" for x in r), msg=str(r))

    def test_严格缩写回归_层7照常(self):
        r = self._search("szj")
        self.assertTrue(r and any(x["name"] == "水钻机" for x in r), msg=str(r))

    def test_中文精确回归_层1照常(self):
        r = self._search("水钻机")
        self.assertTrue(r and any(x["name"] == "水钻机" for x in r), msg=str(r))

    def test_短串守卫_折叠后不足4字符不触发(self):
        # "sui" 折叠后 len=3：层 7.5 <4 守卫仍拦下（不会以模糊拼音身份全表误配）。
        # S16 起层 2 全属性宽召回允许子序列级候选出现（水钻机 "shui" 含子序列 s-u-i，
        # 恰是口音丢 h 的容错收益，宁错杀不放过、排序决胜），但精度必须由排序兜住：
        # 毫不相干的南龙扎带（字段里根本没有 s 序列）不得混入，短串不允许泛洪。
        r = self._search("sui")
        self.assertEqual([x["name"] for x in r], ["水钻机"], msg=str(r))


if __name__ == "__main__":
    unittest.main()
