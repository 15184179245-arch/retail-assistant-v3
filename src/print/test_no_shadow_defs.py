#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""I31/B3 防回归：print_server.py 顶层定义名不得重复。

事故背景（2026-09-15 全量代码审查发现）：模块顶部曾意外复制粘贴 _qr_modules×31 /
_short_name×27 / 四个正则常量 ×20+，Python「后定义覆盖前定义」导致只有最后一份生效——
任何人改到前面任一副本都静默无效（1648 行影子定义已清理）。
本测试在模块级钉死「一个名字只许定义一次」。
"""
import os
import re
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_TARGET = os.path.join(_ROOT, "src", "print", "print_server.py")

_DEF = re.compile(r"^(?:def|class)\s+([A-Za-z_][A-Za-z0-9_]*)")
_ASSIGN = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=(?!=)")


def _top_level_names(lines):
    names = []
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        if not line.strip() or line.lstrip().startswith("#") or line[0] in " \t)":
            i += 1
            continue
        m = _DEF.match(line) or _ASSIGN.match(line)
        if m:
            names.append(m.group(1))
        i += 1
        # 单语句粗粒度：顶层语句首行后跳过缩进续行/空行/注释行
        while i < n and (not lines[i].strip() or lines[i][0] in " \t)" or lines[i].lstrip().startswith("#")):
            i += 1
    return names


class NoShadowDefsTest(unittest.TestCase):
    def test_顶层定义名不得重复(self):
        lines = open(_TARGET, encoding="utf-8").read().splitlines()
        seen, dups = set(), {}
        for name in _top_level_names(lines):
            if name in seen:
                dups[name] = dups.get(name, 1) + 1
            seen.add(name)
        self.assertEqual(dups, {}, f"print_server.py 顶层出现重复定义（后定义覆盖前定义=改了也白改）: {dups}")


if __name__ == "__main__":
    unittest.main()
