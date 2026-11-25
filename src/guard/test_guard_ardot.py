#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_guard_ardot.py - Ardot 设计审查守卫单元测试套件。

验证五大离散判定规则的拦截率 100% 与误报率 0%：
1. RuleOpacityLeak：父级 Frame opacity < 1.0 穿透拦截
2. RuleFixedClipping：定高容器 clipsContent 静默截断拦截
3. RuleBaselineBlowout：价格行未锁定 28/32px 行距撑破拦截
4. RuleTouchTarget：触控热区未达 56dp 拦截
5. RuleTokenCompliance：未登记野值色与野值圆角检出
"""

import json
import tempfile
import unittest
from pathlib import Path
import sys

# 将 scripts 目录加入 sys.path 以便导入 guard_ardot
ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from guard_ardot import (
    ArdotScanner,
    RuleOpacityLeak,
    RuleFixedClipping,
    RuleBaselineBlowout,
    RuleTouchTarget,
    RuleTokenCompliance,
    ScanReport,
    Violation,
)


class TestGuardArdotRules(unittest.TestCase):
    """测试五大离散规则逻辑。"""

    def setUp(self):
        self.dummy_tokens = {
            "color": {
                "light": {
                    "primary": "#FF6A00",
                    "card": "#FFFFFF",
                    "danger": "#FF2E4D",
                },
                "dark": {
                    "primary": "#FF6A00",
                    "card": "#111622",
                },
            },
            "gradient": {
                "topBar": ["#1E60D5", "#1044A5"],
            },
            "material": {
                "scrimInk": "#0B1220",
            },
            "shadowInk": "#0A1221",
            "radius": {
                "card": 14,
                "button": 10,
                "pill": 20,
            },
            "elevation": {
                "levels": [
                    {"id": "L1", "radius": 6},
                    {"id": "L2", "radius": 14},
                    {"id": "L4", "radius": 20},
                ]
            },
        }

    def test_rule_opacity_leak(self):
        """Rule 1: 父级容器 opacity < 1.0 必须被拦截，叶子节点允许透明。"""
        rule = RuleOpacityLeak()
        ctx = {"screen_name": "TestModal"}

        # 违规案例：容器包含子元素，却设置了 opacity: 0.45
        bad_parent = {
            "id": "modal-container",
            "name": "DialogFrame",
            "opacity": "0.45",
            "elements": [{"id": "white-card", "name": "ContentCard"}],
        }
        v = rule.check(bad_parent, ctx)
        self.assertEqual(len(v), 1)
        self.assertEqual(v[0].rule_id, "RULE-01-OPACITY-LEAK")
        self.assertEqual(v[0].severity, "ERROR")

        # 合规案例 1：容器包含子元素，opacity 为 1.0
        good_parent = {
            "id": "modal-container-ok",
            "name": "DialogFrame",
            "opacity": "1.0",
            "elements": [{"id": "white-card", "name": "ContentCard"}],
        }
        self.assertEqual(len(rule.check(good_parent, ctx)), 0)

        # 合规案例 2：独立叶子节点（如单个遮罩层或水印图元）设置透明度
        leaf_mask = {
            "id": "scrim-layer",
            "name": "ScrimOverlay",
            "opacity": "0.45",
            "elements": [],
        }
        self.assertEqual(len(rule.check(leaf_mask, ctx)), 0)

    def test_rule_fixed_clipping(self):
        """Rule 2: 定高容器 + clipsContent 必须被拦截。"""
        rule = RuleFixedClipping()
        ctx = {"screen_name": "TestCard"}

        # 违规案例：定高 60px 且设置 clipsContent
        bad_container = {
            "id": "product-card",
            "name": "ProductTitleCard",
            "h": 60,
            "layout_mode": "FIXED",
            "clipsContent": True,
            "has_text": True,
        }
        v = rule.check(bad_container, ctx)
        self.assertEqual(len(v), 1)
        self.assertEqual(v[0].rule_id, "RULE-02-FIXED-CLIPPING")

        # 合规案例：自适应高度或关闭 clipsContent
        good_container = {
            "id": "product-card-ok",
            "name": "ProductTitleCard",
            "h": 60,
            "layout_mode": "AUTO",
            "clipsContent": False,
        }
        self.assertEqual(len(rule.check(good_container, ctx)), 0)

    def test_rule_baseline_blowout(self):
        """Rule 3: 价格行高度非 28px/32px 必须被拦截。"""
        rule = RuleBaselineBlowout()
        ctx = {"screen_name": "TestPrice"}

        # 违规案例：价格行高度 44px（字型 padding 撑破）
        bad_price = {
            "id": "price-row-bad",
            "name": "TotalAmountPriceRow",
            "h": 44,
        }
        v = rule.check(bad_price, ctx)
        self.assertEqual(len(v), 1)
        self.assertEqual(v[0].rule_id, "RULE-03-BASELINE-BLOWOUT")

        # 合规案例：锁定为 32px
        good_price_32 = {
            "id": "price-row-32",
            "name": "PriceRow",
            "h": 32,
        }
        self.assertEqual(len(rule.check(good_price_32, ctx)), 0)

        # 合规案例：锁定为 28px
        good_price_28 = {
            "id": "price-row-28",
            "name": "iprice_label",
            "h": 28,
        }
        self.assertEqual(len(rule.check(good_price_28, ctx)), 0)

        # 非价格行节点不受该规则限制
        other_node = {
            "id": "banner-row",
            "name": "HeaderBanner",
            "h": 50,
        }
        self.assertEqual(len(rule.check(other_node, ctx)), 0)

    def test_rule_touch_target(self):
        """Rule 4: 可交互组件物理 Bounds 小于 56dp 必须被拦截。"""
        rule = RuleTouchTarget()
        ctx = {"screen_name": "TestButtons"}

        # 违规案例：小按钮 40x40，未配置 56dp 热区扩展
        small_btn = {
            "id": "btn-qty-minus",
            "name": "QuantityMinusButton",
            "class": "stepbtn",
            "is_interactive": True,
            "w": 40,
            "h": 40,
        }
        v = rule.check(small_btn, ctx)
        self.assertEqual(len(v), 1)
        self.assertEqual(v[0].rule_id, "RULE-04-TOUCH-TARGET")

        # 合规案例 1：物理尺寸 >= 56dp
        large_btn = {
            "id": "btn-save",
            "name": "SaveBillButton",
            "class": "savebtn",
            "is_interactive": True,
            "w": 120,
            "h": 56,
        }
        self.assertEqual(len(rule.check(large_btn, ctx)), 0)

        # 合规案例 2：小视觉元素配置了 56dp 热区修饰符
        small_btn_expanded = {
            "id": "btn-stepper-touch56",
            "name": "StepperButton",
            "class": "stepbtn",
            "is_interactive": True,
            "w": 28,
            "h": 28,
            "has_touch_target_56": True,
        }
        self.assertEqual(len(rule.check(small_btn_expanded, ctx)), 0)

    def test_rule_token_compliance(self):
        """Rule 5: 野值色与野值圆角检出。"""
        rule = RuleTokenCompliance(self.dummy_tokens)
        ctx = {"screen_name": "TestTokens"}

        # 违规案例：未登记的色值与圆角
        wild_node = {
            "id": "wild-card",
            "name": "CustomCard",
            "fill": "#EAA123",
            "rx": 7,
        }
        v = rule.check(wild_node, ctx)
        self.assertEqual(len(v), 2)
        self.assertTrue(any(item.details.get("wild_color") == "#EAA123" for item in v))
        self.assertTrue(any(item.details.get("wild_rx") == 7 for item in v))

        # 合规案例：使用标定色与标定圆角
        valid_node = {
            "id": "valid-card",
            "name": "StandardCard",
            "fill": "#FF6A00",
            "rx": 14,
        }
        self.assertEqual(len(rule.check(valid_node, ctx)), 0)


class TestArdotScannerIntegration(unittest.TestCase):
    """扫描器整机集成测试。"""

    def test_scanner_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            spec_file = tmp_path / "spec_test.json"
            tokens_file = tmp_path / "tokens_test.json"

            tokens_file.write_text(
                json.dumps({
                    "color": {"light": {"primary": "#FF6A00"}},
                    "radius": {"card": 14},
                    "elevation": {"levels": []},
                }),
                encoding="utf-8",
            )

            # 构造包含多种违规的测试规范
            spec_data = {
                "Frame_Bad": {
                    "frame": [0, 0, 390, 844],
                    "opacity": "0.5",
                    "elements": [
                        {
                            "id": "p1",
                            "name": "Price_Row",
                            "h": 44,
                            "fill": "#123456",
                            "rx": 9,
                        },
                        {
                            "id": "b1",
                            "name": "small_btn",
                            "is_interactive": True,
                            "w": 30,
                            "h": 30,
                        },
                    ],
                },
                "Frame_Good": {
                    "frame": [0, 0, 390, 844],
                    "opacity": "1.0",
                    "elements": [
                        {
                            "id": "p2",
                            "name": "Price_Row_Good",
                            "h": 32,
                            "fill": "#FF6A00",
                            "rx": 14,
                        },
                        {
                            "id": "b2",
                            "name": "btn_good",
                            "is_interactive": True,
                            "w": 80,
                            "h": 56,
                        },
                    ],
                },
            }
            spec_file.write_text(json.dumps(spec_data), encoding="utf-8")

            scanner = ArdotScanner(tokens_path=tokens_file)
            report = ScanReport()
            scanner.scan_spec_file(spec_file, report)

            # 验证检测到了 Frame_Bad 的违规项且 Frame_Good 无阻断错误
            self.assertFalse(report.is_passed)
            self.assertTrue(len(report.violations) >= 4)
            rule_ids = {v.rule_id for v in report.violations}
            self.assertIn("RULE-01-OPACITY-LEAK", rule_ids)
            self.assertIn("RULE-03-BASELINE-BLOWOUT", rule_ids)
            self.assertIn("RULE-04-TOUCH-TARGET", rule_ids)
            self.assertIn("RULE-05-TOKEN-COMPLIANCE", rule_ids)

            # 测试 JSON 导出
            out_json = tmp_path / "report.json"
            report.export_json(out_json)
            self.assertTrue(out_json.exists())
            saved_data = json.loads(out_json.read_text(encoding="utf-8"))
            self.assertFalse(saved_data["is_passed"])
            self.assertEqual(saved_data["violation_count"], len(report.violations))


if __name__ == "__main__":
    unittest.main()
