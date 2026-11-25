#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-11 老板令：APP 崩溃报告收集（新设备闪退要能远程收回原因）。

锁定 crash_store 四条契约：
① 落盘为 JSONL，服务端补 ts/request_id，原文不改写（超限截断）；
② 超 ROTATE_BYTES 轮转 .1（只留一代），磁盘不被公网灌爆；
③ parse_lines 纯函数：跳过空行/坏行，保序；
④ recent_reports 倒序、limit 收口。
（注：测试不直接裸写文件——写路径只经 save_report，坏行用纯函数喂。）
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import crash_store  # noqa: E402


class CrashReportStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sx-crash-test-")
        self.path = os.path.join(self.tmp, "app-crashes.jsonl")
        self._old_env = os.environ.get("retaildemo_CRASH_LOG")
        os.environ["retaildemo_CRASH_LOG"] = self.path

    def tearDown(self):
        if self._old_env is not None:
            os.environ["retaildemo_CRASH_LOG"] = self._old_env
        else:
            os.environ.pop("retaildemo_CRASH_LOG", None)

    def test_save_appends_jsonl_with_ts_and_request_id(self):
        r = crash_store.save_report({"device": "HONOR K990", "crash": "OutOfMemoryError: bit"}, request_id="abc123")
        self.assertTrue(r["success"])
        with open(self.path, "r", encoding="utf-8") as f:
            lines = [json.loads(x) for x in f if x.strip()]
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["device"], "HONOR K990")
        self.assertEqual(lines[0]["request_id"], "abc123")
        self.assertIn("ts", lines[0])

    def test_rejects_non_dict_payload(self):
        r = crash_store.save_report(["not", "a", "dict"])
        self.assertFalse(r["success"])
        self.assertEqual(r.get("code"), "BAD_REQUEST")

    def test_oversized_crash_field_truncated(self):
        r = crash_store.save_report({"crash": "x" * (crash_store.MAX_CRASH_FIELD + 9999)})
        self.assertTrue(r["success"])
        with open(self.path, "r", encoding="utf-8") as f:
            rec = json.loads(f.readline())
        self.assertLess(len(rec["crash"]), crash_store.MAX_CRASH_FIELD + 100)
        self.assertTrue(rec["crash"].endswith("...[truncated]"))

    def test_rotates_when_over_limit_and_newest_still_readable(self):
        crash_store.ROTATE_BYTES = 256  # 测试内临时收紧
        for i in range(6):
            crash_store.save_report({"crash": "boom-%d" % i, "pad": "y" * 100})
        r = crash_store.recent_reports(limit=10)
        self.assertTrue(r["success"])
        self.assertGreaterEqual(len(r["data"]), 1)          # 轮转后依然可读
        self.assertEqual(r["data"][0]["crash"], "boom-5")   # 最新一份完好、排最前
        # 轮转语义：只在写前超限挪代，同代内允许两条共存——老代必不混入
        self.assertTrue(all(rec["crash"] in ("boom-4", "boom-5") for rec in r["data"]))

    def test_parse_lines_skips_blank_and_bad_lines_keeps_order(self):
        raw = [
            "{broken json",
            "",
            json.dumps({"crash": "first"}, ensure_ascii=False),
            "   ",
            json.dumps({"crash": "second"}, ensure_ascii=False),
            "[not, a, dict]",
        ]
        out = crash_store.parse_lines(raw)
        self.assertEqual([x["crash"] for x in out], ["first", "second"])

    def test_recent_reports_newest_first_and_limit_cap(self):
        for i in range(8):
            crash_store.save_report({"crash": "boom-%d" % i})
        r = crash_store.recent_reports(limit=3)
        self.assertEqual(len(r["data"]), 3)
        self.assertEqual(r["data"][0]["crash"], "boom-7")

    def test_recent_reports_empty_when_missing_file(self):
        r = crash_store.recent_reports()
        self.assertTrue(r["success"])
        self.assertEqual(r["data"], [])


if __name__ == "__main__":
    unittest.main()
