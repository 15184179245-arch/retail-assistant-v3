# -*- coding: utf-8 -*-
"""F4: ops 注册表契约 + sx 参数解析纯函数测试。"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))), "src", "cli"))

from core.ops import (  # noqa: E402
    OPS, aggregate_debts, dedupe_candidates, pick_candidate, price_band, run_op,
)
from cli.sx import ALIASES, parse_args, render  # noqa: E402


class TestOpsRegistry(unittest.TestCase):

    def test_每个op必有名称描述与处理函数(self):
        for name, entry in OPS.items():
            self.assertTrue(entry["desc"], f"{name} 缺描述")
            self.assertTrue(callable(entry["fn"]), f"{name} 缺处理函数")

    def test_写操作checkout在册且注明须确认(self):
        self.assertIn("checkout", OPS)
        self.assertIn("店员确认", OPS["checkout"]["desc"])

    def test_未知op返回ok_false并列出可用项(self):
        r = run_op("不存在的操作", {})
        self.assertFalse(r["ok"])
        self.assertIn("available", r)

    def test_别名覆盖全部常用中文操作(self):
        for zh in ("查", "问", "出库", "状态", "待办"):
            self.assertIn(zh, ALIASES)

    def test_L8四个只读op在册且只读不触发确认(self):
        for name in ("debts", "governance", "reco", "identify"):
            self.assertIn(name, OPS, f"{name} 未注册")

    def test_联网与知识检索op在册且只读(self):
        # 2026-09-27 老板令：联网检索 + 知识库利用 + 读网页
        for name in ("web_search", "web_read", "knowledge_search"):
            self.assertIn(name, OPS, f"{name} 未注册")
            self.assertFalse(OPS[name].get("write"), f"{name} 不应是写操作")
            self.assertNotIn("写操作", OPS[name]["desc"], f"{name} 应为只读")


class TestPriceBand(unittest.TestCase):
    """价格档聚合（2026-09-21 慢答治理②）：把「最便宜的是哪档」从 5~9 跳压成 1 次统计。"""

    ROWS = [
        {"name": "公牛 3P", "spec": "LRS-63a-25A", "sell_price": 33.0, "stock_qty": 4, "sku": "A1"},
        {"name": "天正 3P", "spec": "DZ47-63 C10", "sell_price": 18.5, "stock_qty": 5, "sku": "A2"},
        {"name": "德力西 3P", "spec": "DZ47S C32", "sell_price": 22.0, "stock_qty": 0, "sku": "A3"},
        {"name": "还没录价的", "spec": "8*35", "sell_price": 0, "stock_qty": 9, "sku": "A4"},
        {"name": "空价的", "spec": "M8", "sell_price": None, "stock_qty": 1, "sku": "A5"},
    ]

    def test_未定价不参与最低价且被如实计数(self):
        """店里上千条 sell_price=0 是「还没录价」，报成「0 元」就是错账。"""
        out = price_band(self.ROWS, top=5)
        self.assertEqual(out["min"], 18.5)
        self.assertEqual(out["unpriced"], 2)
        self.assertEqual(out["priced"], 3)
        self.assertNotIn(0, [r["sell_price"] for r in out["cheapest"]])

    def test_按价格升序且top截断(self):
        out = price_band(self.ROWS, top=2)
        self.assertEqual([r["name"] for r in out["cheapest"]], ["天正 3P", "德力西 3P"])

    def test_中位数奇偶都算对(self):
        self.assertEqual(price_band(self.ROWS)["median"], 22.0)          # 3 个取中间
        two = self.ROWS[:2]
        self.assertEqual(price_band(two)["median"], 25.75)               # 2 个取均值

    def test_全空与全无价不炸(self):
        for rows in ([], [{"sell_price": 0}, {"sell_price": None}]):
            out = price_band(rows)
            self.assertIsNone(out["min"])
            self.assertEqual(out["cheapest"], [])
            self.assertTrue(out["success"])

    def test_bool不当价格算(self):
        self.assertEqual(price_band([{"sell_price": True}])["unpriced"], 1)

    def test_op注册为只读且未扫全时如实标注(self):
        self.assertIn("price_band", OPS)
        self.assertFalse(OPS["price_band"]["write"])
        envelope = {"success": True, "data": self.ROWS, "total": 174, "keyword": "3P空开"}
        with mock.patch("core.ops._get", return_value=envelope) as g:
            r = run_op("price_band", {"q": "3P空开"})
        self.assertIn("/api/search?q=3P%E7%A9%BA%E5%BC%80", g.call_args.args[0])
        self.assertTrue(r["ok"])
        self.assertEqual(r["data"]["hits"], 174)
        self.assertEqual(r["data"]["scanned"], 5)
        self.assertIn("只回了前 5 条", r["data"]["note"], "扫不全却不标注＝把部分当全部报")

    def test_top传垃圾值回落默认且超大被夹紧(self):
        with mock.patch("core.ops._get", return_value={"data": [], "total": 0}):
            self.assertTrue(run_op("price_band", {"q": "螺钉", "top": "abc"})["ok"])
        many = [{"name": "n%d" % i, "sell_price": float(i)} for i in range(1, 13)]
        with mock.patch("core.ops._get", return_value={"data": many, "total": 12}):
            got = run_op("price_band", {"q": "x", "top": "99"})["data"]
        self.assertEqual(len(got["cheapest"]), 10, "top 要夹紧，不能由着模型传 99")
        self.assertEqual(got["cheapest"][0]["sell_price"], 1.0)


class TestCliParse(unittest.TestCase):

    def test_k等于v解析为dict且数字转int(self):
        args = parse_args(["q=螺钉", "min_qty=1"])
        self.assertEqual(args["q"], "螺钉")
        self.assertEqual(args["min_qty"], 1)

    def test_裸词落入q与message(self):
        args = parse_args(["螺钉"])
        self.assertEqual(args["q"], "螺钉")
        self.assertEqual(args["message"], "螺钉")

    def test_渲染_搜索结果人话输出(self):
        out = render("search", {"ok": True, "data": {"data": [
            {"name": "螺钉", "spec": "M4", "sell_price": 1.0,
             "stock_qty": 8, "location": "", "sku": "60.1"},
        ]}})
        self.assertIn("螺钉", out)
        self.assertIn("库存8", out)


class TestL8PureFunctions(unittest.TestCase):

    def test_欠款聚合_按客户汇总且散客兜底(self):
        bills = [
            {"status": "pending", "customer_name": "老张", "total": 100, "paidAmount": 20},
            {"status": "pending", "customer_name": "老张", "total": 30.5},
            {"status": "pending", "total": 50},  # 无客户名 → 散客
            {"status": "pending", "customer_name": "李四", "total": 0, "paidAmount": None},
            {"status": "paid", "customer_name": "老张", "total": 999},
        ]
        r = aggregate_debts(bills, top=2)
        self.assertEqual(r["pending_count"], 4)
        self.assertEqual(r["total_outstanding"], 160.5)
        self.assertEqual(r["top"][0], {"name": "老张", "amount": 110.5})
        self.assertEqual(r["top"][1]["name"], "散客")

    def test_欠款聚合_脏金额容错为零(self):
        r = aggregate_debts([{"status": "pending", "customer_name": "王五", "total": "abc"}])
        self.assertEqual(r["total_outstanding"], 0)
        self.assertEqual(r["pending_count"], 1)

    def test_候选挑选_有货优先无货兜底空返None(self):
        cands = [{"name": "a", "stock_qty": 0}, {"name": "b", "stock_qty": 3}]
        self.assertEqual(pick_candidate(cands)["name"], "b")
        self.assertEqual(pick_candidate([{"name": "a", "stock_qty": 0}])["name"], "a")
        self.assertIsNone(pick_candidate([]))
        self.assertIsNone(pick_candidate(None))

    def test_候选挑选_有货有价优先于有货未定价(self):
        # 2026-09-27 看图复盘：未定价泛品类卡曾顶上首位，老王照报「0元」假价
        cands = [{"name": "泛品类球泡", "stock_qty": 9},                 # 有货未定价
                 {"name": "荣事达58W", "stock_qty": 15, "sell_price": 30}]
        self.assertEqual(pick_candidate(cands)["name"], "荣事达58W")

    def test_候选挑选_有价无货让位有货有价但压过未定价(self):
        cands = [{"name": "无货有价", "stock_qty": 0, "sell_price": 42},
                 {"name": "有货未定价", "stock_qty": 4}]
        self.assertEqual(pick_candidate(cands)["name"], "有货未定价")  # 有货 > 有价
        cands2 = [{"name": "无货有价", "stock_qty": 0, "sell_price": 42},
                  {"name": "无货未定价", "stock_qty": 0}]
        self.assertEqual(pick_candidate(cands2)["name"], "无货有价")

    def test_识货对照_未定价标记与提示(self):
        # 2026-09-27 看图复盘：0 元＝未定价不是真价格；候选全未定价时必须显式提醒
        from core import ops as ops_mod
        items = [{"name": "泛品类LED球泡", "sku": "S1", "stock_qty": 9, "sell_price": 0}]
        with mock.patch.object(ops_mod, "_get",
                               return_value={"success": True,
                                             "data": {"items": items}}):
            r = run_op("identify", {"names": ["LED球泡"]})
        self.assertTrue(r["data"]["candidates"][0]["unpriced"])
        self.assertIn("未定价", r["data"].get("hint", ""))

    def test_识货对照_有价候选不误标(self):
        from core import ops as ops_mod
        items = [{"name": "荣事达58W", "sku": "S2", "stock_qty": 15, "sell_price": 30}]
        with mock.patch.object(ops_mod, "_get",
                               return_value={"success": True,
                                             "data": {"items": items}}):
            r = run_op("identify", {"names": ["58W球泡"]})
        self.assertFalse(r["data"]["candidates"][0]["unpriced"])
        self.assertNotIn("hint", r["data"])

    def test_识货去重_按sku截断上限(self):
        items = [{"sku": "1", "name": "a"}, {"sku": "1", "name": "a2"},
                 {"sku": "2", "name": "b"}, {"sku": None, "name": "x"}]
        out = dedupe_candidates(items, cap=10)
        self.assertEqual([i["sku"] for i in out], ["1", "2"])

    def test_L8渲染_欠款与识货人话输出(self):
        d = render("debts", {"ok": True, "data": {
            "pending_count": 2, "total_outstanding": 130.5,
            "top": [{"name": "老张", "amount": 110.5}]}})
        self.assertIn("¥130.5", d)
        self.assertIn("老张", d)
        i = render("identify", {"ok": True, "data": {
            "candidates": [{"matched_name": "角阀", "price": 3.5, "stock": 10, "sku": "8.1"}],
            "unmatched": ["神秘零件"]}})
        self.assertIn("角阀", i)
        self.assertIn("神秘零件", i)


class TestOpsExecute(unittest.TestCase):
    """回归（I30/A1）：只测「op 已注册」测不出漏 return——governance 曾返回 {'ok': True, 'data': None} 门禁假绿。"""

    def test_governance执行返回真实数据而非None(self):
        from core.ops import _data_db_path
        wdb = _data_db_path(("retaildemo_WUJIN_DB", "WUJIN_DB_PATH"), "wujin.db")
        if not os.path.exists(wdb):
            self.skipTest("本机无 wujin.db，跳过执行级断言")
        r = run_op("governance", {})
        self.assertTrue(r["ok"], f"governance 执行失败: {r.get('error')}")
        self.assertIsInstance(r["data"], dict, "op_governance 漏 return（data=None）")
        self.assertIn("data_marks", r["data"])
        self.assertIn("barcode_alias", r["data"])


if __name__ == "__main__":
    unittest.main()
