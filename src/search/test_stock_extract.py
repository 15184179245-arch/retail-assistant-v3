# -*- coding: utf-8 -*-
"""S12 阶段2: extract_stock_conditions 纯函数测试（自然语言库存条件抽取）。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.search_server import extract_stock_conditions  # noqa: E402


class TestExtractStockConditions(unittest.TestCase):

    def test_库存大于N(self):
        conds, cleaned = extract_stock_conditions("螺丝 库存大于5")
        self.assertEqual(conds, {"min_qty": 5})
        self.assertEqual(cleaned.strip(), "螺丝")

    def test_N个以上(self):
        conds, cleaned = extract_stock_conditions("自攻丝 10个以上")
        self.assertEqual(conds, {"min_qty": 10})
        self.assertIn("自攻丝", cleaned)

    def test_没货映射max_qty_0(self):
        conds, cleaned = extract_stock_conditions("生料带 没货的")
        self.assertEqual(conds, {"max_qty": 0})
        self.assertIn("生料带", cleaned)

    def test_有货映射min_qty_1(self):
        conds, cleaned = extract_stock_conditions("水管 有货")
        self.assertEqual(conds, {"min_qty": 1})

    def test_无库存语义原样返回(self):
        conds, cleaned = extract_stock_conditions("螺钉")
        self.assertEqual(conds, {})
        self.assertEqual(cleaned, "螺钉")

    def test_不误伤价格类表述(self):
        conds, cleaned = extract_stock_conditions("生料带几块钱的")
        self.assertEqual(conds, {})

    def test_多个条件同时抽取(self):
        conds, cleaned = extract_stock_conditions("A区 螺丝 10个以上")
        self.assertEqual(conds.get("min_qty"), 10)
        self.assertEqual(conds.get("location_prefix"), "A")
        self.assertIn("螺丝", cleaned)


if __name__ == "__main__":
    unittest.main()
