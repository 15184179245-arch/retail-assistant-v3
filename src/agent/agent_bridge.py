# -*- coding: utf-8 -*-
"""src/agent/agent_bridge.py — 网关侧 Hermes 桥（协议翻译+策略闸门，非 LLM 循环）。

v1 通道：`hermes -z` 子进程（免 token、全能力：SOUL 人格+15 工具+MCP）。
v2 预留：serve WS 会话（session.create/prompt.submit，见 docs/laowang-serve-bridge.md）做多轮流式。
Hermes 不可达 → fallback.route 确定性降级（只读），写意图拒绝。
写操作：本进程票据库出票，/api/agent/confirm 执行——AI 结构上无法自确认。
"""
import os
import subprocess
import threading
import time

from agent import pending_store, vision_identify
from agent.fallback import route as _fallback_route

HERMES_CLI = os.environ.get(
    "retaildemo_HERMES_CLI",
    os.path.expandvars(r"%LOCALAPPDATA%\hermes\hermes-agent\.venv\Scripts\hermes.exe"),
)
ASK_TIMEOUT = int(os.environ.get("retaildemo_AGENT_ASK_TIMEOUT", "110"))
# 2026-09-10 老板拍板：mimo-v2.5 转为降级方案（serve 流式主路走会话默认模型）
FALLBACK_MODEL = os.environ.get("retaildemo_AGENT_FALLBACK_MODEL", "mimo-v2.5")
FALLBACK_PROVIDER = os.environ.get("retaildemo_AGENT_FALLBACK_PROVIDER", "opencode-go")

_confirm_lock = threading.Lock()  # 票据执行串行化（防双击双扣）

# 2026-09-09 审计 P1-A：/api/agent/run 公网匿名可达，原实现无并发上限无限速——
# 可被刷出 hermes 子进程风暴 + LLM 费用放大。与 F9 登录限速同款思路，全部环境变量可调。
_HERMES_SEM = threading.Semaphore(int(os.environ.get("retaildemo_AGENT_MAX_CONCURRENT", "2")))
_HERMES_SEM_TIMEOUT = float(os.environ.get("retaildemo_AGENT_SEM_TIMEOUT", "10"))
_RUN_MIN_INTERVAL = float(os.environ.get("retaildemo_AGENT_MIN_INTERVAL", "5"))
_LAST_ASK: dict = {}  # session_id -> 上次提问时间（限速用）

_HERE = os.path.dirname(os.path.abspath(__file__))   # src/agent
_SRC_DIR = os.path.dirname(_HERE)                    # src
_REPO_DIR = os.path.dirname(_SRC_DIR)                # 仓库根

# 多轮会话记忆（v2）：app_session_id -> {"ts": 时间, "turns": [{"u":用户,"a":老王}]}
# 纯进程内存：网关重启即忘（手册 J 节：店员随手问，不要求跨启动留档）
_SESSIONS: dict = {}
_SESSION_LOCK = threading.Lock()
_SESSION_TTL = 2 * 3600        # 2 小时不说话即忘（新客如初见）
_SESSION_MAX = 50              # 最多记 50 个会话（LRU 淘汰）
_SESSION_MAX_TURNS = 6         # 最多带最近 6 轮进 prompt（控 token）
_TURN_CHARS = 600              # 单侧文本截断


def _remember(app_sid: str, user: str, answer: str) -> None:
    now = time.time()
    with _SESSION_LOCK:
        h = _SESSIONS.get(app_sid)
        if not h or now - h.get("ts", 0) > _SESSION_TTL:
            h = _SESSIONS[app_sid] = {"ts": now, "turns": []}
        h["ts"] = now
        h["turns"].append({"u": user[:_TURN_CHARS], "a": answer[:_TURN_CHARS]})
        h["turns"] = h["turns"][-_SESSION_MAX_TURNS:]
        while len(_SESSIONS) > _SESSION_MAX:
            _SESSIONS.pop(next(iter(_SESSIONS)))


def _history_prompt(app_sid: str) -> str:
    with _SESSION_LOCK:
        h = _SESSIONS.get(app_sid)
        if not h or time.time() - h.get("ts", 0) > _SESSION_TTL:
            return ""
        turns = list(h.get("turns") or [])
    if not turns:
        return ""
    lines = ["[最近对话记录（仅作上下文衔接，不要复述）]"]
    for t in turns:
        lines.append(f"店员：{t['u']}")
        lines.append(f"老王：{t['a']}")
    lines.append("[记录结束，请回答新问题]")
    return "\n".join(lines) + "\n\n"


def _build_prompt(app_sid: str, message: str) -> str:
    hist = _history_prompt(app_sid)
    return (hist + "[新问题]\n" + message) if hist else message


def history_prompt(app_sid: str) -> str:
    """serve 路会话轮换后的记忆续接（2026-09-27 文字污染防线）：
    桥接层有界文字记忆（最近 6 轮 × 600 字）转提示词，与 CLI 回落路同源。"""
    return _history_prompt(app_sid)


def remember_turn(app_sid: str, user: str, answer: str) -> None:
    """serve 路回合落档（桥接层文字记忆），供轮换后注入与跨重启续接。"""
    _remember(app_sid, user, answer)


def identity_wrap(staff: str, message: str) -> str:
    """看人下菜碟（老板 2026-09-19 令）：把提问者身份亮在问题最前面，
    SOUL.md 里有按身份应对的规矩（老板=汇报口吻，店员=干活口吻，没身份=先问是谁）。
    名字来自店员手输的名册，先清洗：压平换行/空白（防破坏标记行格式、防注入假行）。"""
    staff = " ".join((staff or "").split()).replace("[", "（").replace("]", "）")[:24]
    if not staff:
        return message
    return f"[提问者：{staff}]\n{message}"


def save_image(raw: bytes) -> dict:
    """I17：店员发来的商品图片落盘（仅本机，供 hermes vision 读图）。

    安全边界：≤8MB；魔数白名单（JPEG/PNG）；tempfile.mkstemp 原子创建
    （文件名服务端随机生成），目录固定 data/tmp/。
    """
    import tempfile
    if not raw or len(raw) > 8 * 1024 * 1024:
        return {"success": False, "code": "BAD_REQUEST",
                "error": "empty or too large", "user_message": "图片为空或超过 8MB"}
    if raw[:3] == b"\xff\xd8\xff":
        suffix = ".jpg"
    elif raw[:4] == b"\x89PNG":
        suffix = ".png"
    else:
        return {"success": False, "code": "BAD_REQUEST",
                "error": "not jpeg/png", "user_message": "仅支持 JPG/PNG 图片"}
    tmp_dir = os.path.join(_REPO_DIR, "data", "tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    fd, path = tempfile.mkstemp(suffix=suffix, dir=tmp_dir)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
    except OSError:
        return {"success": False, "code": "IO_ERROR", "error": "write failed",
                "user_message": "图片保存失败，稍后再试"}
    return {"success": True, "code": "OK", "data": {"path": path}}


# 店员可上传的普通文件类型（老王 read_file 读不出二进制会直说，见 stream 提示词）
FILE_ALLOWED_EXTS = {"txt", "md", "csv", "log", "json", "pdf",
                     "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg"}


def save_file(raw: bytes, filename: str = "") -> dict:
    """店员上传的普通文件落盘（仅本机，供老王 read_file 查看后回答）。

    安全边界：≤20MB；扩展名白名单（取自原始文件名，只留字母数字，防路径注入）；
    服务端随机文件名，目录固定 data/tmp/files/。
    """
    import tempfile
    if not raw or len(raw) > 20 * 1024 * 1024:
        return {"success": False, "code": "BAD_REQUEST",
                "error": "empty or too large", "user_message": "文件为空或超过 20MB"}
    ext = "".join(ch for ch in os.path.splitext(filename or "")[1].lower() if ch.isalnum())
    if ext not in FILE_ALLOWED_EXTS:
        return {"success": False, "code": "BAD_REQUEST",
                "error": "ext not allowed",
                "user_message": "不支持的文件类型（可发 txt/csv/json/pdf/doc/xls/图片）"}
    tmp_dir = os.path.join(_REPO_DIR, "data", "tmp", "files")
    os.makedirs(tmp_dir, exist_ok=True)
    fd, path = tempfile.mkstemp(suffix="." + ext, dir=tmp_dir)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
    except OSError:
        return {"success": False, "code": "IO_ERROR", "error": "write failed",
                "user_message": "文件保存失败，稍后再试"}
    return {"success": True, "code": "OK",
            "data": {"path": path, "name": (filename or "").strip()[:80]}}


def hermes_online() -> bool:
    return os.path.isfile(HERMES_CLI)


def agent_health(body=None) -> dict:
    return {"success": True, "code": "OK", "data": {
        "hermes": hermes_online(),
        "hermes_cli": HERMES_CLI,
        "fallback": "local-route",
    }}


def pending_create(body: dict) -> dict:
    """HTTP 入口：MCP 子进程经 ops.pending_create 打回网关出票。"""
    b = body or {}
    try:
        t = pending_store.create(b.get("op", ""), b.get("args") or {},
                                 session_id=b.get("session_id", ""),
                                 staff=b.get("staff", ""),
                                 summary=b.get("summary", ""))
    except ValueError as e:
        return {"success": False, "code": "BAD_REQUEST", "error": str(e),
                "user_message": str(e)}
    return {"success": True, "code": "OK", "data": {
        "action_id": t["action_id"],
        "summary": t["summary"],
        "hint": "等店员在 APP 确认后才会执行（action_id 已登记）",
    }}


def acquire_run_slot(session_id: str):
    """同会话两次提问最小间隔（P1-A 限速，全文/流式两路共用）。None=放行；dict=拒绝响应体。"""
    with _SESSION_LOCK:
        _last = _LAST_ASK.get(session_id, 0.0)
        _wait = _RUN_MIN_INTERVAL - (time.time() - _last)
        if _wait > 0:
            return {"success": False, "code": "RATE_LIMITED",
                    "error": "too fast",
                    "user_message": f"老王还在上一问里，请 {_wait:.0f} 秒后再问。"}
        _LAST_ASK[session_id] = time.time()
    return None


def release_run_slot(session_id: str) -> None:
    """还回限速时点（流式桥 serve 首字前失败回落前调用：回落路 agent_run 自查自记，防间隔窗误拒）。"""
    with _SESSION_LOCK:
        _LAST_ASK.pop(session_id, None)


def reset_session(session_id: str) -> None:
    """新对话：丢多轮记忆与限速时点（此前被 agent_run 引用但从未定义——09-10 L10 补上，reset 分支才真正可用）。"""
    with _SESSION_LOCK:
        _SESSIONS.pop(session_id, None)
        _LAST_ASK.pop(session_id, None)


def ui_create(body: dict) -> dict:
    """MCP 子进程经 ops.card 打回网关：登记一次卡片点单（不返回数据、不含样式）。"""
    from agent import a2ui, ui_store
    b = body or {}
    op_name = b.get("op", "")
    if op_name not in a2ui.CARD_OPS:
        return {"success": False, "code": "BAD_REQUEST", "error": f"未知卡片 op: {op_name}",
                "user_message": f"这个查询还没有卡片：{op_name}"}
    spec = ui_store.create(op_name, columns=b.get("columns") or [], sort=b.get("sort") or None,
                           limit=b.get("limit"), session_id=b.get("session_id", ""),
                           staff=b.get("staff", ""))
    return {"success": True, "code": "OK", "data": {
        "card_id": spec["card_id"],
        "hint": "卡片已登记，将随本轮回答一并发给店员（数据由系统现取，你不要复述明细）",
    }}


def agent_run(body: dict) -> dict:
    b = body or {}
    message = (b.get("message") or "").strip()
    session_id = b.get("session_id") or "app-default"
    image_paths = [p for p in (b.get("image_paths") or []) if isinstance(p, str) and p]
    file_paths = [p for p in (b.get("file_paths") or []) if isinstance(p, str) and p][:3]
    if not message and not image_paths and not file_paths:
        return {"success": False, "code": "BAD_REQUEST",
                "error": "empty message", "user_message": "想问什么？"}
    if b.get("reset"):
        reset_session(session_id)
        return {"success": True, "code": "OK", "data": {
            "session_id": session_id, "answer": "好，咱重新开始。",
            "degraded": False, "tool_trace": [], "pending_actions": []}}
    if not message:
        message = "（识别这张图里的商品）" if image_paths else "（看看这个文件）"
    # 2026-09-09 审计 P1-A：同会话两次提问最小间隔（F9 登录限速同款思路，防公网刷子）
    reject = acquire_run_slot(session_id)
    if reject:
        return reject
    started = time.time() - 1.0
    answer, degraded, trace, ops_ran = "", False, [], []
    prompt = _build_prompt(session_id, message)
    if image_paths:
        # 2026-09-27 看图复盘：隔离识图（零上下文 OCR → 纯 JSON），主会话不再加载原图，
        # 防跨图注意力串味；识别失败 vision_block 内部自动回落旧的路径提示。
        prompt = vision_identify.vision_block(image_paths) + prompt
        if not message:
            message = "（识别这张图里的商品）"
    prompt = identity_wrap(b.get("staff") or "", prompt)  # 身份行永远在最前（看人下菜碟）
    if file_paths:
        listed = "；".join(file_paths)
        prompt = ("[店员上传了文件，已保存在本机这些路径：" + listed + "]\n"
                  "请先用 read_file 工具查看文件内容，再结合问题回答。]\n\n" + prompt)
    if hermes_online():
        # 2026-09-09 审计 P1-A：并发上限（默认 2），排队 10s 仍无空位则回 BUSY
        if not _HERMES_SEM.acquire(timeout=_HERMES_SEM_TIMEOUT):
            return {"success": False, "code": "BUSY",
                    "error": "agent busy",
                    "user_message": "老王正忙，稍等几秒再问。"}
        try:
            env = dict(os.environ)
            env["HERMES_ACCEPT_HOOKS"] = "1"  # 服务化无 TTY：钩子提示会挂死，必须自动接受
            # 2026-09-10 老板拍板：mimo-v2.5 转为降级方案——CLI 回落路显式钉模型
            # （serve 流式主路走会话默认 gemini-3.8-flash-high），env 可调。
            cmd = [HERMES_CLI]
            if FALLBACK_MODEL:
                cmd += ["-m", FALLBACK_MODEL]
            if FALLBACK_PROVIDER:
                cmd += ["--provider", FALLBACK_PROVIDER]
            cmd += ["-z", prompt]
            proc = subprocess.run(
                cmd,
                capture_output=True, text=True, encoding="utf-8", errors="replace",
                timeout=ASK_TIMEOUT,
                # hermes 安装根（CLI 上两级）；os.pardir 平台无关拼法（F12：Windows 反斜杠字面量在 Linux 下必错）
                cwd=os.path.abspath(os.path.join(os.path.dirname(HERMES_CLI), os.pardir, os.pardir)),
                stdin=subprocess.DEVNULL,  # 网关进程 stdin 是悬空管道，hermes 读 stdin 会永久阻塞
                env=env,
            )
            if proc.returncode == 0 and proc.stdout.strip():
                answer = proc.stdout.strip()
                trace.append({"op": "hermes", "ok": True, "summary": answer[:60]})
            else:
                answer, degraded, ops_ran = _degrade(
                    message, proc.stderr[-200:] if proc.stderr else "")
                trace.append({"op": "hermes", "ok": False})
        except subprocess.TimeoutExpired:
            answer, degraded, ops_ran = _degrade(message, "timeout")
            trace.append({"op": "hermes", "ok": False, "summary": "超时"})
        except OSError:
            answer, degraded, ops_ran = _degrade(message, "spawn failed")
            trace.append({"op": "hermes", "ok": False})
        finally:
            _HERMES_SEM.release()
    else:
        answer, degraded, ops_ran = _degrade(message, "offline")
        trace.append({"op": "hermes", "ok": False, "summary": "CLI 不存在"})

    tickets = pending_store.list_pending(created_since=started)
    pending_store.associate_session([t["action_id"] for t in tickets], session_id)
    if not degraded and answer:
        _remember(session_id, message, answer)  # 降级回答不入档（非老王本人回答）
    return {"success": True, "code": "OK", "data": {
        "session_id": session_id,
        "answer": answer or "（无回答）",
        "degraded": degraded,
        "tool_trace": trace,
        "ops_ran": ops_ran,
        "pending_actions": [
            {"action_id": t["action_id"], "op": t["op"],
             "summary": t["summary"], "args": t["args"]}
            for t in tickets
        ],
    }}


def agent_confirm(body: dict) -> dict:
    b = body or {}
    action_id = b.get("action_id", "")
    confirm = bool(b.get("confirm"))
    with _confirm_lock:
        ticket = pending_store.get(action_id)
        if not ticket:
            return {"success": False, "code": "ACTION_NOT_FOUND",
                    "error": "ticket missing/expired",
                    "user_message": "该单据已失效，请重新发起"}
        if not confirm:
            pending_store.mark(action_id, "cancelled")
            return {"success": True, "code": "OK", "data": {
                "action_id": action_id, "state": "cancelled", "result": None}}
        # 二次确认通过：此刻才真正执行写操作（在网关进程内）
        from core.ops import run_op
        result = run_op(ticket["op"], ticket.get("args") or {})
        pending_store.mark(action_id, "executed")
        return {"success": True, "code": "OK", "data": {
            "action_id": action_id, "state": "executed", "result": result}}


def _degrade(message: str, reason: str) -> tuple:
    """离线降级：走本地确定性路由（只读）。

    第三个返回值＝本轮**真跑成功**的 op 名，供 a2ui 模板层决定出哪张卡——
    卡片数据只能来自这里，不许凭问题文本猜。
    """
    r = _fallback_route(message)
    ops = [t["op"] for t in r.get("tool_trace") or [] if t.get("ok")]
    return r["answer"], True, ops
