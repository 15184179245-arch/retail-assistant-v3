"""统一日志模块：双输出（控制台+文件）、日志轮转、错误统计、request_id 注入"""
import logging
import os
import sys
import threading
from logging.handlers import RotatingFileHandler

__all__ = ["setup_logging", "get_error_stats", "record_error", "get_logger",
           "StatsHandler", "set_request_id", "RequestIdFilter"]

_request_id_local = threading.local()
_stats_lock = threading.Lock()
_error_stats: dict[str, dict] = {}
_RECENT_MAX = 100


def _resolve_log_dir() -> str:
    candidates = [
        os.environ.get("retaildemo_LOG_DIR", ""),
        "/var/log/retaildemo/",
    ]
    for d in candidates:
        if d and os.access(d, os.W_OK):
            return d
    base = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    fallback = os.path.join(base, "logs")
    try:
        os.makedirs(fallback, exist_ok=True)
        return fallback
    except Exception:
        return fallback


def set_request_id(rid: str):
    _request_id_local.rid = rid


def get_request_id() -> str:
    return getattr(_request_id_local, "rid", "")


class RequestIdFilter(logging.Filter):
    def filter(self, record):
        record.request_id = get_request_id()
        return True


def setup_logging(service_name: str, level: str = "INFO"):
    root = logging.getLogger()
    root.setLevel(getattr(logging, level.upper(), logging.INFO))
    root.handlers.clear()

    fmt = logging.Formatter(
        f"%(asctime)s [{service_name}] %(levelname)-8s [%(request_id)s] %(message)s"
    )

    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    sh.addFilter(RequestIdFilter())
    root.addHandler(sh)

    log_dir = _resolve_log_dir()
    try:
        os.makedirs(log_dir, exist_ok=True)
        log_path = os.path.join(log_dir, f"{service_name}.log")
        fh = RotatingFileHandler(log_path, maxBytes=10*1024*1024, backupCount=5, encoding="utf-8")
        fh.setFormatter(fmt)
        fh.addFilter(RequestIdFilter())
        root.addHandler(fh)
    except Exception:
        pass

    stats_handler = StatsHandler(service_name)
    stats_handler.addFilter(RequestIdFilter())
    root.addHandler(stats_handler)

    root.addFilter(RequestIdFilter())

    return root


def get_error_stats() -> dict:
    with _stats_lock:
        result = {}
        for svc, data in _error_stats.items():
            result[svc] = {
                "error_count": data["error_count"],
                "warning_count": data["warning_count"],
                "recent_errors": list(data["recent_errors"]),
            }
        return result


def record_error(service: str, msg: str, exc_info=None, level: str = "ERROR"):
    with _stats_lock:
        if service not in _error_stats:
            _error_stats[service] = {
                "error_count": 0,
                "warning_count": 0,
                "recent_errors": [],
            }
        stats = _error_stats[service]
        if level.upper() == "WARNING":
            stats["warning_count"] += 1
        else:
            stats["error_count"] += 1
        if isinstance(exc_info, logging.LogRecord):
            ei = exc_info.exc_info
            et = exc_info.exc_text
            if ei and isinstance(ei, tuple) and len(ei) == 3:
                import traceback
                msg = f"{msg}\n{''.join(traceback.format_exception(*ei))}"
            elif et:
                msg = f"{msg}\n{et}"
        elif exc_info and isinstance(exc_info, tuple) and len(exc_info) == 3:
            import traceback
            msg = f"{msg}\n{''.join(traceback.format_exception(*exc_info))}"
        from datetime import datetime
        stats["recent_errors"].append({"time": datetime.now().strftime("%Y-%m-%d %H:%M:%S,%f")[:23],
                                       "msg": msg})
        if len(stats["recent_errors"]) > _RECENT_MAX:
            stats["recent_errors"] = stats["recent_errors"][-_RECENT_MAX:]


class StatsHandler(logging.Handler):
    def __init__(self, service_name):
        super().__init__(level=logging.WARNING)
        self.service_name = service_name

    def emit(self, record):
        if record.levelno >= logging.ERROR:
            record_error(self.service_name, record.getMessage(), exc_info=record, level="ERROR")
        elif record.levelno >= logging.WARNING:
            record_error(self.service_name, record.getMessage(), exc_info=record, level="WARNING")


def get_logger(name=None):
    return logging.getLogger(name)
