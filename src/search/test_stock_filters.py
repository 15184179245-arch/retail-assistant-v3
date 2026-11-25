# -*- coding: utf-8 -*-
"""S12 阶段1: apply_stock_filters 纯函数测试（库存维度结构化过滤）。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.search_server import apply_stock_filters  # noqa: E402


def row(sku, qty, loc):
    return {"sku": sku, "name": "测试" + sku, "stock_qty": qty, "location": loc}


class TestStockFilters(unittest.TestCase):
    def setUp(self):
        self.rows = [
            row("A", 10, "A-1-2-3"),
            row("B", 0, "A-2-1-1"),
            row("C", 5, "B-1-1-1"),
            row("D", None, None),
        ]

    def test_min_qty_过滤None按0处理(self):
        out = apply_stock_filters(self.rows, min_qty=1)
        self.assertEqual([r["sku"] for r in out], ["A", "C"])

    def test_min_qty_0_保留零库存行(self):
        out = apply_stock_filters(self.rows, min_qty=0)
        self.assertEqual(len(out), 4)

    def test_location_prefix(self):
        out = apply_stock_filters(self.rows, location_prefix="A-1")
        self.assertEqual([r["sku"] for r in out], ["A"])

    def test_location_无库位_不误匹配空前缀(self):
        # location_prefix="" 视为不过滤
        out = apply_stock_filters(self.rows, location_prefix="  ")
        self.assertEqual(len(out), 4)

    def test_组合过滤(self):
        out = apply_stock_filters(self.rows, min_qty=1, location_prefix="A")
        self.assertEqual([r["sku"] for r in out], ["A"])


if __name__ == "__main__":
    unittest.main()
