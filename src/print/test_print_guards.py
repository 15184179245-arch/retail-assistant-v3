#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""排雷 B3：print 服务 socket 汇点内网防护契约测试（2026-09-09）。

旧缺陷：_send_tcp 接受任意 ip/port（/print、/print/ping、/print/test、队列
重放全可达），/print/discover 接受任意网段——gateway 一转发即成公网 SSRF 跳板。
本测试钉死：非私网/回环目标一律拦截，不发出任何探测包。
"""
import os
import socket
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from print import print_server  # noqa: E402
from print.print_server import _discover_printers, _send_tcp  # noqa: E402


class SendTcpGuardTest(unittest.TestCase):

    def test_blocks_public_ip(self):
        r = _send_tcp("8.8.8.8", 9100, b"\x1b\x40")
        self.assertFalse(r["success"])
        self.assertIn("拦截", r["error"])

    def test_blocks_bad_port_and_bad_host(self):
        self.assertFalse(_send_tcp("192.0.2.10", 0, b"x")["success"])
        self.assertFalse(_send_tcp("192.0.2.10", 99999, b"x")["success"])
        self.assertFalse(_send_tcp("", 9100, b"x")["success"])
        # 不会解析的保留域（RFC 2606 .invalid）→ 主机校验失败拒绝，无网络请求
        self.assertFalse(_send_tcp("sx-blocked.invalid", 9100, b"x")["success"])

    def test_allows_loopback_socket(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            srv.bind(("127.0.0.1", 0))
            srv.listen(1)
            port = srv.getsockname()[1]
            got = {}

            def serve():
                conn, _ = srv.accept()
                got["data"] = conn.recv(64)
                conn.close()

            t = threading.Thread(target=serve, daemon=True)
            t.start()
            payload = b"\x1b\x40"
            r = _send_tcp("127.0.0.1", port, payload)
            t.join(timeout=5)
            self.assertTrue(r["success"], r)
            self.assertEqual(got.get("data"), payload)
        finally:
            srv.close()


class DiscoverSubnetGuardTest(unittest.TestCase):

    def test_rejects_public_or_malformed_subnet(self):
        for bad in ("8.8.8", "192.0.2.10", "abc", "300.1.1", ""):
            with self.assertRaises(ValueError, msg=bad):
                _discover_printers(bad)


class SendTcpCloseTest(unittest.TestCase):
    """2026-09-11 bug猎杀：_send_tcp 异常路径必须关闭 socket。

    旧缺陷：sock.close() 只在正常路径执行，connect/sendall 抛异常即漏一个
    fd——打印服务常驻，打印机离线反复重试会耗尽 fd 拖垮整个服务。
    """

    def _run(self, raise_in):
        class BoomSock:
            def __init__(self):
                self.closed = False
            def settimeout(self, t): pass
            def connect(self, addr):
                if raise_in == "connect":
                    raise OSError("refused")
            def sendall(self, data):
                if raise_in == "sendall":
                    raise OSError("broken pipe")
            def shutdown(self, how): pass
            def recv(self, n): return b"OK"
            def close(self): self.closed = True

        sock = BoomSock()
        with mock.patch.object(print_server.socket, "socket", return_value=sock):
            r = _send_tcp("192.0.2.10", 9100, b"@")
        return r, sock

    def test_connect失败也关socket(self):
        r, sock = self._run("connect")
        self.assertFalse(r["success"])
        self.assertTrue(sock.closed)

    def test_sendall失败也关socket(self):
        r, sock = self._run("sendall")
        self.assertFalse(r["success"])
        self.assertTrue(sock.closed)

    def test_成功路径照常关socket(self):
        r, sock = self._run(None)
        self.assertTrue(r["success"])
        self.assertTrue(sock.closed)


if __name__ == "__main__":
    unittest.main()
