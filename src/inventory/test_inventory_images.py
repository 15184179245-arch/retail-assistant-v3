#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""I19 商品照片：上传加固（≤8MB + JPEG/PNG 魔数白名单）+ 一货多图 + 删除连带文件。

背景：图片端点 2026-09-04 建好后从未实战（data/images 为空），本卡启用前先上安全与行为锁定。
"""
import base64
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from inventory import inventory_server as inv  # noqa: E402

JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


class InventoryImagesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="sx-img-test-")
        inv.DB_PATH = os.path.join(cls.tmp, "inventory.db")
        # realpath：_f6c 守卫按 realpath 比对，tempdir 的 8.3 短路径（ADMINI~1）必须归一
        inv.IMAGES_DIR = os.path.realpath(os.path.join(cls.tmp, "images"))
        # 同进程多个测试类共用线程本地连接——换库必须重置缓存连接（沿 checkout 测试手法）
        old = getattr(inv._local, "conn", None)
        if old is not None:
            try:
                old.close()
            except Exception:
                pass
        inv._local.conn = None
        inv.init_db()

    def test_upload_jpeg_ok_primary_and_file_written(self):
        r = inv.upload_product_image(11, base64.b64encode(JPEG).decode())
        self.assertTrue(r["success"], r)
        self.assertEqual(r["data"]["is_primary"], 1)
        self.assertEqual(r["data"]["url"], "/images/" + r["data"]["filename"])
        self.assertTrue(os.path.isfile(os.path.join(inv.IMAGES_DIR, r["data"]["filename"])))

    def test_upload_multiple_images_per_product(self):
        inv.upload_product_image(12, base64.b64encode(JPEG).decode())
        r2 = inv.upload_product_image(12, base64.b64encode(PNG).decode())
        inv.upload_product_image(12, base64.b64encode(JPEG).decode())
        self.assertEqual(r2["data"]["is_primary"], 0)  # 第二张起不再是主图
        lst = inv.get_product_images(12)
        self.assertTrue(lst["success"])
        self.assertEqual(len(lst["data"]), 3)  # 一货多图
        self.assertEqual(lst["data"][0]["is_primary"], 1)  # 主图排最前

    def test_upload_rejects_non_image_magic(self):
        r = inv.upload_product_image(13, base64.b64encode(b"GIF89a" + b"\x00" * 32).decode())
        self.assertFalse(r["success"])
        self.assertEqual(r.get("code"), "BAD_REQUEST")

    def test_upload_accepts_dataurl_prefix_from_app(self):
        # 2026-09-11 老板实测：APP 发 dataURL 形式（data:image/jpeg;base64,xxx），
        # 服务端必须剥前缀，否则解出乱码→魔数校验必失败→照片永远「待上传」。
        dataurl = "data:image/jpeg;base64," + base64.b64encode(JPEG).decode()
        r = inv.upload_product_image(15, dataurl)
        self.assertTrue(r["success"], r)
        self.assertTrue(os.path.isfile(os.path.join(inv.IMAGES_DIR, r["data"]["filename"])))

    def test_upload_rejects_oversize(self):
        big = b"\xff\xd8\xff" + b"\x00" * (8 * 1024 * 1024 + 1)
        r = inv.upload_product_image(13, base64.b64encode(big).decode())
        self.assertFalse(r["success"])
        self.assertEqual(r.get("code"), "BAD_REQUEST")

    def test_delete_removes_row_and_file(self):
        r = inv.upload_product_image(14, base64.b64encode(JPEG).decode())
        fn = r["data"]["filename"]
        image_id = inv.get_product_images(14)["data"][0]["id"]
        d = inv.delete_product_image(image_id)
        self.assertTrue(d["success"])
        self.assertFalse(os.path.isfile(os.path.join(inv.IMAGES_DIR, fn)))
        self.assertEqual(inv.get_product_images(14)["data"], [])

    def test_delete_missing_image_reported(self):
        d = inv.delete_product_image(999999)
        self.assertFalse(d["success"])
        self.assertEqual(d.get("code"), "NOT_FOUND")

    def test_error_status_maps_business_failures_to_4xx(self):
        # I19 运维可见性：业务失败一律 4xx，网关日志才分得出成败（此前全是 200）
        self.assertEqual(inv._error_status_for({"success": True}), 200)
        self.assertEqual(inv._error_status_for({"success": False, "code": "BAD_REQUEST"}), 400)
        self.assertEqual(inv._error_status_for({"success": False, "code": "NOT_FOUND"}), 400)
        self.assertEqual(inv._error_status_for("not-a-dict"), 400)
        # 失败响应体格式不变（success/error/code 等信封字段原样），只收紧状态码
        r = inv.upload_product_image(13, base64.b64encode(b"GIF89a" + b"\x00" * 32).decode())
        self.assertEqual(sorted(r.keys()), ["code", "data", "error", "hint", "success", "user_message"])


if __name__ == "__main__":
    unittest.main()
