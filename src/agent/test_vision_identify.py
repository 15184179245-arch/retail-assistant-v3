# -*- coding: utf-8 -*-
"""方案A 隔离识图单测（2026-09-27 看图复盘）：解析/清洗/压图/路径闸/降级回落/提示词构建。
全部离线（urlopen 打桩），不真连中继。"""
import io
import json
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from agent import agent_stream, vision_identify  # noqa: E402


def _fake_jpeg(width=3000, height=2000):
    """生成真 JPEG 字节（压图测试用大图）。"""
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (width, height), (200, 120, 40)).save(buf, "JPEG", quality=90)
    return buf.getvalue()


class _FakeResp:
    def __init__(self, payload: bytes):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self):
        return self._payload


def _relay_body(content: str) -> bytes:
    return json.dumps({"choices": [{"message": {"content": content}}]}).encode("utf-8")


class ParseCleanTests(unittest.TestCase):
    def test_围栏JSON剥壳(self):
        data = vision_identify._parse_json_content('```json\n{"brand":"公牛"}\n```')
        self.assertEqual(data, {"brand": "公牛"})

    def test_裸JSON与前后杂讯(self):
        data = vision_identify._parse_json_content('好的：{"product":"印台"} 以上')
        self.assertEqual(data, {"product": "印台"})

    def test_垃圾回包返None(self):
        self.assertIsNone(vision_identify._parse_json_content("看不清"))
        self.assertIsNone(vision_identify._parse_json_content(""))
        self.assertIsNone(vision_identify._parse_json_content("[1,2]"))

    def test_清洗空字段丢弃与ocr列表合并(self):
        out = vision_identify._clean({"brand": "得力", "model": "", "spec": None,
                                      "ocr_raw": ["得力", "No.9891"]})
        self.assertEqual(out, {"brand": "得力", "ocr_raw": "得力\nNo.9891"})

    def test_清洗全空返None(self):
        self.assertIsNone(vision_identify._clean({"brand": "", "ocr_raw": ""}))


class DownscaleTests(unittest.TestCase):
    def test_大图压边到2048(self):
        raw, mime = vision_identify._downscale(_fake_jpeg(), "image/jpeg")
        from PIL import Image
        im = Image.open(io.BytesIO(raw))
        self.assertLessEqual(max(im.size), 2048)
        self.assertEqual(mime, "image/jpeg")

    def test_小图原样保留(self):
        raw = _fake_jpeg(400, 300)
        out, mime = vision_identify._downscale(raw, "image/jpeg")
        self.assertEqual(out, raw)
        self.assertEqual(mime, "image/jpeg")

    def test_坏字节回落原图(self):
        raw, mime = vision_identify._downscale(b"not-an-image", "image/jpeg")
        self.assertEqual(raw, b"not-an-image")
        self.assertEqual(mime, "image/jpeg")


class IdentifyImageTests(unittest.TestCase):
    def setUp(self):
        vision_identify._CACHE.clear()

    def tearDown(self):
        vision_identify._CACHE.clear()

    def _tmp_image_in_allowed_dir(self):
        os.makedirs(vision_identify._ALLOWED_IMG_DIR, exist_ok=True)
        fd, path = tempfile.mkstemp(prefix="_test_vision_", suffix=".jpg",
                                    dir=vision_identify._ALLOWED_IMG_DIR)
        with os.fdopen(fd, "wb") as f:
            f.write(_fake_jpeg(600, 400))
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return path

    def test_系统文件路径越界拒绝且不发请求(self):
        # 字面量外部位图路径（realpath 后不在 data/tmp 下）：必须拒绝且不发起任何请求
        with mock.patch.object(vision_identify.urllib.request, "urlopen") as up:
            self.assertIsNone(vision_identify.identify_image("C:/Windows/win.ini"))
        up.assert_not_called()

    def test_文件不存在拒绝(self):
        p = os.path.join(vision_identify._ALLOWED_IMG_DIR, "_test_no_such.jpg")
        self.assertIsNone(vision_identify.identify_image(p))

    def test_识别成功与缓存命中(self):
        path = self._tmp_image_in_allowed_dir()
        canned = ('```json\n{"brand":"公牛","product":"漏电断路器","model":"LBE5-63aY",'
                  '"spec":"C32 2P","ocr_raw":"公牛 窄立方"}\n```')
        with mock.patch.object(vision_identify, "_config",
                               return_value=("http://127.0.0.1:8317/v1/chat/completions",
                                             "k", "gemini-3.7-flash-high", 5)), \
             mock.patch.object(vision_identify.urllib.request, "urlopen",
                               return_value=_FakeResp(_relay_body(canned))) as up:
            first = vision_identify.identify_image(path)
            second = vision_identify.identify_image(path)
        self.assertEqual(first["brand"], "公牛")
        self.assertEqual(first["product"], "漏电断路器")
        self.assertEqual(second, first)
        self.assertEqual(up.call_count, 1)  # 第二次走缓存，不再发请求

    def test_回包垃圾返None且短缓存(self):
        path = self._tmp_image_in_allowed_dir()
        with mock.patch.object(vision_identify, "_config",
                               return_value=("http://127.0.0.1:8317/v1/chat/completions",
                                             "k", "gemini-3.7-flash-high", 5)), \
             mock.patch.object(vision_identify.urllib.request, "urlopen",
                               return_value=_FakeResp(_relay_body("根本不是商品"))):
            self.assertIsNone(vision_identify.identify_image(path))

    def test_请求异常返None不抛(self):
        path = self._tmp_image_in_allowed_dir()
        with mock.patch.object(vision_identify, "_config",
                               return_value=("http://127.0.0.1:8317/v1/chat/completions",
                                             "k", "gemini-3.7-flash-high", 5)), \
             mock.patch.object(vision_identify.urllib.request, "urlopen",
                               side_effect=OSError("conn refused")):
            self.assertIsNone(vision_identify.identify_image(path))

    def test_非回环端点拒绝发送(self):
        path = self._tmp_image_in_allowed_dir()
        with mock.patch.object(vision_identify, "_config",
                               return_value=("http://api.example.com/v1/chat/completions",
                                             "k", "m", 5)), \
             mock.patch.object(vision_identify.urllib.request, "urlopen") as up:
            self.assertIsNone(vision_identify.identify_image(path))
        up.assert_not_called()


class KnowledgeHintTests(unittest.TestCase):
    def test_知识库命中取两条(self):
        canned = {"ok": True, "data": {"success": True, "data": [
            {"question": "塑壳断路器怎么选？", "answer": "按额定电流选"},
            {"question": "漏保和空开区别？", "answer": "漏保防人身触电"},
            {"question": "多余第三条", "answer": "不该出现"}]}}
        with mock.patch("core.ops.run_op", return_value=canned):
            hints = vision_identify._knowledge_hint("公牛 塑壳断路器")
        self.assertEqual(len(hints), 2)
        self.assertIn("塑壳断路器怎么选", hints[0])

    def test_知识库失败静默(self):
        with mock.patch("core.ops.run_op", side_effect=OSError("down")):
            self.assertEqual(vision_identify._knowledge_hint("公牛"), [])
        with mock.patch("core.ops.run_op", return_value={"ok": False}):
            self.assertEqual(vision_identify._knowledge_hint("公牛"), [])
        self.assertEqual(vision_identify._knowledge_hint(""), [])


class VisionBlockTests(unittest.TestCase):
    RESULT = {"brand": "公牛", "product": "漏电断路器", "model": "LBE5-63aY",
              "spec": "C32 2P", "ocr_raw": "公牛 窄立方"}

    def test_无图返回空串(self):
        self.assertEqual(vision_identify.vision_block([]), "")

    def test_隔离成功注JSON不带路径(self):
        with mock.patch.object(vision_identify, "identify_images",
                               return_value=[self.RESULT]), \
             mock.patch.object(vision_identify, "_knowledge_hint", return_value=[]):
            block = vision_identify.vision_block(["D:/x/data/tmp/a.jpg"])
        self.assertIn('"brand": "公牛"', block)
        self.assertIn("双轨检索", block)
        self.assertIn("未定价", block)
        self.assertIn("前文", block)
        self.assertNotIn("a.jpg", block)  # 路径不进主会话，原图无从再挂

    def test_知识预取随识货注入(self):
        with mock.patch.object(vision_identify, "identify_images",
                               return_value=[self.RESULT]), \
             mock.patch.object(vision_identify, "_knowledge_hint",
                               return_value=["问：漏保怎么选？\n答：按额定电流"]):
            block = vision_identify.vision_block(["D:/x/data/tmp/a.jpg"])
        self.assertIn("本店知识库", block)
        self.assertIn("漏保怎么选", block)

    def test_识别失败回落路径提示(self):
        with mock.patch.object(vision_identify, "identify_images",
                               return_value=[None]), \
             mock.patch.object(vision_identify, "_knowledge_hint", return_value=[]) as kh:
            block = vision_identify.vision_block(["D:/x/data/tmp/a.jpg"])
        self.assertIn("D:/x/data/tmp/a.jpg", block)
        self.assertIn("vision", block)
        self.assertIn("前文", block)
        kh.assert_not_called()  # 识别失败时不预取知识（没有可信品名）

    def test_部分成功混合(self):
        with mock.patch.object(vision_identify, "identify_images",
                               return_value=[self.RESULT, None]), \
             mock.patch.object(vision_identify, "_knowledge_hint", return_value=[]):
            block = vision_identify.vision_block(["p1.jpg", "p2.jpg"])
        self.assertIn('"brand": "公牛"', block)
        self.assertIn("p2.jpg", block)


class StreamWrapTests(unittest.TestCase):
    def test_无图原样(self):
        self.assertEqual(agent_stream._vision_wrap("看看", []), "看看")

    def test_成功时不带路径(self):
        result = {"brand": "得力", "product": "印台"}
        with mock.patch.object(agent_stream.vision_identify, "identify_images",
                               return_value=[result]):
            out = agent_stream._vision_wrap("看看", ["/data/tmp/a.jpg"])
        self.assertIn("得力", out)
        self.assertNotIn("a.jpg", out)

    def test_失败时带路径(self):
        with mock.patch.object(agent_stream.vision_identify, "identify_images",
                               return_value=[None]):
            out = agent_stream._vision_wrap("看看", ["/data/tmp/a.jpg"])
        self.assertIn("/data/tmp/a.jpg", out)


if __name__ == "__main__":
    unittest.main()
