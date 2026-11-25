# -*- coding: utf-8 -*-
"""F9 同款护栏在 agent 桥上的容量回归（2026-09-09 审计 P1-A）。

审计发现：/api/agent/run 公网匿名可达，原实现无并发上限无限速——
每次调用 spawn hermes 子进程（110s 超时）+ LLM 调用，可被刷出资源风暴。
护栏：全局信号量（默认 2，超时回 BUSY）+ 同会话最小提问间隔（默认 5s）。
"""
import os
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent import agent_bridge  # noqa: E402


def _fake_proc():
    proc = mock.Mock()
    proc.returncode = 0
    proc.stdout = "我是老王。"
    proc.stderr = ""
    return proc


class AgentCapacityTest(unittest.TestCase):

    def setUp(self):
        agent_bridge._LAST_ASK.clear()
        agent_bridge._HERMES_SEM = threading.Semaphore(2)
        # patch 自动还原：全局间隔/超时不许泄漏给后续测试模块（否则全量跑时限速必失效）
        self._patch_interval = mock.patch.object(agent_bridge, "_RUN_MIN_INTERVAL", 0.0)
        self._patch_interval.start()
        self._patch_timeout = mock.patch.object(agent_bridge, "_HERMES_SEM_TIMEOUT", 0.3)
        self._patch_timeout.start()

    def tearDown(self):
        self._patch_timeout.stop()
        self._patch_interval.stop()
        agent_bridge._LAST_ASK.clear()
        agent_bridge._HERMES_SEM = threading.Semaphore(2)

    def test_normal_flow_unaffected(self):
        with mock.patch.object(agent_bridge, "hermes_online", return_value=True), \
             mock.patch.object(agent_bridge.subprocess, "run", return_value=_fake_proc()):
            res = agent_bridge.agent_run({"message": "你好"})
        self.assertTrue(res["success"])
        self.assertEqual(res["code"], "OK")

    def test_min_interval_blocks_immediate_repeat(self):
        agent_bridge._RUN_MIN_INTERVAL = 5.0
        with mock.patch.object(agent_bridge, "hermes_online", return_value=True), \
             mock.patch.object(agent_bridge.subprocess, "run", return_value=_fake_proc()):
            first = agent_bridge.agent_run({"message": "问一", "session_id": "s1"})
            second = agent_bridge.agent_run({"message": "问二", "session_id": "s1"})
        self.assertTrue(first["success"])
        self.assertFalse(second["success"])
        self.assertEqual(second["code"], "RATE_LIMITED")

    def test_interval_is_per_session(self):
        agent_bridge._RUN_MIN_INTERVAL = 5.0
        with mock.patch.object(agent_bridge, "hermes_online", return_value=True), \
             mock.patch.object(agent_bridge.subprocess, "run", return_value=_fake_proc()):
            first = agent_bridge.agent_run({"message": "问一", "session_id": "s1"})
            other = agent_bridge.agent_run({"message": "问二", "session_id": "s2"})
        self.assertTrue(first["success"])
        self.assertTrue(other["success"])

    def test_concurrency_cap_returns_busy(self):
        agent_bridge._HERMES_SEM = threading.Semaphore(1)
        agent_bridge._HERMES_SEM_TIMEOUT = 0.3
        self.assertTrue(agent_bridge._HERMES_SEM.acquire(timeout=1))  # 预占唯一许可
        busy = agent_bridge.agent_run({"message": "挤不进来", "session_id": "busy"})
        agent_bridge._HERMES_SEM.release()
        self.assertFalse(busy["success"])
        self.assertEqual(busy["code"], "BUSY")
        # 释放后恢复正常
        with mock.patch.object(agent_bridge, "hermes_online", return_value=True), \
             mock.patch.object(agent_bridge.subprocess, "run", return_value=_fake_proc()):
            ok = agent_bridge.agent_run({"message": "问一", "session_id": "recover"})
            self.assertTrue(ok["success"])


if __name__ == "__main__":
    unittest.main()
