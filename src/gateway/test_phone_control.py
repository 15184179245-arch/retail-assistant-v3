#!/usr/bin/env python3
"""F9：phone_control 命令表上限回归（2026-09-09 审计 P2）。

审计发现：_phone_commands 只在 phone_queue_command 增、无人调用清理——
结果不被领取即无界增长。修复后表长恒 ≤ 200，超限丢最旧（最小 cid）。
"""
import sys
import os
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import phone_control  # noqa: E402


class PhoneCommandCapTest(unittest.TestCase):

    def setUp(self):
        with phone_control._phone_cmd_lock:
            phone_control._phone_commands.clear()

    def tearDown(self):
        with phone_control._phone_cmd_lock:
            phone_control._phone_commands.clear()

    def test_table_capped_and_oldest_dropped(self):
        with phone_control._phone_cmd_lock:
            first_cid = phone_control._phone_cmd_counter + 1  # 全局计数器跨用例单调，按相对值断言
        for _ in range(phone_control._PHONE_CMD_MAX_ENTRIES + 5):
            phone_control.phone_queue_command("cmd")
        with phone_control._phone_cmd_lock:
            cids = sorted(phone_control._phone_commands)
        self.assertEqual(len(cids), phone_control._PHONE_CMD_MAX_ENTRIES)
        # 最旧的 5 条被丢弃，保留的是最新的（相对首条 cid 断言，不依赖全局计数器初值）
        self.assertEqual(cids[0], first_cid + 5)
        self.assertEqual(cids[-1], first_cid + 204)

    def test_poll_and_result_still_work_after_cap(self):
        cid = phone_control.phone_queue_command("状态")
        got = phone_control.phone_poll()
        self.assertEqual(got.get("id"), cid)
        phone_control.phone_submit_result(cid, "ok")
        res = phone_control.phone_get_result(cid)
        self.assertEqual(res.get("result"), "ok")


if __name__ == "__main__":
    unittest.main()
