#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_svg2vector.py - 矢量资产双轨分流与转换测试套件。

验证：
1. 单色 SVG 精准判定为 glyph，剥离 fill 允许动态染色
2. 复合多色/渐变/带底板 SVG 精准判定为 plate，保留图元并注入禁 tint 注释
3. XML 转换结构完整性
"""

import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from svg2vector import (
    classify_svg,
    clean_glyph_svg,
    convert_svg_to_vector,
)


class TestSvg2Vector(unittest.TestCase):
    """测试 svg2vector.py 分流与转换逻辑。"""

    def setUp(self):
        # 纯单色 SVG 样本（放大镜搜索图标）
        self.monochrome_svg = """<svg width="24" height="24" viewBox="0 0 24 24" fill="none">
            <path d="M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z" stroke="#111827" stroke-width="2" stroke-linecap="round"/>
            <path d="m21 21-4.35-4.35" stroke="#111827" stroke-width="2" stroke-linecap="round"/>
        </svg>"""

        # 带有写死单色 fill 的图标
        self.monochrome_filled_svg = """<svg width="24" height="24" viewBox="0 0 24 24">
            <path d="M12 2L2 7l10 5 10-5-10-5z" fill="#333333"/>
        </svg>"""

        # 复合多色底板 SVG 样本（彩色客户瓷片徽标）
        self.plate_multi_color_svg = """<svg width="36" height="36" viewBox="0 0 36 36">
            <rect width="36" height="36" rx="10" fill="#FFF3E8"/>
            <path d="M12 10h12v16H12z" fill="#FF6A00"/>
            <circle cx="18" cy="18" r="4" fill="#1B7E3B"/>
        </svg>"""

        # 带渐变的复合图元
        self.plate_gradient_svg = """<svg width="40" height="40" viewBox="0 0 40 40">
            <defs>
                <linearGradient id="grad1" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0%" stop-color="#1E60D5"/>
                    <stop offset="100%" stop-color="#1044A5"/>
                </linearGradient>
            </defs>
            <rect width="40" height="40" rx="8" fill="url(#grad1)"/>
        </svg>"""

    def test_classify_monochrome_as_glyph(self):
        """单色轮廓或单一纯色必须分类为 glyph。"""
        self.assertEqual(classify_svg(self.monochrome_svg), "glyph")
        self.assertEqual(classify_svg(self.monochrome_filled_svg), "glyph")

    def test_classify_composite_as_plate(self):
        """多色底板或渐变必须分类为 plate。"""
        self.assertEqual(classify_svg(self.plate_multi_color_svg), "plate")
        self.assertEqual(classify_svg(self.plate_gradient_svg), "plate")

    def test_clean_glyph_svg(self):
        """测试 glyph fill 剥离与标准化。"""
        cleaned = clean_glyph_svg(self.monochrome_filled_svg)
        self.assertNotIn('fill="#333333"', cleaned)
        self.assertIn('fill="#FFFFFFFF"', cleaned)

    def test_convert_to_vector_xml(self):
        """测试转换输出的 VectorDrawable 带有对应分类注释。"""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)

            # 1. 转换 glyph
            glyph_svg_path = tmp_path / "glyph_search.svg"
            glyph_xml_path = tmp_path / "ic_glyph_search.xml"
            glyph_svg_path.write_text(self.monochrome_svg, encoding="utf-8")

            ok = convert_svg_to_vector(str(glyph_svg_path), str(glyph_xml_path))
            self.assertTrue(ok)
            self.assertTrue(glyph_xml_path.exists())
            xml_content = glyph_xml_path.read_text(encoding="utf-8")
            self.assertIn("<vector", xml_content)
            self.assertIn("ic_glyph_*", xml_content)
            self.assertIn("动态染色", xml_content)

            # 2. 转换 plate
            plate_svg_path = tmp_path / "plate_tile.svg"
            plate_xml_path = tmp_path / "ic_plate_tile.xml"
            plate_svg_path.write_text(self.plate_multi_color_svg, encoding="utf-8")

            ok2 = convert_svg_to_vector(str(plate_svg_path), str(plate_xml_path))
            self.assertTrue(ok2)
            self.assertTrue(plate_xml_path.exists())
            xml_content2 = plate_xml_path.read_text(encoding="utf-8")
            self.assertIn("<vector", xml_content2)
            self.assertIn("ic_plate_*", xml_content2)
            self.assertIn("严禁在 Compose 端使用 Icon(..., tint) 染色", xml_content2)


if __name__ == "__main__":
    unittest.main()
