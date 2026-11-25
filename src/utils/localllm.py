"""本地小模型通道（F5）：回环专用 OpenAI 兼容调用（纯标准库）。

配置（.env，不入库）：
- LOCAL_LLM_BASE_URL : 形如 http://127.0.0.1:18608/v1（llama-server）
- LOCAL_LLM_MODEL    : 模型名（llama-server 通常任意非空即可）

安全口径：本通道 host 必须是字面量回环白名单（127.0.0.1/localhost/::1），
DNS 名与内网 IP 一律拒——只许打本机 llama-server。与云端 egress 的
「https 公网域名」守卫（F6）互补而非绕开：两条通道合起来覆盖回环+公网，
中间地带（内网 IP/自定域名）两边都不放行。
"""
import json
import os
import urllib.parse
import urllib.request

ALLOWED_LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


def local_llm_config():
    """读 .env 配置，校验通过返回 (base_url, model)；未配置或配错返回 None（静默不用本地通道）。"""
    base = (os.environ.get("LOCAL_LLM_BASE_URL") or "").strip()
    if not base:
        return None
    model = (os.environ.get("LOCAL_LLM_MODEL") or "").strip() or "local-model"
    u = urllib.parse.urlparse(base)
    if u.scheme != "http" or (u.hostname or "").lower() not in ALLOWED_LOCAL_HOSTS:
        return None
    return base.rstrip("/"), model


def chat_completions(base_url, model, messages, temperature=0.3, max_tokens=512, timeout=20):
    """POST /chat/completions；成功返回 content 文本（优先 content，退 reasoning_content），任何失败返回 None。

    失败一律 None（调用方自行降级），绝不抛错打断业务主链路。
    """
    url = base_url.rstrip("/") + "/chat/completions"
    body = json.dumps({
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }).encode()
    try:
        req = urllib.request.Request(
            url, data=body,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            msg = json.loads(resp.read())["choices"][0]["message"]
        return (msg.get("content") or msg.get("reasoning_content") or "").strip() or None
    except Exception:
        return None
