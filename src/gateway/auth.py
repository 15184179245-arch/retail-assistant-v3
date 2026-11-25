#!/usr/bin/env python3
"""Gateway authentication: JWT, token auth, employee management."""
import json, os, hmac, time, secrets, base64, hashlib, sqlite3, uuid, logging, threading

from .router import BILLS_DB_PATH

AUTH_TOKEN = os.environ.get("GATEWAY_AUTH_TOKEN", "")
AUTH_HEADER = "X-Auth-Token"

JWT_SECRET = os.environ.get("JWT_SECRET", "")
if not JWT_SECRET:
    # 2026-09-09 审计 P2：随机值导致重启后全部登录态失效（多进程/负载均衡必然不一致）
    logging.warning(
        "JWT_SECRET 未设置：本次进程使用随机值（重启即全体登出）。"
        "生产部署请在 .env / start-backend.ps1 设置固定 JWT_SECRET"
    )
    JWT_SECRET = secrets.token_hex(32)


# ---- 登录限速（2026-09-09 审计 P1：/api/auth/login 原本无任何速率限制）----
_LOGIN_RL_LOCK = threading.Lock()
_LOGIN_FAILS: dict = {}
_LOGIN_WINDOW_SEC = 300.0
_LOGIN_MAX_FAILS = int(os.environ.get("LOGIN_MAX_FAILS", "5"))


def login_key(client_ip: str, username: str) -> str:
    # frp 隧道场景来源恒为 127.0.0.1，并入用户名维度避免全站共用一个桶
    return f"{client_ip}:{username}"


def login_allowed(key: str) -> "tuple[bool, int]":
    """滑动窗口内失败次数达到上限则拒绝；返回 (是否放行, 建议重试秒数)。"""
    now = time.time()
    with _LOGIN_RL_LOCK:
        stamps = [t for t in _LOGIN_FAILS.get(key, []) if now - t < _LOGIN_WINDOW_SEC]
        _LOGIN_FAILS[key] = stamps
        if len(stamps) < _LOGIN_MAX_FAILS:
            return True, 0
        retry_after = int(_LOGIN_WINDOW_SEC - (now - stamps[0])) + 1
        return False, max(retry_after, 1)


def login_record_failure(key: str) -> None:
    with _LOGIN_RL_LOCK:
        _LOGIN_FAILS.setdefault(key, []).append(time.time())


def login_reset(key: str) -> None:
    with _LOGIN_RL_LOCK:
        _LOGIN_FAILS.pop(key, None)


def _verify_auth(headers, path: str = "", method: str = "GET") -> bool:
    if not AUTH_TOKEN:
        return True
    if method == "OPTIONS":
        return True
    clean_path = path.split("?")[0].rstrip("/")
    # 2026-09-09 全量审计：移除 "/api/admin/*" 无条件放行——严格模式（设置了
    # GATEWAY_AUTH_TOKEN）下管理端点与其他端点一样必须带 X-Auth-Token；
    # 私服模式（未设置）第一行已全放行，行为不变。
    if clean_path in ("", "/health", "/api/health", "/api/auth/login", "/admin", "/dashboard", "/apk", "/retaildemo-v2.apk", "/api/app/version", "/api/app/check_update",
                      "/api/crash/report", "/crash/report",
                      "/api/logs/ingest", "/logs/ingest"):
        # 2026-09-11 老板令：崩溃报告/运行日志上报免鉴权——最需要崩溃现场与日志的时刻
        # 往往正是鉴权异常的时刻；POST-only + 服务端 5MB 轮转/单条截断/单批上限兜住滥用面。
        return True
    token = headers.get(AUTH_HEADER, "")
    return hmac.compare_digest(token, AUTH_TOKEN)


def _hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    h = hashlib.sha256((salt + password).encode("utf-8")).hexdigest()
    return f"{salt}:{h}"


def _verify_password(password: str, stored: str) -> bool:
    if ":" not in stored:
        return False
    salt, h = stored.split(":", 1)
    expected = hashlib.sha256((salt + password).encode("utf-8")).hexdigest()
    return hmac.compare_digest(expected.encode("utf-8"), h.encode("utf-8"))


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    padding = (4 - len(s) % 4) % 4
    if padding:
        s += "=" * padding
    return base64.urlsafe_b64decode(s)


def _jwt_encode(payload: dict, exp_hours: int = 8) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {**payload, "exp": int(time.time()) + exp_hours * 3600, "iat": int(time.time())}
    header_b64 = _b64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = _b64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    sig = hmac.new(JWT_SECRET.encode("utf-8"), f"{header_b64}.{payload_b64}".encode("utf-8"), hashlib.sha256).digest()
    sig_b64 = _b64url_encode(sig)
    return f"{header_b64}.{payload_b64}.{sig_b64}"


def _jwt_decode(token: str) -> dict | None:
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None
        header_b64, payload_b64, sig_b64 = parts
        expected_sig = hmac.new(JWT_SECRET.encode("utf-8"), f"{header_b64}.{payload_b64}".encode("utf-8"), hashlib.sha256).digest()
        actual_sig = _b64url_decode(sig_b64)
        if not hmac.compare_digest(expected_sig, actual_sig):
            return None
        payload = json.loads(_b64url_decode(payload_b64))
        if payload.get("exp", 0) < time.time():
            return None
        return payload
    except Exception:
        return None


def _local_admin() -> dict:
    return {
        "user_id": "local-admin",
        "username": "admin",
        "role": "admin",
        "display_name": "本地管理员",
    }


def _get_current_user(headers) -> dict | None:
    # 私服模式（未设 GATEWAY_AUTH_TOKEN）：无/坏 Bearer 一律回退本地管理员，免登录可用，
    # 行为与历史版本一致。严格模式（已设 token）：身份一律来自有效 JWT，
    # 缺失/无效返回 None，由调用方返回 401（/api/auth/me、/bills/sync、push_update
    # 等调用点均已兼容 None）。
    # 2026-09-09 全量审计：原实现无条件下放 admin——叠加公网隧道等于全网匿名管理员。
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return _local_admin() if not AUTH_TOKEN else None
    payload = _jwt_decode(auth[7:])
    if payload is None:
        return _local_admin() if not AUTH_TOKEN else None
    return {
        "user_id": payload.get("user_id"),
        "username": payload.get("username"),
        "role": payload.get("role"),
        "display_name": payload.get("display_name"),
    }


def _require_admin(headers) -> dict | None:
    user = _get_current_user(headers)
    if user is None or user.get("role") != "admin":
        return None
    return user


def _init_employees_db():
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS employees (
                id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                display_name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'cashier',
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        cursor = conn.execute("SELECT COUNT(*) FROM employees")
        count = cursor.fetchone()[0]
        if count == 0:
            _admin_pw_env = os.environ.get("ADMIN_PASSWORD")
            admin_pw = _admin_pw_env or "admin123"
            if not _admin_pw_env:
                logging.warning(
                    "ADMIN_PASSWORD 未设置：admin 账户将以默认口令创建——仅限本机私服可接受；"
                    "公网/严格模式部署必须设置 ADMIN_PASSWORD 并轮换默认口令"
                )
            hashed = _hash_password(admin_pw)
            conn.execute(
                "INSERT INTO employees (id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, ?)",
                ("EMP-001", "admin", hashed, "管理员", "admin")
            )
            conn.commit()
            logging.info("default admin created")
        conn.close()
        logging.info("employees database initialized")
    except Exception as e:
        logging.error(f"employees init failed: {e}")
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _list_employees(headers):
    user = _require_admin(headers)
    if not user:
        return {"success": False, "error": "需要管理员权限", "_status": 401}
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT id, username, display_name, role, status, created_at, updated_at FROM employees ORDER BY created_at").fetchall()
        conn.close()
        return {"success": True, "data": [dict(r) for r in rows]}
    except Exception as e:
        return {"success": False, "error": str(e), "_status": 500}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _create_employee(headers, body):
    user = _require_admin(headers)
    if not user:
        return {"success": False, "error": "需要管理员权限", "_status": 401}
    username = body.get("username", "")
    password = body.get("password", "")
    display_name = body.get("display_name", "")
    role = body.get("role", "cashier")
    if not username or not password or not display_name:
        return {"success": False, "error": "缺少必填字段", "_status": 400}
    hashed = _hash_password(password)
    emp_id = "EMP-" + uuid.uuid4().hex[:8].upper()
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute(
            "INSERT INTO employees (id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, ?)",
            (emp_id, username, hashed, display_name, role)
        )
        conn.commit()
        conn.close()
        return {"success": True, "data": {"id": emp_id, "username": username, "display_name": display_name, "role": role}}
    except sqlite3.IntegrityError:
        return {"success": False, "error": "用户名已存在", "_status": 409}
    except Exception as e:
        return {"success": False, "error": str(e), "_status": 500}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _update_employee(headers, emp_id, body):
    user = _require_admin(headers)
    if not user:
        return {"success": False, "error": "需要管理员权限", "_status": 401}
    fields = []
    values = []
    for key in ("display_name", "role", "status"):
        if key in body:
            fields.append(f"{key} = ?")
            values.append(body[key])
    if "password" in body and body["password"]:
        fields.append("password_hash = ?")
        values.append(_hash_password(body["password"]))
    if not fields:
        return {"success": False, "error": "没有要更新的字段", "_status": 400}
    fields.append("updated_at = CURRENT_TIMESTAMP")
    values.append(emp_id)
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute(f"UPDATE employees SET {', '.join(fields)} WHERE id = ?", values)
        if conn.total_changes == 0:
            conn.close()
            return {"success": False, "error": "员工不存在", "_status": 404}
        conn.commit()
        conn.close()
        return {"success": True, "message": "更新成功"}
    except Exception as e:
        return {"success": False, "error": str(e), "_status": 500}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _disable_employee(headers, emp_id):
    user = _require_admin(headers)
    if not user:
        return {"success": False, "error": "需要管理员权限", "_status": 401}
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute("UPDATE employees SET status = 'disabled', updated_at = CURRENT_TIMESTAMP WHERE id = ?", (emp_id,))
        if conn.total_changes == 0:
            conn.close()
            return {"success": False, "error": "员工不存在", "_status": 404}
        conn.commit()
        conn.close()
        return {"success": True, "message": "已禁用"}
    except Exception as e:
        return {"success": False, "error": str(e), "_status": 500}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _get_roster(headers) -> dict:
    """M3（2026-09-11）人员名册：在职店员列表，供 APP 首启「这台机器是谁用」下拉。

    「全员管理员身份」拍板（2026-09-11 老板）下，名册**不是权限系统**——
    任何店端设备都可读（店内设备共享入场券），店员的归属靠账单上的 owner_id 记录，
    不做访问隔离。只返回 active，不回传 password_hash 等敏感列。
    """
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT id, display_name, role FROM employees "
            "WHERE status = 'active' ORDER BY created_at"
        ).fetchall()
        conn.close()
        return {"success": True, "data": [dict(r) for r in rows]}
    except Exception as e:
        logging.error(f"roster load failed: {e}")
        return {"success": False, "error": str(e), "user_message": "人员名册暂时不可用"}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _get_today_revenue(headers, qs) -> dict:
    user = _get_current_user(headers)
    is_admin = user and user.get("role") == "admin"
    if not user:
        return {"success": False, "error": "未认证"}
    staff_filter = qs.get("staff", [None])[0] if qs else None
    today = time.strftime("%Y-%m-%d")
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        rows = conn.execute("SELECT data_json FROM bills").fetchall()
        conn.close()
        today_revenue = 0.0
        today_orders = 0
        pending_count = 0
        for row in rows:
            try:
                bill = json.loads(row[0])
            except (json.JSONDecodeError, TypeError):
                continue
            status = bill.get("status", "")
            created = bill.get("createdAt", "") or bill.get("created_at", "")
            if not created.startswith(today):
                continue
            staff_name = bill.get("staff", "") or bill.get("cashier", "") or bill.get("salesperson", "")
            if staff_name and staff_filter and staff_name != staff_filter:
                continue
            if not is_admin:
                current_name = user.get("display_name", "") if user else ""
                creator = bill.get("created_by_display_name", "") or staff_name
                if creator and creator != current_name:
                    continue
            if status == "paid":
                paid = float(bill.get("paidAmount", bill.get("total", 0)))
                today_revenue += paid
                today_orders += 1
            elif status == "pending":
                pending_count += 1
        return {"success": True, "data": {"today_revenue": round(today_revenue, 2), "today_orders": today_orders, "pending_count": pending_count}}
    except Exception as e:
        return {"success": False, "error": str(e)}
    finally:
        # 2026-09-11 bug猎杀：连接生命周期兜底——execute/commit 抛错（重名/锁定等）
        # 原实现永不 close，常驻网关反复触发即漏 fd。close 幂等，正常路径二次关无害。
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass
