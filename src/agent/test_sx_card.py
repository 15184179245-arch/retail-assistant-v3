# -*- coding: utf-8 -*-
"""src/agent/test_sx_card.py — sx_card 点单链路单测（离线，不连服务、不碰库）。

锁四件事：agent 只能点"已登记的只读 op"、点单不能带数据与样式、一次性取走、
点过的 op 不再被意图/工具路重复出卡。
"""
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import a2ui, agent_bridge, agent_stream, ui_store  # noqa: E402
from core import ops  # noqa: E402

ROWS = [{"name": "单芯铜塑线%d" % i, "spec": "BV10", "stock_qty": 0.01 * (i + 1),
         "unit": "捆", "sell_price": 1100.0, "barcode": "B%d" % i, "cost_price": 583.0}
        for i in range(9)]


class UiStore(unittest.TestCase):

    def setUp(self):
        ui_store.clear()

    def test_入队并一次性取走(self):
        ui_store.create("low_stock", columns=["name", "stock_qty"])
        got = ui_store.take(created_since=0)
        self.assertEqual([g["op"] for g in got], ["low_stock"])
        self.assertEqual(ui_store.take(created_since=0), [])   # 取走即删，防重复出帧

    def test_早于本轮的入队不取(self):
        ui_store.create("low_stock")
        self.assertEqual(ui_store.take(created_since=9e12), [])

    def test_limit越界归零由后续按默认处理(self):
        self.assertIsNone(ui_store.create("low_stock", limit=99)["limit"])
        self.assertEqual(ui_store.create("low_stock", limit=3)["limit"], 3)

    def test_非字符串列名被丢弃(self):
        spec = ui_store.create("low_stock", columns=["name", 7, None])
        self.assertEqual(spec["columns"], ["name"])

    def test_TTL过期(self):
        ui_store.create("low_stock")
        for stored in ui_store._SPECS.values():
            stored["expires"] = 0            # create 返回的是副本，要改就改队列里那份
        self.assertEqual(ui_store.take(created_since=0), [])


class OpCard(unittest.TestCase):

    def test_未知op直接拒绝(self):
        # run_op 的既有约定：op 返回 {"error": ...} 时仍包成 ok=True，错误在 data 里
        # （与 pending_create 同款），所以断言看 data.error。
        r = ops.run_op("card", {"op": "checkout"})
        self.assertIn("没有卡片配置", r["data"]["error"])

    def test_点单参数原样打回网关(self):
        posted = {}

        def fake_post(path, body):
            posted["path"], posted["body"] = path, body
            return {"ok": True, "data": {"card_id": "CARD-x"}}

        with mock.patch.object(ops, "_post", fake_post):
            r = ops.run_op("card", {"op": "low_stock", "columns": "name, stock_qty",
                                    "sort": "-stock_qty", "limit": "3"})
        self.assertTrue(r.get("ok"))
        self.assertEqual(posted["path"], "/api/agent/ui")
        self.assertEqual(posted["body"]["columns"], ["name", "stock_qty"])
        self.assertEqual(posted["body"]["limit"], 3)
        self.assertEqual(posted["body"]["sort"], "-stock_qty")

    def test_limit非数字被拒(self):
        r = ops.run_op("card", {"op": "low_stock", "limit": "很多"})
        self.assertIn("limit", r["data"]["error"])


class BridgeQueues(unittest.TestCase):

    def setUp(self):
        ui_store.clear()

    def test_未知op不入队(self):
        r = agent_bridge.ui_create({"op": "nosuch"})
        self.assertFalse(r["success"])
        self.assertEqual(ui_store.pending_count(), 0)

    def test_合法点单入队并回hint(self):
        r = agent_bridge.ui_create({"op": "low_stock", "columns": ["name"]})
        self.assertTrue(r["success"])
        self.assertIn("卡片已登记", r["data"]["hint"])
        self.assertEqual(ui_store.pending_count(), 1)


class StreamDrain(unittest.TestCase):

    def setUp(self):
        ui_store.clear()

    def _drain(self, exclude=None):
        return agent_stream._queued_ui_frames(0, exclude if exclude is not None else set())

    def test_点单出帧且只含点过的列(self):
        ui_store.create("low_stock", columns=["name", "sell_price"])
        with mock.patch.object(agent_stream, "_op_run",
                               lambda o, a: {"ok": True, "data": {"data": ROWS, "total": 5241}}):
            frames = self._drain()
        self.assertEqual([f["type"] for f in frames], ["ui"])
        tbl = [c for c in frames[0]["messages"][-1]["updateComponents"]["components"]
               if c["component"] == "Table"][0]
        self.assertEqual([c["key"] for c in tbl["columns"]], ["name", "sell_price"])

    def test_成本价点不到(self):
        ui_store.create("low_stock", columns=["cost_price"])
        with mock.patch.object(agent_stream, "_op_run",
                               lambda o, a: {"ok": True, "data": {"data": ROWS}}):
            self.assertEqual(self._drain(), [])      # 全部列不可用 → 不出卡，不抛到流上

    def test_agent点过的op不再被意图路重复出卡(self):
        ui_store.create("low_stock")
        taken = set()
        with mock.patch.object(agent_stream, "_op_run",
                               lambda o, a: {"ok": True, "data": {"data": ROWS}}):
            first = self._drain(taken)
            second = agent_stream._ui_frames("库存预警有哪些", [], exclude=taken)
        self.assertEqual(len(first), 1)
        self.assertEqual(second, [])

    def test_取数抛异常不打死流(self):
        ui_store.create("low_stock")

        def boom(op, args):
            raise RuntimeError("db down")

        with mock.patch.object(agent_stream, "_op_run", boom):
            self.assertEqual(self._drain(), [])


if __name__ == "__main__":
    unittest.main()
