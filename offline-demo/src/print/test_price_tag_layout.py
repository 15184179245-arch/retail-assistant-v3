#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""价签 v3（参考上游业务系统版式）契约测试：五行 + 右码图 + 外框 + 文字防溢出。

与 APP `android-v2/.../print/LabelTemplates.kt` 同参数同几何；几何签名与
`LabelParityTest.kt` 对应，双端改版式必须同步。
"""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from print.print_server import (  # noqa: E402
    _disp_w,
    _op_box,
    _fit_lines,
    _fit_scale,
    _qr_modules,
    _short_name,
    _label_for,
    _price_tag_ops,
    _tspl_price_tag,
)

PRESET_SIZES = [(30, 20), (30, 40), (40, 30), (50, 30), (60, 40), (70, 50), (80, 50)]
HEIGHT_BY_WIDTH = {30: 40, 40: 30, 50: 30, 60: 40, 70: 50, 80: 50}

ITEM = dict(name="304不锈钢外六角螺栓", price="12.50", sku="DEMO-A01",
            spec="M8×35 全螺纹", barcode="88880001")
LONG_ITEM = dict(name="样例DEMO-1山形铁票夹140mm袋装4只装彩色涂层钢板可订做定制加长",
                 price="123456.78", sku="DEMO-A01",
                 spec="合成长规格示例", barcode="88880001")


def _signature(name, price, sku, spec="", barcode="", wm=30, hm=40, template=None, unit=""):
    """几何签名：元素类型+坐标+倍率（跨语言比对用）。"""
    ops = _price_tag_ops(name, price, sku, spec=spec, barcode=barcode,
                         width_mm=wm, height_mm=hm, template=template or {}, unit=unit)
    parts = []
    for op in ops:
        if op[0] == "txt":
            xm = op[5] if len(op) > 5 else op[3]
            parts.append("T%d,%d,%d,%d" % (op[1], op[2], op[3], xm))
        elif op[0] == "qr":
            parts.append("Q%d,%d,%d" % (op[1], op[2], op[3]))
        elif op[0] == "bar":
            parts.append("B%d,%d,%d" % (op[1], op[2], op[3]))
        else:
            parts.append("L%d,%d,%d,%d" % (op[1], op[2], op[3], op[4]))
    return " ".join(parts)


class FitLinesTest(unittest.TestCase):

    def test_disp_width_cjk_vs_ascii(self):
        self.assertEqual(48, _disp_w("螺栓"))
        self.assertEqual(24, _disp_w("M8"))
        self.assertEqual(48, _disp_w("M8螺"))

    def test_wrap_no_truncate(self):
        lines = _fit_lines("不锈钢外六角螺栓加长型螺母", 168, 2)
        self.assertEqual(["不锈钢外六角螺", "栓加长型螺母"], lines)

    def test_wrap_ellipsis_on_overflow(self):
        lines = _fit_lines("样例DEMO-1山形铁票夹140mm袋装4只装彩色涂层钢板可订做定制加长", 224, 2)
        self.assertEqual(2, len(lines))
        self.assertTrue(lines[-1].endswith("…"))

    def test_min_one_char_kept_when_tight(self):
        lines = _fit_lines("自攻钉", 100, 2, 2)
        self.assertTrue(lines)
        self.assertTrue("".join(lines).startswith("自"))


class FitScaleTest(unittest.TestCase):

    def test_price_scale_fit(self):
        self.assertEqual(2, _fit_scale("Y12.50", 144, 3))
        self.assertEqual(1, _fit_scale("Y123456.78", 120, 4, 1))


class LabelAdaptTest(unittest.TestCase):

    def test_label_shortens_to_keep_value(self):
        # 窄列：完整标签放不下时逐字去头，值优先完整
        self.assertEqual("名称", _label_for("商品名称", "自攻钉", 150))
        self.assertEqual("商品名称", _label_for("商品名称", "自攻钉", 400))

    def test_label_keeps_full_on_wide_column(self):
        self.assertEqual("零售价", _label_for("零售价", "Y12.50", 300))


class ReferenceLayoutTest(unittest.TestCase):
    """参考实物版式骨架：白底裸版式（无外框）+ 值行 + 右下角码图。"""

    def test_bare_layout_code_bottom_right(self):
        out = _tspl_price_tag(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                              width=40, height=30, barcode=ITEM["barcode"], template={}, unit="个")
        lines = out.splitlines()
        self.assertEqual(0, sum(1 for line in lines if line.startswith("LINE ")), "裸版式无外框")
        qr = next(line for line in lines if line.startswith("QRCODE "))
        qr_x = int(qr.split()[1].split(",")[0])
        self.assertGreater(qr_x, 40 * 8 // 2, "码在右半边（右下角）")

    def test_price_and_name_on_label(self):
        out = _tspl_price_tag(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                              width=70, height=50, barcode=ITEM["barcode"], template={}, unit="个")
        self.assertIn("12.50", out)
        self.assertIn("304", out)
        self.assertNotIn("【", out, "不再打【】括号")

    def test_mini_label_keeps_name_and_price(self):
        """30×20 面积太小：让位到「名称 + 价格（含单位）」也必须保住——测试反馈拍板
        「编号可以放弃，名称和二维码必须保证」。"""

        ops = _price_tag_ops("自攻钉", "2.50", "100001", spec="M3x16", barcode="88880002",
                             width_mm=30, height_mm=20, template={}, unit="个")
        texts = [op[4] for op in ops if op[0] == "txt"]
        joined = "".join(texts)
        # 名称横向 2× 加粗后占宽翻倍，小纸上会折成两行——拼起来必须完整
        self.assertIn("自攻钉", joined, "名称必须保住（加粗后可能折行）")
        self.assertTrue(any(t.startswith("2.50") for t in texts), "价格必须保住")
        self.assertTrue(any(op[0] == "qr" for op in ops), "二维码必须保住")
        self.assertIn("100001", texts, "编号保留（测试反馈 2026-09-11 改口：编码还是保留吧）")

    def test_offset_shifts_both_directions(self):
        """★ 回归（测试反馈 2026-09-11 实测「二维码没有打印偏移容错」）：
        码包围盒此前按固定 30 模块虚估 + 贴死区边，导致**正向偏移被夹成 0**。"""

        def qr_of(off):
            ops = _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                                 barcode=ITEM["barcode"], width_mm=40, height_mm=30,
                                 template=off, unit="个")
            return [op for op in ops if op[0] == "qr"][0]

        base = qr_of({})
        self.assertGreater(qr_of({"offset_x": 1.0})[1] - base[1], 0, "右移必须真的生效")
        self.assertLess(qr_of({"offset_x": -1.0})[1] - base[1], 0, "左移必须真的生效")
        self.assertGreater(qr_of({"offset_y": 1.0})[2] - base[2], 0, "下移必须真的生效")
        self.assertLess(qr_of({"offset_y": -1.0})[2] - base[2], 0, "上移必须真的生效")

    def test_qr_side_is_real_module_count(self):
        """码的边长 = 真实模块数 × 码格（16 位编号只需 21 模块，不是 30）；
        包围盒另加两侧静默边（纸角四周本就是白纸，按 2 模块算）。"""
        ops = _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                             barcode=ITEM["barcode"], width_mm=40, height_mm=30, template={}, unit="个")
        qr = [op for op in ops if op[0] == "qr"][0]
        self.assertEqual(21, _qr_modules(qr[4]))
        box = _op_box(qr)
        self.assertEqual(21 * qr[3] + 8 * qr[3], box[1] - box[0], "包围盒 = 边长 + 两侧静默边")


class ShortNameTest(unittest.TestCase):
    """超长商品名关键字提取（展示层，不改库——铁律 12）。"""

    def test_extracts_model_and_size(self):
        name, spec = _short_name("示例ZG301标准款长效三防热敏标签纸40*20mm-1250枚(白)(1卷/筒", 600)
        self.assertEqual("示例标准款长效三防热敏标签纸", name)
        self.assertIn("ZG301", spec)
        self.assertIn("40*20mm", spec)

    def test_trims_slash_and_bracket(self):
        name, _ = _short_name("大艺DY-283G三线LD绿光水平仪/配超黑锂电池/12台/件（只修不退不换）", 600)
        self.assertNotIn("/", name)
        self.assertNotIn("只修不退不换", name)

    def test_short_name_left_alone(self):
        self.assertEqual(("示例3029自封袋", ""), _short_name("示例3029自封袋", 600))

    def test_wide_paper_bigger_font(self):
        def max_span(w_mm, h_mm):
            return max((op[3], op[5] if len(op) > 5 else op[3])
                       for op in _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"],
                                                spec=ITEM["spec"], barcode=ITEM["barcode"],
                                                width_mm=w_mm, height_mm=h_mm, template={}, unit="个")
                       if op[0] == "txt")

        big = max_span(80, 50)
        small = max_span(30, 40)
        self.assertGreaterEqual(big, small, "大纸字号不应比小纸更小（面积利用不回退）")

    def test_all_elements_within_page_bounds(self):
        for w_mm, h_mm in PRESET_SIZES:
            w, h = w_mm * 8, h_mm * 8
            for style in ("qr", "barcode"):
                t = {"store_name": "演示门店", "code_style": style,
                     "price_scale": 4, "footer": "正品保障"}
                for it0 in (ITEM, LONG_ITEM):
                    for op in _price_tag_ops(it0["name"], it0["price"], it0["sku"], spec=it0["spec"],
                                             barcode=it0["barcode"], width_mm=w_mm, height_mm=h_mm,
                                             template=t, unit="个"):
                        if op[0] == "txt":
                            _, x, y, mul, text = op[:5]
                            xmul = op[5] if len(op) > 5 else mul
                            self.assertTrue(x >= 4 and x + _disp_w(text) * xmul <= w,
                                            "txt 横向越界 %s %r" % ((w_mm, h_mm), text))
                            self.assertTrue(0 <= y and y + 24 * max(mul, xmul) <= h,
                                            "txt 纵向越界 %s %r" % ((w_mm, h_mm), text))
                        elif op[0] == "qr":
                            _, x, y, cell, data = op
                            side = _qr_modules(data) * cell   # 真实边长（别再按 30 模块硬估）
                            self.assertTrue(x >= 0 and y >= 0 and x + side <= w and y + side <= h,
                                            "qr 越界 %s" % ((w_mm, h_mm),))
                        elif op[0] == "bar":
                            _, x, y, bh, narrow, _ = op
                            self.assertTrue(x >= 0 and y >= 0 and y + bh <= h and narrow in (1, 2, 3))

    def test_offset_zero_keeps_geometry(self):
        base = _tspl_price_tag(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                               width=40, height=30, barcode=ITEM["barcode"], template={}, unit="个")
        with_zero = _tspl_price_tag(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                                    width=40, height=30, barcode=ITEM["barcode"],
                                    template={"offset_x": 0, "offset_y": 0}, unit="个")
        self.assertEqual(base, with_zero)

    def test_offset_right_shift_within_request_and_dead_zone(self):
        """0.5mm 右移：请求 4dot，若版面已贴死区则按可移动量夹紧（绝不出纸）。"""

        def qr_of(template):
            ops = _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                                 barcode=ITEM["barcode"], width_mm=40, height_mm=30,
                                 template=template, unit="个")
            return [op for op in ops if op[0] == "qr"][0]

        b = qr_of({})
        m = qr_of({"offset_x": 0.5})
        self.assertGreaterEqual(m[1], b[1], "右移不得反向")
        self.assertLessEqual(m[1] - b[1], 4, "位移不超过请求量")
        self.assertEqual(b[2], m[2], "纵向不动")

    def test_offset_down_025mm_shifts_2dots(self):
        """0.25mm 下移 = 2dot（该纸型纵向有余量，不被夹紧）。"""

        def qr_of(template):
            ops = _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                                 barcode=ITEM["barcode"], width_mm=40, height_mm=30,
                                 template=template, unit="个")
            return [op for op in ops if op[0] == "qr"][0]

        b = qr_of({})
        m = qr_of({"offset_y": 0.25})
        self.assertEqual(b[2] + 2, m[2], "0.25mm 下移 = 2dot")

    def test_offset_extremes_stay_on_paper(self):
        # ±2mm（UI 上限）下所有元素仍不出纸、不进死区（水平 3dot / 纵向 2dot）
        for w_mm, h_mm in PRESET_SIZES:
            w, h = w_mm * 8, h_mm * 8
            for off in ({"offset_x": 2.0, "offset_y": 2.0}, {"offset_x": -2.0, "offset_y": -2.0}):
                for op in _price_tag_ops(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                                         barcode=ITEM["barcode"], width_mm=w_mm, height_mm=h_mm,
                                         template=off, unit="个"):
                    box = _op_box(op)
                    self.assertGreaterEqual(box[0], 3, "左死区 %s %s" % ((w_mm, h_mm), off))
                    self.assertGreaterEqual(box[2], 2, "上死区 %s %s" % ((w_mm, h_mm), off))
                    self.assertLessEqual(box[1], w - 3, "右死区 %s %s" % ((w_mm, h_mm), off))
                    self.assertLessEqual(box[3], h - 2, "下死区 %s %s" % ((w_mm, h_mm), off))

    def test_tspl_command_structure(self):
        out = _tspl_price_tag(ITEM["name"], ITEM["price"], ITEM["sku"], spec=ITEM["spec"],
                              width=30, height=40, template={"store_name": "演示门店"})
        lines = out.splitlines()
        self.assertEqual("GAP 2 mm, 0 mm", lines[1])
        self.assertTrue(any(re.match(r'^TEXT \d+,\d+,"TSS24.BF2",0,\d+,\d+,', line) for line in lines))
        self.assertEqual("PRINT 1", lines[-1])


if __name__ == "__main__":
    unittest.main()
