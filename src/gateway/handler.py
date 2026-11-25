import json
import gzip
import logging
import os
import socket
import sqlite3
import uuid
import re
import time
import datetime
import urllib.request
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

from utils.validate import parse_json_body, validate_request
from utils.errors import ok_response, err_response, get_logger
from utils.logging import set_request_id, record_error

from gateway.router import (
    BILLS_DB_PATH, SEARCH_SERVICE_URL, INVENTORY_SERVICE_URL, PRINT_SERVICE_URL,
    PRINTER_DEFAULT_PORT,
    ROUTES_GET, ROUTES_GET_WITH_QS, ROUTES_POST, ROUTES_PROXY_GET, ROUTES_PROXY_POST, ROUTES_PROXY_DELETE,
)
from gateway.auth import (
    AUTH_TOKEN, _verify_auth, _get_current_user, _jwt_encode, _verify_password,
    _require_admin,
    login_key, login_allowed, login_record_failure, login_reset,
    _list_employees, _create_employee, _update_employee, _disable_employee,
    _get_today_revenue, _get_roster,
)
from gateway.bills_sync import load_bills, merge_bills, BillsLoadError
from gateway.proxy import proxy
from gateway.printer import tspl_price_tag, send_tspl
from gateway.phone_control import (
    phone_poll, phone_get_result, phone_queue_command, phone_submit_result,
    _PHONE_CMD_MAX_LEN, _PHONE_CMD_BLOCKED_CHARS,
)
from gateway.admin_dashboard import (
    get_dashboard_data, render_dashboard_html, export_marks_csv, sync_mark_items,
    get_admin_products, get_admin_product_detail, get_admin_bills, update_bill_status,
    get_admin_customers, get_admin_inventory_overview, resolve_data_mark,
    get_admin_services_health, admin_ai_chat,
)


def get_latest_apk_info() -> dict:
    env_apk = os.environ.get("APK_PATH")
    if env_apk and os.path.exists(env_apk):
        apk_path = env_apk
    else:
        _repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        apk_candidates = [
            os.path.normpath(os.path.join(_repo_root, "retaildemo-v2.apk")),
            os.path.normpath(os.path.join(_repo_root, "android-v2", "app", "build", "outputs", "apk", "release", "app-release.apk")),
            os.path.normpath(os.path.join(_repo_root, "android-v2", "app", "build", "outputs", "apk", "debug", "app-debug.apk")),
        ]
        apk_path = next((p for p in apk_candidates if os.path.exists(p)), None)

    file_size = os.path.getsize(apk_path) if apk_path and os.path.exists(apk_path) else 0
    mtime = os.path.getmtime(apk_path) if apk_path and os.path.exists(apk_path) else time.time()
    release_date = datetime.datetime.fromtimestamp(mtime).strftime("%Y-%m-%d %H:%M")

    data_dir = os.environ.get("DATA_DIR") or os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "data"))
    version_file = os.path.join(data_dir, "app_version.json")
    version_data = {}
    if os.path.exists(version_file):
        try:
            with open(version_file, "r", encoding="utf-8") as f:
                version_data = json.load(f)
        except Exception:
            pass

    # 动态关联 android-v2/app/build.gradle.kts 作为事实源默认值，确保前后端与打包版本号绝对对齐
    gradle_path = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "android-v2", "app", "build.gradle.kts"))
    default_ver_code = 2
    default_ver_name = "2.1.0"
    if os.path.exists(gradle_path):
        try:
            with open(gradle_path, "r", encoding="utf-8") as gf:
                g_content = gf.read()
            m_code = re.search(r'versionCode\s*=\s*(\d+)', g_content)
            if m_code:
                default_ver_code = int(m_code.group(1))
            m_name = re.search(r'versionName\s*=\s*["\']([^"\']+)["\']', g_content)
            if m_name:
                default_ver_name = m_name.group(1)
        except Exception:
            pass

    ver_code = int(version_data.get("version_code") or version_data.get("versionCode") or default_ver_code)
    ver_name = str(version_data.get("version_name") or version_data.get("versionName") or default_ver_name)
    min_ver_code = int(version_data.get("min_version_code") or version_data.get("minVersionCode") or 1)
    title = str(version_data.get("title") or "店员助手新版本推送")
    update_log = str(version_data.get("update_log") or version_data.get("updateLog") or (
        "1. 连续扫码：支持相机常开批量连扫、同码防抖、取景框手电筒与对焦\n"
        "2. 手写识别：支持现场拍照识别手写数字码与手写商品名\n"
        "3. 扫码绑定：扫码未识别商品支持现场一键绑定副码\n"
        "4. 记账加货：商品条左滑删除，挂单后直达二级账单随时继续加货\n"
        "5. 出库规范：标记出库强制选择收款方式，POS收款强制上传小票凭证\n"
        "6. 串货随手记：串货填名即可挂单，绝不卡柜台开单速度"
    ))
    force_update = bool(version_data.get("force_update") or version_data.get("forceUpdate") or False)

    return {
        "version_code": ver_code,
        "versionCode": ver_code,
        "version_name": ver_name,
        "versionName": ver_name,
        "min_version_code": min_ver_code,
        "minVersionCode": min_ver_code,
        "download_url": "/retaildemo-v2.apk",
        "downloadUrl": "/retaildemo-v2.apk",
        "apk_size": file_size,
        "apkSize": file_size,
        "release_date": version_data.get("release_date") or release_date,
        "releaseDate": version_data.get("releaseDate") or release_date,
        "title": title,
        "update_log": update_log,
        "updateLog": update_log,
        "force_update": force_update,
        "forceUpdate": force_update,
    }



def _f6c(p, base=None):
    """F6 路径守卫：realpath 归一 + 根前缀 + .. 分量显式检查，返回值即落盘路径。"""
    import os as _os
    _root = _os.path.realpath(base or _os.environ.get("retaildemo_PATH_BASE") or _os.getcwd())
    _rp = _os.path.normpath(p)
    if not _os.path.isabs(_rp):
        _rp = _os.path.normpath(_os.path.join(_root, _rp))
    try:
        if _os.path.commonpath([_rp, _root]) != _root:
            raise ValueError("path outside sandbox: " + _rp)
    except ValueError:
        if not _rp.startswith(_root + _os.sep):
            raise ValueError("path outside sandbox: " + _rp)
    if ".." in _os.path.relpath(_rp, _root).split(_os.sep):
        raise ValueError("path traversal blocked: " + _rp)
    return _rp


_IMAGE_NAME_RE = re.compile(r"[A-Za-z0-9_\-]{1,96}\.(jpg|jpeg|png|gif|webp)", re.IGNORECASE)
_IMAGE_PREFIXES = ("/api/inventory/images/", "/images/")


def render_download_page() -> str:
    """手机浏览器直出的 APK 下载页：店员卸载后/装不上时，浏览器打开 /download 自助重装。"""
    info = get_latest_apk_info()
    size_mb = info["apk_size"] / 1024 / 1024
    logs = "".join(
        f"<li>{line}</li>"
        for line in str(info["update_log"]).splitlines()
        if line.strip()
    )
    return (
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width, initial-scale=1'>"
        "<title>店员助手下载</title><style>"
        "body{font-family:-apple-system,'Microsoft YaHei',sans-serif;background:#f6f7f9;"
        "margin:0;padding:24px;display:flex;justify-content:center}"
        ".card{background:#fff;border-radius:16px;padding:28px 22px;max-width:420px;"
        "width:100%;box-shadow:0 2px 12px rgba(0,0,0,.08)}"
        "h1{font-size:22px;margin:0 0 4px;color:#1a1d21}"
        ".ver{color:#8a939b;font-size:14px;margin-bottom:18px}"
        ".btn{display:block;background:#e8590c;color:#fff;text-align:center;"
        "border-radius:12px;padding:15px;font-size:18px;font-weight:700;"
        "text-decoration:none;margin:18px 0 10px}"
        ".tip{color:#8a939b;font-size:13px;line-height:1.7}"
        "ul{color:#3a4046;font-size:14px;line-height:1.9;padding-left:20px;margin:12px 0}"
        "</style></head><body><div class='card'>"
        "<h1>示例零售 · 店员助手</h1>"
        "<div class='ver'>" + info['version_name'] + "（版本号 " + str(info['version_code']) + "） · "
        + format(size_mb, '.1f') + "MB · " + str(info['release_date']) + "</div>"
        "<a class='btn' href='" + info['download_url'] + "'>⬇ 下载安装最新版</a>"
        "<div class='tip'>下载完成后点击安装；若提示「未知来源」，允许本次安装即可。<br>"
        "已装过旧版可直接覆盖安装，无需卸载。</div>"
        "<div class='ver' style='margin-top:18px'>本版更新</div><ul>" + logs + "</ul>"
        "</div></body></html>"
    )


def _resolve_image_path(path: str):
    """I19：/images/<file> 与 /api/inventory/images/<file> → IMAGES_DIR 下真实文件路径。

    文件名来自 URL：先白名单（单段、字母数字-_、限定扩展名）再 F6 路径守卫，
    不合规或不存在一律 None。IMAGES_DIR 与 inventory 服务共用同一环境变量。
    """
    for prefix in _IMAGE_PREFIXES:
        if path.startswith(prefix):
            tail = path[len(prefix):]
            if not _IMAGE_NAME_RE.fullmatch(tail):
                return None
            root = os.path.realpath(os.environ.get("IMAGES_DIR", "/data/images"))
            try:
                p = _f6c(os.path.join(root, tail), base=root)
            except ValueError:
                return None
            return p if os.path.isfile(p) else None
    return None


class GatewayHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        logging.info(f"{self.client_address[0]} - {format % args}")

    def _read_body_with_timeout(self, timeout: int = 15) -> bytes:
        # 2026-09-09 审计：Content-Length 原本无上限（内存 DoS 面），超限按空体拒收
        max_body_bytes = 10 * 1024 * 1024
        try:
            content_length = int(self.headers.get("Content-Length", 0) or 0)
        except ValueError:
            return b"{}"
        if content_length <= 0:
            return b"{}"
        if content_length > max_body_bytes:
            return b"{}"
        if hasattr(self, "request") and hasattr(self.request, "settimeout"):
            self.request.settimeout(timeout)
        try:
            return self.rfile.read(content_length)
        except socket.timeout:
            return b"{}"

    def _send_json(self, data: dict, status: int = 200, request_id: str = "", tag_request_id: bool = True):
        if not _verify_auth(self.headers, self.path, self.command):
            self.send_response(401)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.end_headers()
            self.wfile.write(json.dumps(
                err_response("未授权访问", "UNAUTHORIZED", "未授权访问"),
                ensure_ascii=False
            ).encode("utf-8"))
            return
        # tag_request_id=False 供「裸数据体」路由使用（GET /bills/sync 的值必须全是纯账单对象）：
        # 2026-09-11 老板实测：_request_id 混进裸 map 后，APP 端 Gson 按账单对象解析这个字符串值
        # 直接抛 Expected BEGIN_OBJECT but was STRING，账单同步/恢复全挂。
        if request_id and tag_request_id and isinstance(data, dict):
            data["_request_id"] = request_id
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        # 协商 gzip：只在客户端声明支持时启用（Python urllib / C# HttpClient 默认不发
        # Accept-Encoding，保持不压缩零影响；OkHttp 与浏览器默认带且自动解压）。
        # 搜索类大响应（弱查询可达 300KB+）走 frp 隧道是查价转圈的主因，压缩比约 10:1。
        accept_enc = (self.headers.get("Accept-Encoding") or "").lower()
        if len(raw) >= 1024 and "gzip" in accept_enc:
            raw = gzip.compress(raw)
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _send_html(self, html_content: str, status: int = 200):
        body = html_content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_csv(self, csv_content: str, filename: str = "data_marks.csv"):
        body = csv_content.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/csv; charset=utf-8")
        self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _handle_apk(self, is_head: bool = False):
        env_apk = os.environ.get("APK_PATH")
        if env_apk:
            apk_path = env_apk if os.path.exists(env_apk) else None
        else:
            apk_candidates = [
                os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "retaildemo-v2.apk")),
                os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "android-v2", "app", "build", "outputs", "apk", "release", "app-release.apk")),
                os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "android-v2", "app", "build", "outputs", "apk", "debug", "app-debug.apk")),
            ]
            apk_path = next((p for p in apk_candidates if os.path.exists(p)), None)
        if not apk_path:
            self._send_json(err_response("APK not found", "NOT_FOUND", "APK 文件未找到"), 404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", 'attachment; filename="retaildemo-v2.apk"')
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.send_header("Content-Length", str(os.path.getsize(apk_path)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if not is_head:
            with open(apk_path, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)

    def _handle_product_image(self, path: str):
        """I19：商品照片字节直读（inventory 的 /images/* APP 够不着 :18604，proxy 只走 JSON）。"""
        resolved = _resolve_image_path(path)
        if resolved is None:
            return self._send_json(err_response("图片不存在", "NOT_FOUND", "图片不存在"), 404)
        mime = {
            "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
            "gif": "image/gif", "webp": "image/webp",
        }.get(os.path.splitext(resolved)[1].lower().lstrip("."), "application/octet-stream")
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Cache-Control", "max-age=86400")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(os.path.getsize(resolved)))
        self.end_headers()
        with open(resolved, "rb") as f:
            while chunk := f.read(65536):
                self.wfile.write(chunk)

    def do_HEAD(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path in ("/apk", "/retaildemo-v2.apk"):
            return self._handle_apk(is_head=True)
        self.send_response(200)
        self.end_headers()

    def do_OPTIONS(self):
        self._send_json({})

    def do_GET(self):
        request_id = uuid.uuid4().hex[:12]
        set_request_id(request_id)
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        # 1. 兼容从第三方应用或复制链接时 # 被编码为 %23 的情况（如 /dashboard%23overview）
        if "%23" in self.path:
            clean_url = unquote(self.path)
            self.send_response(302)
            self.send_header("Location", clean_url)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        unquoted_path = unquote(parsed.path)
        base_path = unquoted_path.split("#")[0].rstrip("/")
        path = base_path

        # 2026-09-11 bug猎杀（越权访问）：/api/admin/* 原实现 GET 侧零授权检查——
        # 严格模式（F8 切换）下未认证请求即可读全部账单/客户/毛利并导出 CSV。
        # 私服模式（未设 AUTH_TOKEN）_get_current_user 回退本地 admin，店内行为不变。
        if path.startswith("/api/admin") and not _require_admin(self.headers):
            return self._send_json(err_response("需要管理员权限", "FORBIDDEN", "请使用管理员账号登录后台"), 403, request_id=request_id)

        # I19 商品照片静态直读：同机同盘仿 APK 直读模式，白名单+路径守卫后供字节
        if path.startswith("/images/") or path.startswith("/api/inventory/images/"):
            return self._handle_product_image(path)

        # 2. 路由直通与别名重定向（如 /overview, /products, /bills, /inventory, /customers, /governance, /system, /ai）
        sub_tabs = ("overview", "products", "bills", "inventory", "customers", "governance", "system", "ai")
        clean_name = base_path.lstrip("/")
        if clean_name in sub_tabs:
            self.send_response(302)
            self.send_header("Location", f"/dashboard#{clean_name}")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        if base_path.startswith("/dashboard/") and len(base_path) > len("/dashboard/"):
            sub = base_path[len("/dashboard/"):].split("/")[0]
            if sub in sub_tabs:
                self.send_response(302)
                self.send_header("Location", f"/dashboard#{sub}")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return

        if base_path.startswith("/admin/") and len(base_path) > len("/admin/"):
            sub = base_path[len("/admin/"):].split("/")[0]
            if sub in sub_tabs:
                self.send_response(302)
                self.send_header("Location", f"/dashboard#{sub}")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return

        # I5 治理页（同义词/查询日志/串货/待补全，server-rendered 只读浏览）
        if base_path == "/admin/gov":
            from admin.pages import render_gov_page
            return self._send_html(render_gov_page())

        # APP 之外的手机浏览器下载入口（卸载后/装不上时自助重装）
        if base_path in ("/download", "/app", "/xiazai"):
            return self._send_html(render_download_page())

        # 3. 经营大盘主页
        if base_path in ("", "/", "/admin", "/dashboard") or base_path.startswith(("/dashboard", "/admin")):
            data = get_dashboard_data()
            return self._send_html(render_dashboard_html(data))

        if path == "/api/admin/export-marks":
            csv_content = export_marks_csv()
            return self._send_csv(csv_content, "data_marks.csv")

        if path in ("/api/admin/dashboard/data", "/api/admin/overview"):
            data = get_dashboard_data()
            return self._send_json(ok_response(data=data), request_id=request_id)

        # I5 治理队列数据（JSON）
        if path == "/api/admin/gov/data":
            from admin import governance as _gov
            return self._send_json(ok_response(data={
                "synonyms": _gov.synonym_groups(200),
                "query_log": _gov.query_log(100),
                "zero_hit_top": _gov.zero_hit_top(15),
                "strade": _gov.strade_ledger(),
                "custom_queue": _gov.pending_custom_queue(),
                "alias_recent": _gov.alias_recent(50),
            }), request_id=request_id)

        if path == "/api/admin/products":
            kw = (qs.get("keyword") or [""])[0]
            cat = (qs.get("category") or [""])[0]
            ft = (qs.get("filter_type") or ["all"])[0]
            try:
                page = int((qs.get("page") or ["1"])[0])
            except (ValueError, TypeError):
                page = 1
            try:
                page_size = int((qs.get("page_size") or ["30"])[0])
            except (ValueError, TypeError):
                page_size = 30
            res = get_admin_products(keyword=kw, category=cat, filter_type=ft, page=page, page_size=page_size)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path.startswith("/api/admin/products/"):
            try:
                pid = int(path.split("/")[-1])
                res = get_admin_product_detail(pid)
                return self._send_json(ok_response(data=res), request_id=request_id)
            except Exception as e:
                return self._send_json(err_response(str(e)), 400, request_id=request_id)

        if path == "/api/admin/bills":
            kw = (qs.get("keyword") or [""])[0]
            st = (qs.get("status") or ["all"])[0]
            sf = (qs.get("staff") or ["all"])[0]
            try:
                page = int((qs.get("page") or ["1"])[0])
            except (ValueError, TypeError):
                page = 1
            try:
                page_size = int((qs.get("page_size") or ["25"])[0])
            except (ValueError, TypeError):
                page_size = 25
            res = get_admin_bills(keyword=kw, status=st, staff=sf, page=page, page_size=page_size)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path == "/api/admin/customers":
            res = get_admin_customers()
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path in ("/api/admin/inventory/overview", "/api/admin/inventory"):
            res = get_admin_inventory_overview()
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path in ("/api/admin/services/status", "/api/admin/services"):
            res = get_admin_services_health()
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path in ("/api/app/version", "/api/app/check_update"):
            info = get_latest_apk_info()
            return self._send_json(ok_response(data=info), request_id=request_id)

        if path in ("/apk", "/retaildemo-v2.apk"):
            return self._handle_apk(is_head=False)

        if path in ("/print/ping", "/api/print/ping"):
            ip = (qs.get("ip") or [""])[0]
            if not ip:
                return self._send_json(err_response("缺少 ip 参数", "MISSING_FIELD", "请提供打印机 IP 地址"), 400)
            port_raw = (qs.get("port") or [str(PRINTER_DEFAULT_PORT)])[0]
            try:
                port = int(port_raw)
            except (TypeError, ValueError):
                return self._send_json(err_response("端口不合法", "BAD_REQUEST", "打印机端口必须是数字"), 400)
            result = send_tspl(ip, port, chr(27) + '~@')
            return self._send_json(result)

        if path == "/api/auth/me":
            user = _get_current_user(self.headers)
            if not user:
                return self._send_json(err_response("无效的Token", "UNAUTHORIZED", "登录已过期，请重新登录"), 401, request_id=request_id)
            return self._send_json({"success": True, "data": user}, request_id=request_id)

        if path == "/api/employees":
            result = _list_employees(self.headers)
            status = result.pop("_status", 200)
            return self._send_json(result, status, request_id=request_id)

        if path in ("/api/roster", "/roster"):
            return self._send_json(_get_roster(self.headers), request_id=request_id)

        # 2026-09-11 老板令：设备↔店员绑定登记 + 在线感知（哪台设备谁在用、谁在线，账单数据好管理）
        if path in ("/api/devices", "/devices"):
            from gateway.device_registry import list_devices
            return self._send_json(list_devices(), request_id=request_id)

        # 2026-09-11：崩溃报告查看（远程排障；严格模式下需带 token）
        if path in ("/api/crash/reports", "/crash/reports"):
            try:
                limit = int((qs.get("limit") or ["50"])[0])
            except (TypeError, ValueError):
                limit = 50
            from gateway.crash_store import recent_reports
            return self._send_json(recent_reports(limit=limit), request_id=request_id)

        # 2026-09-11（H2）：APP 运行日志检索——服务器实时收回店内手机日志定位问题
        # （level/tag/device_id/q 过滤；严格模式下需带 token，与 crash/reports 同口径）
        if path in ("/api/logs/recent", "/logs/recent"):
            try:
                limit = int((qs.get("limit") or ["200"])[0])
            except (TypeError, ValueError):
                limit = 200
            from gateway.log_store import recent_logs
            return self._send_json(
                recent_logs(
                    limit=limit,
                    level=(qs.get("level") or [""])[0],
                    tag=(qs.get("tag") or [""])[0],
                    device_id=(qs.get("device_id") or [""])[0],
                    q=(qs.get("q") or [""])[0],
                ),
                request_id=request_id,
            )

        # S17（2026-09-11 老板令）：上游同步进度查询——点击同步＝真实登录管家婆拉最新，
        # APP 轮询本端点看 登录/拉取/写入/完成 四态与统计（进程内单飞作业）。
        if path in ("/api/sync/upstream/status", "/sync/upstream/status"):
            from gateway import upstream_sync
            return self._send_json(
                ok_response(data=upstream_sync.get_status(
                    upstream_sync.status_path_for(upstream_sync.default_db_path()))),
                request_id=request_id)

        if path in ("/bills/sync", "/api/bills/sync"):
            user = _get_current_user(self.headers)
            if not user:
                return self._send_json(err_response("未认证", "UNAUTHORIZED", "请先登录"), 401, request_id=request_id)
            is_admin = user.get("role") == "admin"
            # M2（2026-09-11）：读库失败必须报错，不能返回 {} 冒充「服务器没单」——
            # 否则重装后的「恢复」按钮会显示「已恢复 0 条」，店员以为账真没了。
            try:
                all_bills = load_bills(raise_on_error=True)
            except BillsLoadError:
                logging.error("bills/sync GET: 账单库读取失败，返回 503 而非空表")
                return self._send_json(
                    err_response("账单库读取失败", "BILLS_UNAVAILABLE",
                                 "服务器账单库暂时不可用，请稍后重试；本地账单未受影响"),
                    503, request_id=request_id)
            if is_admin:
                # 裸 map 数据体：值全是账单对象，禁注 _request_id（tag_request_id=False）
                return self._send_json(all_bills, request_id=request_id, tag_request_id=False)
            user_name = user.get("display_name", "")
            filtered = {}
            for bid, bill in all_bills.items():
                creator = bill.get("created_by_display_name", "") or bill.get("staff", "")
                if not creator:
                    filtered[bid] = bill
                elif creator == user_name:
                    filtered[bid] = bill
            return self._send_json(filtered, request_id=request_id, tag_request_id=False)

        if path == "/api/stats/revenue/today":
            result = _get_today_revenue(self.headers, qs)
            return self._send_json(result, request_id=request_id)

        if path == "/api/logs/stats":
            from utils.logging import get_error_stats
            return self._send_json(ok_response(data=get_error_stats()))

        if path == "/api/phone/poll":
            cmd = phone_poll()
            return self._send_json(cmd, request_id=request_id)

        if path.startswith("/api/phone/result/"):
            try:
                cid = int(path.split("/")[-1])
            except (ValueError, IndexError):
                return self._send_json(err_response("invalid id", "BAD_REQUEST", "无效的指令 ID"), 400, request_id=request_id)
            return self._send_json(phone_get_result(cid), request_id=request_id)

        if path in ROUTES_GET:
            return self._send_json(ROUTES_GET[path](qs, None), request_id=request_id)

        if path in ROUTES_GET_WITH_QS:
            raw_qs = parsed.query
            return self._send_json(ROUTES_GET_WITH_QS[path](raw_qs), request_id=request_id)

        if path.startswith("/inventory/cell/") or path.startswith("/api/inventory/cell/"):
            code = path.split("/")[-1]
            result = proxy(f"/cell/{code}", service_url=INVENTORY_SERVICE_URL, request_id=request_id)
            return self._send_json(result)

        if path in ROUTES_PROXY_GET:
            target_path, service_url = ROUTES_PROXY_GET[path]
            if qs:
                target_path += "?" + "&".join(f"{k}={urllib.request.quote(v[0])}" for k, v in qs.items())
            result = proxy(target_path, service_url=service_url, request_id=request_id)
            return self._send_json(result)

        self._send_json({"success": False, "data": [], "hint": f"未知路径: {path}"}, 404, request_id=request_id)

    def do_POST(self):
        request_id = uuid.uuid4().hex[:12]
        set_request_id(request_id)
        body = self._read_body_with_timeout(15)
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        # 2026-09-11 bug猎杀（越权访问）：/api/admin/bills/status、/api/admin/marks/resolve
        # 原实现零授权检查——严格模式下未认证可改账单状态（干扰业务数据）。
        # 私服模式回退本地 admin，店内行为不变。
        if path.startswith("/api/admin") and not _require_admin(self.headers):
            return self._send_json(err_response("需要管理员权限", "FORBIDDEN", "请使用管理员账号登录后台"), 403, request_id=request_id)

        # 2026-09-11 老板令：APP 崩溃报告收集——新设备若再闪退，远程就能收回堆栈定位原因。
        # 免鉴权白名单（见 auth._verify_auth）：需要崩溃报告的时刻往往正是登录态异常的时刻。
        if path in ("/api/crash/report", "/crash/report"):
            try:
                data = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                return self._send_json(err_response("JSON 解析失败", "BAD_REQUEST", "崩溃报告格式错误"), 400, request_id=request_id)
            from gateway.crash_store import save_report
            return self._send_json(save_report(data if isinstance(data, dict) else {}, request_id=request_id), request_id=request_id)

        # 2026-09-11 老板令（H2）：APP 运行日志批量上报——日常异常（网络失败/同步失败）不再无处可查。
        # 免鉴权白名单（见 auth._verify_auth）：出问题的时刻常是登录态异常的时刻；单批/单条/轮转护栏兜滥用。
        if path in ("/api/logs/ingest", "/logs/ingest"):
            try:
                data = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                return self._send_json(err_response("JSON 解析失败", "BAD_REQUEST", "日志格式错误"), 400, request_id=request_id)
            if not isinstance(data, dict):
                return self._send_json(err_response("请求体必须是 JSON 对象", "BAD_REQUEST", "请求体格式错误"), 400, request_id=request_id)
            from gateway.log_store import ingest_logs
            envelope = {k: v for k, v in data.items() if k != "records"}
            return self._send_json(
                ingest_logs(data.get("records"), request_id=request_id, envelope=envelope),
                request_id=request_id,
            )

        # 设备报到：APP 启动/绑店员/15 分钟周期心跳，服务器据此知道谁在线、哪台设备绑谁
        if path in ("/api/device/checkin", "/device/checkin"):
            try:
                data = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                return self._send_json(err_response("JSON 解析失败", "BAD_REQUEST", "请求体不是合法 JSON"), 400, request_id=request_id)
            if not isinstance(data, dict):
                return self._send_json(err_response("请求体必须是 JSON 对象", "BAD_REQUEST", "请求体格式错误"), 400, request_id=request_id)
            from gateway.device_registry import checkin_device
            result = checkin_device(
                data.get("device_id", ""),
                staff_id=data.get("staff_id", ""),
                staff_name=data.get("staff_name", ""),
                model=data.get("model", ""),
                app_version=data.get("app_version", ""),
            )
            status = 400 if (not result.get("success") and result.get("code") == "BAD_REQUEST") else 200
            return self._send_json(result, status, request_id=request_id)

        # I22 货架多终端同步：push 本地脏行 + 按水位拉增量（老板令「所有终端都能同步到」）。
        # 鉴权与 /api/sync 同口径（严格模式下带 token）；单批行数护栏在 store 内兜底。
        if path in ("/api/shelves/sync", "/shelves/sync"):
            try:
                data = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                return self._send_json(err_response("JSON 解析失败", "BAD_REQUEST", "货架同步数据格式错误"), 400, request_id=request_id)
            if not isinstance(data, dict):
                return self._send_json(err_response("请求体必须是 JSON 对象", "BAD_REQUEST", "请求体格式错误"), 400, request_id=request_id)
            from gateway.shelf_store import sync as shelf_sync
            result = shelf_sync(data, request_id=request_id)
            status = 400 if (not result.get("success") and result.get("code") == "BAD_REQUEST") else 200
            return self._send_json(result, status, request_id=request_id)

        if path == "/api/auth/login":
            data, err = validate_request(body, {
                "username": {"required": True, "message": "缺少用户名或密码"},
                "password": {"required": True, "message": "缺少用户名或密码"},
            })
            if err:
                return self._send_json(err, 400, request_id=request_id)
            username = data["username"]
            password = data["password"]
            # 2026-09-09 审计：登录限速（按 来源IP+用户名，窗口内失败超限即 429）
            _rl_key = login_key(self.client_address[0], str(username))
            _rl_ok, _rl_retry = login_allowed(_rl_key)
            if not _rl_ok:
                return self._send_json(err_response(
                    "登录尝试过于频繁", "RATE_LIMITED",
                    f"登录尝试过于频繁，请 {_rl_retry} 秒后重试"), 429, request_id=request_id)
            try:
                conn = sqlite3.connect(BILLS_DB_PATH)
                conn.row_factory = sqlite3.Row
                row = conn.execute("SELECT * FROM employees WHERE username = ? OR display_name = ?", (username, username)).fetchone()
                conn.close()
                if not row:
                    login_record_failure(_rl_key)
                    return self._send_json(err_response("用户名或密码错误", "AUTH_FAILED", "用户名或密码错误"), 401, request_id=request_id)
                emp = dict(row)
                if not _verify_password(password, emp["password_hash"]):
                    login_record_failure(_rl_key)
                    return self._send_json(err_response("用户名或密码错误", "AUTH_FAILED", "用户名或密码错误"), 401, request_id=request_id)
                if emp["status"] != "active":
                    return self._send_json(err_response("账户已被禁用", "DISABLED", "该账户已被禁用，请联系管理员"), 403, request_id=request_id)
                token = _jwt_encode({
                    "user_id": emp["id"],
                    "username": emp["username"],
                    "display_name": emp["display_name"],
                    "role": emp["role"],
                })
                login_reset(_rl_key)
                return self._send_json({
                    "success": True,
                    "data": {
                        "token": token,
                        "user": {
                            "id": emp["id"],
                            "username": emp["username"],
                            "display_name": emp["display_name"],
                            "role": emp["role"],
                        }
                    }
                }, request_id=request_id)
            except Exception as e:
                return self._send_json(err_response(str(e)), 500, request_id=request_id)

        if path == "/api/employees":
            data, err = validate_request(body, {
                "username": {"required": True, "message": "请填写用户名"},
                "password": {"required": True, "message": "请填写密码"},
                "display_name": {"required": True, "message": "请填写显示名称"},
                "role": {"default": "cashier"},
                "note": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=request_id)
            result = _create_employee(self.headers, data)
            status = result.pop("_status", 200)
            return self._send_json(result, status, request_id=request_id)

        if path.startswith("/api/employees/") and path.endswith("/delete"):
            emp_id = path.split("/")[-2]
            result = _disable_employee(self.headers, emp_id)
            status = result.pop("_status", 200)
            return self._send_json(result, status, request_id=request_id)

        if path.startswith("/api/employees/"):
            emp_id = path.split("/")[-1]
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            result = _update_employee(self.headers, emp_id, data)
            status = result.pop("_status", 200)
            return self._send_json(result, status, request_id=request_id)

        if path in ("/bills/sync", "/api/bills/sync"):
            current_user = _get_current_user(self.headers)
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            try:
                bills = data if isinstance(data, list) else data.get("bills", [])
                if current_user:
                    for b in bills:
                        if "created_by_user_id" not in b:
                            b["created_by_user_id"] = current_user.get("user_id", "")
                            b["created_by_display_name"] = current_user.get("display_name", "")
                            b["staff"] = current_user.get("display_name", "")
                        # M3（2026-09-11）：owner_id 是归属的一等字段，改名不会断链
                        # （display_name 字符串匹配在改名/同名时会失效）。
                        if not b.get("owner_id"):
                            b["owner_id"] = b.get("created_by_user_id") or current_user.get("user_id", "")
                result = merge_bills(bills)
                return self._send_json({"success": True, **result}, request_id=request_id)
            except Exception as e:
                return self._send_json({"success": False, "error": str(e)}, 400, request_id=request_id)

        # S17（2026-09-11 老板令）：点击同步 → **真实上游同步**（登录管家婆拉当场最新 →
        # 比对 → 仅变更落库）。单飞后台作业，本接口立即返回；进度走 /api/sync/upstream/status。
        # 鉴权口径同 push_update：严格模式需登录，私服模式（无 GATEWAY_AUTH_TOKEN）直接放行。
        if path in ("/api/sync/upstream", "/sync/upstream"):
            user = _get_current_user(self.headers)
            if AUTH_TOKEN and not user:
                return self._send_json(err_response("未认证", "UNAUTHORIZED", "请先登录"), 401, request_id=request_id)
            from gateway import upstream_sync
            return self._send_json(ok_response(data=upstream_sync.start_sync()), request_id=request_id)

        if path in ("/api/marks/sync", "/api/marks/batch"):
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            items = data if isinstance(data, list) else data.get("marks", data.get("items", []))
            res = sync_mark_items(items)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path == "/api/admin/app/push_update":
            user = _get_current_user(self.headers)
            if AUTH_TOKEN and not user:
                return self._send_json(err_response("未认证", "UNAUTHORIZED", "请先登录"), 401, request_id=request_id)
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            data_dir = os.environ.get("DATA_DIR") or os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "data")
            import tempfile
            _repo_root = os.path.realpath(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
            _vd = os.path.realpath(os.path.join(os.path.realpath(data_dir), "app_version.json"))
            _roots = [_repo_root, os.path.realpath(tempfile.gettempdir())]
            if not any(os.path.commonpath([_vd, _r]) == _r
                       and ".." not in os.path.relpath(_vd, _r).split(os.sep) for _r in _roots):
                return self._send_json(err_response("BAD_PATH", "BAD_REQUEST", "illegal path"), 400, request_id=request_id)
            version_file = _vd
            os.makedirs(os.path.dirname(version_file), exist_ok=True)
            current = get_latest_apk_info()
            if "version_code" in data or "versionCode" in data:
                try:
                    current["version_code"] = int(data.get("version_code") or data.get("versionCode"))
                except (TypeError, ValueError):
                    current["version_code"] = 0  # 伪造/垃圾值归零，不 500（2026-09-11 bug猎杀）
                current["versionCode"] = current["version_code"]
            if "version_name" in data or "versionName" in data:
                current["version_name"] = str(data.get("version_name") or data.get("versionName"))
                current["versionName"] = current["version_name"]
            if "update_log" in data or "updateLog" in data:
                current["update_log"] = str(data.get("update_log") or data.get("updateLog"))
                current["updateLog"] = current["update_log"]
            if "title" in data:
                current["title"] = str(data["title"])
            if "force_update" in data or "forceUpdate" in data:
                current["force_update"] = bool(data.get("force_update") or data.get("forceUpdate"))
                current["forceUpdate"] = current["force_update"]
            current["release_date"] = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            current["releaseDate"] = current["release_date"]
            with open(version_file, "w", encoding="utf-8") as f:
                json.dump(current, f, ensure_ascii=False, indent=2)
            return self._send_json(ok_response(data=current), request_id=request_id)

        if path == "/api/admin/bills/status":
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            bill_id = data.get("bill_id", "")
            new_status = data.get("status", "")
            staff = data.get("staff", "")
            res = update_bill_status(bill_id, new_status, staff=staff)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path == "/api/admin/marks/resolve":
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            try:
                mark_id = int(data.get("id", 0))
            except (ValueError, TypeError):
                mark_id = 0
            note = data.get("note", "")
            res = resolve_data_mark(mark_id, note=note)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path == "/api/admin/gov/synonym":
            # I30/A2：治理页（admin/pages.py）是裸 HTML form，浏览器按表单编码提交；
            # APP/CLI 走 JSON。按 Content-Type 分流，两条通道共用同一校验与落库。
            ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            if ctype == "application/x-www-form-urlencoded":
                form = parse_qs(body.decode("utf-8", "replace"), keep_blank_values=True)
                data = {
                    "canonical": (form.get("canonical") or [""])[0],
                    # 页面标签写「逗号分隔」，店员实际会输全角逗号——归一后再切分
                    "synonyms": [s for chunk in form.get("synonyms") or []
                                 for s in chunk.replace("，", ",").split(",")],
                    "category": (form.get("category") or [""])[0],
                }
            else:
                data, err = parse_json_body(body)
                if err:
                    return self._send_json(err, 400, request_id=request_id)
            from admin import governance as _gov
            canonical = (data.get("canonical") or "").strip()
            synonyms = [s.strip() for s in (data.get("synonyms") or []) if str(s).strip()]
            if not canonical or not synonyms:
                return self._send_json(err_response("canonical/synonyms 必填", code="BAD_REQUEST"), 400, request_id=request_id)
            ok = _gov.add_synonym(canonical, synonyms, (data.get("category") or "").strip())
            return self._send_json(ok_response(data={"canonical": canonical, "added": ok}), request_id=request_id)

        if path == "/api/admin/ai/chat":
            data, err = parse_json_body(body)
            if err:
                return self._send_json(err, 400, request_id=request_id)
            query = data.get("query", "")
            res = admin_ai_chat(query)
            return self._send_json(ok_response(data=res), request_id=request_id)

        if path in ("/print/label", "/api/print/label"):
            data, err = validate_request(body, {
                "ip": {"required": True, "message": "请填写打印机IP"},
                "port": {"type": int, "default": PRINTER_DEFAULT_PORT},
                "items": {"type": list, "default": []},
                "name": {"default": ""},
                "price": {"default": "0.00"},
                "sku": {"default": ""},
                "spec": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=request_id)
            try:
                ip = data["ip"]
                port = data["port"]
                items = data["items"] or [data]
                results = []
                for item in items:
                    name = item.get("name", "")
                    price = item.get("price", "0.00")
                    sku = item.get("sku", "")
                    spec = item.get("spec", "")
                    tspl = tspl_price_tag(name, str(price), str(sku), str(spec))
                    r = send_tspl(ip, port, tspl)
                    results.append(r)
                return self._send_json({"success": True, "printed": len(results), "results": results}, request_id=request_id)
            except Exception as e:
                return self._send_json({"success": False, "error": str(e)}, 400, request_id=request_id)

        if path in ("/sales/batch", "/api/sales/batch"):
            return self._send_json({"success": True, "synced": 0, "message": "本地模式，无需同步"}, request_id=request_id)

        if path in ("/auth/exchange", "/api/auth/exchange"):
            return self._send_json({"success": True, "token": "local-mode"}, request_id=request_id)

        if path in ("/locations/batch", "/api/locations/batch"):
            return self._send_json({"success": True, "synced": 0}, request_id=request_id)

        if path in ("/inventory/adjust", "/api/inventory/adjust"):
            _, err = validate_request(body, {})
            if err:
                return self._send_json(err, 400, request_id=request_id)
            result = proxy("/adjust", body, service_url=INVENTORY_SERVICE_URL, request_id=request_id)
            return self._send_json(result, request_id=request_id)

        if path == "/api/logs/report":
            data, err = validate_request(body, {
                "errors": {"required": True, "type": list, "message": "缺少 errors 字段"},
                "source": {"default": "unknown"},
                "url": {"default": ""},
            })
            if err:
                return self._send_json(err, 400)
            errors = data["errors"]
            source = data["source"]
            url = data["url"]
            log = get_logger("gateway")
            log.warning(f"[frontend-errors] {source} {url}: {len(errors)} errors")
            for err in errors[:20]:
                log.warning(f"  FE: {err.get('msg','')} @ {err.get('url','')}:{err.get('line','')}")
            record_error("frontend", f"{len(errors)} errors from {source}")
            return self._send_json(ok_response({"logged": len(errors)}))

        if path == "/api/phone/send":
            data, err = validate_request(body, {
                "cmd": {"required": True, "message": "缺少 cmd"},
            })
            if err:
                return self._send_json(err, 400, request_id=request_id)
            cmd = data["cmd"][:_PHONE_CMD_MAX_LEN]
            for ch in _PHONE_CMD_BLOCKED_CHARS:
                if ch in cmd:
                    return self._send_json(err_response("cmd 包含非法字符", "INVALID_INPUT"), 400, request_id=request_id)
            cid = phone_queue_command(cmd)
            return self._send_json({"id": cid, "cmd": cmd}, request_id=request_id)

        if path == "/api/phone/result":
            data, err = validate_request(body, {
                "id": {"type": int, "default": 0},
                "result": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=request_id)
            phone_submit_result(data["id"], data["result"])
            return self._send_json({"ok": True}, request_id=request_id)

        if path == "/api/agent/image":
            # I17：店员商品图片上传（raw 字节直通，桥内做魔数/大小校验后落盘）
            from agent import agent_bridge
            return self._send_json(agent_bridge.save_image(body), request_id=request_id)

        if path.startswith("/api/agent/file"):
            # 2026-09-19：店员普通文件上传（txt/csv/pdf/doc/xls 等，raw 直通；桥内做
            # 扩展名白名单/大小校验后落盘 data/tmp/files/）。原文件名走 ?name=（URL 编码）。
            if not _verify_auth(self.headers, self.path, self.command):
                return self._send_json(err_response("未授权访问", "UNAUTHORIZED", "未授权访问"), 401, request_id=request_id)
            from agent import agent_bridge
            from urllib.parse import urlparse as _up, parse_qs as _pq, unquote as _uq
            qs = _pq(_up(self.path).query)
            filename = (_uq((qs.get("name") or [""])[0]) or "")[:120]
            return self._send_json(agent_bridge.save_file(body, filename), request_id=request_id)

        if path == "/api/agent/stream":
            # L10：流式老王（SSE）。鉴权与普通 API 同闸；逐事件写 data: 行即时 flush。
            if not _verify_auth(self.headers, self.path, self.command):
                return self._send_json(err_response("未授权访问", "UNAUTHORIZED", "未授权访问"), 401, request_id=request_id)
            try:
                payload = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                payload = {}
            if not isinstance(payload, dict):
                payload = {}
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.end_headers()
            from agent import agent_stream
            try:
                for evt in agent_stream.stream_events(payload):
                    self.wfile.write(b"data: " + json.dumps(evt, ensure_ascii=False).encode("utf-8") + b"\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass  # 店员关面板/断网：停止生成即可
            return

        if path in ("/api/pos/settlement-receipt", "/pos/settlement-receipt"):
            # B13：收银端结算回执上报（复用 GATEWAY_AUTH_TOKEN + 64KB 上限 + 字段白名单）
            if not _verify_auth(self.headers, self.path, self.command):
                return self._send_json(err_response("未授权访问", "UNAUTHORIZED", "未授权访问"), 401, request_id=request_id)
            if len(body) > 64 * 1024:
                return self._send_json(err_response("请求体过大", "BODY_TOO_LARGE", "回执体不得超过 64KB"), 413, request_id=request_id)
            try:
                payload = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                return self._send_json(err_response("请求体必须为合法 JSON", "BAD_JSON"), 400, request_id=request_id)
            res = ROUTES_POST[path](payload)
            status_code = 200 if res.get("success") else 400
            return self._send_json(res, status_code, request_id=request_id)

        if path in ROUTES_POST:
            # I16：进程内 agent 路由（run/confirm/pending），先于代理兜底命中
            try:
                payload = json.loads(body.decode("utf-8")) if body else {}
            except (ValueError, UnicodeDecodeError):
                payload = {}
            if not isinstance(payload, dict):
                payload = {}
            return self._send_json(ROUTES_POST[path](payload), request_id=request_id)

        if path in ROUTES_PROXY_POST:
            target_path, service_url, proxy_timeout = ROUTES_PROXY_POST[path]
            result = proxy(target_path, body, service_url, timeout=proxy_timeout, request_id=request_id)
            return self._send_json(result, request_id=request_id)

        self._send_json(
            {"success": False, "data": [], "hint": f"未知路径: {path}"}, 404, request_id=request_id
        )

    def do_DELETE(self):
        request_id = uuid.uuid4().hex[:12]
        set_request_id(request_id)
        body = self._read_body_with_timeout(15)
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = parse_qs(parsed.query)

        if path.startswith("/api/print/printers/") and len(path.split("/")) == 5:
            printer_id = path.split("/")[-1]
            target = f"/api/print/printers/{printer_id}"
            result = proxy(target, body, service_url=PRINT_SERVICE_URL, timeout=10, request_id=request_id, method="DELETE")
            return self._send_json(result, request_id=request_id)

        # 账单物理删除端点：DELETE /api/bills/{id} 或 DELETE /bills/{id}
        parts = path.split("/")
        if (path.startswith("/api/bills/") and len(parts) == 4) or (path.startswith("/bills/") and len(parts) == 3):
            if not _verify_auth(self.headers, self.path, self.command):
                return self._send_json(err_response("未授权访问", "UNAUTHORIZED", "未授权访问"), 401, request_id=request_id)
            bill_id = parts[-1]
            from gateway.bills_sync import delete_bill
            res = delete_bill(bill_id)
            status_code = 200 if res.get("success") else 400
            return self._send_json(res, status_code, request_id=request_id)

        if path in ROUTES_PROXY_DELETE:
            target_path, service_url, proxy_timeout = ROUTES_PROXY_DELETE[path]
            if qs:
                target_path += "?" + "&".join(f"{k}={urllib.request.quote(v[0])}" for k, v in qs.items())
            result = proxy(target_path, body, service_url, timeout=proxy_timeout, request_id=request_id, method="DELETE")
            return self._send_json(result, request_id=request_id)

        self._send_json(
            {"success": False, "data": [], "hint": f"未知路径: {path}"}, 404, request_id=request_id
        )
