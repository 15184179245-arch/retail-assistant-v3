#!/usr/bin/env python3
"""Unit tests for gateway APK distribution routes."""
import io
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.normpath(os.path.join(os.path.dirname(__file__), "..")))
from gateway.handler import GatewayHandler


class DummyGatewayHandler(GatewayHandler):
    """Subclass of GatewayHandler to mock network I/O for unit testing."""
    def __init__(self, path):
        self.command = "GET"
        self.path = path
        self.headers = {}
        self.rfile = io.BytesIO()
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


class TestApkRoute(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.fake_apk = os.path.join(self.temp_dir.name, "app-debug.apk")
        with open(self.fake_apk, "wb") as f:
            f.write(b"PK\x03\x04" + b"TEST_APK_DATA" * 100)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_apk_download_routes(self):
        for route in ("/apk", "/retaildemo-v2.apk"):
            with patch.dict(os.environ, {"APK_PATH": self.fake_apk}):
                handler = DummyGatewayHandler(route)
                handler.do_GET()
                self.assertEqual(handler.sent_status, 200)
                self.assertEqual(handler.sent_headers.get("content-type"), "application/vnd.android.package-archive")
                self.assertIn("retaildemo-v2.apk", handler.sent_headers.get("content-disposition", ""))
                self.assertEqual(handler.sent_headers.get("content-length"), str(os.path.getsize(self.fake_apk)))
                self.assertEqual(handler.wfile.getvalue(), b"PK\x03\x04" + b"TEST_APK_DATA" * 100)

    def test_apk_head_routes(self):
        for route in ("/apk", "/retaildemo-v2.apk"):
            with patch.dict(os.environ, {"APK_PATH": self.fake_apk}):
                handler = DummyGatewayHandler(route)
                handler.command = "HEAD"
                handler.do_HEAD()
                self.assertEqual(handler.sent_status, 200)
                self.assertEqual(handler.sent_headers.get("content-type"), "application/vnd.android.package-archive")
                self.assertIn("retaildemo-v2.apk", handler.sent_headers.get("content-disposition", ""))
                self.assertEqual(handler.sent_headers.get("content-length"), str(os.path.getsize(self.fake_apk)))
                self.assertEqual(handler.wfile.getvalue(), b"")

    def test_apk_not_found(self):
        with patch.dict(os.environ, {"APK_PATH": os.path.join(self.temp_dir.name, "nonexistent.apk")}):
            handler = DummyGatewayHandler("/apk")
            handler.do_GET()
            self.assertEqual(handler.sent_status, 404)


if __name__ == "__main__":
    unittest.main()
