#!/usr/bin/env python3
"""示例零售 - 聊天服务 (port 18603)
AI agent chat + memory CRUD.
Calls search service (:18601) and knowledge service (:18602) via HTTP.
"""
import sqlite3, time, os, sys, threading, logging, json, urllib.request, urllib.error, traceback, signal
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs
from typing import Any, Callable
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, load_env, setup_logging
from utils.validate import validate_request
from utils.logging import set_request_id
from utils.localllm import local_llm_config, chat_completions as _local_chat  # F5 本地小模型通道
import random

_sysrand = random.SystemRandom()  # F6: 抖动随机源升级为系统级安全随机
from enum import Enum

DB_PATH = os.environ.get("WUJIN_DB_PATH", "/data/wujin.db")
CHAT_DB_PATH = os.environ.get("CHAT_DB_PATH", "/data/chat.db")  # Separate DB for memories (wujin.db locked by original MCP)
START_TIME = time.time()

SEARCH_SERVICE_URL = os.environ.get("SEARCH_SERVICE_URL", "http://127.0.0.1:18601")
KNOWLEDGE_SERVICE_URL = os.environ.get("KNOWLEDGE_SERVICE_URL", "http://127.0.0.1:18602")

# --- LLM config from environment (with .env file support) ---
load_env()

LLM_API_URL = os.environ.get("LLM_API_URL", "https://api.siliconflow.cn/v1/chat/completions")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "deepseek-ai/DeepSeek-V4-Flash")
LLM_TIMEOUT = 30.0

# ─── Error Classes (ported from laowang3/old/receptionist/errors.ts) ──────────

class BaseError(Exception):
    """Base class for all custom errors. Provides unified name + code structure."""
    name: str = "BaseError"
    code: str = "UNKNOWN"

    def __init__(self, message: str, cause: Exception | None = None):
        super().__init__(message)
        self.cause = cause


class ValidationError(BaseError):
    """验证错误 — 用户输入验证失败"""
    name = "ValidationError"
    code = "VALIDATION_ERROR"

    def __init__(self, message: str, field: str, cause: Exception | None = None):
        super().__init__(message, cause)
        self.field = field


class NotFoundError(BaseError):
    """未找到错误 — 请求的资源不存在"""
    name = "NotFoundError"
    code = "NOT_FOUND"

    def __init__(self, message: str, resource: str, cause: Exception | None = None):
        super().__init__(message, cause)
        self.resource = resource


class DatabaseError(BaseError):
    """数据库错误 — 数据库操作失败"""
    name = "DatabaseError"
    code = "DATABASE_ERROR"

    def __init__(self, message: str, query: str | None = None, cause: Exception | None = None):
        super().__init__(message, cause)
        self.query = query


class NetworkError(BaseError):
    """网络错误 — 外部服务连接异常"""
    name = "NetworkError"
    code = "NETWORK_ERROR"

    def __init__(self, message: str, service: str, cause: Exception | None = None):
        super().__init__(message, cause)
        self.service = service


class AiTimeoutError(BaseError):
    """超时错误 — 操作超出指定时间限制"""
    name = "TimeoutError"
    code = "TIMEOUT_ERROR"

    def __init__(self, message: str, operation: str, timeout_ms: int, cause: Exception | None = None):
        super().__init__(message, cause)
        self.operation = operation
        self.timeout_ms = timeout_ms


class ConfigurationError(BaseError):
    """配置错误 — 系统配置无效"""
    name = "ConfigurationError"
    code = "CONFIGURATION_ERROR"

    def __init__(self, message: str, config_key: str, cause: Exception | None = None):
        super().__init__(message, cause)
        self.config_key = config_key


def extract_error_message(error: Any) -> str:
    """从 unknown 错误中提取可读的错误信息"""
    if isinstance(error, Exception):
        return str(error)
    if isinstance(error, str):
        return error
    return "未知错误"


# ─── Retry Mechanism (ported from laowang3/old/receptionist/retry.ts) ──────────

class BackoffStrategy(Enum):
    """退避策略"""
    EXPONENTIAL = "exponential"
    FIXED = "fixed"
    LINEAR = "linear"
    RANDOM = "random"


class RetryConfig:
    """重试配置"""

    def __init__(
        self,
        max_attempts: int = 3,
        base_delay_ms: int = 1000,
        max_delay_ms: int = 30000,
        backoff_multiplier: float = 2.0,
        jitter_factor: float = 0.1,
        strategy: BackoffStrategy = BackoffStrategy.EXPONENTIAL,
        retryable_errors: list[str] | None = None,
        is_retryable: Callable[[Exception], bool] | None = None,
        on_retry: Callable[[dict], None] | None = None,
    ):
        self.max_attempts = max(1, min(100, max_attempts))
        self.base_delay_ms = max(0, base_delay_ms)
        self.max_delay_ms = max(0, max_delay_ms)
        self.backoff_multiplier = max(1.0, backoff_multiplier)
        self.jitter_factor = max(0.0, min(1.0, jitter_factor))
        self.strategy = strategy
        self.retryable_errors = retryable_errors
        self.is_retryable = is_retryable
        self.on_retry = on_retry


DEFAULT_RETRY_CONFIG = RetryConfig()


def calculate_delay(config: RetryConfig, attempt: int) -> int:
    """计算退避延迟。返回值已 clamp 至 [0, max_delay_ms] 区间。"""
    raw: float
    if config.strategy == BackoffStrategy.EXPONENTIAL:
        raw = config.base_delay_ms * (config.backoff_multiplier ** (attempt - 1))
    elif config.strategy == BackoffStrategy.FIXED:
        raw = config.base_delay_ms
    elif config.strategy == BackoffStrategy.LINEAR:
        raw = config.base_delay_ms * attempt
    elif config.strategy == BackoffStrategy.RANDOM:
        exponential = config.base_delay_ms * (config.backoff_multiplier ** (attempt - 1))
        raw = exponential * _sysrand.random()
    else:
        raise ConfigurationError(f"未知退避策略: {config.strategy}", "strategy")

    # 应用抖动（非 RANDOM 策略时也加入微量抖动防止惊群）
    if config.strategy != BackoffStrategy.RANDOM and config.jitter_factor > 0:
        jitter = raw * config.jitter_factor * (_sysrand.random() * 2 - 1)
        raw = max(0.0, raw + jitter)

    return min(max(0, round(raw)), config.max_delay_ms)


def _is_retryable(config: RetryConfig, error: Exception) -> bool:
    """判断错误是否可重试。优先使用 is_retryable 自定义函数；否则按 retryable_errors 名称列表匹配。"""
    if config.is_retryable is not None:
        return config.is_retryable(error)
    if config.retryable_errors and len(config.retryable_errors) > 0:
        return type(error).__name__ in config.retryable_errors
    return True


def retry(fn: Callable[[], Any], config: RetryConfig | None = None) -> dict:
    """执行带重试的操作。

    Args:
        fn: 要重试的同步函数
        config: 重试配置（默认 DEFAULT_RETRY_CONFIG）

    Returns:
        dict: {"success": bool, "value": Any, "error": Exception|None,
               "attempts": int, "total_duration_ms": float, "history": list}
    """
    cfg = config or DEFAULT_RETRY_CONFIG
    history: list[dict] = []
    start_ms = time.monotonic() * 1000
    last_error: Exception | None = None

    for attempt in range(1, cfg.max_attempts + 1):
        attempt_start = time.monotonic() * 1000

        try:
            value = fn()
            duration_ms = (time.monotonic() * 1000) - attempt_start
            history.append({
                "attempt": attempt, "timestamp": time.time(),
                "error": None, "delay_ms": 0,
                "duration_ms": round(duration_ms, 2),
            })
            return {
                "success": True, "value": value, "error": None,
                "attempts": attempt,
                "total_duration_ms": round((time.monotonic() * 1000) - start_ms, 2),
                "history": history,
            }
        except Exception as e:
            duration_ms = (time.monotonic() * 1000) - attempt_start
            last_error = e

            if not _is_retryable(cfg, e):
                history.append({
                    "attempt": attempt, "timestamp": time.time(),
                    "error": str(e), "delay_ms": 0,
                    "duration_ms": round(duration_ms, 2),
                })
                return {
                    "success": False, "value": None, "error": e,
                    "attempts": attempt,
                    "total_duration_ms": round((time.monotonic() * 1000) - start_ms, 2),
                    "history": history,
                }

            is_last = attempt >= cfg.max_attempts
            delay_ms = 0 if is_last else calculate_delay(cfg, attempt)

            history.append({
                "attempt": attempt, "timestamp": time.time(),
                "error": str(e), "delay_ms": delay_ms,
                "duration_ms": round(duration_ms, 2),
            })

            if is_last:
                break

            if cfg.on_retry:
                cfg.on_retry({
                    "attempt": attempt, "error": e, "delay_ms": delay_ms,
                    "total_elapsed": round((time.monotonic() * 1000) - start_ms, 2),
                })

            time.sleep(delay_ms / 1000.0)

    return {
        "success": False, "value": None, "error": last_error,
        "attempts": len(history),
        "total_duration_ms": round((time.monotonic() * 1000) - start_ms, 2),
        "history": history,
    }


# ─── Preset Factories ─────────────────────────────────────────────────────────

def exponential_backoff(**overrides) -> RetryConfig:
    """创建指数退避重试配置"""
    kwargs = {"strategy": BackoffStrategy.EXPONENTIAL, **overrides}
    return RetryConfig(**kwargs)


def fixed_interval(**overrides) -> RetryConfig:
    """创建固定间隔重试配置"""
    kwargs = {"strategy": BackoffStrategy.FIXED, "jitter_factor": 0, **overrides}
    return RetryConfig(**kwargs)


def linear_backoff(**overrides) -> RetryConfig:
    """创建线性退避重试配置"""
    kwargs = {"strategy": BackoffStrategy.LINEAR, **overrides}
    return RetryConfig(**kwargs)


def random_jitter(**overrides) -> RetryConfig:
    """创建随机抖动重试配置"""
    kwargs = {"strategy": BackoffStrategy.RANDOM, "jitter_factor": 1.0, **overrides}
    return RetryConfig(**kwargs)


_local = threading.local()
_metrics_lock = threading.Lock()
_request_count = 0
_error_count = 0


def _inc_req():
    global _request_count
    with _metrics_lock:
        _request_count += 1


def _inc_err():
    global _error_count
    with _metrics_lock:
        _error_count += 1


def _ok(data, **extra):
    _inc_req()
    return ok_response(data, **extra)


def _err(msg, code="UNKNOWN", user_message="系统异常，请稍后再试"):
    _inc_err()
    return err_response(msg, code, user_message)


def get_db() -> sqlite3.Connection:
    """Return read-only connection to main wujin.db (shared with other services)."""
    if not hasattr(_local, "conn") or _local.conn is None:
        conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA cache_size=-8000")
        conn.execute("PRAGMA mmap_size=268435456")
        conn.execute("PRAGMA temp_store=MEMORY")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=5000")
        _local.conn = conn
    return _local.conn


def get_chat_db() -> sqlite3.Connection:
    """Return write-capable connection to separate chat.db (memories table)."""
    if not hasattr(_local, "chat_conn") or _local.chat_conn is None:
        conn = sqlite3.connect(CHAT_DB_PATH, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA cache_size=-8000")
        conn.execute("PRAGMA temp_store=MEMORY")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS memories (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                context TEXT DEFAULT '',
                session_id TEXT DEFAULT '',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_memories_key ON memories(key)")
        # Migrate old DBs that lack session_id column
        try:
            conn.execute("ALTER TABLE memories ADD COLUMN session_id TEXT DEFAULT ''")
        except sqlite3.OperationalError:
            pass  # Column already exists
        conn.execute("CREATE INDEX IF NOT EXISTS idx_memories_session ON memories(session_id)")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                products TEXT DEFAULT '[]',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_conv_session ON conversation_messages(session_id)")
        # I7：问答未命中沉淀（knowledge 为空即记一笔，agent 脚本按频次生成草稿；失败不影响主流程）
        conn.execute("""
            CREATE TABLE IF NOT EXISTS unmatched_queries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query TEXT NOT NULL,
                status TEXT DEFAULT 'open',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_unmatched_status ON unmatched_queries(status)")
        conn.commit()
        _local.chat_conn = conn
    return _local.chat_conn


# ─── HTTP Call Helpers ──────────────────────────────────────────────

def _call_search(keyword: str) -> dict:
    """Call search service to find products (with retry)."""
    body = json.dumps({"keyword": keyword, "limit": 8}).encode()
    _u = urlparse(SEARCH_SERVICE_URL)
    if _u.scheme not in ("http", "https") or _u.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("blocked search service url: %r" % (SEARCH_SERVICE_URL,))

    def _do():
        req = urllib.request.Request(
            f"{SEARCH_SERVICE_URL}/search",
            data=body,
            method="POST",
            headers={"Content-Type": "application/json; charset=utf-8"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))

    result = retry(_do, exponential_backoff(max_attempts=2, base_delay_ms=500))
    if result["success"]:
        return result["value"]
    return {"success": False, "data": []}


def _call_knowledge(query: str) -> dict:
    """Call knowledge service to find relevant entries (with retry)."""
    body = json.dumps({"query": query, "limit": 3}).encode()
    _u = urlparse(KNOWLEDGE_SERVICE_URL)
    if _u.scheme not in ("http", "https") or _u.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("blocked knowledge service url: %r" % (KNOWLEDGE_SERVICE_URL,))

    def _do():
        req = urllib.request.Request(
            f"{KNOWLEDGE_SERVICE_URL}/search",
            data=body,
            method="POST",
            headers={"Content-Type": "application/json; charset=utf-8"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))

    result = retry(_do, exponential_backoff(max_attempts=2, base_delay_ms=500))
    if result["success"]:
        return result["value"]
    return {"success": False, "data": []}


def _llm_chat_response(user_msg: str, products: list, knowledge: list, context_parts: list = None) -> str:
    """Generate a natural language response using LLM directly."""
    api_url = os.environ.get("LLM_API_URL", "https://api.siliconflow.cn/v1/chat/completions")
    api_key = os.environ.get("LLM_API_KEY", "")
    _lu = urlparse(api_url)
    if _lu.scheme != "https" or not _lu.hostname or "." not in _lu.hostname:
        raise ValueError("blocked llm url: %r" % (api_url,))
    model = os.environ.get("LLM_MODEL", "deepseek-ai/DeepSeek-V4-Flash")
    _loc = local_llm_config()  # F5: 本地小模型通道（云端不可达时的离线问答兜底；回环白名单）
    if not api_key and _loc is None:
        if products:
            names = [p.get("name", "") for p in products[:3]]
            return f"找到 {len(products)} 个商品，例如：" + "、".join(names)
        return "没找到相关商品，换个词试试吧"

    context_text = "\n\n".join(context_parts) if context_parts else ""

    system_prompt = """你是示例零售店的AI店员助手。回答要简洁自然，1-2句话直接给答案，不要分点编号，不要套话。"""
    user_prompt = f"客户问：{user_msg}\n\n{context_text}\n\n请回答："

    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 500
    }).encode()

    def _do_llm_call():
        req = urllib.request.Request(
            api_url, data=body,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
        )
        resp = urllib.request.urlopen(req, timeout=25)
        msg = json.loads(resp.read())["choices"][0]["message"]
        # 支持推理模型：优先取 content，为空则取 reasoning_content
        text = msg.get("content") or msg.get("reasoning_content") or ""
        return text.strip()

    result = retry(_do_llm_call, exponential_backoff(max_attempts=3, base_delay_ms=1000))
    if result["success"]:
        return result["value"]

    # F5: 云端失败（断网/超时/欠费）→ 本地小模型兜底问答（回环 llama-server）
    if _loc is not None:
        _t = _local_chat(_loc[0], _loc[1], [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ], temperature=0.3, max_tokens=500, timeout=60)
        if _t:
            return _t

    if products:
        names = [p.get("name", "") for p in products[:3]]
        return f"找到 {len(products)} 个商品，例如：" + "、".join(names)
    return "没找到相关商品，换个词试试吧"


# ─── Agent / Chat ────────────────────────────────────────────────────

def _format_product_context(context) -> str:
    """I6 浮窗商品上下文拼装（纯函数，供单测）。空/无名返回空串，调用方跳过注入。"""
    if not isinstance(context, dict):
        return ""
    name = str(context.get("name") or "").strip()
    if not name:
        return ""
    spec = str(context.get("spec") or "").strip()
    sku = str(context.get("sku") or "").strip()
    line = f"当前商品：{name}"
    if spec:
        line += f"（规格 {spec}）"
    if sku:
        line += f" [sku {sku}]"
    return line


def api_agent(messages: list, session_id: str = "", context=None) -> dict:
    """Process a chat message via search + knowledge + LLM (Agent loop)."""
    try:
        if not messages:
            return _err("messages required")
        last_msg = messages[-1]["content"] if messages else ""
        if not last_msg:
            return _err("empty message")

        # 1. Search products
        search_result = _call_search(last_msg)
        # search 内部异常时回包 data 为 null（非缺键），.get 默认值不生效会炸 products[:8]
        products = search_result.get("data") or []

        # 2. Search knowledge base
        knowledge_result = _call_knowledge(last_msg)
        knowledge = knowledge_result.get("data") or []

        # 3. Recall relevant memories
        memories = recall_memory("%", session_id) if last_msg else {"data": []}
        memory_list = memories.get("data", []) if isinstance(memories, dict) else []

        # 4. Format context for LLM
        product_lines = []
        for p in products[:8]:
            name = p.get("name", "")
            price = p.get("sell_price", "")
            spec = p.get("spec", "")
            location = p.get("location", "")
            line = f"- {name}"
            if price: line += f" ¥{price}"
            if spec: line += f" ({spec})"
            if location: line += f" [{location}]"
            product_lines.append(line)

        kb_lines = []
        for k in knowledge[:3]:
            title = k.get("question") or k.get("keyword") or ""
            ans = k.get("answer") or ""
            line = f"- {title}"
            if ans and len(str(ans)) < 200:
                line += f": {ans}"
            kb_lines.append(line)

        context_parts = []
        # I6 浮窗商品上下文置顶（店员正看着这件货问，优先于搜索召回）
        product_context_line = _format_product_context(context)
        if product_context_line:
            context_parts.append(product_context_line)
        if product_lines:
            context_parts.append("搜到的商品：\n" + "\n".join(product_lines))
        if kb_lines:
            context_parts.append("相关知识：\n" + "\n".join(kb_lines))

        # 5. Include relevant memories
        memory_hints = []
        for m in memory_list[:3]:
            val = m.get("value", "") if isinstance(m, dict) else str(m)
            if val: memory_hints.append(val)
        if memory_hints:
            context_parts.append("历史记忆：\n" + "\n".join("- " + h for h in memory_hints))

        # 6. Generate LLM response
        response_text = _llm_chat_response(last_msg, products, knowledge, context_parts)

        # I7：知识未命中即沉淀（knowledge 为空记一笔；写失败吞掉不影响主流程）
        if not knowledge and last_msg and len(last_msg.strip()) >= 2:
            try:
                get_chat_db().execute(
                    "INSERT INTO unmatched_queries(query) VALUES(?)", (last_msg.strip(),)
                )
                get_chat_db().commit()
            except Exception:
                pass

        return {
            "response": response_text,
            "products": products,
            "knowledge": knowledge,
            "session_id": session_id,
        }
    except Exception as e:
        logging.getLogger(__name__).error(f"[agent] Failed: {e}\n{traceback.format_exc()}")
        try:
            products = _call_search(last_msg).get("data", []) if last_msg else []
        except Exception:
            products = []
        if products:
            names = [p.get("name", "") for p in products[:3]]
            fallback = f"找到 {len(products)} 个商品，例如：" + "、".join(names)
        else:
            fallback = "没找到相关商品"
        return {"response": fallback, "products": products, "session_id": session_id}


# ─── Memory CRUD ─────────────────────────────────────────────────────

def store_memory(key: str, value: str, context: str = "", session_id: str = "") -> dict:
    """Remember something across sessions."""
    try:
        db = get_chat_db()
        db.execute("""
            INSERT OR REPLACE INTO memories (key, value, context, session_id, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, (key.strip(), value.strip(), context.strip(), session_id.strip()))
        db.commit()
        return _ok({"key": key, "stored": True})
    except Exception as e:
        return _err(str(e))


def recall_memory(pattern: str, session_id: str = "") -> dict:
    """Recall memories matching a key pattern, optionally filtered by session_id."""
    try:
        db = get_chat_db()
        pattern = pattern.strip()
        sid = session_id.strip()
        rows = db.execute(
            "SELECT * FROM memories WHERE key LIKE ? AND (session_id = ? OR ? = '') ORDER BY updated_at DESC",
            (pattern, sid, sid)
        ).fetchall()
        return _ok([dict(r) for r in rows], total=len(rows))
    except Exception as e:
        return _err(str(e))


def forget_memory(key: str) -> dict:
    """Permanently remove a memory by its exact key."""
    try:
        db = get_chat_db()
        db.execute("DELETE FROM memories WHERE key = ?", (key.strip(),))
        db.commit()
        return _ok({"deleted": True, "key": key})
    except Exception as e:
        return _err(str(e))


def save_message(session_id: str, role: str, content: str, products: list = None) -> dict:
    """Save a conversation message to DB."""
    try:
        db = get_chat_db()
        db.execute(
            "INSERT INTO conversation_messages (session_id, role, content, products) VALUES (?, ?, ?, ?)",
            (session_id.strip(), role.strip(), content.strip(), json.dumps(products or [], ensure_ascii=False))
        )
        db.commit()
        return {"success": True}
    except Exception as e:
        return {"success": False, "error": str(e)}


def load_messages(session_id: str, limit: int = 50) -> dict:
    """Load all messages for a session ordered by creation time."""
    try:
        db = get_chat_db()
        rows = db.execute(
            "SELECT id, role, content, products, created_at FROM conversation_messages WHERE session_id = ? ORDER BY id ASC LIMIT ?",
            (session_id.strip(), limit)
        ).fetchall()
        data = []
        for r in rows:
            d = dict(r)
            try:
                d["products"] = json.loads(d["products"])
            except (json.JSONDecodeError, TypeError):
                d["products"] = []
            data.append(d)
        return {"success": True, "data": data, "total": len(data)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def list_memories(pattern: str = "", session_id: str = "") -> dict:
    """List stored memories, optionally filtered by session_id."""
    try:
        db = get_chat_db()
        sid = session_id.strip()
        if pattern:
            p = f"%{pattern.strip()}%"
            rows = db.execute(
                "SELECT key, value, context, session_id, updated_at FROM memories WHERE (key LIKE ? OR value LIKE ?) AND (session_id = ? OR ? = '') ORDER BY updated_at DESC LIMIT 50",
                (p, p, sid, sid)
            ).fetchall()
        else:
            rows = db.execute(
                "SELECT key, value, context, session_id, updated_at FROM memories WHERE session_id = ? OR ? = '' ORDER BY updated_at DESC LIMIT 50",
                (sid, sid)
            ).fetchall()
        results = []
        for r in rows:
            d = dict(r)
            if d["value"] and len(d["value"]) > 200:
                d["value"] = d["value"][:200]
            results.append(d)
        return _ok(results, total=len(results))
    except Exception as e:
        return _err(str(e))


def health_check() -> dict:
    try:
        db = get_db()
        cnt = db.execute("SELECT COUNT(*) FROM products").fetchone()[0]
        cdb = get_chat_db()
        mem_cnt = cdb.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        up = int(time.time() - START_TIME)
        h, m, s = up // 3600, (up % 3600) // 60, up % 60
        return _ok({
            "status": "healthy",
            "uptime": f"{h}h {m}m {s}s",
            "products": cnt,
            "memories": mem_cnt,
            "requests_total": _request_count,
            "errors_total": _error_count,
        })
    except Exception as e:
        return _err(str(e))


# ─── HTTP Handler ────────────────────────────────────────────────────

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle requests in separate threads for concurrency."""
    allow_reuse_address = True
    daemon_threads = True


class ChatHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, data: dict, status: int = 200, request_id: str = ""):
        if request_id and isinstance(data, dict):
            data["_request_id"] = request_id
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def do_GET(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        path = parsed.path.rstrip("/")

        if path == "/health":
            return self._send_json(health_check(), request_id=self.request_id)

        # Memory GET
        if path == "/memory":
            key = qs.get("key", [""])[0]
            session_id = qs.get("session_id", [""])[0]
            if not key:
                return self._send_json(err_response("缺少 key 参数", "MISSING_FIELD"), 400, request_id=self.request_id)
            result = recall_memory(key, session_id)
            return self._send_json(result, request_id=self.request_id)

        if path == "/memories":
            pattern = qs.get("pattern", [""])[0]
            session_id = qs.get("session_id", [""])[0]
            result = list_memories(pattern, session_id)
            return self._send_json(result, request_id=self.request_id)

        if path == "/messages":
            session_id = qs.get("session_id", [""])[0]
            if not session_id:
                return self._send_json(err_response("缺少 session_id", "MISSING_FIELD"), 400, request_id=self.request_id)
            result = load_messages(session_id)
            return self._send_json(result, request_id=self.request_id)

        self._send_json(err_response(f"未知路径: {path}", "NOT_FOUND"), 404, request_id=self.request_id)

    def do_POST(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length else b"{}"
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path == "/chat":
            data, err = validate_request(body, {
                "messages": {"required": True, "type": list, "message": "请发送消息内容"},
                "session_id": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            messages = data["messages"]
            session_id = data["session_id"]
            # I6 context 不走 validate_request（其 dict 类型会被 str() 化破坏），直接读原始 body
            try:
                raw_body = json.loads(body) if body else {}
            except Exception:
                raw_body = {}
            result = api_agent(messages, session_id, raw_body.get("context"))
            if messages:
                last = messages[-1]
                save_message(session_id, last["role"], last["content"])
                if result.get("response"):
                    save_message(session_id, "assistant", result["response"], result.get("products", []))
            return self._send_json(result, request_id=self.request_id)

        if path == "/memory":
            data, err = validate_request(body, {
                "key": {"required": True, "message": "请填写记忆关键词"},
                "value": {"required": True, "message": "请填写记忆内容"},
                "context": {"default": ""},
                "session_id": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = store_memory(data["key"], data["value"], data["context"], data["session_id"])
            return self._send_json(result, request_id=self.request_id)

        if path in ("/memory/delete", "/forget"):
            data, err = validate_request(body, {
                "key": {"required": True, "message": "请指定要删除的关键词"},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = forget_memory(data["key"])
            return self._send_json(result, request_id=self.request_id)

        if path == "/memories":
            data, err = validate_request(body, {
                "pattern": {"default": ""},
                "session_id": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = list_memories(data["pattern"], data["session_id"])
            return self._send_json(result, request_id=self.request_id)

        if path == "/recall":
            data, err = validate_request(body, {
                "key": {"required": True, "message": "请指定要回忆的关键词"},
                "session_id": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = recall_memory(data["key"], data["session_id"])
            return self._send_json(result, request_id=self.request_id)

        self._send_json(err_response(f"未知路径: {path}", "NOT_FOUND"), 404, request_id=self.request_id)

    def do_OPTIONS(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        self._send_json({}, request_id=self.request_id)


def _run_server(host: str = None, port: int = 18603):
    # 2026-09-09 审计：下游服务默认只绑回环（仅网关本机调用），0.0.0.0 需显式环境变量。
    host = host or os.environ.get("CHAT_HOST", "127.0.0.1")
    setup_logging("chat")
    server = ThreadedHTTPServer((host, port), ChatHandler)
    logging.info(f"Server listening on {host}:{port}")
    def _sigterm(signum, frame):
        logging.info("SIGTERM received, shutting down...")
        server.shutdown()
    signal.signal(signal.SIGTERM, _sigterm)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Shutting down...")
        server.server_close()


if __name__ == "__main__":
    _run_server()
