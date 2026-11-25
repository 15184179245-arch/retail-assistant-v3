#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""I30/A2 回归：治理页（admin/pages.py）同义词新增是裸 HTML form，浏览器按
application/x-www-form-urlencoded 提交；端点原实现只 parse JSON → 恒 400，
「同义词人工确认通道」网页侧端到端坏死。修复后按 Content-Type 分流，双通道可达。

测试不落真库：add_synonym 打桩（铁律 12：数据神圣）；鉴权闸打桩（单测聚焦分流本身）。
"""
import io
import os
import sys
import unittest
from unittest.mock import patch
from urllib.parse import urlencode

sys.path.insert(0, os.path.normpath(os.path.join(os.path.dirname(__file__), "..")))
from gateway.handler import GatewayHandler  # noqa: E402


class DummyGatewayHandler(GatewayHandler):
    """仿 test_apk_route：mock 网络 I/O，只驱动 do_POST 分发逻辑。"""

    def __init__(self, path, body=b"", headers=None):
        self.command = "POST"
        self.path = path
        self.headers = headers or {}
        self.rfile = io.BytesIO(body)
        self.wfile = io.BytesIO()
        self._headers_buffer = []
        self.sent_status = None
        self.sent_headers = {}

    def send_response(self, code, message=None):
        self.sent_status = code

    def send_header(self, keyword, value):
        self.sent_headers[keyword.lower()] = value

    def end_headers(self):
        pass


class TestGovSynonymForm(unittest.TestCase):

    def _post(self, body, ctype):
        headers = {"Content-Length": str(len(body))}
        if ctype:
            headers["Content-Type"] = ctype
        h = DummyGatewayHandler("/api/admin/gov/synonym", body, headers)
        with patch("gateway.handler._require_admin", return_value=True), \
                patch("gateway.handler._verify_auth", return_value=True), \
                patch("admin.governance.add_synonym", return_value=True) as add:
            h.do_POST()
        return h, add

    def test_表单编码通道可达不再400(self):
        body = urlencode({"canonical": "活接", "synonyms": "直接,万能接头"}).encode("utf-8")
        h, add = self._post(body, "application/x-www-form-urlencoded")
        self.assertEqual(h.sent_status, 200, f"表单通道仍被拒: {h.wfile.getvalue()!r}")
        add.assert_called_once_with("活接", ["直接", "万能接头"], "")

    def test_表单同义词按逗号切分且容忍中文逗号外空白(self):
        body = urlencode({"canonical": "管箍", "synonyms": " 管古 ， 束节 ,,内丝 "} ).encode("utf-8")
        h, add = self._post(body, "application/x-www-form-urlencoded")
        self.assertEqual(h.sent_status, 200)
        # 端点既有逻辑负责 strip/过滤空串；这里验证切分链路送进了三个词
        args = add.call_args[0]
        self.assertEqual(args[0], "管箍")
        self.assertEqual([s.strip() for s in args[1] if str(s).strip()], ["管古", "束节", "内丝"])

    def test_json通道不受影响(self):
        body = '{"canonical":"活接","synonyms":["直接"]}'.encode("utf-8")
        h, add = self._post(body, "application/json")
        self.assertEqual(h.sent_status, 200)
        add.assert_called_once_with("活接", ["直接"], "")

    def test_无ContentType按原JSON路径兜底(self):
        body = '{"canonical":"活接","synonyms":["直接"]}'.encode("utf-8")
        h, add = self._post(body, "")
        self.assertEqual(h.sent_status, 200)
        add.assert_called_once()


if __name__ == "__main__":
    unittest.main()
