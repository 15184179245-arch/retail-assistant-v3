#!/usr/bin/env python3
"""示例零售 - 语音识别服务 (port 18607)

I17 语音输入（老板 2026-09-10 定向「联网实时」）：
  主路 = MiMo 云端 ASR（api.xiaomimimo.com，mimo-v2.5-asr，中英+方言，key 在 .env）
  兜底 = 本地 sherpa-onnx 流式 zipformer 中英 int8（断网/云端故障时店内仍可用）

APP：录音 16k PCM16 → 静默断句 → POST 网关 /api/asr/recognize（WAV 字节）→ 文本。
本服务只服务网关（本机回环），密钥不落日志。
"""
import array
import base64
import ipaddress
import json
import logging
import os
import re
import socket
import struct
import sys
import threading
import time
import urllib.request
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, setup_logging

LISTEN_HOST = os.environ.get("ASR_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("ASR_PORT", 18607))
START_TIME = time.time()

# ── MiMo 云端 ASR ──────────────────────────────────────────────
MIMO_API_KEY = (os.environ.get("MIMO_API_KEY") or "").strip()
# 2026-09-10 审计口径：出网仅 https + 域名白名单 + 解析 IP 边界校验 + 禁重定向
MIMO_API_BASE = (os.environ.get("ASR_API_BASE")
                 or "https://api.xiaomimimo.com/v1/chat/completions").strip()
MIMO_ALLOWED_HOSTS = {"api.xiaomimimo.com"}
MIMO_MODEL = os.environ.get("ASR_MODEL", "mimo-v2.5-asr")
MIMO_TIMEOUT = int(os.environ.get("ASR_MIMO_TIMEOUT", 20))

# ── 本地 sherpa 兜底 ───────────────────────────────────────────
SHERPA_MODEL_DIR = os.environ.get(
    "ASR_SHERPA_MODEL_DIR",
    os.path.join("data", "asr-models",
                 "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"))

MAX_AUDIO_BYTES = 6 * 1024 * 1024  # ≈3 分钟 16k PCM16，远低于 MiMo base64 10MB 上限

_stats = {"total": 0, "success": 0, "mimo": 0, "sherpa": 0, "failed": 0}
_stats_lock = threading.Lock()
_sherpa = None
_sherpa_lock = threading.Lock()


def _validate_api_base(url: str) -> bool:
    """出网闸门：https + 域名白名单 + 解析结果全是公网地址（防 DNS rebinding/内网穿透）。"""
    try:
        p = urlparse(url)
        if p.scheme != "https" or p.hostname not in MIMO_ALLOWED_HOSTS:
            return False
        infos = socket.getaddrinfo(p.hostname, 443, proto=socket.IPPROTO_TCP)
        for info in infos:
            ip = ipaddress.ip_address(info[4][0])
            if (ip.is_private or ip.is_loopback or ip.is_link_local
                    or ip.is_reserved or ip.is_multicast or ip.is_unspecified):
                return False
        return bool(infos)
    except Exception:
        return False


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止跟随重定向：白名单只保目标域，重定向可能被引去内网。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def wrap_pcm_to_wav(pcm: bytes, sample_rate: int = 16000, channels: int = 1,
                    bits: int = 16) -> bytes:
    """裸 PCM16 包 44 字节标准 WAV 头。"""
    byte_rate = sample_rate * channels * bits // 8
    block_align = channels * bits // 8
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE" + b"fmt " + \
        struct.pack("<IHHIIHH", 16, 1, channels, sample_rate,
                    byte_rate, block_align, bits) + \
        b"data" + struct.pack("<I", len(pcm))
    return header + pcm


def _extract_text(resp: dict) -> str:
    """OpenAI 兼容响应取转写文本（content 优先，退 reasoning_content）。"""
    try:
        msg = resp["choices"][0]["message"]
        return ((msg.get("content") or msg.get("reasoning_content") or "")
                .strip())
    except Exception:
        return ""


# 五金店语音搜索领域提示（2026-09-10 老板令「提示词全量覆盖优化」）：
# 固定转写规则 + 由 gen_asr_prompt.py 从商品库生成的全量热词表（data/asr_hotwords.txt，
# mtime 热加载），文件缺失回退下面的内置简版词表。
ASR_PROMPT_RULES = (
    "你是五金店的语音搜索转写引擎。请把音频逐字转写成搜索关键词文本，遵守："
    "①只输出转写文本本身，不要任何解释或前后缀，禁止添加句号、逗号等任何标点；"
    "②规格与型号保留原样（如 4分、1寸、M8、M10、Φ4、304、201、45#、12mm），禁止改写成汉字；"
    "③条码、货号与逐位报出的数字一律写成阿拉伯数字串（「幺」=1、「洞」=0，"
    "例：读「零零一点九九点幺九五七」要输出 001.99.1957），禁止写成汉字数字；"
    "④优先按下面词表中的本店词汇转写："
)
ASR_FALLBACK_WORDS = (
    "螺栓 螺母 自攻丝 膨胀丝 法兰 生料带 内六角 角磨机 切割片 电钻 冲击钻 "
    "焊条 水管 接头 直接 闸阀 球阀 三通 弯头 合页 铰链 锁芯 卷尺 水平尺 电线 绝缘胶带"
)
HOTWORDS_FILE = os.environ.get(
    "ASR_HOTWORDS_FILE", os.path.join("data", "asr_hotwords.txt"))
_hotwords_cache: tuple[float, str] | None = None  # (mtime, 词表正文)
_hotwords_lock = threading.Lock()


def load_hotwords(path: str = HOTWORDS_FILE) -> str:
    """读生成的热词表（mtime 变更即热加载）；缺失/异常回退内置简版。"""
    global _hotwords_cache
    try:
        mtime = os.path.getmtime(path)
        with _hotwords_lock:
            if _hotwords_cache and _hotwords_cache[0] == mtime:
                return _hotwords_cache[1]
        with open(path, "r", encoding="utf-8") as f:
            words = "\n".join(
                ln.strip() for ln in f
                if ln.strip() and not ln.startswith("#")
            )
        with _hotwords_lock:
            _hotwords_cache = (mtime, words)
        return words
    except Exception:
        return ASR_FALLBACK_WORDS


def domain_prompt() -> str:
    """组装 system 提示：固定规则 + 数据驱动全量词表。"""
    return ASR_PROMPT_RULES + load_hotwords()


def build_mimo_messages(wav_bytes: bytes, language: str = "zh") -> list:
    """构造 MiMo 请求 messages：领域提示 + 音频（独立纯函数便于单测）。"""
    b64 = base64.b64encode(wav_bytes).decode()
    return [
        {"role": "system", "content": domain_prompt()},
        {"role": "user", "content": [{
            "type": "input_audio",
            "input_audio": {"data": f"data:audio/wav;base64,{b64}"},
        }]},
    ]


def mimo_recognize(wav_bytes: bytes, language: str = "zh") -> str | None:
    """MiMo 云端识别；任何失败返回 None（调用方走本地兜底），绝不抛错。"""
    if not MIMO_API_KEY or not _validate_api_base(MIMO_API_BASE):
        return None
    body = json.dumps({
        "model": MIMO_MODEL,
        "messages": build_mimo_messages(wav_bytes, language),
        "extra_body": {"asr_options": {"language": language}},
    }).encode()
    try:
        req = urllib.request.Request(
            MIMO_API_BASE, data=body,
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {MIMO_API_KEY}"})
        opener = urllib.request.build_opener(_NoRedirect)
        with opener.open(req, timeout=MIMO_TIMEOUT) as resp:
            data = json.loads(resp.read())
        return _extract_text(data) or None
    except Exception as e:
        logging.warning("mimo asr 失败: %s", type(e).__name__)
        return None


def _load_sherpa():
    """懒加载本地引擎；失败返回 None 并记日志（服务仍可用 MiMo 主路）。"""
    global _sherpa
    with _sherpa_lock:
        if _sherpa is not None:
            return _sherpa
        try:
            import sherpa_onnx
            _sherpa = sherpa_onnx.OnlineRecognizer.from_transducer(
                tokens=os.path.join(SHERPA_MODEL_DIR, "tokens.txt"),
                encoder=os.path.join(SHERPA_MODEL_DIR,
                                     "encoder-epoch-99-avg-1.int8.onnx"),
                decoder=os.path.join(SHERPA_MODEL_DIR,
                                     "decoder-epoch-99-avg-1.onnx"),
                joiner=os.path.join(SHERPA_MODEL_DIR,
                                    "joiner-epoch-99-avg-1.int8.onnx"),
                num_threads=2,
            )
            logging.info("sherpa 本地引擎就绪 (%s)", SHERPA_MODEL_DIR)
        except Exception as e:
            logging.warning("sherpa 引擎加载失败: %s", e)
        return _sherpa


def sherpa_recognize(samples: list[float], sample_rate: int = 16000) -> str | None:
    """本地流式引擎整段解码；失败返回 None。"""
    rec = _load_sherpa()
    if rec is None:
        return None
    try:
        with _sherpa_lock:
            s = rec.create_stream()
            for i in range(0, len(samples), 1600):
                s.accept_waveform(sample_rate, samples[i:i + 1600])
                while rec.is_ready(s):
                    rec.decode_stream(s)
            s.input_finished()
            while rec.is_ready(s):
                rec.decode_stream(s)
            return (rec.get_result(s) or "").strip() or None
    except Exception as e:
        logging.warning("sherpa 识别失败: %s", e)
        return None


def _read_wav(pcm_bytes: bytes):
    """极简 WAV 解析（16-bit PCM data 块）；非 WAV 返回 None。"""
    if len(pcm_bytes) < 44 or pcm_bytes[:4] != b"RIFF" or pcm_bytes[8:12] != b"WAVE":
        return None
    pos = 12
    while pos + 8 <= len(pcm_bytes):
        cid = pcm_bytes[pos:pos + 4]
        size = struct.unpack("<I", pcm_bytes[pos + 4:pos + 8])[0]
        if cid == b"data":
            raw = pcm_bytes[pos + 8:pos + 8 + size]
            out = array.array("h")
            usable = len(raw) - (len(raw) % 2)
            out.frombytes(raw[:usable])
            if sys.byteorder == "big":
                out.byteswap()
            return out.tolist()
        pos += 8 + size + (size & 1)
    return None


class AsrHandler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        logging.info("%s - %s", self.client_address[0], fmt % args)

    def _send_json(self, data: dict, status: int = 200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/")
        if path in ("/health", "/api/health"):
            with _stats_lock:
                st = dict(_stats)
            return self._send_json(ok_response({
                "service": "retaildemo-asr", "status": "healthy",
                "uptime_s": round(time.time() - START_TIME, 1),
                "mimo_ready": bool(MIMO_API_KEY),
                "sherpa_ready": _sherpa is not None,
                "stats": st,
            }))
        self._send_json(err_response("未知路径", "NOT_FOUND", "未知路径"), 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path not in ("/recognize", "/api/recognize"):
            return self._send_json(err_response("未知路径", "NOT_FOUND", "未知路径"), 404)
        try:
            n = int(self.headers.get("Content-Length", 0) or 0)
        except ValueError:
            n = 0
        if n <= 0:
            return self._send_json(err_response("空音频", "EMPTY_AUDIO", "未收到音频"), 400)
        if n > MAX_AUDIO_BYTES:
            return self._send_json(err_response(
                "音频过长", "TOO_LARGE", "录音超过 3 分钟上限"), 413)
        audio = self.rfile.read(n)
        qs = parse_qs(parsed.query)
        rate = int((qs.get("rate") or ["16000"])[0] or 16000)
        lang = (qs.get("lang") or ["zh"])[0] or "zh"

        with _stats_lock:
            _stats["total"] += 1
        t0 = time.time()

        ctype = (self.headers.get("Content-Type") or "").lower()
        samples = None
        if "wav" in ctype or audio[:4] == b"RIFF":
            samples = _read_wav(audio)
        if samples is None:  # 裸 PCM16 直接包 WAV
            audio = wrap_pcm_to_wav(audio, rate)

        text, engine = None, None
        text = mimo_recognize(audio, lang)          # 主路：云端
        if text:
            engine = "mimo"
        else:                                        # 兜底：本地
            if samples is None:
                samples = _read_wav(audio)
            if samples:
                text = sherpa_recognize(samples, rate)
                engine = "sherpa"

        ms = int((time.time() - t0) * 1000)
        with _stats_lock:
            if text:
                _stats["success"] += 1
                _stats[engine] = _stats.get(engine, 0) + 1
            else:
                _stats["failed"] += 1
        if not text:
            return self._send_json(err_response(
                "没有听清", "NO_MATCH", "没听清，请靠近一点再说一次"))
        text = _clean_for_search(text)
        logging.info("asr ok engine=%s ms=%d chars=%d", engine, ms, len(text))
        return self._send_json(ok_response(
            {"text": text, "engine": engine, "ms": ms}))


# ── 中文数字 → 阿拉伯数字（2026-09-29 老板反馈「语音数字识别出来都是中文大写，
#    不利于条码搜索」）── 覆盖小写一二三/大写壹贰叁/逐位读法幺洞〇。
_ZH_DIGIT_MAP = {
    "零": "0", "〇": "0", "洞": "0",
    "一": "1", "幺": "1",
    "二": "2", "三": "3", "四": "4", "五": "5",
    "六": "6", "七": "7", "八": "8", "九": "9",
    "壹": "1", "贰": "2", "叁": "3", "肆": "4", "伍": "5",
    "陆": "6", "柒": "7", "捌": "8", "玖": "9",
}
_ZH_POWER_MAP = {"十": 10, "拾": 10, "百": 100, "佰": 100,
                 "千": 1000, "仟": 1000, "万": 10000}
_ZH_DOT_CHARS = "点"  # 只有点是口语小数点；。．是句读，当簇边界（由后续清洗规则处理）
_ZH_NUM_CLUSTER_RE = re.compile(
    "[" + re.escape("".join(_ZH_DIGIT_MAP) + "".join(_ZH_POWER_MAP)
                    + _ZH_DOT_CHARS) + "]+")
# 单字也不会组词的数字字，单簇也敢转（199幺957→1991957）；一~九单字不转（防「五金」→「5金」）
_ZH_SAFE_SINGLES = frozenset("幺〇")


def _zh_composite_value(cluster: str) -> int | None:
    """进位读法按数值转：「二十五」→25、「三百」→300、「壹万贰仟」→12000。

    歧义读法宁可不转返回 None：「四十五六」「三万五」「一百五」（150?105?）。
    """
    prev_digit = ""
    for i, ch in enumerate(cluster):
        if ch in _ZH_DIGIT_MAP:
            # 相邻两个非零数字（四十五六）无标准读法
            if prev_digit and int(_ZH_DIGIT_MAP[prev_digit]) > 0:
                return None
            prev_digit = ch
            continue
        prev_digit = ""
        if ch in "百佰千仟万" and i + 1 < len(cluster):
            rest = cluster[i + 1:]
            # 百/千/万后跟纯数字尾巴且中间无进位 → 口语省略读法（一百五/三万五）歧义
            if not any(c in _ZH_POWER_MAP for c in rest):
                return None
    total = section = num = 0
    for ch in cluster:
        if ch in _ZH_POWER_MAP:
            p = _ZH_POWER_MAP[ch]
            if p == 10000:
                section = (section + num) * p if (section + num) else p
                total += section
                section = num = 0
            else:
                section += (num or 1) * p
                num = 0
        else:
            num = num * 10 + int(_ZH_DIGIT_MAP[ch])
    return total + section + num


def _zh_convert_cluster(cluster: str, full: str, start: int) -> str:
    """单簇裁决：能确定是「数」才转，是「词」（五金/三通/两用）一律保留。"""
    has_power = any(ch in _ZH_POWER_MAP for ch in cluster)
    digit_positions = [ch for ch in cluster if ch in _ZH_DIGIT_MAP]
    if has_power:
        if any(ch in _ZH_DOT_CHARS for ch in cluster):
            return cluster                      # 「十点五」类歧义不动
        if any(ch in "零〇洞幺" for ch in cluster):
            return cluster                      # 逐位读法混进位词（洞洞壹佰）保前导零优先，不数值化
        if not digit_positions:
            return cluster                      # 「千瓦」「百万」纯进位词
        if start > 0 and full[start - 1].isdigit():
            return cluster                      # 「3千瓦」前邻阿拉伯数字
        val = _zh_composite_value(cluster)
        return str(val) if val is not None else cluster
    if any(ch in _ZH_DOT_CHARS for ch in cluster):
        if len(digit_positions) < 2:
            return cluster
        for i, ch in enumerate(cluster):        # 小数点必须两侧都是数字（「三点」时间/「点五」开头不动）
            if ch in _ZH_DOT_CHARS and not (
                    i > 0 and i < len(cluster) - 1
                    and cluster[i - 1] in _ZH_DIGIT_MAP
                    and cluster[i + 1] in _ZH_DIGIT_MAP):
                return cluster
        return "".join("." if ch in _ZH_DOT_CHARS else _ZH_DIGIT_MAP[ch]
                       for ch in cluster)
    if len(cluster) >= 2:
        return "".join(_ZH_DIGIT_MAP[ch] for ch in cluster)  # 逐位串保前导零：零零幺→001
    if cluster in _ZH_SAFE_SINGLES:
        return _ZH_DIGIT_MAP[cluster]
    return cluster


def zh_numerals_to_digits(text: str) -> str:
    """识别文本里的中文数字串 → 阿拉伯数字（条码/货号语音可搜）。

    只动「整簇都是数字字符」的串：零零一点九九点幺九五七→001.99.1957、
    壹玖玖壹玖伍柒→1991957、二点五→2.5、二十五→25；五金/三通/万用表/两用不动。
    """
    if not text:
        return text
    out: list[str] = []
    pos = 0
    for m in _ZH_NUM_CLUSTER_RE.finditer(text):
        out.append(text[pos:m.start()])
        out.append(_zh_convert_cluster(m.group(), text, m.start()))
        pos = m.end()
    out.append(text[pos:])
    return "".join(out)


def _clean_for_search(text: str) -> str:
    """识别结果搜索友好清洗（2026-09-11 老板反馈「识别后都带句号」）：
    句号等句读标点对搜索是实打实的破坏（LIKE/FTS 因尾部句号直接查不到）。
    规则：⓪中文数字串转阿拉伯数字（条码/货号语音可搜，词守卫防「五金→5金」）；
    ①数字间句号/点先归一为半角点（12。5→12.5，保住规格）；②中间句读标点替换为空格
    （作为词边界）；③尾部标点（含英文点）全删；④空白收紧。字母数字与括号引号一律保留。
    """
    t = (text or "").strip()
    t = zh_numerals_to_digits(t)
    t = re.sub(r"([0-9])[。．点]([0-9])", r"\1.\2", t)  # 数字间句号/点归一为小数点（12。5→12.5）
    t = re.sub(r"[。．，、；：？！,;?!]+", " ", t)       # 中间句读→空格（词边界，不与商品名粘连）
    t = re.sub(r"[.,;?!。．，、；：？！\s]+$", "", t)     # 尾部标点（含英文点）全删
    return re.sub(r"\s+", " ", t).strip()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def main():
    setup_logging("asr")
    logging.info("asr 服务启动 %s:%d mimo=%s sherpa_dir=%s",
                 LISTEN_HOST, LISTEN_PORT,
                 "on" if MIMO_API_KEY else "off(未配 key)",
                 SHERPA_MODEL_DIR)
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), AsrHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
