#!/usr/bin/env python3
"""货架多终端同步存储（I22 + I23）。

背景（2026-09-13 老板两条令）：
① 「货架信息所有终端都能同步到包括NFC和扫货架码」——货架数据此前只存在于 APP 本地 Room，
   A 手机建的货架/绑定，B 手机扫同一张 NFC 贴或货位码必然查不到。本模块把货架数据落到服务端。
② 「货架也要编入分区和楼层，像文件夹一样分类整理」——层级从三级（货架→层→格）扩为
   文件夹树（分区 zone 可嵌套，如「一楼/五金区/货架A」）→ 货架 → 层 → 格。

存储选型（重要，勿轻改）：**JSONL 事件日志 + 内存索引**，与 `crash_store` / `log_store` 同范式。
不用 SQL 是刻意的：本模块任何 SQL 语句都会命中本机安全闸的「SQL 出现即拦」规则（项目在案的误报类），
而货架数据量极小（全店几百行），JSONL 足够且更简单可审计——
- 落盘 `logs/shelf-sync.jsonl`：一行一条变更事件（table/entity_id/数据行/server_seq）；
  超 [ROTATE_BYTES] 换卷留一代（同 log_store 写法）。
- 读：进程启动/换卷后重建内存索引 `{table: {entity_id: row}}`，比较与快照都在内存完成。

设计（记录在案，勿轻改）：
- **对象主键 = tagId**：NFC 贴与货位标签里写的就是 `SX:<tagId>`，码本身不含设备信息；
  各端用 tagId 对位即可跨机解析。本地自增 id 只在设备内有效，不进本库。
- **软删除**：deleted=1 上行同步；不物理删除，否则删除会在别的设备「复活」。
- **单调水位 server_seq**：每次改动分配自增序号（事件里持久化）；客户端带 last_seq 增量拉取。
- **行级 LWW（最后写入者胜）**：按 updated_at 比较，旧的跳过、新的覆盖。
- 铁律 12 复核：本模块只同步「位置结构」，不碰库存数量、不改商品数据。

对外入口：[sync]（POST /api/shelves/sync）、[state]（GET /api/shelves/state）。
响应包络遵循项目契约：{"success", "data", "code", "error", "user_message", "hint"}。
"""
import json
import os
import threading
import time

_TABLES = ("zones", "shelves", "levels", "slots", "bindings")

_MAX_ROWS_PER_PUSH = 2000        # 单批护栏：防异常客户端把服务器打爆
ROTATE_BYTES = 2 * 1024 * 1024   # 日志超 2MB 换卷留一代（照 log_store 范式）


def _log_path():
    """日志落点：环境变量优先（部署可指到数据盘），默认仓库 logs/（同 log_store/crash_store）。"""
    env = os.environ.get("SHELF_SYNC_LOG")
    if env:
        return os.path.normpath(env)
    return os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "logs", "shelf-sync.jsonl"))


# 客户端推上来的行允许携带的业务列（服务端只信这些；entity_id/updated_at/deleted 单独取）
_ROW_COLS = {
    "zones": ("parent_id", "name", "note", "sort_order", "created_at"),
    "shelves": ("zone_id", "name", "note", "sort_order", "created_at"),
    "levels": ("shelf_id", "name", "note", "sort_order", "created_at"),
    "slots": ("level_id", "name", "note", "sort_order", "created_at"),
    "bindings": ("level_id", "slot_id", "product_id", "barcode", "name", "misplaced", "note",
                 "created_by", "created_at"),
}
_INT_COLS = {"sort_order", "product_id", "misplaced", "created_at"}
_FK_COLS = {"parent_id", "zone_id", "shelf_id", "level_id", "slot_id"}
_FREE_COLS = {"note", "barcode", "created_by"}

_LOCK = threading.Lock()
_STATE = None  # {"seq": int, "rows": {table: {entity_id: row}}}


def _ok(data=None):
    return {"success": True, "data": data, "code": "OK"}


def _err(msg, code="BAD_REQUEST", user_message="货架同步请求有误", hint=None):
    return {
        "success": False, "data": None, "code": code,
        "error": msg, "user_message": user_message, "hint": hint or msg,
    }


def _clean_str(v, limit=200):
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    return s[:limit]


def _clean_int(v, default=0):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _empty_state():
    return {"seq": 0, "rows": {t: {} for t in _TABLES}}


def _read_log():
    """重建内存索引：逐行读事件日志，后写覆盖先写（含软删行，客户端拉取后自行过滤展示）。"""
    state = _empty_state()
    path = _log_path()
    if not os.path.exists(path):
        return state
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except ValueError:
                continue  # 坏行跳过：日志容错优先（宁少一行不崩服务）
            table = ev.get("table")
            if table not in state["rows"]:
                continue
            eid = ev.get("entity_id")
            if not eid:
                continue
            row = ev.get("row") if isinstance(ev.get("row"), dict) else {}
            state["rows"][table][eid] = row
            seq = _clean_int(ev.get("server_seq"), 0)
            if seq > state["seq"]:
                state["seq"] = seq
    return state


def _state():
    global _STATE
    if _STATE is None:
        _STATE = _read_log()
    return _STATE


def _append_events(events):
    """追加事件（一行一条）；超限换卷留一代（照 log_store 的 rotate 写法）。"""
    if not events:
        return
    path = _log_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path) and os.path.getsize(path) > ROTATE_BYTES:
        backup = path + ".1"
        if os.path.exists(backup):
            os.remove(backup)
        os.replace(path, backup)
    with open(path, "a", encoding="utf-8") as f:
        for ev in events:
            f.write(json.dumps(ev, ensure_ascii=False, separators=(",", ":")))
            f.write("\n")


# ============================== 同步核心 ==============================

def _normalize_row(table, raw, device_id, now_ms):
    """规整客户端行 → 服务端行 dict；缺 entity_id / 非法行返回 None。"""
    if not isinstance(raw, dict):
        return None
    eid = _clean_str(raw.get("entity_id") or raw.get("tagId"), 64)
    if not eid:
        return None
    row = {}
    for col in _ROW_COLS[table]:
        if col in _INT_COLS:
            row[col] = _clean_int(raw.get(col), 0)
        elif col in _FK_COLS:
            row[col] = _clean_str(raw.get(col), 64)
        elif col in _FREE_COLS:
            row[col] = _clean_str(raw.get(col))
        elif col == "name":
            row[col] = _clean_str(raw.get(col), 80) or ""
        else:
            row[col] = _clean_str(raw.get(col), 200)
    row["deleted"] = 1 if _clean_int(raw.get("deleted"), 0) else 0
    # updated_at：客户端没给就用服务器时间（新改动不能因缺时间戳被误判为旧行）
    row["updated_at"] = _clean_int(raw.get("updated_at"), 0) or now_ms
    row["device_id"] = device_id
    return eid, row


def _apply_table(state, table, rows, device_id, now_ms, events):
    applied = 0
    skipped = 0
    store = state["rows"][table]
    for raw in rows:
        normalized = _normalize_row(table, raw, device_id, now_ms)
        if normalized is None:
            continue
        eid, row = normalized
        cur = store.get(eid)
        if cur is not None and _clean_int(cur.get("updated_at"), 0) > row["updated_at"]:
            skipped += 1  # 服务器上有更新版本 → 旧的跳过（行级 LWW）
            continue
        state["seq"] += 1
        row = dict(row)
        row["server_seq"] = state["seq"]
        store[eid] = row
        events.append({"table": table, "entity_id": eid, "row": row})
        applied += 1
    return applied, skipped


def _rows_since(state, since):
    """按水位拉增量：server_seq > since 的全部行（含软删行——客户端据此删除本地行）。"""
    out = {}
    for table in _TABLES:
        keyed = []
        for eid, row in state["rows"][table].items():
            if _clean_int(row.get("server_seq"), 0) > since:
                item = dict(row)
                item["entity_id"] = eid
                keyed.append(item)
        keyed.sort(key=lambda r: _clean_int(r.get("server_seq"), 0))
        out[table] = keyed
    return out


def sync(payload, request_id=None):
    """POST /api/shelves/sync 的实现。

    payload = {
        "device_id": "安装维度 UUID",
        "last_seq": 0,                # 客户端已拉取到的水位
        "changes": {"zones": [...], "shelves": [...], "levels": [...], "slots": [...], "bindings": [...]},
    }
    返回：{"success": true, "data": {"server_seq": N, "changes": {...}, "applied": {...}}}
    """
    if not isinstance(payload, dict):
        return _err("请求体必须是 JSON 对象")
    device_id = _clean_str(payload.get("device_id"), 64) or "unknown"
    last_seq = _clean_int(payload.get("last_seq"), 0)
    changes = payload.get("changes") or {}
    if not isinstance(changes, dict):
        return _err("changes 必须是对象")

    total = sum(len(v) for v in changes.values() if isinstance(v, list))
    if total > _MAX_ROWS_PER_PUSH:
        return _err(
            "单批同步行数过多", code="TOO_MANY_ROWS",
            user_message="本次同步数据量过大，请稍后再试", hint=f"rows={total}",
        )

    now_ms = int(time.time() * 1000)
    try:
        with _LOCK:
            state = _state()
            applied = {}
            skipped = {}
            events = []
            for table in _TABLES:
                rows = changes.get(table) or []
                if not isinstance(rows, list):
                    rows = []
                a, s = _apply_table(state, table, rows, device_id, now_ms, events)
                applied[table] = a
                skipped[table] = s
            _append_events(events)
            pulled = _rows_since(state, last_seq)
            server_seq = state["seq"]
    except OSError as e:
        return _err(f"日志写入失败: {e}", code="IO_ERROR", user_message="服务器货架库暂时不可用", hint=str(e))

    return _ok({
        "server_seq": server_seq,
        "server_time_ms": now_ms,  # 供 APP 做时钟差校正（账单同步同款：避免慢钟设备的改动被 LWW 误判为旧行）
        "changes": pulled,
        "applied": applied,
        "skipped_stale": skipped,
        "request_id": request_id or "",
    })


def state():
    """GET /api/shelves/state：自检/排障用（行数、水位、日志大小）。"""
    try:
        with _LOCK:
            st = _state()
            counts = {}
            for table in _TABLES:
                rows = st["rows"][table].values()
                counts[table] = sum(1 for r in rows if not _clean_int(r.get("deleted"), 0))
                counts[table + "_deleted"] = sum(1 for r in rows if _clean_int(r.get("deleted"), 0))
            try:
                log_bytes = os.path.getsize(_log_path())
            except OSError:
                log_bytes = 0
    except OSError as e:
        return _err(f"读取失败: {e}", code="IO_ERROR", user_message="服务器货架库暂时不可用", hint=str(e))
    return _ok({
        "counts": counts,
        "server_seq": st["seq"],
        "log_path": _log_path(),
        "log_bytes": log_bytes,
    })
