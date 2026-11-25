"""pinyintools 单元测试（qa.ps1 Gate 2 自动发现：src/**/test_*.py）。

回归背景（AGENTS.md 待做 2026-09-02）：wujin.db 历史 pinyin/initials 列含
"fsjb（dj） " 类中文标点噪音 + 尾随空格，导致 initials 等值/前缀匹配全部失效
（在线搜 fsjb 0 命中「防水胶布（大卷）」）。本模块输出必须为纯 [a-z0-9]（全拼含空格）。
"""
import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from utils.pinyintools import canon_sql, canonical_fuzzy, clean_query, full_pinyin, initials


class PinyinToolsSpec(unittest.TestCase):
    def test_initials_回归_中文括号商品_输出纯字母(self):
        # 旧数据为 "fsjb（dj） "（含括号+尾随空格）——修复后必须可等值/前缀匹配
        self.assertEqual(initials("防水胶布（大卷）"), "fsjbdj")

    def test_initials_纯中文_无尾随空格(self):
        self.assertEqual(initials("灯带头"), "ddt")

    def test_initials_英文数字保留整体(self):
        self.assertEqual(initials("TVS控制继电器"), "tvskzjdq")
        self.assertEqual(initials("LED灯泡"), "leddp")

    def test_initials_斜杠连字符剔除(self):
        # AC/DC 旧数据 "AC/DCkgdy"，搜 acdc 无法前缀命中
        self.assertEqual(initials("AC/DC开关电源"), "acdckgdy")

    def test_full_pinyin_中文括号商品_token纯字母(self):
        self.assertEqual(full_pinyin("防水胶布（大卷）"), "fang shui jiao bu da juan")

    def test_full_pinyin_英文开头(self):
        self.assertEqual(full_pinyin("LED灯泡"), "led deng pao")

    def test_full_pinyin_纯英数字_剔标点(self):
        self.assertEqual(full_pinyin("0.5mm"), "05mm")

    def test_clean_query_用户输入_剔标点空白小写(self):
        self.assertEqual(clean_query("FSJB"), "fsjb")
        self.assertEqual(clean_query("fsjbdj"), "fsjbdj")
        self.assertEqual(clean_query("ac/dc"), "acdc")
        self.assertEqual(clean_query("  "), "")

    def test_空输入安全(self):
        self.assertEqual(initials(""), "")
        self.assertEqual(full_pinyin(""), "")
        self.assertEqual(clean_query(""), "")


class CanonicalFuzzySpec(unittest.TestCase):
    """S14 口音归一：折叠恒等（口音串与标准串同形）+ 查询侧/SQL 列侧同构。"""

    def test_折叠恒等_全拼带空格与口音连写同形(self):
        # 标准 "shui zuan ji" vs 口音连写 "suizuanji"（丢 h）必须归一为同一串
        self.assertEqual(canonical_fuzzy("shui zuan ji"), canonical_fuzzy("suizuanji"))
        self.assertEqual(canonical_fuzzy("shui zuan ji"), "suizualji")

    def test_平翘舌_zh_ch_sh组(self):
        self.assertEqual(canonical_fuzzy("zhuantou"), canonical_fuzzy("zuantou"))
        self.assertEqual(canonical_fuzzy("chitang"), canonical_fuzzy("citan"))
        self.assertEqual(canonical_fuzzy("shanshi"), canonical_fuzzy("sansi"))

    def test_前后鼻音_ang_eng_ing组(self):
        self.assertEqual(canonical_fuzzy("fangding"), canonical_fuzzy("fandin"))
        self.assertEqual(canonical_fuzzy("fangding"), "faldil")

    def test_声母nl不分_双向归一(self):
        self.assertEqual(canonical_fuzzy("nanlong"), canonical_fuzzy("lanlong"))
        self.assertEqual(canonical_fuzzy("nanlong"), "lallolg")

    def test_边界_空_数字_无模糊成员原样(self):
        self.assertEqual(canonical_fuzzy(""), "")
        self.assertEqual(canonical_fuzzy("05mm"), "05mm")
        self.assertEqual(canonical_fuzzy("fsjb"), "fsjb")

    def test_SQL列侧与Python查询侧同构(self):
        # 核心不变量：同一条替换链跑在 SQLite REPLACE 与 Python str.replace 上结果一致
        samples = ["shui zuan ji", "nan long zha dai", "fang shui jiao bu da juan",
                   "led deng pao", "zhuantou", "abc123"]
        conn = sqlite3.connect(":memory:")
        conn.execute("CREATE TABLE t(p TEXT)")
        conn.executemany("INSERT INTO t VALUES (?)", [(s,) for s in samples])
        for s in samples:
            col_side = conn.execute(
                f"SELECT {canon_sql('p')} FROM t WHERE p=?", (s,)
            ).fetchone()[0]
            self.assertEqual(col_side, canonical_fuzzy(s), msg=s)

    def test_SQL同构端到端_口音查询命中标准列(self):
        conn = sqlite3.connect(":memory:")
        conn.execute("CREATE TABLE t(p TEXT)")
        conn.execute("INSERT INTO t VALUES ('shui zuan ji')")
        n = conn.execute(
            f"SELECT COUNT(*) FROM t WHERE {canon_sql('p')} LIKE ?",
            (f"%{canonical_fuzzy('suizuanji')}%",)
        ).fetchone()[0]
        self.assertEqual(n, 1)


if __name__ == "__main__":
    unittest.main()
