# -*- coding: utf-8 -*-
"""src/agent/fallback.py — Hermes 不可达时的本地确定性指令路由（纯函数，好单测）。

原则（手册 G 节）：降级模式只做只读查询；写意图一律拒绝并引导（不产票据，零误操作风险）。
"""
import re

from core.ops import run_op

_INTENT_RULES = [
    ("todos", re.compile(r"待办|要办的事|todo", re.I)),
    ("low_stock", re.compile(r"预警|低库存|缺货|没货的|库存预警")),
    ("knowledge_search", re.compile(r"知识|怎么用|怎么配|替代")),
    ("search", re.compile(r"查|搜索|找|多少钱|价格|有没有|还有|库存|多少")),
]

_WRITE_PATTERN = re.compile(r"出库|扣库存|打印|打签|加知识|删|改价")

_GUIDE = ("老王离线（电脑端未在线），已切换本地指令模式：可查价/查库存预警/看待办。\n"
          "出库、打印等写操作需要老王在线后执行。")


def match_op(message: str):
    """确定性意图匹配（生成式 UI 也用它决定出哪张卡）。

    写意图一律返回 None：只读才允许自动出卡，写操作必须走票据确认（I16 红线）。
    """
    msg = (message or "").strip()
    if _WRITE_PATTERN.search(msg):
        return None
    for op, pat in _INTENT_RULES:
        if pat.search(msg):
            return op
    return None


def route(message: str) -> dict:
    msg = (message or "").strip()
    if _WRITE_PATTERN.search(msg):
        return {"answer": "老王离线，出库/打印等写操作暂不可用，请在电脑端跑 sx 或稍后再试。",
                "tool_trace": [], "degraded": True}
    op = match_op(msg)
    if op:
        r = run_op(op, _args_for(op, msg))
        if not r.get("ok"):
            return {"answer": f"本地查询失败：{r.get('error', '')}",
                    "tool_trace": [{"op": op, "ok": False}],
                    "degraded": True}
        answer = _summarize(op, r.get("data"))
        return {"answer": answer,
                "tool_trace": [{"op": op, "ok": True, "summary": answer[:60]}],
                "degraded": True}
    return {"answer": _GUIDE, "tool_trace": [], "degraded": True}


def _args_for(op: str, msg: str) -> dict:
    if op == "search":
        kw = re.sub(r"[查搜索找一下下个有没还多少价格库存的?？]", "", msg)
        return {"q": kw.strip() or msg}
    if op == "knowledge_search":
        return {"q": msg}
    return {}


def _summarize(op: str, data) -> str:
    if op == "search":
        rows = (data or {}).get("data") if isinstance(data, dict) else None
        rows = rows or []
        if not rows:
            return "没有找到匹配的商品"
        out = [f"命中 {len(rows)} 条："]
        for r in rows[:6]:
            out.append(f"  {r.get('name')} {r.get('spec') or ''} "
                       f"¥{r.get('sell_price')} 库存{r.get('stock_qty')}")
        return "\n".join(out)
    if op == "low_stock":
        rows = (data or {}).get("data") or []
        return f"库存预警 {len(rows)} 项"
    if op == "todos":
        rows = (data or {}).get("data") or []
        return f"待办 {len(rows)} 条"
    return "(结果见上方数据)"
