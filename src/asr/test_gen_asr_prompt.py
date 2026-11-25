"""gen_asr_prompt 纯函数单测：词频统计过滤 + 词表渲染。"""
import collections
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from src.asr import gen_asr_prompt


class CollectTokensTest(unittest.TestCase):

    def test_纯中文词入库_停用词与规格词被滤(self):
        rows = [("螺栓 自攻丝 的 系列 M8 4分",), ("螺栓 (12角)套筒12.5mm",)]
        extra = ["内六角", "水平尺", "zhang"]  # extra 里含字母词应被滤
        c = gen_asr_prompt.collect_tokens(rows, extra)
        self.assertEqual(c["螺栓"], 2)
        self.assertIn("自攻丝", c)
        self.assertIn("内六角", c)
        self.assertIn("水平尺", c)
        for bad in ("的", "系列", "M8", "4分", "zhang"):
            self.assertNotIn(bad, c, bad)

    def test_空行与None安全(self):
        c = gen_asr_prompt.collect_tokens([(None,), ("",)], [])
        self.assertEqual(c, collections.Counter())


class RenderHotwordsTest(unittest.TestCase):

    def test_渲染_类目段在前_词频降序(self):
        counter = collections.Counter({"螺栓": 9, "自攻丝": 5, "角磨机": 1})
        out = gen_asr_prompt.render_hotwords(["手动工具", "紧固件", "未归类"], counter, top_n=2)
        lines = out.splitlines()
        self.assertTrue(lines[0].startswith("#"))            # 注释头
        cats = lines[lines.index("# CATEGORIES 全部商品类目") + 1]
        self.assertIn("手动工具", cats)
        self.assertNotIn("未归类", cats)                     # 未归类不进词表
        words = lines[lines.index("# HOTWORDS 商品高频词(词频降序)") + 1]
        self.assertEqual(words.split(), ["螺栓", "自攻丝"])  # top_n=2 且按词频降序


class GuardPathTest(unittest.TestCase):
    """Mimosa L2 路径守卫：禁穿越、限仓库子树。"""

    def test_仓库内路径放行并归一(self):
        out = gen_asr_prompt.guard_path(os.path.join("data", "asr_hotwords.txt"), "--out")
        self.assertFalse(out.endswith(".."))
        self.assertTrue(os.path.isabs(out))

    def test_穿越与仓库外路径拒绝(self):
        # 2026-09-11：全量 discover 时前置测试可能 chdir，相对穿越（data\..\..）
        # 的落点随 CWD 漂移会落回仓库内而不抛错；改用 _REPO_ROOT 锚定，CWD 无关。
        outside = os.path.join(gen_asr_prompt._REPO_ROOT, "..", "evil.txt")
        with self.assertRaises(ValueError):
            gen_asr_prompt.guard_path(outside, "--out")
        with self.assertRaises(ValueError):
            gen_asr_prompt.guard_path("C:\\Windows\\evil.txt" if os.name == "nt"
                                      else "/etc/evil.txt", "--db")


if __name__ == "__main__":
    unittest.main()
