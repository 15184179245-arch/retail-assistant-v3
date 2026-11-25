# -*- coding: utf-8 -*-
"""src/agent/agent_stream.py — L10 流式老王桥：hermes serve WS → SSE 事件流。

对话源：hermes serve 的 WS JSON-RPC（契约实测版 docs/laowang-serve-bridge.md）：
  session.create → prompt.submit → event 帧（message.delta 带回答文本 → message.complete 收尾）。
serve 未配置/不可达/websocket-client 未装 → 逐字回落 agent_bridge.agent_run（一次性全文，v2 行为零回归）。
事件流（SSE data: 行，每行一个 JSON）：
  {"type":"delta","text":"..."}                    回答增量（可能多次）
  {"type":"thinking"}                              老王在思考/工具调用中（无内容，仅状态）
  {"type":"done","data":{...agent_run 数据形...}}   收尾：session_id/degraded/pending_actions/answer(全文时)
  {"type":"error","code":...,"user_message":...}   失败（APP 出重试气泡）
写操作红线不变：票据仍由 hermes 工具经 /api/agent/pending 打回，turn 收尾统一带出 pending_actions。
"""
import json
import logging
import os
import threading
import time

from agent import a2ui, agent_bridge, fallback, pending_store, ui_store, vision_identify

log = logging.getLogger("agent_stream")


def _op_run(op, args):
    """延迟导入 core.ops（与 agent_confirm 同款做法，避开 import 成环）。"""
    from core.ops import run_op
    return run_op(op, args)


def _ui_frames(message, tools_seen, exclude=()):
    """本轮该出哪些卡。

    两个来源，都只认真实数据：
      1) 本轮真调过的 sx_* 工具（模型主动查了）；
      2) 问题的确定性意图（2026-09-19 实测：hermes 常常不调 sx_* 而是自己 execute_code
         糊弄过去，只靠 1) 卡片几乎不会出现；意图匹配来自 fallback 的只读规则，
         写意图一律不出卡）。
    两条路的行数据都由 run_op 现取，模型不参与生成结构。
    exclude：agent 已通过 sx_card 点过单的 op，不再重复出卡。
    """
    ops = [o for o in (a2ui.op_for_tool(t) for t in tools_seen) if o]
    # 意图触发只允许"无参数也安全"的只读 op（名单在 catalog 里）：search 这类必须带查询词，
    # 空参去跑会拿回垃圾结果再画成卡，比不出卡更糟。
    intent = fallback.match_op(message)
    if intent and intent not in ops:
        ops.append(intent)          # 是否放行由 a2ui.SAFE_OPS 统一把关
    ops = [o for o in ops if o not in exclude]
    return a2ui.frames_for_ops(ops, _op_run)


def _queued_ui_frames(started, already):
    """排空本轮 agent 点单的卡片。agent 显式点过的 op，不再由意图/工具路重复出卡。"""
    frames = []
    for spec in ui_store.take(created_since=started):
        if spec["op"] in already:
            continue
        already.add(spec["op"])
        try:
            r = _op_run(spec["op"], _spec_args(spec))
            if not isinstance(r, dict) or not r.get("ok"):
                continue
            msgs = a2ui.card_for(spec["op"], r.get("data"), columns=spec.get("columns") or None,
                                 sort=spec.get("sort"), limit=spec.get("limit"))
        except Exception:
            continue              # 点单坏了只少一张卡，绝不打死这条流
        if msgs:
            frames.append({"type": "ui", "messages": msgs})
    return frames


def _prefetch_block(message, image_paths, file_paths):
    """无参数、结果确定的只读查询，在交给模型之前先替它跑掉，数据随问题一起进模型。

    实测（2026-09-21，hermes agent.log）：模型一跳 3.9~5.3s，而被它调的工具本身只要
    0.1~0.97s——「先花一跳决定调哪个工具」是纯白付的一跳。

    **只预取"零参数"的 op**（low_stock / todos 这类），这条边界是实测划的：
    查价必须先有一个干净的关键词，而把店员那句人话剥成关键词这件事正则做不好——
    「螺钉」500 条命中、首条正是`螺钉8*35`，可「螺钉多少钱」只剩 44 条且首条变了；
    「∅8mm膨胀螺栓」不管剥字还是整句送，前三名都是不相干的`14*18mm方头接头`。
    预取一份错数据比多等 4 秒毒得多——模型自己推关键词反而推得对（实测量过）。
    写意图 match_op 直接返回 None，票据闸门（I16）与识货/读文件链路一概不受影响。
    """
    if image_paths or file_paths:   # 识货/读文件必须先让模型动工具，塞猜来的数据只会误导
        return None
    op = fallback.match_op(message)
    if not op:
        return None
    from mcp import retaildemo_mcp     # 复用同一份出口预算与 _slim 投影，不另写一套裁剪
    from core.ops import OPS
    entry = OPS.get(op) or {}
    if entry.get("write") or entry.get("params"):   # 要参数的一律交还模型自己查，见上方实测
        return None
    try:
        call = retaildemo_mcp.tools_call({"name": "sx_" + op, "arguments": {}})
    except Exception:               # 预取坏了就当没这回事，模型照常有全套工具
        return None
    if call.get("isError"):
        return None
    text = ((call.get("content") or [{}])[0] or {}).get("text") or ""
    if not text:
        return None
    return ("[系统已实时查过本店数据库，结果如下。直接据此回答，不必再调同类 sx_* 工具；"
            "不够用就照常自己查]\n" f"sx_{op} ->\n{text}\n\n")


def _spec_args(spec):
    """点单只允许收窄查询范围（limit / 排序），不接受 agent 传任意 op 参数。"""
    args = {}
    if spec.get("limit"):
        args["limit"] = spec["limit"]
    return args

SERVE_URL = os.environ.get("retaildemo_HERMES_SERVE_URL", "")
SERVE_TOKEN = os.environ.get("retaildemo_HERMES_SERVE_TOKEN",
                             os.environ.get("HERMES_DASHBOARD_SESSION_TOKEN", ""))
# 终端式流传输（老板 2026-09-19 定）：不设总时长上限，有帧就一直流到回合自然结束；
# 只防"彻底静默"（死连接/卡死）——超过静默窗没有任何帧才软停。APP 侧停止按钮随时可断。
STREAM_IDLE_TIMEOUT = int(os.environ.get("retaildemo_AGENT_STREAM_IDLE_S", "120"))

# 2026-09-27 文字注意力污染防线（老板令「注意力污染不止图片对话也有」）：serve 会话
# 不再无限堆积——每 retaildemo_AGENT_SERVE_MAX_TURNS 轮或闲置超过
# retaildemo_AGENT_SERVE_SESSION_TTL_S 自动换新 serve 会话；换新时把桥接层有界文字记忆
# （最近 6 轮 × 600 字，与 CLI 回落路同款）注入一次：记忆续得上，污染面封顶在 N 轮。
# hermes 自带压缩器按 token 阈值才压老轮次、受保护尾部不动，对店里十来轮的短会话
# 无能为力——轮换必须在桥接层做。
SERVE_MAX_TURNS = int(os.environ.get("retaildemo_AGENT_SERVE_MAX_TURNS", "6"))
SERVE_SESSION_TTL = float(os.environ.get("retaildemo_AGENT_SERVE_SESSION_TTL_S", "7200"))

# app_session_id -> hermes session_id（serve 侧自己有多轮记忆，这里只做映射）。
# 落盘持久化：网关重启不清映射，店员重开 APP 才能续上同一份 serve 会话记忆。
_SERVE_SESSIONS: dict = {}
_MAP_LOCK = threading.Lock()
_MAP_FILE = os.environ.get(
    "retaildemo_AGENT_SERVE_SESSIONS",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "data", "agent_serve_sessions.json"))
_MAP_MAX = 200  # 上限防无限膨胀（每个"新对话"一个 app_sid）


def _load_serve_sessions() -> None:
    try:
        with open(_MAP_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            for k, v in data.items():
                if not isinstance(k, str) or not k:
                    continue
                if isinstance(v, str) and v:
                    _SERVE_SESSIONS[k] = v  # 旧版纯 sid 条目
                elif isinstance(v, dict) and isinstance(v.get("sid"), str) and v.get("sid"):
                    _SERVE_SESSIONS[k] = v  # 新版 {"sid":…, "stored":…}
    except (OSError, ValueError):
        pass  # 没有存档/坏档：从空映射起步，等价于旧内存行为


def _save_serve_sessions() -> None:
    try:
        os.makedirs(os.path.dirname(_MAP_FILE), exist_ok=True)
        while len(_SERVE_SESSIONS) > _MAP_MAX:
            _SERVE_SESSIONS.pop(next(iter(_SERVE_SESSIONS)))
        tmp = _MAP_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(_SERVE_SESSIONS, f, ensure_ascii=False)
        os.replace(tmp, _MAP_FILE)
    except OSError:
        pass  # 落盘失败不杀流：最坏情况网关重启后另建 serve 会话（记忆从新）


_load_serve_sessions()

_END_TYPES = {"message.complete", "turn.complete", "agent.done", "complete",
              "turn.end", "agent.final", "session.idle"}
_TEXT_KEYS = ("delta", "text", "chunk")


class _SessionExpired(Exception):
    """serve 侧会话已不存在（被回收/重启清掉）——外层换新 socket 重连重建。"""


class _TurnTimeout(Exception):
    """回合超时。active=True 表示 serve 侧已在跑（思考/工具/出过字）——外层必须软停，
    绝不回落 CLI（回落=同一问题两头跑，串成两个会话）；active=False 才允许回落。"""

    def __init__(self, active: bool):
        super().__init__("stream deadline")
        self.active = active


def frame_text(params: dict) -> str:
    """event 帧里的文本增量：params.delta/text/chunk + params.payload.* 同键（探针实证的文本位）。"""
    parts = []
    for holder in (params, params.get("payload") or {}):
        if isinstance(holder, dict):
            for key in _TEXT_KEYS:
                v = holder.get(key)
                if isinstance(v, str) and v:
                    parts.append(v)
    return "".join(parts)


def is_end_event(etype: str, method: str) -> bool:
    """turn 收尾判定：event 帧的 params.type 或顶层 method 命中任一结束语义。"""
    return etype in _END_TYPES or method in _END_TYPES


def sid_from_result(result) -> str:
    """session.create 回包里抠会话 id：session_id → sid → 首个字符串值（探针同款兜底）。"""
    if not isinstance(result, dict):
        return ""
    for key in ("session_id", "sid"):
        v = result.get(key)
        if isinstance(v, str) and v:
            return v
    for v in result.values():
        if isinstance(v, str) and v:
            return v
    return ""


def reset_stream_session(app_sid: str) -> None:
    """新对话：丢 app→serve 会话映射（serve 侧旧会话自然闲置，不强删）。"""
    with _MAP_LOCK:
        _SERVE_SESSIONS.pop(app_sid, None)
        _save_serve_sessions()


def _rotate_if_stale(app_sid: str) -> bool:
    """serve 会话轮换判定（文字注意力污染防线）。True=已换新（映射已清，本轮将建新会话）。

    触发：累计轮数达 SERVE_MAX_TURNS，或距上一轮超过 SERVE_SESSION_TTL。
    旧版纯字符串条目视为未知时长，一律轮换一次（升级成带计数的条目）。"""
    with _MAP_LOCK:
        entry = _SERVE_SESSIONS.get(app_sid)
        if entry is None:
            return False  # 本来就要新建（新店员/刚显式 reset/刚轮换过）
        if isinstance(entry, dict):
            turns = int(entry.get("turns") or 0)
            age = time.time() - float(entry.get("ts") or 0)
            if turns < SERVE_MAX_TURNS and age < SERVE_SESSION_TTL:
                return False
        _SERVE_SESSIONS.pop(app_sid, None)
        _save_serve_sessions()
    log.info("serve session rotated (app=%s stale_entry=%s max_turns=%d ttl=%.0fs)",
             app_sid, type(entry).__name__, SERVE_MAX_TURNS, SERVE_SESSION_TTL)
    return True


def _bump_turn(app_sid: str) -> None:
    """serve 回合计数 +1 并刷新时间戳（轮换判定用）。"""
    with _MAP_LOCK:
        entry = _SERVE_SESSIONS.get(app_sid)
        if isinstance(entry, dict):
            entry["turns"] = int(entry.get("turns") or 0) + 1
            entry["ts"] = time.time()
            _save_serve_sessions()


def _vision_wrap(message: str, image_paths: list) -> str:
    """识货提示词（流式/CLI 两路同源 vision_identify.vision_block；serve 有会话记忆，不再灌历史）。

    2026-09-27 看图复盘：隔离识图成功时主会话只收纯 JSON——原图不再进入 serve 会话
    上下文，跨图注意力串味在物理上不可能发生；识别失败自动回落旧的
    「路径 + vision 工具」提示（vision_block 内部处理）。"""
    if not image_paths:
        return message
    return vision_identify.vision_block(image_paths) + message


def _connect():
    """连 serve WS（懒加载 websocket-client；未安装返回 None → 回落全文路径）。"""
    try:
        import websocket  # pip install websocket-client（宪法第8条：不手搓 RFC6455）
    except ImportError:
        return None
    url = SERVE_URL + ("?token=" + SERVE_TOKEN if SERVE_TOKEN else "")
    return websocket.create_connection(url, timeout=4, max_size=2 ** 22)


def _entry_ids(entry):
    """映射条目 → (运行时 sid, stored_session_id)。兼容旧版纯字符串值。"""
    if isinstance(entry, dict):
        return entry.get("sid") or "", entry.get("stored") or ""
    return (entry or ""), ""


def _resume_session(ws, app_sid: str, stored_sid: str) -> str:
    """serve 回收会话后（4001）用 stored_session_id 唤醒原会话：serve 从会话库恢复
    完整转录（老板要的「会话记忆不丢」就是靠这一步）。返回新的运行时 sid。"""
    if not stored_sid:
        raise _SessionExpired(app_sid)  # 旧版映射/会话未落库：只能重建新会话
    ws.send(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "session.resume",
                        "params": {"session_id": stored_sid}}))
    ws.settimeout(30)
    while True:
        raw = ws.recv()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(msg, dict) or msg.get("id") != 3:
            continue  # 广播/事件帧忽略，只等 resume 回包
        if msg.get("error"):
            log.warning("session.resume failed (stored=%s): %s", stored_sid, msg["error"])
            raise _SessionExpired(app_sid)
        new_sid = sid_from_result(msg.get("result"))
        if not new_sid:
            raise _SessionExpired(app_sid)
        with _MAP_LOCK:
            _SERVE_SESSIONS[app_sid] = {"sid": new_sid, "stored": stored_sid,
                                        "turns": 0, "ts": time.time()}
            log.info("serve session resumed (app=%s stored=%s new_sid=%s)",
                     app_sid, stored_sid, new_sid)
            _save_serve_sessions()
        return new_sid


def _serve_turn(ws, app_sid: str, prompt: str, started: float):
    """一次 serve 对话往返：建/复用会话 → prompt.submit → 逐帧翻译成事件。"""
    with _MAP_LOCK:
        entry = _SERVE_SESSIONS.get(app_sid)
    hermes_sid, stored_sid = _entry_ids(entry)
    log.info("serve turn begin (app=%s mapped_sid=%r map_size=%d)",
             app_sid, hermes_sid, len(_SERVE_SESSIONS))
    if not hermes_sid:
        ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.create", "params": {}}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == 1:
                err = msg.get("error")
                if err:
                    raise RuntimeError(f"session.create: {err}")
                hermes_sid = sid_from_result(msg.get("result"))
                if not hermes_sid:
                    raise RuntimeError("session.create 无 session_id")
                stored_sid = str((msg.get("result") or {}).get("stored_session_id") or "")
                break
        with _MAP_LOCK:
            _SERVE_SESSIONS[app_sid] = {"sid": hermes_sid, "stored": stored_sid,
                                        "turns": 0, "ts": time.time()}
            log.info("serve session created (app=%s sid=%r stored=%r map_size=%d)",
                     app_sid, hermes_sid, stored_sid, len(_SERVE_SESSIONS))
            _save_serve_sessions()
    submit_ack_id = 2
    ws.send(json.dumps({"jsonrpc": "2.0", "id": submit_ack_id, "method": "prompt.submit",
                        "params": {"session_id": hermes_sid, "text": prompt}}))
    submitted = False
    thinking_sent = False
    saw_answer = False
    # 2026-09-19 serve 新版协议实测（0.21.3，帧序探针 _reports/order_probe_tmp.py）：
    # prompt.submit 后立刻先推一条 session.info{running:false}（闲态心跳），回答全到达
    # 后再推一条。旧判定「见 running=false 即收流」会掐在首条上 → APP 只收到空回答，
    # 表现为「连不上/没反应」。现要求本回合真的跑过（有 message.start 且有
    # message.complete）才允许 running=false 收尾。
    turn_started = False
    turn_completed = False
    saw_tool = False
    while True:
        ws.settimeout(STREAM_IDLE_TIMEOUT)
        try:
            raw = ws.recv()
        except Exception as exc:
            # 静默超过窗口：回合还活着就软停（外层不回落），否则 CLI 回落会把同一问题
            # 在另一个会话里再跑一遍 = 串成两个会话（09-19 实锤）。
            if turn_started and type(exc).__name__ == "WebSocketTimeoutException":
                raise _TurnTimeout(active=True) from exc
            raise
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            continue  # 单帧坏数据不杀流（hermes-android 协议对照：工具结果可能是结构化 JSON）
        if not isinstance(msg, dict):
            continue
        if msg.get("id") == submit_ack_id:
            if msg.get("error"):
                # 会话被 serve 回收（ws_orphan_reap）：先用 stored id resume 找回原会话
                # （转录在会话库里，记忆不丢）；resume 不可用才抛给外层重连重建。
                code = (msg["error"] or {}).get("code")
                if code == 4001:
                    with _MAP_LOCK:
                        _SERVE_SESSIONS.pop(app_sid, None)
                        _save_serve_sessions()
                    new_sid = _resume_session(ws, app_sid, stored_sid)
                    hermes_sid = new_sid
                    submit_ack_id = 4
                    ws.send(json.dumps({"jsonrpc": "2.0", "id": submit_ack_id,
                                        "method": "prompt.submit",
                                        "params": {"session_id": hermes_sid, "text": prompt}}))
                    continue
                raise RuntimeError(f"prompt.submit: {msg['error']}")
            submitted = True
            continue
        params = msg.get("params") or {}
        etype = params.get("type", "") if isinstance(params, dict) else ""
        method = msg.get("method", "")
        if etype == "message.delta":
            text = frame_text(params)
            if text:
                saw_answer = True
                yield {"type": "delta", "text": text}
        elif etype in ("thinking.delta", "reasoning.available") and not thinking_sent:
            thinking_sent = True
            yield {"type": "thinking"}
        elif etype in ("tool.start", "tool.complete"):
            # 2026-09-10 hermes-android 协议对照：agent 动手时服务端报工具名（payload.name），
            # 透传给 APP 显示「正在调用 <工具>…」；tool.complete 只用于清状态，不再外发
            if etype == "tool.start":
                saw_tool = True
                name = ""
                payload = params.get("payload") or {}
                if isinstance(payload, dict):
                    name = payload.get("name") or params.get("name") or ""
                yield {"type": "tool", "name": str(name or "")[:60]}
        elif etype == "message.start":
            turn_started = True
        elif etype == "session.info":
            # 权威忙/闲信号（hermes-android 同款结论）：running=false 可终结被打断/压缩的回合，
            # 不必干等 recv 超时——但只认「回合跑过之后」的那条；提交后首条闲态心跳
            # （serve 0.21.3 新序）不许收流，否则回答还没来流就被掐死。
            payload = params.get("payload") or {}
            if (isinstance(payload, dict) and payload.get("running") is False
                    and turn_started and turn_completed):
                break
        elif is_end_event(etype, method):
            # 2026-09-10 原始帧实证：一个回合由若干「message.start→…→message.complete」循环组成，
            # 思考/工具消息也会发 complete。只有收到过回答增量之后的 complete 才是回合终点，
            # 否则继续等真回答（防止提前掐流——第二问零 delta 的根因）。
            if etype == "message.complete":
                turn_completed = True
            if etype == "message.complete" and not saw_answer:
                continue
            break


def stream_events(payload: dict):
    """SSE 事件生成器：网关 /api/agent/stream 唯一入口。"""
    b = payload or {}
    message = (b.get("message") or "").strip()
    session_id = b.get("session_id") or "app-default"
    image_paths = [p for p in (b.get("image_paths") or []) if isinstance(p, str) and p]
    file_paths = [p for p in (b.get("file_paths") or []) if isinstance(p, str) and p][:3]
    if not message and not image_paths and not file_paths:
        yield {"type": "error", "code": "BAD_REQUEST", "user_message": "想问什么？"}
        return
    if b.get("reset"):
        reset_stream_session(session_id)
        agent_bridge.reset_session(session_id)
        yield {"type": "done", "data": {
            "session_id": session_id, "answer": "好，咱重新开始。",
            "degraded": False, "tool_trace": [], "pending_actions": []}}
        return
    started = time.time() - 1.0
    staff = (b.get("staff") or "").strip()
    if not message:
        message = "（识别这张图里的商品）" if image_paths else "（看看这个文件）"
    # 文字注意力污染防线（2026-09-27）：先做轮换判定——达轮数上限/超时的 serve 会话
    # 就地换新，并把桥接层有界文字记忆（最近 6 轮）注入一次。会话内的 serve 转录
    # 永远不超过 N 轮，老话题固化在结构上进不了新会话。
    rotated = _rotate_if_stale(session_id)
    hist = agent_bridge.history_prompt(session_id) if rotated else ""
    # 预取先算、拼在身份包装**里面**：[提问者：x] 得留在整段最前面，SOUL.md 按身份
    # 应对的规矩认的就是那一行。只在流式主路做（回落 CLI 路有自己的历史提示词）。
    pre = _prefetch_block(message, image_paths, file_paths) if SERVE_URL else None
    if image_paths:
        # 隔离识图在网关内先跑（秒级状态先出，APP 气泡有事可显示）
        yield {"type": "tool", "name": "隔离识图（防串图）"}
    prompt = agent_bridge.identity_wrap(
        staff, hist + (pre or "") + _vision_wrap(message, image_paths))
    if file_paths:
        listed = "；".join(file_paths)
        prompt = ("[店员上传了文件，已保存在本机这些路径：" + listed + "]\n"
                  "请先用 read_file 工具查看文件内容，再结合问题回答。"
                  "二进制文档（pdf/doc/xls）读不出就直说，并建议发图片或文字描述。]\n\n"
                  + prompt)

    # 限速先行于建连（P1-A）：被拒就不许白连 serve WS；serve 未配置时回落路自有限速
    if SERVE_URL:
        reject = agent_bridge.acquire_run_slot(session_id)
        if reject:
            yield {"type": "error", "code": reject.get("code", "RATE_LIMITED"),
                   "user_message": reject.get("user_message", "稍后再试")}
            return
    # 主路：serve WS 流式。两次尝试：serve 侧会话被回收（闲置/重启）时 prompt.submit 回
    # 4001，此时映射已清，换新 socket 重连重建即可（同 socket 递归会被残留广播帧干扰，
    # 2026-09-19 实测 96ms 空 done）。
    tools_seen = []  # 本轮真调过的工具名 → a2ui 模板层据此决定出哪张卡（不凭文本猜）
    for attempt in (1, 2):
        if not SERVE_URL:
            break
        try:
            ws = _connect()
        except Exception as exc:
            # 2026-09-11 现网事故：serve 侧 token 轮换后握手 403，建连原在 try 外
            # 直接炸掉整个 SSE 生成器（APP 秒报重试气泡），回落路没机会兜底。
            # 按首字前失败同款处理：还回限速时点，走全文回落。
            log.warning("serve connect failed (session=%s): %s: %s",
                        session_id, type(exc).__name__, exc)
            agent_bridge.release_run_slot(session_id)
            break
        try:
            got_text = False
            answer_parts = []
            for evt in _serve_turn(ws, session_id, prompt, started):
                if evt.get("type") == "delta":
                    got_text = True
                    answer_parts.append(evt.get("text") or "")
                elif evt.get("type") == "tool":
                    tools_seen.append(evt.get("name") or "")
                yield evt
            if not got_text:
                log.warning("serve turn ended with NO answer (session=%s sid=%s)",
                            session_id, _entry_ids(_SERVE_SESSIONS.get(session_id))[0])
            # 轮换防线的回合计数与文字记忆（桥接层保存最近 6 轮，轮换后注入续接）
            _bump_turn(session_id)
            agent_bridge.remember_turn(
                session_id, message,
                "".join(answer_parts) if got_text else "（这一问查得较久，结果稍后见对话记录）")
            queued_ops = set()
            for frame in _queued_ui_frames(started, queued_ops):
                yield frame
            for frame in _ui_frames(message, tools_seen, exclude=queued_ops):
                yield frame
            tickets = pending_store.list_pending(created_since=started)
            pending_store.associate_session(
                [t["action_id"] for t in tickets], session_id)
            yield {"type": "done", "data": {
                "session_id": session_id, "answer": "", "degraded": False,
                "tool_trace": [{"op": "hermes-serve", "ok": True}],
                "pending_actions": [
                    {"action_id": t["action_id"], "op": t["op"],
                     "summary": t["summary"], "args": t["args"]} for t in tickets]}}
            return
        except _SessionExpired:
            log.warning("serve session expired, reconnecting (session=%s attempt=%d)",
                        session_id, attempt)
            continue
        except _TurnTimeout as timeout:
            if not timeout.active:
                # 会话一动没动就静默（死会话）：回落全文路兜底
                log.warning("serve turn idle before start (session=%s)", session_id)
                agent_bridge.release_run_slot(session_id)
                break
            # 终端式软停：serve 侧还在跑只是慢，答案稍后自己落进会话记录；
            # 这里绝不回落 CLI（回落=同一问题两头跑、串成两个会话，09-19 实锤）。
            log.warning("serve turn soft-stop on idle (session=%s sid=%s)",
                        session_id, _entry_ids(_SERVE_SESSIONS.get(session_id))[0])
            yield {"type": "delta",
                   "text": "（这次查得比较久，我先收到这儿——结果稍后会出现在对话记录里，你也可以再问我一次）"}
            _bump_turn(session_id)
            agent_bridge.remember_turn(session_id, message,
                                       "（这一问查得较久，结果稍后见对话记录）")
            queued_ops = set()
            for frame in _queued_ui_frames(started, queued_ops):
                yield frame
            for frame in _ui_frames(message, tools_seen, exclude=queued_ops):
                yield frame
            tickets = pending_store.list_pending(created_since=started)
            pending_store.associate_session(
                [t["action_id"] for t in tickets], session_id)
            yield {"type": "done", "data": {
                "session_id": session_id, "answer": "", "degraded": False,
                "tool_trace": [{"op": "hermes-serve", "ok": True}],
                "pending_actions": [
                    {"action_id": t["action_id"], "op": t["op"],
                     "summary": t["summary"], "args": t["args"]} for t in tickets]}}
            return
        except Exception as exc:
            log.warning("serve turn failed (session=%s got_text=%s): %s: %s",
                        session_id, got_text, type(exc).__name__, exc)
            if got_text:
                # 流中断在中途：不再补全文（内容可能重复），交由 APP 报错重试
                yield {"type": "error", "code": "STREAM_BROKEN",
                       "user_message": "回答中断了，点重试再来一次"}
                return
            # 首字前失败 → 静默回落全文路径（还回限速时点，防回落路被间隔窗误拒）
            agent_bridge.release_run_slot(session_id)
            break
        finally:
            try:
                ws.close()
            except Exception:
                pass
    # 回落：v2 全文路径（自带限速/信号量/降级），一次性作为一个 delta 吐出
    result = agent_bridge.agent_run(b)
    if result.get("success"):
        data = result.get("data") or {}
        answer = data.get("answer") or ""
        if answer:
            yield {"type": "delta", "text": answer}
        queued_ops = set()
        for frame in _queued_ui_frames(started, queued_ops):
            yield frame
        for frame in _ui_frames(message, data.get("ops_ran") or [], exclude=queued_ops):
            yield frame
        yield {"type": "done", "data": data}
    else:
        yield {"type": "error", "code": result.get("code", "AGENT_ERROR"),
               "user_message": result.get("user_message", "老王暂不可用，稍后再试")}
