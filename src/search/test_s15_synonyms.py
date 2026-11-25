# -*- coding: utf-8 -*-
"""S15 学习队列首批沉淀：口语词→标准词同义词生效断言（usage_stats 真实零命中沉淀）。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from search.search_server import _expand_colloquial  # noqa: E402


class S15SynonymBatchTest(unittest.TestCase):
    def test_拉爆_展开到膨胀螺栓(self):
        terms = _expand_colloquial("拉爆")
        self.assertIn("拉爆螺栓", terms)
        self.assertIn("膨胀螺栓", terms)

    def test_奶头锤_展开到橡胶锤(self):
        terms = _expand_colloquial("奶头锤")
        self.assertIn("橡胶锤", terms)

    def test_电罗机_展开到电烙铁(self):
        terms = _expand_colloquial("电罗机")
        self.assertIn("电烙铁", terms)


if __name__ == "__main__":
    unittest.main()
