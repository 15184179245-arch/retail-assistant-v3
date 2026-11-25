#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APP 运行日志收集（2026-09-11 老板令：新版本增加日志系统，服务器实时收回日志定位问题）。

存储 = JSONL 追加（logs/app-logs.jsonl，.gitignore 已覆盖 logs/），模式与 crash_store 一致：
服务端只做落盘 + 补封存字段，不改写内容。差异点：批量 ingest（APP 一次打包 N 条）、
查询支持 level/tag/device_id/q 过滤（.1 旧卷一并纳入）。超 5MB 轮转 .1（只留一代），
防隧道公网被灌爆磁盘。
"""
import json
import logging
import os
import threading
import time

from gateway.crash_store import parse_lines

_LOCK = threading.Lock()

# 测试/部署可用环境变量覆盖；默认落在仓库 logs/ 下（不入库）
def log_path() -> str:
    return os.environ.get(
        "retaildemo_APP_LOG",
        os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "logs", "app-logs.jsonl")),
    )

ROTATE_BYTES = 5 * 1024 * 1024
MAX_BATCH = 500        # 单批条数上限
MAX_MSG_FIELD = 4000   # 单条 message 上限
MAX_LIMIT = 500        # recent_logs 单次返回上限


def ingest_logs(records, request_id: str = "", envelope=None) -> dict:
    """落盘一批运行日志。records 必须是 list[dict]；envelope 为请求级字段
    （device_id/app_version/model），并入每条记录（记录自身同名字段优先）。
    每条补 ts（缺省服务器落盘时间）/request_id；非 dict 条目跳过。"""
    if not isinstance(records, list):
        return {"success": False, "error": "records 必须是 JSON 数组", "code": "BAD_REQUEST"}
    env = envelope if isinstance(envelope, dict) else {}
    ts_now = time.strftime("%Y-%m-%d %H:%M:%S")
    cleaned = []
    for rec in records[:MAX_BATCH]:
        if not isinstance(rec, dict):
            continue
        entry = dict(env)
        entry.update(rec)
        msg = entry.get("message")
        if isinstance(msg, str) and len(msg) > MAX_MSG_FIELD:
            entry["message"] = msg[:MAX_MSG_FIELD] + "...[truncated]"
        entry.setdefault("ts", ts_now)
        if request_id:
            entry["request_id"] = request_id
        cleaned.append(entry)
    path = log_path()
    try:
        with _LOCK:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if os.path.exists(path) and os.path.getsize(path) > ROTATE_BYTES:
                backup = path + ".1"
                try:
                    if os.path.exists(backup):
                        os.remove(backup)
                    os.replace(path, backup)
                except OSError as e:
                    logging.warning(f"app log rotate failed: {e}")
            with open(path, "a", encoding="utf-8") as f:
                for entry in cleaned:
                    f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError as e:
        logging.error(f"app log ingest failed: {e}")
        return {"success": False, "error": str(e), "code": "IO_ERROR"}
    return {"success": True, "data": {"saved": len(cleaned)}}


def _read_all_records(path: str) -> list:
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return parse_lines(f.readlines())


def recent_logs(limit: int = 200, level: str = "", tag: str = "", device_id: str = "", q: str = "") -> dict:
    """倒序返回最近 N 条（远程排障用，最新在前），支持 level/tag/device_id 精确过滤与
    q 子串（message 内）过滤；.1 旧卷一并纳入。limit 收口防一次拖爆。"""
    path = log_path()
    try:
        with _LOCK:
            records = _read_all_records(path + ".1") + _read_all_records(path)
    except OSError as e:
        logging.error(f"app log read failed: {e}")
        return {"success": False, "error": str(e)}
    lvl = (level or "").strip().upper()
    tag_f = (tag or "").strip()
    dev_f = (device_id or "").strip()
    q_f = (q or "").strip()
    if lvl:
        records = [r for r in records if str(r.get("level", "")).upper() == lvl]
    if tag_f:
        records = [r for r in records if str(r.get("tag", "")) == tag_f]
    if dev_f:
        records = [r for r in records if str(r.get("device_id", "")) == dev_f]
    if q_f:
        records = [r for r in records if q_f in str(r.get("message", ""))]
    return {"success": True, "data": list(reversed(records))[: max(1, min(limit, MAX_LIMIT))]}
