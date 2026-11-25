import json
import logging
import socket
import urllib.error
import urllib.request
from gateway.circuit_breaker import cb_is_open, cb_record_success, cb_record_failure
from gateway.router import SEARCH_SERVICE_URL
from gateway.auth import AUTH_TOKEN, AUTH_HEADER


def _service_name(service_url: str) -> str:
    port_map = {
        "18601": "search",
        "18602": "knowledge",
        "18603": "chat",
        "18604": "inventory+todos",
    }
    try:
        port = service_url.rsplit(":", 1)[-1]
        return port_map.get(port, port)
    except Exception:
        return service_url


def proxy(path: str, body: bytes | None = None, service_url: str = SEARCH_SERVICE_URL, timeout: int = 10, request_id: str = "", user_info: dict | None = None, method: str | None = None) -> dict:
    if cb_is_open(service_url):
        name = _service_name(service_url)
        return {"success": False, "degraded": True, "hint": "该服务暂时不可用，请稍后再试", "service": name}
    url = f"{service_url}{path}"
    method = method or ("POST" if body else "GET")
    data = body if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if AUTH_TOKEN:
        req.add_header(AUTH_HEADER, AUTH_TOKEN)
    if user_info:
        req.add_header("X-User-Id", user_info.get("user_id", ""))
        req.add_header("X-User-Role", user_info.get("role", ""))
        req.add_header("X-User-Name", user_info.get("display_name", ""))
    if request_id:
        req.add_header("X-Request-Id", request_id)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            cb_record_success(service_url)
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        # I19 运维可见性：下游业务 4xx 此前被静默转成 200，网关日志全是 200 分不出成败
        logging.warning("downstream %s %s -> HTTP %s", method, path, e.code)
        try:
            return json.loads(e.read().decode("utf-8"))
        except Exception:
            cb_record_failure(service_url)
            return {"success": False, "data": [], "hint": f"service error: {e.code}"}
    except (urllib.error.URLError, ConnectionError, socket.timeout):
        cb_record_failure(service_url)
        name = _service_name(service_url)
        return {"success": False, "degraded": True, "hint": "该服务暂时不可用，请稍后再试", "service": name}
    except Exception as e:
        cb_record_failure(service_url)
        return {"success": False, "data": [], "hint": str(e)}
