"""请求校验工具函数

标准化 JSON body 解析 + 字段校验 + 类型转换。
消除各服务重复的 json.loads + .get() 模板代码。

用法:
    schema = {
        "name": {"required": True, "type": str, "message": "请填写名称"},
        "qty":  {"required": True, "type": int, "default": 0, "message": "请填写数量"},
        "note": {"default": ""},
    }
    data, err = validate_request(body_bytes, schema)
    if err:
        return self._send_json(err, 400)
    # data = {"name": "xxx", "qty": 5, "note": ""}
"""
import json
from typing import Any


def parse_json_body(body_bytes: bytes) -> tuple[dict | None, dict | None]:
    """安全解析 JSON body，返回 (data_或_None, error_response_或_None)"""
    try:
        data = json.loads(body_bytes) if body_bytes else {}
        if not isinstance(data, dict):
            return None, {"success": False, "data": None, "error": "请求体必须是 JSON 对象", "code": "BAD_REQUEST", "user_message": "请求数据格式错误", "hint": "请求数据格式错误"}
        return data, None
    except json.JSONDecodeError:
        return None, {"success": False, "data": None, "error": "无效的 JSON 格式", "code": "BAD_REQUEST", "user_message": "请求数据格式错误", "hint": "请求数据格式错误"}


def validate_fields(data: dict, schema: dict) -> tuple[dict | None, dict | None]:
    """按 schema 校验字段，返回 (cleaned_或_None, error_response_或_None)

    Schema 格式:
        field_name: {
            "required": bool,   # 是否必填，默认 False
            "type": type,        # 期望类型，默认 str
            "default": Any,      # 缺省值（不设置则与 type 关联：str→""，int→0，list→[]）
            "message": str,      # 校验失败的 user_message
        }
    """
    cleaned = {}
    for field, rules in schema.items():
        required = rules.get("required", False)
        expected_type = rules.get("type", str)
        has_default = "default" in rules
        default = rules.get("default", _type_default(expected_type))
        user_msg = rules.get("message", f"请填写{field}")

        raw = data.get(field)

        if raw is None or (isinstance(raw, str) and raw.strip() == ""):
            if required:
                return None, _missing_field(field, user_msg)
            if has_default:
                cleaned[field] = default
            else:
                cleaned[field] = _type_default(expected_type)
            continue

        try:
            if expected_type is bool:
                cleaned[field] = _coerce_bool(raw)
            elif expected_type is int:
                cleaned[field] = int(raw)
            elif expected_type is float:
                cleaned[field] = float(raw)
            elif expected_type is list:
                cleaned[field] = raw if isinstance(raw, list) else [raw]
            elif expected_type is dict:
                # 2026-09-09 排雷 N2：此前 dict 落进兜底分支被 str(raw) 字符串化，
                # /print/test 拿到字符串再 .get() 直接 AttributeError（测试打印全坏）
                if not isinstance(raw, dict):
                    return None, _type_error(field, dict, user_msg)
                cleaned[field] = raw
            else:
                cleaned[field] = str(raw).strip()
        except (ValueError, TypeError):
            return None, _type_error(field, expected_type, user_msg)

    return cleaned, None


def validate_request(body_bytes: bytes, schema: dict) -> tuple[dict | None, dict | None]:
    """解析 + 校验一步完成，返回 (cleaned_data, error_response)"""
    data, err = parse_json_body(body_bytes)
    if err:
        return None, err
    return validate_fields(data, schema)


def _type_default(t: type) -> Any:
    if t is int:
        return 0
    if t is float:
        return 0.0
    if t is bool:
        return False
    if t is list:
        return []
    if t is dict:
        return {}
    return ""


def _coerce_bool(raw) -> bool:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return raw != 0
    if isinstance(raw, str):
        return raw.lower() in ("true", "1", "yes", "on")
    return bool(raw)


def _missing_field(field: str, user_msg: str) -> dict:
    return {
        "success": False,
        "data": None,
        "error": f"缺少必填字段: {field}",
        "code": "MISSING_FIELD",
        "user_message": user_msg,
        "hint": user_msg,
    }


def _type_error(field: str, expected: type, user_msg: str) -> dict:
    return {
        "success": False,
        "data": None,
        "error": f"字段 {field} 类型错误，期望 {expected.__name__}",
        "code": "TYPE_ERROR",
        "user_message": user_msg,
        "hint": user_msg,
    }
