# -*- coding: utf-8 -*-
"""L10 追加：老王「预取」单测（agent_stream._prefetch_block）。

为什么单开一个文件：test_agent_stream.py 此刻带着别班未提交的改动（MULTI_AGENT 第 13 条
不代人落盘），新契约放独立文件免得和他们那一批搅进同一个 diff。

被测的提速假设：模型一跳 3.9~5.3s，而它要调的只读工具本身只要 0.1~0.97s，所以
「先花一跳决定调哪个工具」是白付的一跳——结果确定的查询就在交给模型前先跑掉。
"""
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import agent_stream  # noqa: E402
from core import ops as core_ops  # noqa: E402
from mcp import retaildemo_mcp  # noqa: E402


def _fake_call(payload, is_error=False):
    return mock.patch.object(
        retaildemo_mcp, "tools_call",
        return_value={"content": [{"type": "text",
                                   "text": json.dumps(payload, ensure_ascii=False)}],
                      "isError": is_error})


class PrefetchZeroArgOnlyTests(unittest.TestCase):
    """边界：只预取零参数的 op。要参数的（search / knowledge_search）交还模型自己推。"""

    def test_库存预警预取且空参(self):
        with _fake_call({"ok": True, "data": {"data": [{"name": "BV10"}], "total": 5241}}) as called:
            block = agent_stream._prefetch_block("看看哪些货缺货", [], [])
        self.assertEqual(called.call_args.args[0], {"name": "sx_low_stock", "arguments": {}})
        self.assertIn("BV10", block)
        self.assertIn("不必再调同类", block, "没告诉模型不用再查，就白付了预取那份")

    def test_待办预取(self):
        with _fake_call({"ok": True, "data": {"data": []}}) as called:
            agent_stream._prefetch_block("今天有什么要办的事", [], [])
        self.assertEqual(called.call_args.args[0]["name"], "sx_todos")

    def test_查价绝不预取_关键词剥不干净(self):
        """实测：「螺钉」500 命中首条是螺钉8*35，「螺钉多少钱」只剩 44 条且首条变了；
        「∅8mm膨胀螺栓」剥字与整句两种送法，前三名全是 14*18mm 方头接头。
        喂一份错数据比多等一跳毒得多——所以带参数的 op 一律不预取。"""
        with mock.patch.object(retaildemo_mcp, "tools_call") as called:
            for msg in ("3P空开多少钱", "∅8mm膨胀螺栓多少钱", "螺钉多少钱", "有没有8*35的"):
                self.assertIsNone(agent_stream._prefetch_block(msg, [], []), msg)
        called.assert_not_called()

    def test_知识检索不预取(self):
        with mock.patch.object(retaildemo_mcp, "tools_call") as called:
            self.assertIsNone(agent_stream._prefetch_block("3P空开替代用什么", [], []))
        called.assert_not_called()

    def test_零参数这条判据看注册表不看名单(self):
        """判据是 op 自己有没有 params，不是维护一张白名单——op 加了参数就自动退出预取。"""
        self.assertEqual(core_ops.OPS["low_stock"]["params"], {})
        self.assertTrue(core_ops.OPS["search"]["params"])


class PrefetchMustStepAsideTests(unittest.TestCase):
    def test_写意图绝不预取(self):
        """预取绕过 AI 循环，但票据红线不能被它凿穿：写操作一个字都不该提前执行。"""
        with mock.patch.object(retaildemo_mcp, "tools_call") as called:
            for msg in ("把这单出库", "帮我打印价签", "改价 5 块", "删掉这条"):
                self.assertIsNone(agent_stream._prefetch_block(msg, [], []), msg)
        called.assert_not_called()

    def test_图片与文件问题不预取(self):
        """识货/读文件必须先让模型动工具，塞一份猜来的数据只会误导。"""
        with mock.patch.object(retaildemo_mcp, "tools_call") as called:
            self.assertIsNone(agent_stream._prefetch_block("看看哪些货缺货", ["/tmp/a.jpg"], []))
            self.assertIsNone(agent_stream._prefetch_block("今天有什么要办的事", [], ["/tmp/b.xlsx"]))
        called.assert_not_called()

    def test_没有意图命中就不查(self):
        with mock.patch.object(retaildemo_mcp, "tools_call") as called:
            self.assertIsNone(agent_stream._prefetch_block("今晚吃啥", [], []))
        called.assert_not_called()

    def test_查询失败退回原路不阻断(self):
        with _fake_call({"ok": False, "error": "网关超时"}, is_error=True):
            self.assertIsNone(agent_stream._prefetch_block("看看哪些货缺货", [], []))

    def test_预取炸了也不许带崩主路(self):
        with mock.patch.object(retaildemo_mcp, "tools_call", side_effect=RuntimeError("boom")):
            self.assertIsNone(agent_stream._prefetch_block("看看哪些货缺货", [], []))

    def test_空回执不注入(self):
        with mock.patch.object(retaildemo_mcp, "tools_call", return_value={"content": []}):
            self.assertIsNone(agent_stream._prefetch_block("看看哪些货缺货", [], []))


class PrefetchWiringTests(unittest.TestCase):
    def _run(self, message, pre_text=None):
        seen = {}

        def fake_turn(ws, app_sid, prompt, started):
            seen["prompt"] = prompt
            return iter([])

        ctx = [_fake_call({"ok": True, "data": {"data": [{"name": "BV10"}], "total": 5241}})
               if pre_text is None else mock.patch.object(
                   retaildemo_mcp, "tools_call",
                   return_value={"content": [{"type": "text", "text": pre_text}], "isError": False})]
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://nowhere"), \
             mock.patch.object(agent_stream.agent_bridge, "acquire_run_slot", return_value=None), \
             mock.patch.object(agent_stream, "_connect", return_value=object()), \
             mock.patch.object(agent_stream, "_serve_turn", fake_turn), \
             ctx[0]:
            list(agent_stream.stream_events({"message": message, "staff": "店员",
                                             "session_id": "t-wiring"}))
        return seen["prompt"]

    def test_身份行仍在最前预取紧随其后(self):
        """SOUL.md 按身份应对认的就是 `[提问者：x]` 这一行——预取块只能跟在它后面。"""
        prompt = self._run("看看哪些货缺货")
        self.assertTrue(prompt.startswith("[提问者：店员]"), "身份行被预取块顶到后面去了")
        self.assertLess(prompt.index("提问者"), prompt.index("系统已实时查过"))
        self.assertLess(prompt.index("系统已实时查过"), prompt.index("看看哪些货缺货"))

    def test_不预取时提示词与改动前一致(self):
        prompt = self._run("3P空开多少钱", pre_text='{"ok": true, "data": {}}')
        self.assertNotIn("系统已实时查过", prompt)
        self.assertEqual(prompt, "[提问者：店员]\n3P空开多少钱")

    def test_回落CLI路不许多查一次(self):
        """SERVE_URL 未配（回落 CLI 路）有自己的历史提示词，不该再预取一遍。"""
        with mock.patch.object(agent_stream, "SERVE_URL", ""), \
             mock.patch.object(retaildemo_mcp, "tools_call") as called, \
             mock.patch.object(agent_stream.agent_bridge, "agent_run",
                               return_value={"success": True, "data": {"answer": "ok"}}):
            list(agent_stream.stream_events({"message": "看看哪些货缺货",
                                             "session_id": "t-noff"}))
        called.assert_not_called()


if __name__ == "__main__":
    unittest.main()
