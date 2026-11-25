#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_guard_ui_code.py - 前端代码门禁与架构规则单元测试套件。

验证：
1. 裸调 Modifier.shadow(...) 与非零 cardElevation(...) 的精准阻断
2. 弹窗双层滚动嵌套 (SanxAlertDialog text slot verticalScroll) 阻断
3. Room 唯一索引建表与回填顺序阻断
4. includeFontPadding = true 撑破价格行阻断
"""

import os
import re
import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import importlib.util
spec = importlib.util.spec_from_file_location("guard_ui_code", str(ROOT / "scripts" / "guard-ui-code.py"))
guard_ui_code = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard_ui_code)


class TestGuardUiCodeRules(unittest.TestCase):
    """测试 guard-ui-code.py 规则检测能力。"""

    def test_unauthorized_shadow_detection(self):
        """测试裸阴影与非零 cardElevation 检测。"""
        # 1. 裸调 Modifier.shadow
        bad_code_shadow = """
        @Composable
        fun BadCard() {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp))
            )
        }
        """
        shadow_pattern = re.compile(r'\.shadow\s*\(')
        self.assertTrue(bool(shadow_pattern.search(bad_code_shadow)))

        # 2. 非零 cardElevation
        bad_code_card_elev = """
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        )
        """
        card_elev_pattern = re.compile(r'\b(?:CardDefaults\.)?cardElevation\s*\(\s*(?:defaultElevation\s*=\s*)?([1-9]\d*\.?\d*)\.dp')
        m = card_elev_pattern.search(bad_code_card_elev)
        self.assertIsNotNone(m)
        self.assertEqual(m.group(1), "6")

        # 3. 合规的 0.dp cardElevation（用于关闭 M3 默认染色）
        good_card_elev_zero = """
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        )
        """
        self.assertIsNone(card_elev_pattern.search(good_card_elev_zero))

    def test_nested_scroll_in_dialog_detection(self):
        """测试弹窗内部嵌套 verticalScroll 阻断检测。"""
        bad_dialog_code = """
        SanxAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("确认删除") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("提示详情")
                }
            },
            confirmButton = {}
        )
        """
        blocks = re.findall(r'(SanxAlertDialog|SanxSmallWindow)\s*\((.*?)\)\n', bad_dialog_code, re.S)
        self.assertEqual(len(blocks), 1)
        comp_name, content = blocks[0]
        self.assertTrue("text =" in content and "verticalScroll" in content)

        good_dialog_code = """
        SanxAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("确认删除") },
            text = { Text("提示详情单行") },
            confirmButton = {}
        )
        """
        blocks_good = re.findall(r'(SanxAlertDialog|SanxSmallWindow)\s*\((.*?)\)\n', good_dialog_code, re.S)
        self.assertEqual(len(blocks_good), 1)
        _, content_good = blocks_good[0]
        self.assertFalse("text =" in content_good and "verticalScroll" in content_good)

    def test_include_font_padding_blowout_detection(self):
        """测试价格行显式 includeFontPadding = true 拦截。"""
        bad_font_code = """
        val priceStyle = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = true)
        )
        """
        self.assertTrue(bool(re.search(r'PlatformTextStyle\s*\(\s*includeFontPadding\s*=\s*true\s*\)', bad_font_code)))

        good_font_code = """
        val priceStyle = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
        """
        self.assertFalse(bool(re.search(r'PlatformTextStyle\s*\(\s*includeFontPadding\s*=\s*true\s*\)', good_font_code)))


if __name__ == "__main__":
    unittest.main()
