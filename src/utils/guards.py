# -*- coding: utf-8 -*-
"""src/utils/guards.py — F6 安全加固统一防护原语（零新依赖，纯标准库）。

原语清单，供网关/微服务/同步器在把「外部可控值」交给危险汇点前校验：
- safe_url(url, allow_private): SSRF 防护——scheme 白名单 + 主机解析 IP 边界校验
  （默认禁止回环/私网/保留/链路本地/组播；内网服务间调用显式 allow_private=True）。
- safe_lan_host / safe_port / safe_lan_subnet: 内网 socket 汇点防护——方向与
  safe_url 相反，只放行私网/回环目标（2026-09-09 排雷 B3 为打印服务接入）。
- confine(path, base): 路径穿越防护——realpath 归一后必须落在 base（默认 CWD，
  可用环境变量 retaildemo_PATH_BASE 放行运维自定根）之内。
- ident(name): SQL 标识符白名单——只放行 [A-Za-z_][A-Za-z0-9_]*（表名/列名拼接专用）。

所有函数校验失败一律 raise ValueError，调用方按各自错误通道返回 4xx/err。
"""
import functools
import ipaddress
import os
import re
import socket
from urllib.parse import urlparse

_IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def ident(name: str) -> str:
    """校验 SQL 标识符（表名/列名），通过则原样返回，否则 raise。"""
    if not _IDENT_RE.match(name or ""):
        raise ValueError("illegal identifier: %r" % (name,))
    return name


@functools.lru_cache(maxsize=256)
def _host_ok(host: str, allow_private: bool) -> bool:
    if host in ("127.0.0.1", "::1", "localhost"):
        return allow_private
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0])
        except ValueError:
            return False
        if ip.is_loopback and not allow_private:
            return False
        if ip.is_link_local or ip.is_reserved or ip.is_multicast:
            return False          # 含云厂商元数据 169.254.169.254
        if ip.is_private and not allow_private:
            return False
    return True


def safe_url(url: str, allow_private: bool = False) -> str:
    """校验出站 URL：仅 http(s)；主机解析后的 IP 必须越界合法。通过则原样返回。"""
    p = urlparse(url or "")
    if p.scheme not in ("http", "https"):
        raise ValueError("blocked url scheme: %r" % (p.scheme,))
    host = p.hostname
    if not host or not _host_ok(host, bool(allow_private)):
        raise ValueError("blocked url host: %r" % (host,))
    return url


@functools.lru_cache(maxsize=256)
def _host_all_lan(host: str) -> bool:
    """主机的全部解析结果都必须落在私网/回环（打印机等内网 socket 汇点专用）。"""
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    if not infos:
        return False
    for info in infos:
        try:
            ip = ipaddress.ip_address(info[4][0])
        except ValueError:
            return False
        if not (ip.is_private or ip.is_loopback):
            return False
    return True


def safe_lan_host(host) -> str:
    """校验目标主机为私网/回环（与 safe_url 相反方向：这里公网才算违规）。
    适用于打印机直连这类「只应触达店内局域网」的裸 socket 汇点。"""
    h = str(host or "").strip()
    if not h or not _host_all_lan(h):
        raise ValueError("host is not a LAN address: %r" % (host,))
    return h


def safe_port(port) -> int:
    """校验端口为 1-65535 的整数，通过则返回 int。"""
    try:
        p = int(port)
    except (TypeError, ValueError):
        raise ValueError("port not an integer: %r" % (port,))
    if not (1 <= p <= 65535):
        raise ValueError("port out of range: %r" % (port,))
    return p


def safe_lan_subnet(subnet) -> str:
    """校验 /24 扫描网段形如 a.b.c 且整体落在私网/回环（网段扫描汇点专用）。"""
    s = str(subnet or "").strip()
    parts = s.split(".")
    if len(parts) != 3 or not all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
        raise ValueError("subnet must look like a.b.c (/24): %r" % (subnet,))
    net = ipaddress.ip_network(s + ".0/24")
    if not (net.network_address.is_private or net.network_address.is_loopback):
        raise ValueError("subnet is not private: %r" % (subnet,))
    return s


def confine(path: str, base: str = None) -> str:
    """校验路径落在沙箱根内（默认 CWD，环境变量 retaildemo_PATH_BASE 可覆盖）。

    系统临时目录（tempfile.gettempdir()）是第二合法根——诊断脚本/单测的
    临时 DB 合法落点；其余越界路径一律拒绝。
    """
    import tempfile
    root = os.path.realpath(base or os.environ.get("retaildemo_PATH_BASE") or os.getcwd())
    roots = [root]
    try:
        roots.append(os.path.realpath(tempfile.gettempdir()))
    except Exception:                       # noqa: BLE001
        pass
    rp = os.path.realpath(str(path))
    for r in roots:
        try:
            if os.path.commonpath([rp, r]) == r:
                return rp
        except ValueError:                  # Windows 跨盘符等无公共前缀
            continue
    raise ValueError("path outside sandbox: %s" % rp)
