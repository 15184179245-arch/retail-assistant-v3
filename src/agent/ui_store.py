# -*- coding: utf-8 -*-
"""src/agent/ui_store.py — 老王卡片的待渲染队列（agent 点单 → 网关出帧）。

与 pending_store（写操作票据）同一套范式：MCP 子进程经 HTTP 打回网关进程入队，
只有本轮 SSE 流的收尾能取走它。禁止 import core.ops（取数由网关在 drain 时做）。

红线：这里只登记"渲染哪个只读查询、要哪几列"，不携带任何数据本身，也不携带任何样式；
数据一律在出帧时由 ops 现取，模型不参与生成结构。
"""
import threading
import time
import uuid

TTL = 60  # 秒：回合早结束了就别再补发卡片

_LOCK = threading.Lock()
_SPECS = {}


def create(op, columns=None, sort=None, limit=None, session_id="", staff=""):
    """登记一次点单。op 必须是只读且已登记卡片的 op（由调用方 a2ui.CARD_OPS 把关）。"""
    now = time.time()
    spec = {
        "card_id": "CARD-" + uuid.uuid4().hex[:8],
        "op": op,
        "columns": [c for c in (columns or []) if isinstance(c, str)][:5],
        "sort": sort if isinstance(sort, str) else None,
        "limit": limit if isinstance(limit, int) and 0 < limit <= 5 else None,
        "session_id": session_id or "",
        "staff": staff or "",
        "created_at": now,
        "expires": now + TTL,
    }
    with _LOCK:
        _purge()
        _SPECS[spec["card_id"]] = spec
    return dict(spec)


def _purge():
    now = time.time()
    for k in [k for k, v in _SPECS.items() if v["expires"] < now]:
        _SPECS.pop(k, None)


def take(created_since=0.0, session_id=""):
    """取走并删除本轮入队的点单（一次性，防重复出帧）。"""
    with _LOCK:
        _purge()
        out = []
        for k, v in list(_SPECS.items()):
            if v["created_at"] < created_since:
                continue
            if session_id and v["session_id"] not in ("", session_id):
                continue
            out.append(dict(v))
            _SPECS.pop(k, None)
        return sorted(out, key=lambda s: s["created_at"])


def pending_count():
    with _LOCK:
        _purge()
        return len(_SPECS)


def clear():
    with _LOCK:
        _SPECS.clear()
