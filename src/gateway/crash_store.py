#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APP 崩溃报告收集（2026-09-11 老板令：新设备若再闪退，必须能收回原因）。

存储 = JSONL 追加（logs/app-crashes.jsonl，.gitignore 已覆盖 logs/）：
一行一份报告，服务端只做落盘+封存字段，不做任何改写——堆栈原文最值钱。
超 5MB 轮转成 .1（只留一代），防止隧道公网被灌爆磁盘。
"""
import json
import logging
import os
import threading
import time

_LOCK = threading.Lock()

# 测试/部署可用环境变量覆盖；默认落在仓库 logs/ 下（不入库）
def crash_log_path():
    return os.environ.get(
        "retaildemo_CRASH_LOG",
        os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "logs", "app-crashes.jsonl")),
    )

ROTATE_BYTES = 5 * 1024 * 1024
MAX_CRASH_FIELD = 200 * 1024  # 单份堆栈原文上限，防单条灌爆


def save_report(payload: dict, request_id: str = "") -> dict:
    """落盘一份崩溃报告。payload 任意 JSON dict（APP 端定义字段），服务端补 ts/request_id。"""
    if not isinstance(payload, dict):
        return {"success": False, "error": "payload 必须是 JSON 对象", "code": "BAD_REQUEST"}
    crash = payload.get("crash") or payload.get("stack") or ""
    if isinstance(crash, str) and len(crash) > MAX_CRASH_FIELD:
        payload["crash"] = crash[:MAX_CRASH_FIELD] + "\n...[truncated]"
    record = {
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "request_id": request_id,
        **payload,
    }
    path = crash_log_path()
    line = json.dumps(record, ensure_ascii=False)
    with _LOCK:
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if os.path.exists(path) and os.path.getsize(path) > ROTATE_BYTES:
                backup = path + ".1"
                try:
                    if os.path.exists(backup):
                        os.remove(backup)
                    os.replace(path, backup)
                except OSError as e:
                    logging.warning(f"crash log rotate failed: {e}")
            with open(path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError as e:
            logging.error(f"crash report save failed: {e}")
            return {"success": False, "error": str(e), "code": "IO_ERROR"}
    return {"success": True, "data": {"saved": True}}


def parse_lines(lines) -> list:
    """JSONL → dict 列表（纯函数便于单测）：跳过空行/坏行，保序（旧→新）。"""
    out = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(rec, dict):
            out.append(rec)
    return out


def recent_reports(limit: int = 50) -> dict:
    """倒序返回最近 N 份报告（远程排障用，最新在前）。"""
    path = crash_log_path()
    if not os.path.exists(path):
        return {"success": True, "data": []}
    try:
        with _LOCK:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                records = parse_lines(f.readlines())
        return {"success": True, "data": list(reversed(records))[: max(1, min(limit, 200))]}
    except OSError as e:
        logging.error(f"crash report read failed: {e}")
        return {"success": False, "error": str(e)}
