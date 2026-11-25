"""apk_manifest 单测：合成最小 AXML 夹具逐属性锁定解析契约 + 真实 APK 集成抽查。

夹具生成器 build_minimal_axml 按 aapt2 的 chunk 布局手工拼字节
（字符串池 UTF-16 + 单个 manifest start-element），供本文件与
gateway/test_app_version.py 复用——F12 契约测试都靠它构造「版本号各异」的假 APK。
"""
import os
import struct
import unittest
import zipfile

from utils.apk_manifest import read_apk_version

_ANDROID_NS = "http://schemas.android.com/apk/res/android"


def build_minimal_axml(strings, attrs):
    """strings: [str]；attrs: [(属性名, raw池索引|None, dataType, data)] → AXML 字节。"""
    body = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(body))
        body += struct.pack("<H", len(s))
        body += s.encode("utf-16-le")
        body += b"\x00\x00"
    while len(body) % 4:
        body += b"\x00"
    strings_start = 28 + 4 * len(strings)
    pool_size = strings_start + len(body)
    pool = struct.pack("<HHIIIIII", 0x0001, 28, pool_size, len(strings), 0, 0, strings_start, 0)
    pool += b"".join(struct.pack("<I", o) for o in offsets)
    pool += bytes(body)

    attrs_bytes = bytearray()
    for name, raw, dtype, data in attrs:
        # ResXMLTree_attribute：ns(4) name(4) rawValue(4) size(2) res0(1) dataType(1) data(4)
        attrs_bytes += struct.pack(
            "<IIIHBBI",
            strings.index(_ANDROID_NS),
            strings.index(name),
            0xFFFFFFFF if raw is None else raw,
            20, 0, dtype, data,
        )
    attr_ext = struct.pack(
        "<IIHHHHHH",
        strings.index(_ANDROID_NS), strings.index("manifest"),
        20, 20, len(attrs), 0, 0, 0,
    )
    node = struct.pack("<HHIII", 0x0102, 16, 16 + 20 + len(attrs_bytes), 1, 0xFFFFFFFF)
    elem = node + attr_ext + bytes(attrs_bytes)

    header = struct.pack("<HHI", 0x0003, 8, 8 + len(pool) + len(elem))
    return header + pool + elem


def build_fake_apk(path, version_code_raw=None, version_code_data=8,
                   code_dtype=0x10, version_name="2.5.0-test"):
    """构造一个最小假 APK（zip 内仅 AndroidManifest.xml），供契约与网关测试用。"""
    strings = ["manifest", "versionCode", "versionName", _ANDROID_NS, version_name]
    name_idx = strings.index(version_name)
    attrs = [
        ("versionCode", version_code_raw, code_dtype, version_code_data),
        ("versionName", name_idx, 0x03, name_idx),
    ]
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", build_minimal_axml(strings, attrs))
    return path


class TestApkManifest(unittest.TestCase):
    def _parse_tmp(self, tmpdir, **kw):
        path = os.path.join(tmpdir, "fake.apk")
        build_fake_apk(path, **kw)
        return read_apk_version(path)

    def test_version_code_int_dec(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            code, name = self._parse_tmp(d, version_code_data=8)
        self.assertEqual(code, 8)
        self.assertEqual(name, "2.5.0-test")

    def test_version_code_int_hex(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            code, _ = self._parse_tmp(d, code_dtype=0x11, version_code_data=16)
        self.assertEqual(code, 16)

    def test_version_code_as_string_typed(self):
        import tempfile
        # 某些构建器把 versionCode 写成字符串 typedValue（rawValue 为空）
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "fake.apk")
            strings = ["manifest", "versionCode", "versionName", _ANDROID_NS, "777", "9.9.9"]
            attrs = [
                ("versionCode", None, 0x03, strings.index("777")),
                ("versionName", strings.index("9.9.9"), 0x03, strings.index("9.9.9")),
            ]
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("AndroidManifest.xml", build_minimal_axml(strings, attrs))
            code, name = read_apk_version(path)
        self.assertEqual(code, 777)
        self.assertEqual(name, "9.9.9")

    def test_version_code_huge_int(self):
        import tempfile
        # 自增方案（epoch 分钟 ≈ 2980 万）远超历史手工号，必须无损解析
        with tempfile.TemporaryDirectory() as d:
            code, _ = self._parse_tmp(d, version_code_data=29812345)
        self.assertEqual(code, 29812345)

    def test_bad_version_code_string_returns_none(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "fake.apk")
            strings = ["manifest", "versionCode", "versionName", _ANDROID_NS, "not-a-number", "x"]
            attrs = [("versionCode", None, 0x03, strings.index("not-a-number"))]
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("AndroidManifest.xml", build_minimal_axml(strings, attrs))
            code, name = read_apk_version(path)
        self.assertIsNone(code)
        self.assertIsNone(name)

    def test_missing_file_returns_none_none(self):
        code, name = read_apk_version("Z:/definitely/not/there.apk")
        self.assertIsNone(code)
        self.assertIsNone(name)

    def test_corrupt_bytes_return_none_none(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "bad.apk")
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("AndroidManifest.xml", b"\x03\x00garbage-garbage")
            code, name = read_apk_version(path)
        self.assertIsNone(code)
        self.assertIsNone(name)

    def test_real_apk_integration(self):
        # 集成抽查：仓里只要有真包（发布正本或构建产物），解析必须给出正号与非空版本名
        repo_root = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
        candidates = [
            os.path.join(repo_root, "retaildemo-v2.apk"),
            os.path.join(repo_root, "android-v2", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
        ]
        apk = next((p for p in candidates if os.path.exists(p)), None)
        if not apk:
            self.skipTest("仓内暂无真实 APK，跳过集成抽查")
        code, name = read_apk_version(apk)
        self.assertIsInstance(code, int)
        self.assertGreater(code, 0)
        self.assertTrue(name)


if __name__ == "__main__":
    unittest.main()
