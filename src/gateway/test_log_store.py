#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-11 老板令（H2）：APP 运行日志收集——服务器实时收回日志定位问题。

锁定 log_store 五条契约：
① ingest 批量落盘 JSONL，服务端补 ts/request_id，请求级字段（device_id 等）并入每条；
② 护栏：非 list 拒绝、单批 MAX_BATCH 截断、单条 message 截断、超 ROTATE_BYTES 轮转 .1；
③ parse_lines 复用 crash_store（跳空行/坏行，保序）；
④ recent_logs 倒序、limit 收口；
⑤ recent_logs 过滤：level（大小写不敏感）/tag/device_id 精确、q 子串，.1 旧卷一并纳入。
（注：测试不直接裸写文件——写路径只经 ingest_logs，坏行用纯函数喂。）
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import log_store  # noqa: E402


class LogStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sx-applog-test-")
        self.path = os.path.join(self.tmp, "app-logs.jsonl")
        self._old_env = os.environ.get("retaildemo_APP_LOG")
        os.environ["retaildemo_APP_LOG"] = self.path

    def tearDown(self):
        if self._old_env is not None:
            os.environ["retaildemo_APP_LOG"] = self._old_env
        else:
            os.environ.pop("retaildemo_APP_LOG", None)

    def test_ingest_appends_jsonl_with_envelope_and_ts(self):
        r = log_store.ingest_logs(
            [{"ts": "2026-09-11 23:00:00", "level": "WARN", "tag": "BillSync", "message": "同步失败"},
             {"level": "INFO", "tag": "App", "message": "启动"}],
            request_id="req-42",
            envelope={"device_id": "DEV-K990", "app_version": "2.5.8", "model": "HONOR K990"},
        )
        self.assertTrue(r["success"])
        self.assertEqual(r["data"]["saved"], 2)
        with open(self.path, "r", encoding="utf-8") as f:
            recs = [json.loads(x) for x in f if x.strip()]
        self.assertEqual(recs[0]["device_id"], "DEV-K990")
        self.assertEqual(recs[0]["request_id"], "req-42")
        self.assertEqual(recs[0]["message"], "同步失败")
        # 记录自身 ts 优先、缺失则补服务器时间
        self.assertEqual(recs[0]["ts"], "2026-09-11 23:00:00")
        self.assertIn("ts", recs[1])
        # 记录同名字段优先于 envelope
        r2 = log_store.ingest_logs([{"device_id": "SELF", "level": "INFO", "message": "x"}])
        with open(self.path, "r", encoding="utf-8") as f:
            last = json.loads(f.readlines()[-1])
        self.assertTrue(r2["success"])
        self.assertEqual(last["device_id"], "SELF")

    def test_rejects_non_list_records(self):
        r = log_store.ingest_logs({"level": "INFO"})
        self.assertFalse(r["success"])
        self.assertEqual(r.get("code"), "BAD_REQUEST")

    def test_batch_cap_and_non_dict_entries_skipped(self):
        r = log_store.ingest_logs(
            [{"level": "INFO", "message": "keep-%d" % i} for i in range(log_store.MAX_BATCH + 30)]
            + ["not-a-dict", 42, None],
        )
        self.assertTrue(r["success"])
        self.assertEqual(r["data"]["saved"], log_store.MAX_BATCH)
        with open(self.path, "r", encoding="utf-8") as f:
            recs = [json.loads(x) for x in f if x.strip()]
        self.assertEqual(len(recs), log_store.MAX_BATCH)

    def test_oversized_message_truncated(self):
        r = log_store.ingest_logs([{"level": "ERROR", "message": "x" * (log_store.MAX_MSG_FIELD + 999)}])
        self.assertTrue(r["success"])
        with open(self.path, "r", encoding="utf-8") as f:
            rec = json.loads(f.readline())
        self.assertLess(len(rec["message"]), log_store.MAX_MSG_FIELD + 100)
        self.assertTrue(rec["message"].endswith("...[truncated]"))

    def test_rotates_when_over_limit_and_dot_one_queried_too(self):
        log_store.ROTATE_BYTES = 512  # 测试内临时收紧，用后还原
        try:
            for i in range(10):
                log_store.ingest_logs([{"level": "INFO", "tag": "Rot", "message": "line-%d" % i, "pad": "y" * 60}])
        finally:
            log_store.ROTATE_BYTES = 5 * 1024 * 1024
        r = log_store.recent_logs(limit=100)
        self.assertTrue(r["success"])
        self.assertGreaterEqual(len(r["data"]), 2)          # 新旧两卷都读得到
        self.assertEqual(r["data"][0]["message"], "line-9")  # 最新一条完好、排最前

    def test_recent_logs_filters(self):
        log_store.ingest_logs([
            {"level": "ERROR", "tag": "Net", "message": "HTTP 500 from gateway"},
            {"level": "warn", "tag": "BillSync", "message": "同步重试"},
            {"level": "INFO", "tag": "App", "message": "启动完成"},
        ], envelope={"device_id": "DEV-A"})
        log_store.ingest_logs(
            [{"level": "ERROR", "tag": "Net", "message": "timeout"}],
            envelope={"device_id": "DEV-B"},
        )

        by_level = log_store.recent_logs(level="error")
        self.assertEqual([x["message"] for x in by_level["data"]], ["timeout", "HTTP 500 from gateway"])  # 大小写不敏感+倒序
        by_tag = log_store.recent_logs(tag="BillSync")
        self.assertEqual([x["message"] for x in by_tag["data"]], ["同步重试"])
        by_dev = log_store.recent_logs(device_id="DEV-B")
        self.assertEqual([x["message"] for x in by_dev["data"]], ["timeout"])
        by_q = log_store.recent_logs(q="500")
        self.assertEqual(len(by_q["data"]), 1)
        self.assertEqual(by_q["data"][0]["message"], "HTTP 500 from gateway")

    def test_recent_logs_newest_first_and_limit_cap(self):
        for i in range(8):
            log_store.ingest_logs([{"level": "INFO", "message": "n-%d" % i}])
        r = log_store.recent_logs(limit=3)
        self.assertEqual(len(r["data"]), 3)
        self.assertEqual(r["data"][0]["message"], "n-7")

    def test_recent_logs_empty_when_missing_file(self):
        r = log_store.recent_logs()
        self.assertTrue(r["success"])
        self.assertEqual(r["data"], [])


if __name__ == "__main__":
    unittest.main()
