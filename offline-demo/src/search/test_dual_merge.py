"""双路汇合纯函数单测（S4 Step7）：A有/A无+B兜底/B不可用 三分支。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.dual_merge import merge_dual_paths  # noqa: E402

A = [{"id": 1, "name": "角阀 4分"}, {"id": 2, "name": "角阀 6分"}]
B_HIT = [{"id": 9, "name": "三角阀"}]


def term_fn(term):
    # 模拟兜底重查：扩展词命中返回，否则空
    return B_HIT if term == "三角阀" else []


class TestMergeDualPaths(unittest.TestCase):
    def test_A有结果原样返回(self):
        out, extra = merge_dual_paths(A, {"rewrite": ["角阀", "三角阀"], "verdict": "has"}, term_fn)
        self.assertEqual(out, A)

    def test_A有结果_B给消歧提示(self):
        out, extra = merge_dual_paths(A, {"rewrite": ["角阀", "三角阀"], "verdict": "has"}, term_fn)
        self.assertIn("disambiguation", extra)
        self.assertEqual(extra["disambiguation"]["term"], "三角阀")

    def test_A有结果_消歧词已在商品名中则不提示(self):
        a2 = [{"id": 1, "name": "三角阀"}]
        out, extra = merge_dual_paths(a2, {"rewrite": ["角阀", "三角阀"], "verdict": "has"}, term_fn)
        self.assertNotIn("disambiguation", extra)

    def test_A无结果_B扩展词兜底(self):
        out, extra = merge_dual_paths([], {"rewrite": ["泄压阀", "减压阀"], "verdict": "has"},
                                     lambda t: A if t == "减压阀" else [])
        self.assertEqual(out, A)
        self.assertTrue(extra.get("llm_fallback"))

    def test_B超时降级不抛错(self):
        out, extra = merge_dual_paths(A, None, term_fn)
        self.assertEqual(out, A)
        self.assertEqual(extra, {}, "降级分支不产生错误字段")

    def test_AB都空返回空(self):
        out, extra = merge_dual_paths([], {"rewrite": ["不存在的词"], "verdict": "none"}, term_fn)
        self.assertEqual(out, [])

    def test_limit截断(self):
        rows = [{"id": i, "name": f"x{i}"} for i in range(100)]
        out, extra = merge_dual_paths(rows, None, term_fn, limit=5)
        self.assertEqual(len(out), 5)


if __name__ == "__main__":
    unittest.main()
