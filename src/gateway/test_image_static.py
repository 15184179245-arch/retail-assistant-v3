#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""I19 网关静态图片直读：文件名白名单 + 路径守卫（/images/* 与 /api/inventory/images/*）。

背景：inventory 服务自身可静态服务图片，但 APP 只认网关 :18767，proxy() 又强制 JSON
解码走不了二进制——网关必须直读 IMAGES_DIR（与 APK 直读同模式）。文件名来自 URL，
必须先过白名单再落盘解析，杜绝路径穿越。
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import handler  # noqa: E402


class GatewayImageStaticTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sx-gw-img-")
        self.old = os.environ.get("IMAGES_DIR")
        os.environ["IMAGES_DIR"] = os.path.realpath(self.tmp)
        with open(os.path.join(self.tmp, "42_abcd1234.jpg"), "wb") as f:
            f.write(b"\xff\xd8\xff" + b"\x00" * 8)

    def tearDown(self):
        if self.old is None:
            os.environ.pop("IMAGES_DIR", None)
        else:
            os.environ["IMAGES_DIR"] = self.old

    def test_valid_filename_resolves_to_real_file(self):
        p = handler._resolve_image_path("/images/42_abcd1234.jpg")
        self.assertIsNotNone(p)
        self.assertTrue(os.path.isfile(p))

    def test_api_prefixed_path_resolves(self):
        self.assertIsNotNone(handler._resolve_image_path("/api/inventory/images/42_abcd1234.jpg"))

    def test_missing_file_returns_none(self):
        self.assertIsNone(handler._resolve_image_path("/images/42_deadbeef.jpg"))

    def test_bad_extension_rejected(self):
        self.assertIsNone(handler._resolve_image_path("/images/evil.exe"))

    def test_traversal_and_subpath_rejected(self):
        self.assertIsNone(handler._resolve_image_path("/images/..%2F..%2Fsecret.txt"))
        self.assertIsNone(handler._resolve_image_path("/images/sub/dir/ok.jpg"))
        self.assertIsNone(handler._resolve_image_path("/images/no<such>file.jpg"))

    def test_unrelated_prefix_not_matched(self):
        self.assertIsNone(handler._resolve_image_path("/etc/passwd"))
        self.assertIsNone(handler._resolve_image_path("/imagesx/42_abcd1234.jpg"))


if __name__ == "__main__":
    unittest.main()
