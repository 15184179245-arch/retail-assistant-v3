# -*- coding: utf-8 -*-
"""src/core/web_search.py — sx_web_search 联网搜索客户端（2026-09-27 老板令「用上反重力的谷歌联网搜索」）。

通道：本机 antigravity 反代（CLIProxyAPI :8317）的 **Gemini 原生 generateContent 端点**
+ `google_search` 内建接地工具（2026-09-27 实弹探针证实透传：groundingChunks 返回真实
检索结果，webSearchQueries 记录实际执行的搜索词）。OpenAI tools 形状只会得到普通
函数调用，不接地，故必须走 /v1beta 原生形状。

产出：接地摘要文本（不贴长链接，来源以域名标注）+ 实际搜索词 + 来源域名列表。
店内的价格/库存不归它管（那是 sx_search / sx_xzg 的权威）。

安全边界（SSRF 防线）：
  1. 端点只认 http/https；
  2. 主机名经 getaddrinfo 解析后**所有 IP 必须是回环地址**（反代在本机；把店员问句
     发往任何非回环地址都拒绝）——env retaildemo_VISION_ALLOW_NONLOCAL=1 才可放开；
  3. 请求钉到**已验证的 IP 字面量**（原 Host 头保留），DNS rebinding 无效；
  4. urlopen 只接受本函数构造的钉死 URL，不接收任何未校验字符串。

配置（环境变量，.env 注入）：
  retaildemo_WEBSEARCH_URL     默认 http://127.0.0.1:8317/v1beta/models/gemini-3.7-flash-high:generateContent
  retaildemo_WEBSEARCH_API_KEY 默认回落 retaildemo_VISION_API_KEY（同一反代的密钥）
  retaildemo_WEBSEARCH_TIMEOUT 默认 30s
"""
import ipaddress
import json
import logging
import os
import socket
import time
import urllib.error
import urllib.parse
import urllib.request

log = logging.getLogger("web_search")

DEFAULT_URL = ("http://127.0.0.1:8317/v1beta/models/"
               "gemini-3.7-flash-high:generateContent")
_DEFAULT_TIMEOUT = 30.0
_ANSWER_CAP = 1800        # 接地摘要注入主会话的字符上限
_SOURCES_CAP = 8          # 来源域名列表上限
_URLS_CAP = 5             # web_read 返回的实际读取 URL 上限
_INSTRUCTION = (
    "你是五金店店员的联网搜索助手。必须先用 google_search 检索，再基于检索结果回答，"
    "检索不到就直说检索不到，禁止编造。回答控制在 300 字内说清要点；"
    "正文里不要贴任何长链接，来源只在句尾用「（来源：域名）」标注。"
)
_READ_INSTRUCTION = (
    "你是五金店店员的网页阅读助手。用 url_context 读取给定网页，基于页面内容用中文回答，"
    "页面打不开或没有相关内容就直说，禁止编造。回答 300 字内说清要点，不贴长链接。"
)


def _config():
    try:
        timeout = float(os.environ.get("retaildemo_WEBSEARCH_TIMEOUT", "") or _DEFAULT_TIMEOUT)
    except ValueError:
        timeout = _DEFAULT_TIMEOUT
    url = os.environ.get("retaildemo_WEBSEARCH_URL", "").strip() or DEFAULT_URL
    key = (os.environ.get("retaildemo_WEBSEARCH_API_KEY", "").strip()
           or os.environ.get("retaildemo_VISION_API_KEY", "").strip())
    return url, key, timeout


def _resolve_pinned_url(url: str) -> str:
    """校验端点并返回钉到已验证 IP 的 URL；不合规抛 ValueError。

    防线：scheme 白名单 → 主机名解析 → 全部 IP 必须回环（或显式放开）→
    用该 IP 字面量重组 URL（防 DNS rebinding，Host 头由 urllib 按原主机名带出）。"""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ValueError("bad endpoint scheme/host")
    allow_nonlocal = os.environ.get("retaildemo_VISION_ALLOW_NONLOCAL", "").strip() == "1"
    infos = socket.getaddrinfo(parts.hostname, None)
    ips = sorted({i[4][0] for i in infos})
    if not allow_nonlocal and not all(ipaddress.ip_address(ip).is_loopback for ip in ips):
        raise ValueError("endpoint host is not loopback")
    pinned_host = parts.hostname if allow_nonlocal else ips[0]
    netloc = parts.netloc.replace(parts.hostname, pinned_host)
    return urllib.parse.urlunsplit((parts.scheme, netloc, parts.path, parts.query, parts.fragment))


def _post_json(pinned_url: str, body: dict, key: str, timeout: float) -> dict:
    """urlopen 的唯一出口：只吃 _resolve_pinned_url 产出的钉死 URL。
    5xx/网络瞬断重试 2 次（2026-09-27 店端实弹：铜价查询吃中继 500，行情类查询频率高，
    反代上游偶发抖动必须就地消化）。"""
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    last_exc = None
    for attempt in range(3):
        if attempt:
            time.sleep(1.5 * attempt)  # 1.5s / 3s 退避
        req = urllib.request.Request(pinned_url, data=json.dumps(body).encode("utf-8"),
                                     headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            last_exc = exc
            if exc.code < 500:
                raise  # 4xx 是请求本身的问题（401/429），重试无意义
        except (urllib.error.URLError, OSError, json.JSONDecodeError) as exc:
            last_exc = exc
    raise last_exc  # type: ignore[misc]


def _domain(uri: str) -> str:
    try:
        host = urllib.parse.urlsplit(uri).hostname or ""
    except ValueError:
        return ""
    return host.removeprefix("www.")


def _parse(out: dict) -> dict:
    """Gemini 原生回包 → {answer, queries, sources}。"""
    cand = (out.get("candidates") or [{}])[0]
    text = "".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts") or []
                   if isinstance(p, dict))
    meta = cand.get("groundingMetadata") or {}
    queries = [str(q) for q in (meta.get("webSearchQueries") or [])][:5]
    sources, seen = [], set()
    for chunk in meta.get("groundingChunks") or []:
        web = (chunk or {}).get("web") or {}
        dom = _domain(str(web.get("uri") or ""))
        if dom and dom not in seen:
            seen.add(dom)
            sources.append({"domain": dom, "title": str(web.get("title") or "")[:120]})
        if len(sources) >= _SOURCES_CAP:
            break
    return {"answer": str(text or "").strip()[:_ANSWER_CAP],
            "queries": queries, "sources": sources}


def search(q: str, timeout: float | None = None) -> dict:
    """谷歌接地搜索。任何失败都返回带 hint 的降级体，绝不抛（老王照实说联网不可用即可）。"""
    q = (q or "").strip()
    empty = {"answer": "", "queries": [], "sources": []}
    if not q:
        return {**empty, "hint": "需要 q 参数（搜索词）"}
    url, key, cfg_timeout = _config()
    try:
        pinned = _resolve_pinned_url(url)
    except (ValueError, OSError) as exc:  # getaddrinfo 失败是 gaierror(OSError)，同样降级
        log.warning("web_search: 端点校验拒绝 %s", exc)
        return {**empty, "hint": "联网检索配置不可用（%s）" % exc}
    body = {"contents": [{"role": "user", "parts": [{"text": _INSTRUCTION + "\n\n检索任务：" + q}]}],
            "tools": [{"google_search": {}}]}
    try:
        t0 = time.time()
        out = _post_json(pinned, body, key, timeout or cfg_timeout)
        data = _parse(out)
        if not data["answer"]:
            log.warning("web_search: 回包无文本 %r", q[:40])
            return {**empty, "hint": "联网检索没有返回内容"}
        log.info("web_search: %r → %d 字摘要, %d 来源 (%.1fs)",
                 q[:40], len(data["answer"]), len(data["sources"]), time.time() - t0)
        return data
    except Exception as exc:  # noqa: BLE001  联网是增强不是依赖，失败必须降级
        log.warning("web_search: %r 失败 %s: %s", q[:40], type(exc).__name__, exc)
        return {**empty, "hint": "联网检索暂不可用（%s: %s）" % (type(exc).__name__, exc)}


def _validate_public_url(url: str) -> str:
    """外网网页地址校验（出站工具防被当内网探针）：scheme 白名单 +
    主机解析后全部 IP 必须是公网地址（挡回环/内网/链路本地/保留/组播段）。"""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ValueError("只支持带 http/https 的完整网页地址")
    try:
        infos = socket.getaddrinfo(parts.hostname, None)
    except OSError as exc:
        raise ValueError("域名解析失败") from exc
    for i in infos:
        addr = ipaddress.ip_address(i[4][0])
        if (addr.is_loopback or addr.is_private or addr.is_link_local
                or addr.is_reserved or addr.is_multicast or addr.is_unspecified):
            raise ValueError("不允许访问内网/保留地址")
    return url


def _parse_read(out: dict) -> dict:
    """url_context 回包 → {answer, urls}。urls 取实际检索到的页面列表。"""
    cand = (out.get("candidates") or [{}])[0]
    text = "".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts") or []
                   if isinstance(p, dict))
    urls = []
    for m in (cand.get("urlContextMetadata") or {}).get("urlMetadata") or []:
        if not isinstance(m, dict):
            continue
        got = str(m.get("retrievedUrl") or "")
        if got:
            urls.append({"url": got[:300], "status": str(m.get("urlRetrievalStatus") or "")})
        if len(urls) >= _URLS_CAP:
            break
    return {"answer": str(text or "").strip()[:_ANSWER_CAP], "urls": urls}


def read_url(url: str, question: str = "", timeout: float | None = None) -> dict:
    """url_context 读取指定网页（谷歌侧抓取）。失败降级带 hint，绝不抛。"""
    url = (url or "").strip()
    empty = {"answer": "", "urls": []}
    if not url:
        return {**empty, "hint": "需要 url 参数（完整网页地址，含 https://）"}
    try:
        _validate_public_url(url)
    except ValueError as exc:
        log.warning("web_read: URL 校验拒绝 %s", exc)
        return {**empty, "hint": "网页地址不可用（%s）" % exc}
    relay_url, key, cfg_timeout = _config()
    try:
        pinned = _resolve_pinned_url(relay_url)
    except (ValueError, OSError) as exc:
        log.warning("web_read: 端点校验拒绝 %s", exc)
        return {**empty, "hint": "网页阅读配置不可用（%s）" % exc}
    ask = (("想了解：" + question.strip()[:120] + "\n\n") if (question or "").strip() else "")
    body = {"systemInstruction": {"parts": [{"text": _READ_INSTRUCTION}]},
            "contents": [{"role": "user",
                          "parts": [{"text": ask + "读取并总结这个网页：" + url}]}],
            "tools": [{"url_context": {}}]}
    try:
        t0 = time.time()
        out = _post_json(pinned, body, key, timeout or cfg_timeout)
        data = _parse_read(out)
        if not data["answer"]:
            log.warning("web_read: 回包无文本 %r", url[:60])
            return {**empty, "hint": "网页读取没有返回内容（可能页面打不开）"}
        log.info("web_read: %r → %d 字摘要, %d URL (%.1fs)",
                 url[:60], len(data["answer"]), len(data["urls"]), time.time() - t0)
        return data
    except Exception as exc:  # noqa: BLE001
        log.warning("web_read: %r 失败 %s: %s", url[:60], type(exc).__name__, exc)
        return {**empty, "hint": "网页读取暂不可用（%s: %s）" % (type(exc).__name__, exc)}
