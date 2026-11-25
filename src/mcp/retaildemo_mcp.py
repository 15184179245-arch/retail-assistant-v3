# -*- coding: utf-8 -*-
"""retaildemo_mcp.py — 示例零售 MCP stdio server（F4 老王的手）。

把 core/ops.py 的操作以标准 MCP (Model Context Protocol) 工具暴露给
Hermes 等支持 MCP 的 agent 运行时。纯标准库，JSON-RPC 2.0 over stdio。

Hermes config.yaml 接入：
    mcp_servers:
      retaildemo:
        command: python
        args: ["C:\\...\\src\\mcp\\retaildemo_mcp.py"]
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from core.ops import OPS, run_op  # noqa: E402

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "retaildemo", "version": "1.0.0"}

# 出口预算（2026-09-20 慢答归因）：一次 sx_search 实测回 500 行 / 267KB，
# 把老王单次上下文从 22k token 顶到 28k，模型面对大块还要多跳去挖，
# 一个查价问题跑了 8 跳 / 78.3s。裁的是"给模型看的这一份回执"，库里数据一字节不动。
# 按体积触发而不是按行数：low_stock 的 50 行才 11KB，本来就在预算内，不该被误伤。
MAX_ROWS = int(os.environ.get("retaildemo_MCP_MAX_ROWS", "20"))
MAX_RESULT_CHARS = int(os.environ.get("retaildemo_MCP_MAX_RESULT_CHARS", "12000"))
# 只为服务端检索存在的字段，模型用不上却按行付费。
_NOISE_FIELDS = frozenset({"jieba_tokens", "pinyin", "initials", "description",
                           "product_code"})


def _tool_name(op_name):
    return "sx_" + op_name


def _schema_for(entry):
    props = {}
    required = []
    for pname, pdesc in entry["params"].items():
        props[pname] = {"type": "string", "description": pdesc}
    return {
        "type": "object",
        "properties": props,
        "required": required,
    }


def tools_list():
    tools = []
    for name, entry in sorted(OPS.items()):
        tools.append({
            "name": _tool_name(name),
            "description": entry["desc"],
            "inputSchema": _schema_for(entry),
        })
    return {"tools": tools}


def _slim_rows(rows):
    """行形列表 → 留在出口预算内的行；顺带剥掉只为服务端检索存在的字段。"""
    kept, used = [], 0
    for row in rows:
        if not isinstance(row, dict):
            kept.append(row)
            continue
        slim = {k: v for k, v in row.items() if k not in _NOISE_FIELDS}
        used += len(json.dumps(slim, ensure_ascii=False))
        if kept and used > MAX_RESULT_CHARS:
            break
        kept.append(slim)
    return kept


def _cap_nested(node):
    """递归封顶任意深度的行形列表与超长字符串。

    为什么需要它：`_slim` 只认 {ok, data:{data:[…]}} 这一种信封，而现网实测
    `sx_ask` 一次回过 223,048 字符——大列表藏在 data.products / data.knowledge 这类
    别的键下，_slim 看不见，结果照旧被 hermes 落盘成 1,500 字符存根，模型等于没看到
    数据只能反复重查（2026-09-21 agent.log 实锤）。
    字符串一律带标记截尾，绝不吐半截坏 JSON。
    """
    if isinstance(node, dict):
        out = {}
        for key, val in node.items():
            if isinstance(val, list) and val and all(isinstance(x, dict) for x in val):
                kept = _slim_rows(val[:MAX_ROWS])
                out[key] = kept
                if len(val) > len(kept):
                    out[key + "_note"] = f"共 {len(val)} 条，这里只回前 {len(kept)} 条"
            else:
                out[key] = _cap_nested(val)
        return out
    if isinstance(node, list):
        return [_cap_nested(x) for x in node]
    if isinstance(node, str) and len(node) > MAX_RESULT_CHARS:
        return node[:MAX_RESULT_CHARS] + "…(内容过长已截尾，需要完整信息请缩小查询范围)"
    return node


def _slim(result):
    """超出出口预算的行形结果：只留前 MAX_ROWS 行、丢掉检索内部字段，并如实标注被裁。

    total 一律保持原值——把 500 改成 20 会教模型对店员少报商品。
    hint 是给模型的下一步指令：不写清"怎么收窄"，模型就会换个词再撞一遍全量。
    aggregate 是**裁之前**在全量行上算的价格档：实测模型拿到 20/174 行会自信地报
    「最低 33 元，再往下没有更便宜的了」，而 174 行里的真最低是 6 元——尾部行被裁掉
    之后，那个事实就永远丢了。所以把结论算好随结果一起给，不指望模型自己去补一跳。
    """
    data = result.get("data") if isinstance(result, dict) else None
    rows = data.get("data") if isinstance(data, dict) else None
    if not isinstance(rows, list) or not rows:
        return result
    if len(json.dumps(result, ensure_ascii=False)) <= MAX_RESULT_CHARS:
        return result
    out = dict(result)
    out_data = dict(data)
    kept = _slim_rows(rows[:MAX_ROWS])
    out_data["data"] = kept
    out_data["returned"] = len(kept)
    out_data["truncated"] = True
    hint = (f"结果太多，这里只回前 {len(kept)} 行（共 {data.get('total', len(rows))} 条）。"
            "别据此报总数以外的清单；要更准就换更具体的规格词或加 min_qty 再查一次。")
    if any(isinstance(r, dict) and "sell_price" in r for r in rows):
        from core.ops import price_band
        agg = price_band(rows, top=3)
        agg.pop("cheapest", None)
        agg["over"] = len(rows)
        out_data["aggregate"] = agg
        hint += ("问「最便宜/最贵/什么价位」一律以 aggregate 为准——它是在全部 "
                 f"{len(rows)} 条上算的，这 {len(kept)} 行的极值不是全量极值。")
    out_data["hint"] = hint
    out["data"] = out_data
    return out


def _bounded(result):
    """工具回给模型的最终一层闸：先按信封裁（_slim），仍超预算就递归兜住其它形状。"""
    slimmed = _slim(result)
    if len(json.dumps(slimmed, ensure_ascii=False)) <= MAX_RESULT_CHARS:
        return slimmed
    return _cap_nested(slimmed)


def tools_call(params):
    name = params.get("name", "")
    op_name = name[3:] if name.startswith("sx_") else name
    args = params.get("arguments") or {}
    entry = OPS.get(op_name)
    if entry and entry.get("write"):
        # I16 红线闸门：写操作经 MCP（AI 自主循环）调用时不出票不执行，
        # 改为出待确认票据——confirm 只能由店员在 APP 发起，AI 结构上无法自确认。
        ticket = run_op("pending_create", {
            "op": op_name, "args": args,
            "summary": f"写操作待确认：{op_name}",
        })
        text = json.dumps({
            "ok": True,
            "gated": True,
            "answer_hint": "该写操作已生成待确认票据，等店员在 APP 点「确认」后才会执行。请如实告知用户：已受理，待确认。",
            "result": ticket,
        }, ensure_ascii=False)
        return {"content": [{"type": "text", "text": text}], "isError": False}
    result = _bounded(run_op(op_name, args))
    text = json.dumps(result, ensure_ascii=False)
    return {"content": [{"type": "text", "text": text}], "isError": not result.get("ok", False)}


def handle(msg):
    method = msg.get("method", "")
    mid = msg.get("id")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": msg.get("params", {}).get("protocolVersion", PROTOCOL_VERSION),
            "capabilities": {"tools": {}},
            "serverInfo": SERVER_INFO,
        }}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": tools_list()}
    if method == "tools/call":
        return {"jsonrpc": "2.0", "id": mid, "result": tools_call(msg.get("params", {}))}
    if mid is not None:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"未知方法: {method}"}}
    return None


def _log(msg):
    """诊断只进 stderr——stdout 是协议通道，混一个字符就会让客户端解帧错位。"""
    sys.stderr.write("[retaildemo-mcp] " + msg + "\n")
    sys.stderr.flush()


def _write(obj):
    """一行帧 = 一条 UTF-8 JSON 字节，绕开文本层的码页。

    Windows 上 sys.stdout 默认码页是 GBK：09-17 就实测过 tools/list 的中文被写成 GBK
    字节（0xce…）让客户端按 UTF-8 解崩；09-20 再实锤 ∅(U+2205) 这类 GBK 没有的字符
    直接把服务写死。写二进制 + 代理字符兜底，任何一帧都崩不掉进程。
    """
    try:
        data = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
    except UnicodeEncodeError:
        # 只有孤立代理字符会走到这儿（上一次读歪的字节被模型原样回抄）：转义成 ASCII 送出
        data = (json.dumps(obj, ensure_ascii=True) + "\n").encode("ascii")
    buf = getattr(sys.stdout, "buffer", None)
    if buf is not None:
        buf.write(data)
        buf.flush()
    else:
        sys.stdout.write(data.decode("utf-8"))
        sys.stdout.flush()


def main():
    # 按 bytes 读、自己解码：MCP 规范规定 stdio 传输是 UTF-8，不能让 Python 拿码页猜。
    for raw in sys.stdin.buffer:
        try:
            line = raw.decode("utf-8").strip()
        except UnicodeDecodeError:
            # 退一步按 surrogateescape 解：宁可回一帧 isError，也不静默丢请求（客户端会干等）
            line = raw.decode("utf-8", "surrogateescape").strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as exc:
            _log(f"skip unparsable frame: {exc}")
            continue
        if not isinstance(msg, dict):
            _log(f"skip non-object frame: {type(msg).__name__}")
            continue
        try:
            resp = handle(msg)
        except Exception as exc:  # noqa: BLE001 —— 一帧的异常只废这一帧
            _log(f"{msg.get('method')} crashed: {type(exc).__name__}: {exc}")
            resp = {"jsonrpc": "2.0", "id": msg.get("id"),
                    "error": {"code": -32603, "message": f"{type(exc).__name__}: {exc}"}}
        if resp is not None:
            _write(resp)


if __name__ == "__main__":
    main()
