"""spec_rules 单测（S4 Step7）：五金规格同义映射正反查。"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.spec_rules import normalize_spec, expand_spec_terms  # noqa: E402


class TestNormalizeSpec(unittest.TestCase):
    def test_正反查(self):
        self.assertEqual(normalize_spec("4分"), "1/2")
        self.assertEqual(normalize_spec("1/2"), "4分")
        self.assertEqual(normalize_spec("6分"), "3/4")
        self.assertEqual(normalize_spec("3/4"), "6分")
        self.assertEqual(normalize_spec("1寸"), '1"')
        self.assertEqual(normalize_spec('1"'), "1寸")

    def test_未命中原样返回(self):
        self.assertEqual(normalize_spec("M10"), "M10")
        self.assertEqual(normalize_spec(" 4分 "), "1/2", "应先 strip 再查表")

    def test_空串(self):
        self.assertEqual(normalize_spec(""), "")
        self.assertEqual(normalize_spec(None), None)


class TestExpandSpecTerms(unittest.TestCase):
    def test_子串扩展(self):
        self.assertEqual(expand_spec_terms("4分堵头"), ["4分堵头", "1/2堵头"])
        self.assertEqual(expand_spec_terms("1寸阀门"), ['1寸阀门', '1"阀门'])

    def test_原词在前且去重(self):
        out = expand_spec_terms("4分")
        self.assertEqual(out[0], "4分")
        self.assertEqual(len(out), len(set(out)))

    def test_无映射原样(self):
        self.assertEqual(expand_spec_terms("膨胀螺丝"), ["膨胀螺丝"])
        self.assertEqual(expand_spec_terms(""), [""])


if __name__ == "__main__":
    unittest.main()
