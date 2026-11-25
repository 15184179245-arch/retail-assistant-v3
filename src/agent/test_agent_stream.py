# -*- coding: utf-8 -*-
"""L10: agent_stream 流式桥单测（帧翻译纯函数 / 降级回落 / 会话映射 / stub WS，不真连）。"""
import json
import os
import sys
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import a2ui, agent_bridge, agent_stream  # noqa: E402


class FrameTextTests(unittest.TestCase):
    def test_顶层_delta键取文本(self):
        self.assertEqual(agent_stream.frame_text({"type": "message.delta", "delta": "你好"}), "你好")

    def test_顶层text与chunk兜底(self):
        self.assertEqual(agent_stream.frame_text({"text": "abc"}), "abc")
        self.assertEqual(agent_stream.frame_text({"chunk": "xyz"}), "xyz")

    def test_payload嵌套文本位(self):
        self.assertEqual(agent_stream.frame_text({"payload": {"delta": "deep"}}), "deep")

    def test_非文本帧返回空(self):
        self.assertEqual(agent_stream.frame_text({"type": "thinking.delta"}), "")
        self.assertEqual(agent_stream.frame_text({"delta": 123}), "")


class EndEventTests(unittest.TestCase):
    def test_event帧类型收尾(self):
        self.assertTrue(agent_stream.is_end_event("message.complete", "event"))
        self.assertTrue(agent_stream.is_end_event("turn.complete", "event"))
        self.assertFalse(agent_stream.is_end_event("message.delta", "event"))

    def test_顶层method收尾(self):
        self.assertTrue(agent_stream.is_end_event("", "session.idle"))


class SidExtractionTests(unittest.TestCase):
    def test_session_id优先(self):
        self.assertEqual(agent_stream.sid_from_result({"session_id": "a", "sid": "b"}), "a")

    def test_sid兜底(self):
        self.assertEqual(agent_stream.sid_from_result({"sid": "b"}), "b")

    def test_首个字符串值兜底(self):
        self.assertEqual(agent_stream.sid_from_result({"foo": "bar", "n": 1}), "bar")

    def test_空回包返回空串(self):
        self.assertEqual(agent_stream.sid_from_result({}), "")
        self.assertEqual(agent_stream.sid_from_result(None), "")


class StreamEventsTests(unittest.TestCase):
    def setUp(self):
        # 点单队列是进程级全局，不清会跨用例泄漏（实测让一条老用例多出 ui 帧）
        from agent import ui_store
        ui_store.clear()
        # 会话映射落盘隔离：单测绝不写生产 data/ 目录
        self._tmp_map = os.path.join(
            os.environ.get("TEMP", "/tmp"), "agent_serve_sessions_test.json")
        try:
            os.remove(self._tmp_map)
        except OSError:
            pass
        self._map_patcher = mock.patch.object(agent_stream, "_MAP_FILE", self._tmp_map)
        self._map_patcher.start()
        agent_stream._SERVE_SESSIONS.clear()
        agent_bridge._SESSIONS.clear()
        agent_bridge._LAST_ASK.clear()

    def tearDown(self):
        self._map_patcher.stop()
        try:
            os.remove(self._tmp_map)
        except OSError:
            pass
        agent_stream._SERVE_SESSIONS.clear()
        agent_bridge._SESSIONS.clear()
        agent_bridge._LAST_ASK.clear()

    def test_空消息报错事件(self):
        evts = list(agent_stream.stream_events({"message": "  "}))
        self.assertEqual(evts[0]["type"], "error")
        self.assertEqual(evts[0]["code"], "BAD_REQUEST")

    def test_reset清映射并回固定话(self):
        agent_stream._SERVE_SESSIONS["s1"] = "hermes-1"
        evts = list(agent_stream.stream_events({"reset": True, "message": "x", "session_id": "s1"}))
        self.assertEqual(evts[0]["type"], "done")
        self.assertNotIn("s1", agent_stream._SERVE_SESSIONS)
        self.assertEqual(evts[0]["data"]["answer"], "好，咱重新开始。")

    def test_serve未配置回落全文路径(self):
        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "全文回答", "degraded": False,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", ""), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned) as run:
            evts = list(agent_stream.stream_events({"message": "查价", "session_id": "s1"}))
        run.assert_called_once()
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual(evts[0]["text"], "全文回答")
        self.assertEqual(evts[1]["data"]["answer"], "全文回答")

    def test_回落失败出error事件(self):
        canned = {"success": False, "code": "RATE_LIMITED",
                  "user_message": "老王还在上一问里，请 3 秒后再问。"}
        with mock.patch.object(agent_stream, "SERVE_URL", ""), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned):
            evts = list(agent_stream.stream_events({"message": "查价", "session_id": "s1"}))
        self.assertEqual(evts[0]["type"], "error")
        self.assertEqual(evts[0]["code"], "RATE_LIMITED")

    def test_serve限速直接拒绝不进serve(self):
        agent_bridge._LAST_ASK["s1"] = __import__("time").time()
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://127.0.0.1:9/ws"), \
             mock.patch.object(agent_stream, "_connect", side_effect=AssertionError("不该连")):
            evts = list(agent_stream.stream_events({"message": "查价", "session_id": "s1"}))
        self.assertEqual(evts[0]["type"], "error")
        self.assertEqual(evts[0]["code"], "RATE_LIMITED")

    def test_serve全链stub_ws流式收包(self):
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-9"}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "thinking.delta", "delta": "想"}},
            {"method": "event", "params": {"type": "message.delta", "delta": "你"}},
            {"method": "event", "params": {"type": "message.delta", "delta": "好"}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def __init__(self):
                self.sent = []

            def send(self, s):
                self.sent.append(json.loads(s))

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        stub = StubWs()
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=stub):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["thinking", "delta", "delta", "done"])
        self.assertEqual("".join(e["text"] for e in evts if e["type"] == "delta"), "你好")
        self.assertEqual(evts[-1]["data"]["degraded"], False)
        # 会话映射已存（带轮换计数），且 prompt.submit 带 serve 会话 id
        self.assertEqual(agent_stream._SERVE_SESSIONS["s1"]["sid"], "hs-9")
        self.assertEqual(agent_stream._SERVE_SESSIONS["s1"]["stored"], "")
        self.assertEqual(agent_stream._SERVE_SESSIONS["s1"]["turns"], 1)  # 本回合已计入轮换防线
        submit = [s for s in stub.sent if s.get("method") == "prompt.submit"][0]
        self.assertEqual(submit["params"]["session_id"], "hs-9")
        self.assertEqual(submit["params"]["text"], "在吗")

    def test_工具消息的complete不掐流_等真回答(self):
        """2026-09-10 原始帧实证：思考/工具消息也发 message.complete，只有拿到过
        回答增量之后的 complete 才是回合终点（第二问零 delta 的根因回归）。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-1"}},
            {"method": "event", "params": {"type": "message.start"}},      # 工具/思考消息
            {"method": "event", "params": {"type": "thinking.delta", "payload": {"text": "想"}}},
            {"method": "event", "params": {"type": "message.complete"}},   # 无增量，不许掐流
            {"method": "event", "params": {"type": "message.start"}},      # 真回答消息
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "你上次问了"}}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "在吗"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()):
            evts = list(agent_stream.stream_events({"message": "我上一句问了什么", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["thinking", "delta", "delta", "done"])
        self.assertEqual("".join(e["text"] for e in evts if e["type"] == "delta"), "你上次问了在吗")

    def test_工具事件透传_店员可见老王在动手(self):
        """hermes-android 协议对照：tool.start/tool.complete（payload.name）。
        tool.start 透传为 {"type":"tool","name":...}；tool.complete 只清状态不外发。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-3"}},
            {"method": "event", "params": {"type": "tool.start", "payload": {"name": "sx_search_low_stock"}}},
            {"method": "event", "params": {"type": "tool.complete", "payload": {"name": "sx_search_low_stock"}}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "50+ 种偏低"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                f = frames.pop(0)
                return f if isinstance(f, str) else json.dumps(f)

            def settimeout(self, t):
                pass

            def close(self):
                pass

        rows = [{"name": "单芯铜塑线%d" % i, "spec": "BV10", "stock_qty": 0.01,
                 "unit": "捆", "sell_price": 1100.0, "barcode": "B%d" % i,
                 "cost_price": 583.0} for i in range(9)]
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()), \
             mock.patch.object(agent_stream, "_op_run",
                               return_value={"ok": True, "data": {"data": rows}}):
            evts = list(agent_stream.stream_events({"message": "库存健康吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["tool", "delta", "ui", "done"])
        self.assertEqual(evts[0]["name"], "sx_search_low_stock")
        # ui 帧必须先于 done，APP 才能把卡挂到本轮回答上
        msgs = evts[2]["messages"]
        self.assertEqual(msgs[0]["createSurface"]["surfaceId"], "low_stock")
        data_rows = [m["updateDataModel"]["value"] for m in msgs
                     if "updateDataModel" in m and m["updateDataModel"]["path"] == "/rows"]
        self.assertEqual(len(data_rows[0]), agent_stream.a2ui.MAX_ROWS)
        self.assertNotIn("cost_price", data_rows[0])  # 成本价不下发手机

    def test_带参数的op不许被意图触发(self):
        # search 需要查询词，空参跑会拿回垃圾结果再画成卡 —— 名单外一律不触发
        self.assertNotIn("search", a2ui.CATALOG["intent"]["safeOps"])
        self.assertNotIn("quick_pricing", a2ui.CATALOG["intent"]["safeOps"])
        with mock.patch.object(agent_stream, "_op_run",
                               side_effect=AssertionError("不该被调用")):
            frames = agent_stream._ui_frames("有没有 4 分的球阀", [])   # 命中 search 意图
        self.assertEqual(frames, [])

    def test_单帧坏数据不杀流(self):
        """hermes-android 同款教训：一帧解析失败不许整个流崩掉，跳过继续。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-4"}},
            "garbage{{{ not-json",
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "还好"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                f = frames.pop(0)
                return f if isinstance(f, str) else json.dumps(f)

            def settimeout(self, t):
                pass

            def close(self):
                pass

        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()):
            evts = list(agent_stream.stream_events({"message": "怎么样", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])

    def test_session_info_running_false_回合跑过后权威终结(self):
        """hermes-android 同款结论：message.complete 会漏掉被打断/压缩的回合，
        session.info(running=false) 是权威空闲信号——回合跑过后立即收尾，不干等 recv 超时。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-5"}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "thinking.delta", "payload": {"text": "想"}}},
            {"method": "event", "params": {"type": "message.complete"}},   # 无增量，不许掐流
            {"method": "event", "params": {"type": "session.info", "payload": {"running": False}}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "不该到达"}}},
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["thinking", "done"])

    def test_submit后首条闲态心跳不掐流_等真回答(self):
        """2026-09-19 serve 0.21.3 实测（连不上根因）：prompt.submit 后立刻先推一条
        session.info{running:false}（闲态心跳），旧判定在此掐流 → APP 只收到空 done。
        现要求只有回合跑过（message.start + message.complete）后的 running=false 才收尾。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-6"}},
            {"method": "event", "params": {"type": "session.info", "payload": {"running": False}}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "thinking.delta", "payload": {"text": "想"}}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "2"}}},
            {"method": "event", "params": {"type": "message.complete"}},
            {"method": "event", "params": {"type": "session.info", "payload": {"running": False}}},
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()):
            evts = list(agent_stream.stream_events({"message": "1+1", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["thinking", "delta", "done"])
        self.assertEqual([e["text"] for e in evts if e["type"] == "delta"], ["2"])

    def test_serve会话过期4001_stored_id_resume找回记忆(self):
        """2026-09-19 老板令「会话记忆不丢」：serve 侧 ws_orphan_reap 回收会话后转录仍在
        会话库（stored_session_id）。4001 时桥用 stored id session.resume 唤醒原会话，
        记忆接续；无 stored id（旧映射/重启清库）才退回全新会话。"""
        frames_a = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-a",
                                                   "stored_session_id": "20260919_1"}},
            {"jsonrpc": "2.0", "id": 2, "error": {"code": 4001, "message": "session not found"}},
            # resume 回包（id=3）：serve 铸造新运行时 sid，转录已恢复
            {"jsonrpc": "2.0", "id": 3, "result": {"session_id": "hs-b", "resumed": "20260919_1"}},
            {"method": "event", "params": {"type": "session.info", "payload": {"running": False}}},
            # 重提交（id=4）后的回答流
            {"jsonrpc": "2.0", "id": 4},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "9"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def __init__(self):
                self.submits = []

            def send(self, s):
                m = json.loads(s)
                if m.get("method") == "prompt.submit":
                    self.submits.append(m)

            def recv(self):
                return json.dumps(frames_a.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        stub = StubWs()
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=stub):
            evts = list(agent_stream.stream_events({"message": "数字是几", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual([e["text"] for e in evts if e["type"] == "delta"], ["9"])
        # 映射更新为 resume 后的新运行时 sid，stored id 保持
        self.assertEqual(agent_stream._SERVE_SESSIONS.get("s1", {}).get("sid"), "hs-b")
        self.assertEqual(agent_stream._SERVE_SESSIONS.get("s1", {}).get("stored"), "20260919_1")
        # 二次提交打到 resume 出的新会话上
        self.assertEqual(len(stub.submits), 2)
        self.assertEqual(stub.submits[0]["params"]["session_id"], "hs-a")
        self.assertEqual(stub.submits[1]["params"]["session_id"], "hs-b")

    def test_serve会话过期4001_无stored_id才重建新会话(self):
        """无 stored id（旧版映射/会话未落库）时：4001 → 换新 socket 重连新建（记忆从新）。"""
        frames_a = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-old"}},
            {"jsonrpc": "2.0", "id": 2, "error": {"code": 4001, "message": "session not found"}},
        ]
        frames_b = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-new"}},
            {"method": "event", "params": {"type": "session.info", "payload": {"running": False}}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "新会话"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def __init__(self, frames):
                self._frames = list(frames)

            def send(self, s):
                pass

            def recv(self):
                return json.dumps(self._frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        sockets = [StubWs(frames_a), StubWs(frames_b)]
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", side_effect=lambda: sockets.pop(0)):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual([e["text"] for e in evts if e["type"] == "delta"], ["新会话"])
        self.assertEqual(agent_stream._SERVE_SESSIONS.get("s1", {}).get("sid"), "hs-new")
        self.assertEqual(agent_stream._SERVE_SESSIONS.get("s1", {}).get("stored"), "")

    def test_活跃回合静默_软停不回落CLI(self):
        """2026-09-19 实锤（串成两个会话）：serve 侧还在调工具只是慢，桥超时放弃后回落
        CLI 把同一问题再跑一遍。终端式流传输后：回合活跃（见过 message.start）时静默
        = 软停（出提示文案+done），绝不回落。"""
        class WebSocketTimeoutException(Exception):
            pass

        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-slow"}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "tool.start", "payload": {"name": "sx_xzg"}}},
            WebSocketTimeoutException("Connection timed out"),  # 长工具链静默
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                f = frames.pop(0)
                if isinstance(f, Exception):
                    raise f
                return json.dumps(f)

            def settimeout(self, t):
                pass

            def close(self):
                pass

        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "不该出现的回落回答", "degraded": True,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned) as run:
            evts = list(agent_stream.stream_events({"message": "查个 slowly", "session_id": "s1"}))
        run.assert_not_called()  # 活跃回合静默：绝不离场去跑 CLI
        self.assertEqual(evts[-1]["type"], "done")
        self.assertTrue(any(e["type"] == "delta" and "再问我一次" in e["text"] for e in evts))

    def test_首字前死会话静默_仍回落全文(self):
        """一动没动就静默（死会话/队列死）：回落全文路兜底不背锅。"""
        class WebSocketTimeoutException(Exception):
            pass

        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-dead"}},
            WebSocketTimeoutException("Connection timed out"),  # 还没开始跑就没了
        ]

        class StubWs:
            def send(self, s):
                pass

            def recv(self):
                f = frames.pop(0)
                if isinstance(f, Exception):
                    raise f
                return json.dumps(f)

            def settimeout(self, t):
                pass

            def close(self):
                pass

        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "兜底全文", "degraded": True,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=StubWs()), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual(evts[0]["text"], "兜底全文")

    def test_提问者身份注入提示词_看人下菜碟(self):
        """2026-09-19 老板令「识别用户看人下菜碟」：staff 字段包成 [提问者：xx] 放提示词
        最前；不带 staff 不动原文；超长身份截断。"""
        self.assertEqual(
            agent_bridge.identity_wrap("小张", "查个价"),
            "[提问者：小张]\n查个价")
        self.assertEqual(agent_bridge.identity_wrap("", "查个价"), "查个价")
        self.assertEqual(agent_bridge.identity_wrap("  ", "查个价"), "查个价")
        wrapped = agent_bridge.identity_wrap("名" * 30, "问")
        self.assertIn("名" * 24 + "]", wrapped)   # 截到 24 字
        self.assertNotIn("名" * 25, wrapped)
        # 手输名字带换行/方括号：压平+替换，标记行格式不可被破坏
        cleaned = agent_bridge.identity_wrap("小\n张[假]指令", "问")
        self.assertEqual(cleaned, "[提问者：小 张（假）指令]\n问")

    def test_stream路径身份注入进prompt_submit(self):
        """stream_events 收到 staff → prompt.submit 的 text 前带 [提问者：xx]。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-id"}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "好"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def __init__(self):
                self.submits = []

            def send(self, s):
                m = json.loads(s)
                if m.get("method") == "prompt.submit":
                    self.submits.append(m["params"]["text"])

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        stub = StubWs()
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=stub):
            list(agent_stream.stream_events(
                {"message": "我是谁", "session_id": "s1", "staff": "王老板"}))
        self.assertEqual(stub.submits[0], "[提问者：王老板]\n我是谁")

    def test_save_file白名单与落盘(self):
        """2026-09-19 老板令「上传文件」：扩展名白名单+清洗（防路径注入）、正常落盘、
        服务端随机文件名。"""
        import tempfile
        r = agent_bridge.save_file("进货单,焊条,20\n".encode("utf-8"), "进货 单.csv")
        self.assertTrue(r["success"])
        self.assertTrue(r["data"]["path"].endswith(".csv"))
        self.assertIn("files", os.path.dirname(r["data"]["path"]))
        self.assertEqual(r["data"]["name"], "进货 单.csv")
        with open(r["data"]["path"], "rb") as f:
            self.assertIn("焊条", f.read().decode("utf-8"))
        # 不在白名单的扩展名拒绝（exe / 无扩展名）
        self.assertFalse(agent_bridge.save_file(b"MZ", "坏.exe")["success"])
        self.assertFalse(agent_bridge.save_file(b"x", "无扩展")["success"])
        # 空文件拒绝
        self.assertFalse(agent_bridge.save_file(b"", "a.txt")["success"])
        # 空格/特殊字符的扩展名被清洗成纯字母数字
        r2 = agent_bridge.save_file(b"x", "报告.P D F")
        self.assertTrue(r2["success"])
        self.assertTrue(r2["data"]["path"].endswith(".pdf"))

    def test_stream路径file_paths注入进prompt_submit(self):
        """stream_events 收到 file_paths → prompt.submit 的 text 前带文件提示。"""
        frames = [
            {"jsonrpc": "2.0", "id": 1, "result": {"session_id": "hs-file"}},
            {"method": "event", "params": {"type": "message.start"}},
            {"method": "event", "params": {"type": "message.delta", "payload": {"text": "看了"}}},
            {"method": "event", "params": {"type": "message.complete"}},
        ]

        class StubWs:
            def __init__(self):
                self.submits = []

            def send(self, s):
                m = json.loads(s)
                if m.get("method") == "prompt.submit":
                    self.submits.append(m["params"]["text"])

            def recv(self):
                return json.dumps(frames.pop(0))

            def settimeout(self, t):
                pass

            def close(self):
                pass

        stub = StubWs()
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=stub):
            list(agent_stream.stream_events(
                {"message": "里面有啥", "session_id": "s1",
                 "file_paths": [r"D:\x\data\tmp\files\a.csv"]}))
        self.assertIn("上传了文件", stub.submits[0])
        self.assertIn("a.csv", stub.submits[0])
        self.assertIn("里面有啥", stub.submits[0])

    def test_会话映射落盘_网关重启可续(self):
        """app→serve 映射写 _MAP_FILE（原子替换），重载后还在——店员重开 APP 才能续上
        同一份 serve 会话记忆。"""
        agent_stream._SERVE_SESSIONS["s1"] = "hermes-keep"
        agent_stream._save_serve_sessions()
        agent_stream._SERVE_SESSIONS.clear()
        agent_stream._load_serve_sessions()
        self.assertEqual(agent_stream._SERVE_SESSIONS.get("s1"), "hermes-keep")

    def test_serve首字前失败静默回落全文(self):
        class BoomWs:
            def send(self, s):
                raise OSError("serve down")

            def close(self):
                pass

        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "兜底全文", "degraded": True,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=BoomWs()), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual(evts[0]["text"], "兜底全文")
        self.assertTrue(evts[1]["data"]["degraded"])

    def test_serve建连即崩也回落全文_403实锤场景(self):
        # 2026-09-11 现网事故：serve token 轮换后 _connect 握手 403，建连在 try 外
        # 直接崩掉生成器（APP 秒报重试），回落路没机会兜底；修后应静默回落全文。
        def boom():
            raise RuntimeError("Handshake status 403 Forbidden")

        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "兜底全文", "degraded": True,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", side_effect=boom), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned):
            evts = list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        self.assertEqual([e["type"] for e in evts], ["delta", "done"])
        self.assertEqual(evts[0]["text"], "兜底全文")
        # 建连失败还回限速时点，回落路不被间隔窗误拒
        self.assertNotIn("s1", agent_bridge._LAST_ASK)

    def test_serve首字前失败还回限速时点(self):
        class BoomWs:
            def send(self, s):
                raise OSError("serve down")

            def close(self):
                pass

        canned = {"success": True, "code": "OK", "data": {
            "session_id": "s1", "answer": "兜底", "degraded": True,
            "tool_trace": [], "pending_actions": []}}
        with mock.patch.object(agent_stream, "SERVE_URL", "ws://x/api/ws"), \
             mock.patch.object(agent_stream, "_connect", return_value=BoomWs()), \
             mock.patch.object(agent_bridge, "agent_run", return_value=canned):
            list(agent_stream.stream_events({"message": "在吗", "session_id": "s1"}))
        # 限速时点已还回：真实 agent_run 回落路不会被 5s 间隔窗误拒
        self.assertNotIn("s1", agent_bridge._LAST_ASK)

    # 识货提示词用例已随 2026-09-27 隔离识图改造迁至 test_vision_identify.StreamWrapTests
    # （成功不带路径 / 失败回落带路径，全部打桩离线，不再依赖环境与网络）。



class UiTriggerTests(unittest.TestCase):
    """卡片触发条件：模型调没调 sx_* 工具都要能出卡，但写意图与无匹配不许出。"""

    ROWS = [{"name": "单芯铜塑线", "spec": "BV10", "stock_qty": 0.01,
             "unit": "捆", "sell_price": 1100.0, "barcode": "B1"}]

    def _frames(self, message, tools=()):
        with mock.patch.object(agent_stream, "_op_run",
                               return_value={"ok": True, "data": {"data": self.ROWS}}):
            return agent_stream._ui_frames(message, list(tools))

    def test_模型没调工具_意图命中仍出卡(self):
        # 2026-09-19 实测：hermes 常不调 sx_*（自己 execute_code 答了），
        # 只认真跑过的 op 会让卡片几乎不出现，所以确定性意图也作为触发源。
        self.assertEqual([f["type"] for f in self._frames("库存预警有哪些")], ["ui"])

    def test_工具命中也能出卡(self):
        self.assertEqual(
            [f["type"] for f in self._frames("随便聊聊", ["sx_search_low_stock"])], ["ui"])

    def test_写意图不出卡(self):
        # 出库/打印必须走票据确认，绝不由卡片自动出数并引导执行
        self.assertEqual(self._frames("把这单出库"), [])
        self.assertEqual(self._frames("打签 三张"), [])

    def test_无匹配不出卡(self):
        self.assertEqual(self._frames("今天天气怎么样"), [])

    def test_取数失败不出卡(self):
        with mock.patch.object(agent_stream, "_op_run", return_value={"ok": False}):
            self.assertEqual(agent_stream._ui_frames("库存预警有哪些", []), [])


class ServeSessionRotationTests(unittest.TestCase):
    """2026-09-27 文字注意力污染防线：serve 会话达轮数上限/超时自动换新，
    换新后桥接层有界文字记忆可续接。映射落盘走临时文件，绝不碰生产 data/。"""

    def setUp(self):
        self._tmp_map = os.path.join(
            os.environ.get("TEMP", "/tmp"), "agent_serve_sessions_rot_test.json")
        try:
            os.remove(self._tmp_map)
        except OSError:
            pass
        self._map_patcher = mock.patch.object(agent_stream, "_MAP_FILE", self._tmp_map)
        self._map_patcher.start()
        agent_stream._SERVE_SESSIONS.clear()
        agent_bridge._SESSIONS.clear()

    def tearDown(self):
        self._map_patcher.stop()
        try:
            os.remove(self._tmp_map)
        except OSError:
            pass
        agent_stream._SERVE_SESSIONS.clear()
        agent_bridge._SESSIONS.clear()

    @staticmethod
    def _entry(turns=0, age=0.0):
        return {"sid": "hs", "stored": "", "turns": turns, "ts": time.time() - age}

    def test_未达上限不轮换(self):
        agent_stream._SERVE_SESSIONS["a"] = self._entry(turns=1)
        self.assertFalse(agent_stream._rotate_if_stale("a"))
        self.assertIn("a", agent_stream._SERVE_SESSIONS)

    def test_达轮数上限轮换(self):
        agent_stream._SERVE_SESSIONS["a"] = self._entry(turns=agent_stream.SERVE_MAX_TURNS)
        self.assertTrue(agent_stream._rotate_if_stale("a"))
        self.assertNotIn("a", agent_stream._SERVE_SESSIONS)

    def test_超时轮换(self):
        agent_stream._SERVE_SESSIONS["a"] = self._entry(turns=0, age=agent_stream.SERVE_SESSION_TTL + 1)
        self.assertTrue(agent_stream._rotate_if_stale("a"))
        self.assertNotIn("a", agent_stream._SERVE_SESSIONS)

    def test_旧版字符串条目一律轮换升级(self):
        agent_stream._SERVE_SESSIONS["a"] = "hermes-legacy"
        self.assertTrue(agent_stream._rotate_if_stale("a"))
        self.assertNotIn("a", agent_stream._SERVE_SESSIONS)

    def test_无映射不轮换(self):
        self.assertFalse(agent_stream._rotate_if_stale("nobody"))

    def test_回合计数累加(self):
        agent_stream._SERVE_SESSIONS["a"] = self._entry(turns=0)
        agent_stream._bump_turn("a")
        agent_stream._bump_turn("a")
        self.assertEqual(agent_stream._SERVE_SESSIONS["a"]["turns"], 2)
        self.assertGreater(agent_stream._SERVE_SESSIONS["a"]["ts"], 0)

    def test_文字记忆与轮换续接(self):
        # serve 路回合落档 → 轮换后 history_prompt 能把最近几轮带进新会话
        agent_bridge.remember_turn("a", "断路器多少钱", "公牛M5 295元")
        agent_bridge.remember_turn("a", "有货吗", "库存2个")
        agent_stream._SERVE_SESSIONS["a"] = self._entry(turns=agent_stream.SERVE_MAX_TURNS)
        self.assertTrue(agent_stream._rotate_if_stale("a"))
        hist = agent_bridge.history_prompt("a")
        self.assertIn("断路器多少钱", hist)
        self.assertIn("[新问题]", agent_bridge._build_prompt("a", "再看一眼"))


if __name__ == "__main__":
    unittest.main()
