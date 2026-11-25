"""统一 API 响应格式和日志配置"""
import logging
import os
import sys
from typing import Any


def api_response(success: bool, data: Any = None, error: str = None,
                 error_code: str = None, user_message: str = None, **extra):
    """统一 API 响应格式

    Args:
        success: 是否成功
        data: 成功时的数据
        error: 技术错误信息
        error_code: 错误码
        user_message: 展示给用户的友好提示
        **extra: 额外的键值对（如 _request_id）

    Returns:
        统一格式的响应字典
    """
    resp: dict[str, Any] = {
        "success": success,
        "code": error_code if error_code else ("OK" if success else "UNKNOWN"),
    }
    if success:
        resp["data"] = data if data is not None else []
    else:
        resp["error"] = error or "系统异常"
        resp["user_message"] = user_message or "系统异常，请稍后再试"
    resp.update(extra)
    return resp


def setup_logging(service_name: str, level: str = "INFO"):
    """统一日志配置（委派给 logging.py）"""
    from utils.logging import setup_logging as _setup
    return _setup(service_name, level)


def get_logger(name=None):
    """获取 logger 实例（委派给 logging.py）"""
    from utils.logging import get_logger
    return get_logger(name)


def load_env(filename: str = ".env") -> None:
    """加载 .env 文件到环境变量（如果存在）"""
    env_path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), filename)
    if os.path.isfile(env_path):
        with open(env_path, encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())


def ok_response(data: Any = None, **extra) -> dict:
    """统一成功响应格式"""
    resp: dict[str, Any] = {"success": True, "data": data if data is not None else [], "code": "OK"}
    resp.update(extra)
    return resp


def err_response(msg: str = "系统异常",
                  code: str = "UNKNOWN",
                  user_message: str = "系统异常，请稍后再试") -> dict:
    """统一错误响应格式"""
    return {
        "success": False,
        "data": None,
        "error": msg,
        "code": code,
        "user_message": user_message,
        "hint": user_message,
    }
