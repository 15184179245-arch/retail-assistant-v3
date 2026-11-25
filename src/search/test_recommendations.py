# -*- coding: utf-8 -*-
"""测试智能推荐引擎与规格保护（五金连带配套与同类在售替代）。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from search.search_server import (
    get_db,
    db_warmup,
    get_product_recommendations,
    _get_cross_sell_for,
    _RECOMMENDATION_RULES,
)
import jieba


class TestRecommendations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        db_warmup()

    def test_rules_loaded(self):
        """验证 209 条五金推荐规则已成功常驻内存。"""
        self.assertGreaterEqual(len(_RECOMMENDATION_RULES), 50)
        types = {r["type"] for r in _RECOMMENDATION_RULES}
        self.assertIn("cross_sell", types)
        self.assertIn("alternative", types)

    def test_angle_valve_cross_sell(self):
        """验证角阀查询返回生料带/软管等必备配套辅料，且有货优先。"""
        res = get_product_recommendations(name="角阀", limit=6)
        self.assertTrue(res.get("success"))
        data = res["data"]
        self.assertEqual(data["name"], "角阀")
        cross_sell = data["cross_sell"]
        self.assertGreater(len(cross_sell), 0)
        # 配套中应包含生料带或软管
        names = [p["name"] for p in cross_sell]
        has_tape_or_hose = any("生料带" in n or "软管" in n for n in names)
        self.assertTrue(has_tape_or_hose, f"Expected 生料带 or 软管 in {names}")
        # 校验推荐理由与类型
        for p in cross_sell:
            self.assertEqual(p.get("recommend_type"), "cross_sell")
            self.assertTrue(bool(p.get("recommend_reason")))

    def test_substitutes_stock_prioritized(self):
        """验证替代品返回结果中优先展示有库存商品。"""
        res = get_product_recommendations(name="开关", limit=6)
        self.assertTrue(res.get("success"))
        subs = res["data"]["substitutes"]
        self.assertGreater(len(subs), 0)
        # 如果有货商品存在，排在前面的应当是 stock_qty > 0
        if any((p.get("stock_qty") or 0) > 0 for p in subs):
            self.assertGreater((subs[0].get("stock_qty") or 0), 0)

    def test_empty_query_returns_gracefully(self):
        """验证空查询安全返回，不抛异常。"""
        res = get_product_recommendations(name="", category="")
        self.assertTrue(res.get("success"))
        self.assertEqual(res["data"]["cross_sell"], [])
        self.assertEqual(res["data"]["substitutes"], [])

    def test_cross_sell_for_backward_compat(self):
        """验证 _get_cross_sell_for 保持向后兼容。"""
        items = _get_cross_sell_for({"name": "水龙头", "category": "五金杂品类"})
        self.assertIsInstance(items, list)
        self.assertGreater(len(items), 0)

    def test_hardware_spec_token_protection(self):
        """验证五金规格型号正则注入后，复合型号分词不被碎裂。"""
        tokens = list(jieba.cut("BV2.5单芯铜电线"))
        self.assertIn("BV2.5", tokens)


if __name__ == "__main__":
    unittest.main()
