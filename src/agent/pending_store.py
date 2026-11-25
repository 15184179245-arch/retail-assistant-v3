# -*- coding: utf-8 -*-
"""src/agent/pending_store.py — 写操作待确认票据库（I16 红线闸门）。

唯一权威在网关进程内：MCP server（Hermes 子进程）经 ops 打回网关出票，
只有 /api/agent/confirm 能执行。MCP 侧无 sx_confirm 工具 → AI 结构上无法自确认。
禁止 import core.ops（防环：ops 的 pending_create 反向依赖本模块经网关 HTTP）。
"""
import threading
import time
import uuid

TTL = 30 * 60  # 票据 30 分钟未确认自动作废

_LOCK = threading.Lock()
_TICKETS: dict = {}


def _purge_expired() -> None:
    now = time.time()
    stale = [k for k, v in _TICKETS.items()
             if v.get("expires", 0) < now or v.get("state") in ("executed", "cancelled")]
    for k in stale:
        _TICKETS.pop(k, None)


def create(op: str, args: dict, session_id: str = "", staff: str = "",
           summary: str = "") -> dict:
    if op not in WRITE_OPS:
        raise ValueError(f"非写操作不允许出票: {op}")
    now = time.time()
    ticket = {
        "action_id": "ACT-" + uuid.uuid4().hex[:8],
        "op": op,
        "args": args or {},
        "session_id": session_id or "",
        "staff": staff or "",
        "summary": summary or f"写操作待确认：{op}",
        "state": "created",
        "created_at": now,
        "expires": now + TTL,
    }
    with _LOCK:
        _purge_expired()
        _TICKETS[ticket["action_id"]] = ticket
    return dict(ticket)


def get(action_id: str):
    with _LOCK:
        _purge_expired()
        t = _TICKETS.get(action_id)
        return dict(t) if t else None


def list_pending(session_id: str = "", created_since: float = 0.0) -> list:
    with _LOCK:
        _purge_expired()
        out = []
        for v in _TICKETS.values():
            if v.get("state") != "created":
                continue
            if session_id and v.get("session_id") not in ("", session_id):
                continue
            if created_since and v.get("created_at", 0) < created_since:
                continue
            out.append({k: v[k] for k in
                        ("action_id", "op", "summary", "args", "created_at")})
        return sorted(out, key=lambda t: t["created_at"])


def mark(action_id: str, state: str) -> bool:
    if state not in ("executed", "cancelled"):
        raise ValueError(f"非法票据状态: {state}")
    with _LOCK:
        t = _TICKETS.get(action_id)
        if not t or t.get("state") != "created":
            return False
        t["state"] = state
        return True


def associate_session(action_ids, session_id: str) -> None:
    """agent_run 收尾：把本轮子进程窗口内出的票据挂到 app 会话。"""
    with _LOCK:
        for a in action_ids or []:
            t = _TICKETS.get(a)
            if t and not t.get("session_id"):
                t["session_id"] = session_id


WRITE_OPS = {"checkout", "print", "todo_create", "knowledge_add"}
