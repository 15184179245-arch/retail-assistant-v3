#!/usr/bin/env python3
"""F6/F9 安全原语 utils/guards.py 契约测试（2026-09-09 审计补缺）。

审计发现：F6 安全原语（safe_url/confine/ident）零单测——本次多个内联守卫
变体与生产调用点（ops/xzg_sync/kb_common/inventory/agent_coord）都压在
这三个函数上，原语错一分、全仓守卫错一寸。

边界说明：_host_ok 对域名做真实 DNS 解析（lru_cache），因此本测试对域名
用例只用「确定不会解析的保留域」与 IP 字面量，不发真实网络请求。
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from utils.guards import confine, ident, safe_lan_host, safe_lan_subnet, safe_port, safe_url  # noqa: E402


class IdentTest(unittest.TestCase):

    def test_accepts_plain_identifiers(self):
        for name in ("products", "_tmp", "a", "b_2", "ROOM_MASTER"):
            self.assertEqual(ident(name), name)

    def test_rejects_injection_and_fragments(self):
        for name in ("", "a b", "a-b", "a;b", 'a"b', "a'b", "a.b", "1abc",
                     "products; DROP TABLE x", "a" + chr(36) + "b"):
            with self.assertRaises(ValueError, msg=name):
                ident(name)


class SafeUrlTest(unittest.TestCase):

    def test_allows_loopback_when_private(self):
        url = "http://127.0.0.1:18767/health"
        self.assertEqual(safe_url(url, allow_private=True), url)
        self.assertEqual(safe_url("http://localhost:18602/search", allow_private=True),
                         "http://localhost:18602/search")

    def test_blocks_loopback_without_private_flag(self):
        with self.assertRaises(ValueError):
            safe_url("http://127.0.0.1:18603/chat")
        with self.assertRaises(ValueError):
            safe_url("http://localhost/x")

    def test_blocks_non_http_schemes(self):
        for url in ("ftp://127.0.0.1/x", "file:///c:/windows/win.ini", "javascript:alert(1)"):
            with self.assertRaises(ValueError, msg=url):
                safe_url(url, allow_private=True)

    def test_blocks_public_internet_when_private_only(self):
        # 确定不会解析的保留测试域（RFC 2606 .invalid）→ 主机校验失败拒绝
        with self.assertRaises(ValueError):
            safe_url("https://px-does-not-exist.invalid/api")

    def test_accepts_public_https_domain(self):
        # RFC 2606 保留域 example.com / example.org 可解析，且是公网 https
        url = "https://example.com/v1/chat"
        self.assertEqual(safe_url(url), url)


class ConfineTest(unittest.TestCase):

    def setUp(self):
        self._old_base = os.environ.get("retaildemo_PATH_BASE")
        os.environ.pop("retaildemo_PATH_BASE", None)

    def tearDown(self):
        if self._old_base is not None:
            os.environ["retaildemo_PATH_BASE"] = self._old_base
        else:
            os.environ.pop("retaildemo_PATH_BASE", None)

    def test_relative_path_resolves_inside_cwd(self):
        rp = confine("src/./gateway/../gateway/handler.py")
        self.assertTrue(os.path.isabs(rp))
        self.assertNotIn("..", rp)

    def test_traversal_rejected(self):
        outside = os.path.join(os.path.expanduser("~"), "confine-should-not-pass")
        with self.assertRaises(ValueError):
            confine(os.path.join(outside, "..", "..", "escape.txt"),
                    base=os.path.dirname(outside))

    def test_tempdir_is_second_root(self):
        import tempfile
        p = os.path.join(tempfile.gettempdir(), "sx-confine-probe.txt")
        self.assertEqual(confine(p), os.path.realpath(p))

    def test_explicit_base_overrides_env(self):
        import tempfile
        root = tempfile.mkdtemp(prefix="sx-confine-base-")
        p = os.path.join(root, "sub", "file.txt")
        self.assertEqual(confine(p, base=root), os.path.realpath(p))

    def test_windows_absolute_outside_rejected(self):
        with self.assertRaises(ValueError):
            confine(os.path.join(os.path.expanduser("~"), "sx-escape-should-fail.json"))


class SafeLanHostTest(unittest.TestCase):
    """排雷 B3 新原语：内网 socket 汇点只放行私网/回环（方向与 safe_url 相反）。"""

    def test_accepts_private_and_loopback(self):
        for host in ("192.0.2.10", "192.0.2.10", "192.0.2.10", "127.0.0.1"):
            self.assertEqual(safe_lan_host(host), host)

    def test_rejects_public_unresolvable_and_empty(self):
        for host in ("8.8.8.8", "1.2.3.4", "", None, "sx-blocked.invalid"):
            with self.assertRaises(ValueError, msg=host):
                safe_lan_host(host)


class SafePortTest(unittest.TestCase):

    def test_accepts_int_and_numeric_str(self):
        self.assertEqual(safe_port(9100), 9100)
        self.assertEqual(safe_port("9100"), 9100)
        self.assertEqual(safe_port(1), 1)
        self.assertEqual(safe_port(65535), 65535)

    def test_rejects_out_of_range_and_garbage(self):
        for bad in (0, -1, 65536, 99999, "abc", None, ""):
            with self.assertRaises(ValueError, msg=bad):
                safe_port(bad)


class SafeLanSubnetTest(unittest.TestCase):

    def test_accepts_private_prefixes(self):
        for subnet in ("192.168.1", "10.0.0", "172.16.31", "127.0.0"):
            self.assertEqual(safe_lan_subnet(subnet), subnet)

    def test_rejects_public_malformed(self):
        for bad in ("8.8.8", "1.2.3", "300.1.1", "192.0.2.10", "abc", ""):
            with self.assertRaises(ValueError, msg=bad):
                safe_lan_subnet(bad)


if __name__ == "__main__":
    unittest.main()
