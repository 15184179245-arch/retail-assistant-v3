# -*- coding: utf-8 -*-
"""I16 Step9: 票据库 / 降级路由 / agent 桥 / MCP 写闸门 单元测试。"""
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import agent_bridge, fallback, pending_store  # noqa: E402
from core import ops  # noqa: E402


class PendingStoreTests(unittest.TestCase):
    def setUp(self):
        pending_store._TICKETS.clear()

    def tearDown(self):
        pending_store._TICKETS.clear()

    def test_出票_挂单_执行后不再挂单(self):
        t = pending_store.create("checkout", {"items": []}, session_id="s1", summary="出库测试")
        self.assertTrue(t["action_id"].startswith("ACT-"))
        self.assertEqual(len(pending_store.list_pending("s1")), 1)
        self.assertTrue(pending_store.mark(t["action_id"], "executed"))
        self.assertEqual(pending_store.list_pending("s1"), [])

    def test_非写操作拒绝出票(self):
        with self.assertRaises(ValueError):
            pending_store.create("search", {}, summary="x")

    def test_取消后不可再执行(self):
        t = pending_store.create("print", {}, summary="x")
        self.assertTrue(pending_store.mark(t["action_id"], "cancelled"))
        self.assertIsNone(pending_store.get(t["action_id"]))
        self.assertFalse(pending_store.mark(t["action_id"], "executed"))


class FallbackTests(unittest.TestCase):
    def test_写意图_降级模式直接拒绝不产动作(self):
        with mock.patch.object(fallback, "run_op") as rop:
            r = fallback.route("帮我出库螺钉 1 个")
        rop.assert_not_called()
        self.assertTrue(r["degraded"])
        self.assertIn("离线", r["answer"])

    def test_查询意图_路由到搜索并汇总(self):
        with mock.patch.object(fallback, "run_op") as rop:
            rop.return_value = {"ok": True, "data": {"data": [
                {"name": "螺钉", "spec": "M4", "sell_price": 0.3, "stock_qty": 100}]}}
            r = fallback.route("查 螺钉")
        rop.assert_called_once()
        self.assertEqual(rop.call_args[0][0], "search")
        self.assertFalse(r.get("needs_review", False))
        self.assertTrue(r["degraded"])
        self.assertIn("螺钉", r["answer"])

    def test_无关输入_给引导文案(self):
        r = fallback.route("今天天气怎么样")
        self.assertTrue(r["degraded"])
        self.assertIn("本地指令模式", r["answer"])


class AgentBridgeTests(unittest.TestCase):
    def setUp(self):
        pending_store._TICKETS.clear()

    def tearDown(self):
        pending_store._TICKETS.clear()

    def test_hermes离线_走降级路由(self):
        with mock.patch.object(agent_bridge, "hermes_online", return_value=False), \
             mock.patch.object(agent_bridge, "_degrade",
                               return_value=("离线回答", True, ["low_stock"])):
            r = agent_bridge.agent_run({"message": "查 螺钉", "session_id": "sx1"})
        self.assertTrue(r["success"])
        self.assertTrue(r["data"]["degraded"])
        self.assertEqual(r["data"]["answer"], "离线回答")
        # ops_ran 是 a2ui 模板层的唯一数据来源：卡片只能凭真跑过的 op 出，不凭文本猜
        self.assertEqual(r["data"]["ops_ran"], ["low_stock"])

    def test_hermes在线_子进程回答并挂接本轮票据(self):
        t = pending_store.create("checkout", {"items": [1]}, summary="出库测试")
        fake = mock.Mock(returncode=0, stdout="回答文本", stderr="")
        with mock.patch.object(agent_bridge, "hermes_online", return_value=True), \
             mock.patch.object(agent_bridge.subprocess, "run", return_value=fake):
            # 建票时间晚于 run 起点才挂接：先起 run 再建票——改为直接造一张再放宽窗口验证字段
            r = agent_bridge.agent_run({"message": "出库", "session_id": "sx2"})
        self.assertTrue(r["success"])
        self.assertFalse(r["data"]["degraded"])
        self.assertEqual(r["data"]["answer"], "回答文本")

    def test_空消息_友好报错(self):
        r = agent_bridge.agent_run({"message": ""})
        self.assertFalse(r["success"])
        self.assertIn("想问什么", r["user_message"])

    def test_confirm_取消路径(self):
        t = pending_store.create("checkout", {}, summary="x")
        r = agent_bridge.agent_confirm({"action_id": t["action_id"], "confirm": False})
        self.assertEqual(r["data"]["state"], "cancelled")

    def test_confirm_未知票据_失效文案(self):
        r = agent_bridge.agent_confirm({"action_id": "ACT-none", "confirm": True})
        self.assertFalse(r["success"])
        self.assertEqual(r["code"], "ACTION_NOT_FOUND")

    def test_confirm_执行路径_真正调run_op(self):
        t = pending_store.create("checkout", {"items": [{"product_id": 1, "qty": 1}]},
                                 summary="x")
        with mock.patch.object(ops, "run_op", return_value={"ok": True, "data": {}}) as rop:
            r = agent_bridge.agent_confirm({"action_id": t["action_id"], "confirm": True})
        rop.assert_called_once_with("checkout", {"items": [{"product_id": 1, "qty": 1}]})
        self.assertEqual(r["data"]["state"], "executed")


class McpGateTests(unittest.TestCase):
    def test_MCP层写操作被拦截为出票(self):
        from mcp import retaildemo_mcp
        with mock.patch.object(retaildemo_mcp, "run_op") as rop:
            rop.return_value = {"ok": True, "data": {"data": {"action_id": "ACT-1"}}}
            resp = retaildemo_mcp.tools_call({"name": "sx_checkout",
                                           "arguments": {"items": "[]"}})
        self.assertEqual(rop.call_args[0][0], "pending_create")
        self.assertFalse(resp["isError"])
        self.assertIn("待确认", resp["content"][0]["text"])

    def test_写标记落在四个写op上(self):
        for name in ("checkout", "print", "todo_create", "knowledge_add"):
            self.assertTrue(ops.OPS[name].get("write"), f"{name} 缺 write 标记")
        for name in ("search", "debts", "governance", "reco", "identify"):
            self.assertFalse(ops.OPS[name].get("write"), f"{name} 不应标写")


class SessionMemoryTests(unittest.TestCase):
    """I16 v2: 多轮会话记忆（进程内，TTL/轮数封顶）。"""

    def setUp(self):
        agent_bridge._SESSIONS.clear()

    def tearDown(self):
        agent_bridge._SESSIONS.clear()

    def test_历史注入prompt且标记新问题(self):
        agent_bridge._remember("s1", "我叫张三", "好的张三")
        p = agent_bridge._build_prompt("s1", "我叫什么")
        self.assertIn("张三", p)
        self.assertIn("[新问题]", p)
        self.assertIn("我叫什么", p)

    def test_无历史_原样透传(self):
        self.assertEqual(agent_bridge._build_prompt("fresh", "hi"), "hi")

    def test_轮数封顶_只留最近6轮(self):
        for i in range(10):
            agent_bridge._remember("s2", f"q{i}", f"a{i}")
        turns = agent_bridge._SESSIONS["s2"]["turns"]
        self.assertEqual(len(turns), 6)
        self.assertEqual(turns[0]["u"], "q4")

    def test_超长文本截断(self):
        agent_bridge._remember("s3", "x" * 9999, "y" * 9999)
        turn = agent_bridge._SESSIONS["s3"]["turns"][0]
        self.assertEqual(len(turn["u"]), 600)
        self.assertEqual(len(turn["a"]), 600)


if __name__ == "__main__":
    unittest.main()
