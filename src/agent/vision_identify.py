# -*- coding: utf-8 -*-
"""src/agent/vision_identify.py — 方案A 桥接层隔离识图（2026-09-27 看图复盘修复）。

故障（桌面复盘《老王看图故障复盘与对话记录_20260927》）：同一 serve 会话连续看图时，
hermes vision_analyze 原生快路把图片挂进主会话上下文（context_compressor 对受保护
尾部的原生图片也不能降级为文本），多图叠加后发生跨图注意力泄漏——新图被前图品类
强行覆盖（断路器/球泡灯残影连错三次）。会话内提示词对抗不了已进 Attention Matrix
的前序权重，复盘给出的修复即本模块：主会话不再加载原图。

原理：网关进程内对视觉模型直接发一次零上下文请求（仅当前一张图 + 纯 OCR 提示词，
无 SOUL、无工具、无任何历史），产出纯 JSON 文本注入主会话提示词。原图物理上进不了
主会话，残影无从发生；主会话拿干净文本调 sx_identify / sx_search 对库存。

配置（环境变量，.env 注入；默认指本机 antigravity 视觉中继）：
  retaildemo_VISION_URL      默认 http://127.0.0.1:8317/v1/chat/completions
  retaildemo_VISION_API_KEY  中继密钥（缺省发无鉴权请求，本地中继会 401 → 走降级）
  retaildemo_VISION_MODEL    默认 gemini-3.7-flash-high（多模态；deepseek 系无视觉不可用）
  retaildemo_VISION_TIMEOUT  默认 45s（2048px 压图实测 ~8s；5.8MB 原图 ~20s）
  retaildemo_VISION_ALLOW_NONLOCAL  置 1 才允许非回环端点（默认只许本机，防配置手滑把
                          店员图片发去外网）

降级链：端点不可达/超时/密钥错/解析失败/路径越界 → identify_image 返回 None，
vision_block 对该图回落旧行为（把路径交给主会话 vision 工具看图）——最坏退回
2026-09-27 之前的行为，不会更差。
"""
import base64
import ipaddress
import json
import logging
import os
import socket
import threading
import time
import urllib.parse
import urllib.request

log = logging.getLogger("vision_identify")

_HERE = os.path.dirname(os.path.abspath(__file__))            # src/agent
_REPO_DIR = os.path.dirname(os.path.dirname(_HERE))            # 仓库根
# 只识网关自己落盘的图（save_image 固定写 data/tmp/，识别别人的路径一律拒绝）
_ALLOWED_IMG_DIR = os.path.realpath(os.path.join(_REPO_DIR, "data", "tmp"))

DEFAULT_VISION_URL = "http://127.0.0.1:8317/v1/chat/completions"
DEFAULT_VISION_MODEL = "gemini-3.7-flash-high"
_DEFAULT_TIMEOUT = 45.0
_MAX_IDENTIFY_IMAGES = 3     # 单次提问最多识 3 张（店员实际一问一图，留余量）
_MAX_SIDE = 2048             # 压边目标：5.8MB 原图 → ~0.4MB，时延 20s 档 → 8s 档（实测）
_JPEG_QUALITY = 85
_FIELD_CAP = 120             # brand/product/model/spec 单字段字符上限
_OCR_CAP = 2000              # ocr_raw 注入主会话的字符上限（控 token）
_OK_TTL = 600.0              # 成功结果缓存 10 分钟（同图重试/回落重跑不再二次花钱）
_FAIL_TTL = 120.0            # 失败也短缓存：中继挂掉时别让每次重试都白等一个超时
_CACHE_MAX = 32

_SYSTEM_PROMPT = (
    "你是五金店识图器。只看这一张图：先逐字读出图上所有文字（品牌标语、品名、型号、"
    "规格、条码旁数字、价格标签），不要遗漏；再判断品牌、商品品类、型号、规格。"
    '只输出一个 JSON 对象：{"brand":"…","product":"…","model":"…","spec":"…",'
    '"ocr_raw":"逐字原文"}。字段值保持图上原文，看不清的字段填空字符串，禁止编造。'
)

_CACHE: dict = {}
_CACHE_LOCK = threading.Lock()


def _config():
    try:
        timeout = float(os.environ.get("retaildemo_VISION_TIMEOUT", "") or _DEFAULT_TIMEOUT)
    except ValueError:
        timeout = _DEFAULT_TIMEOUT
    return (os.environ.get("retaildemo_VISION_URL", "").strip() or DEFAULT_VISION_URL,
            os.environ.get("retaildemo_VISION_API_KEY", "").strip(),
            os.environ.get("retaildemo_VISION_MODEL", "").strip() or DEFAULT_VISION_MODEL,
            timeout)


def _endpoint_allowed(url: str) -> bool:
    """视觉端点默认只许回环地址（中继是本机内网件；防手滑把店员图片发去外网）。"""
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return False
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return False
    if os.environ.get("retaildemo_VISION_ALLOW_NONLOCAL", "").strip() == "1":
        return True
    try:
        infos = socket.getaddrinfo(parts.hostname, None)
    except OSError:
        return False
    return all(ipaddress.ip_address(i[4][0]).is_loopback for i in infos)


def _downscale(raw: bytes, mime: str) -> tuple:
    """大图压边（PIL 在打包清单 hiddenimports 里）；压不动就原样返回。"""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(raw))
        if max(im.size) > _MAX_SIDE:
            im.thumbnail((_MAX_SIDE, _MAX_SIDE))
            buf = io.BytesIO()
            im.convert("RGB").save(buf, "JPEG", quality=_JPEG_QUALITY)
            return buf.getvalue(), "image/jpeg"
    except Exception:  # noqa: BLE001  压图是优化不是依赖：任何失败都回原图
        pass
    return raw, mime


def _parse_json_content(text: str):
    """模型回包 → dict。剥 ```json 围栏、截首个 { 到末个 }，解析失败返回 None。"""
    if not isinstance(text, str) or not text.strip():
        return None
    body = text.strip()
    if body.startswith("```"):
        body = body.strip("`")
        if body.lower().startswith("json"):
            body = body[4:]
    start, end = body.find("{"), body.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(body[start:end + 1])
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def _content_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # 防部分中继把 content 拆成多段 parts
        return "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


def _clean(data: dict):
    """裁剪模型输出：空字段丢弃、超长截断。产不出任何有效字段返回 None。"""
    out = {}
    for key in ("brand", "product", "model", "spec"):
        val = str(data.get(key) or "").strip()[:_FIELD_CAP]
        if val:
            out[key] = val
    ocr = data.get("ocr_raw")
    if isinstance(ocr, list):
        ocr = "\n".join(str(x) for x in ocr)
    ocr = str(ocr or "").strip()[:_OCR_CAP]
    if ocr:
        out["ocr_raw"] = ocr
    return out or None


def _cached(path: str):
    with _CACHE_LOCK:
        hit = _CACHE.get(path)
    if not hit or time.time() > hit[0]:
        return "miss", None
    return ("ok" if isinstance(hit[1], dict) else "fail"), hit[1]


def _store(path: str, result) -> None:
    ttl = _OK_TTL if isinstance(result, dict) else _FAIL_TTL
    with _CACHE_LOCK:
        if len(_CACHE) >= _CACHE_MAX:
            _CACHE.pop(next(iter(_CACHE)))
        _CACHE[path] = (time.time() + ttl, result)


def identify_image(path: str, timeout: float | None = None):
    """单图零上下文识别 → JSON dict；任何失败返回 None（绝不抛）。"""
    try:
        real = os.path.realpath(path)
    except (OSError, ValueError):
        return None
    if not (real.startswith(_ALLOWED_IMG_DIR + os.sep) and os.path.isfile(real)):
        log.warning("vision_identify: 路径越界拒绝 %s", os.path.basename(path))
        return None
    state, hit = _cached(real)
    if state != "miss":
        return hit
    result = None
    try:
        with open(real, "rb") as f:
            raw = f.read()
        mime = "image/png" if raw[:4] == b"\x89PNG" else "image/jpeg"
        raw, mime = _downscale(raw, mime)
        data_url = f"data:{mime};base64," + base64.b64encode(raw).decode("ascii")
        url, key, model, cfg_timeout = _config()
        if not _endpoint_allowed(url):
            log.warning("vision_identify: 端点非本机回环，拒绝发送（retaildemo_VISION_ALLOW_NONLOCAL=1 可解）")
        else:
            body = {"model": model, "max_tokens": 700, "temperature": 0,
                    "messages": [{"role": "user", "content": [
                        {"type": "text", "text": _SYSTEM_PROMPT},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ]}]}
            headers = {"Content-Type": "application/json"}
            if key:
                headers["Authorization"] = "Bearer " + key
            req = urllib.request.Request(
                url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
            t0 = time.time()
            with urllib.request.urlopen(req, timeout=timeout or cfg_timeout) as resp:
                out = json.loads(resp.read().decode("utf-8"))
            content = _content_text(((out.get("choices") or [{}])[0].get("message") or {})
                                    .get("content"))
            result = _clean(_parse_json_content(content) or {})
            if result:
                log.info("vision_identify: %s → %s/%s (%.1fs)",
                         os.path.basename(real), result.get("brand", ""),
                         result.get("product", ""), time.time() - t0)
            else:
                log.warning("vision_identify: 回包解析不出有效字段 %s", os.path.basename(real))
    except Exception as exc:  # noqa: BLE001  识图失败必须走降级而不是打死提问
        log.warning("vision_identify: %s 失败 %s: %s",
                    os.path.basename(real), type(exc).__name__, exc)
    _store(real, result)
    return result


def identify_images(paths: list) -> list:
    """多图顺序识别（店员一问一图，3 张封顶），与入参一一对应。"""
    return [identify_image(p) for p in (paths or [])[:_MAX_IDENTIFY_IMAGES]]


def _knowledge_hint(query: str) -> list:
    """识货成功后从本店知识库取 2 条补充知识（怎么用/怎么选/配什么）。

    2026-09-27 老板令「知识库没有利用上」：知识检索随识货结果一起进主会话，
    不再指望模型自觉去查。失败静默跳过（知识是增强不是依赖）。"""
    query = (query or "").strip()[:40]
    if not query:
        return []
    try:
        from core.ops import run_op
        r = run_op("knowledge_search", {"q": query, "limit": 2})
        if not isinstance(r, dict) or not r.get("ok"):
            return []
        rows = (r.get("data") or {}).get("data") if isinstance((r.get("data") or {}), dict) else None
        items = rows if isinstance(rows, list) else (r.get("data") if isinstance(r.get("data"), list) else [])
        out = []
        for row in (items or [])[:2]:
            if not isinstance(row, dict):
                continue
            qa = "问：%s\n答：%s" % (str(row.get("question") or "")[:80],
                                    str(row.get("answer") or "")[:200])
            out.append(qa)
        return out
    except Exception:  # noqa: BLE001
        return []


def vision_block(image_paths: list) -> str:
    """看图提示词构建（流式 serve 与 CLI 回落两路共用）。

    隔离识别成功 → 注入纯 JSON 与识货纪律，**不给路径**（主会话无从再挂原图）；
    任一图失败 → 该图回落旧行为（路径 + vision 工具指引），并附防串图告诫。"""
    if not image_paths:
        return ""
    results = identify_images(image_paths)
    lines = []
    first_result = None
    for path, result in zip(image_paths, results):
        if result:
            first_result = first_result or result
            if not lines:
                lines.append("[店员发来商品图片。系统已在独立隔离会话完成纯 OCR 识别"
                             "（主会话不加载原图，防止前文商品串图残影），结果如下：]")
            lines.append(json.dumps(result, ensure_ascii=False))
        else:
            lines.append("[店员发来商品图片，已保存在本机：" + path + "]\n"
                         "请用 vision 工具看图，判断可能是什么五金商品（给 2-3 个候选名称），"
                         "再调 sx_identify 工具（names 参数传候选 JSON 数组）对照商品库。"
                         "注意：只依据本图内容识别，严禁把本会话前面聊过的商品"
                         "（品牌/品类/价格）联想成本图内容。]")
    # 知识库随识货预取（老板 2026-09-27 令「知识库没有利用上」）：结构化保障，不靠模型自觉
    if first_result:
        hint_q = " ".join(str(first_result.get(k) or "") for k in ("brand", "product")).strip()
        hints = _knowledge_hint(hint_q)
        if hints:
            lines.append("[本店知识库「%s」前几条（识货时店员常追问怎么用/怎么选，可酌情参考，"
                         "与本图无关就不要提）]" % hint_q)
            lines.extend("- " + h for h in hints)
    lines.append("[识货纪律（店员 2026-09-27 复盘定）："
                 "1) 只依据本次识别结果对照商品库，前文聊过的商品一律不得套用到本图；"
                 "2) 先调 sx_identify（names 传识别结果的候选名 JSON 数组）对照商品库；"
                 "不中或可疑时做双轨检索：sx_search 用「品牌+规格」查一次，"
                 "再用「纯规格词」查一次（库内建档可能漏品牌，如「E27-40W白光-柱形灯(新款)」"
                 "品牌字段是默认品牌）；"
                 "3) 库内价格为 0 或缺失＝未定价，必须直说「库内未定价」，禁止报「0元」；"
                 "4) 识别结果为空或看不清就直说并建议扫码。]")
    return "\n".join(lines) + "\n\n"
