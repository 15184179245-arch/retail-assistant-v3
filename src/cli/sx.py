# -*- coding: utf-8 -*-
"""sx.py — 示例零售 CLI（F4）。python src/cli/sx.py <op> [参数 k=v ...]

示例：
    python src/cli/sx.py status
    python src/cli/sx.py search q=螺钉
    python src/cli/sx.py search q=螺钉 min_qty=1
    python src/cli/sx.py ask message=4分水管接头有哪些
    python src/cli/sx.py checkout items=[{"product_id":123,"qty":2}] staff=老板
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "search"))

from core.ops import OPS, run_op  # noqa: E402

# 中文别名 → op 名
ALIASES = {
    "状态": "status", "查": "search", "搜索": "search", "问": "ask",
    "出库": "checkout", "待办": "todos", "预警": "low_stock",
    "知识": "knowledge_search", "加知识": "knowledge_add",
    "打印": "print", "打签": "print",
    "欠款": "debts", "治理": "governance", "推荐": "reco",
    "识货": "identify", "识图": "identify",
}


def parse_args(argv):
    """把 ['q=螺钉', 'min_qty=1'] 解析成 dict（值尝试 JSON 解析，退化原字符串）。"""
    args = {}
    for a in argv:
        if "=" in a:
            k, _, v = a.partition("=")
            try:
                args[k] = json.loads(v)
            except (json.JSONDecodeError, ValueError):
                args[k] = v
        else:
            args.setdefault("q", a)
            args.setdefault("message", a)
    return args


def render(name, result):
    """人话渲染。"""
    if not result.get("ok"):
        return f"❌ {result.get('error')}"
    data = result.get("data")
    if name == "status":
        return "✅ 网关在线（六服务见 /health）"
    if name == "search":
        rows = data.get("data") or []
        if not rows:
            return "没有找到匹配的商品"
        out = [f"共 {len(rows)} 条："]
        for r in rows[:8]:
            out.append(f"  {r.get('name')} {r.get('spec') or ''} "
                       f"¥{r.get('sell_price')} 库存{r.get('stock_qty')} "
                       f"[{r.get('location') or '无货位'}] sku={r.get('sku')}")
        return "\n".join(out)
    if name == "ask":
        if isinstance(data, dict) and "response" in data:
            prods = data.get("products") or []
            line = data.get("response") or "（无回答）"
            if prods:
                line += "\n相关商品：" + "、".join(p.get("name", "") for p in prods[:5])
            return line
        return json.dumps(data, ensure_ascii=False)[:800]
    if name == "checkout":
        return f"✅ 出库完成：{json.dumps(data, ensure_ascii=False)[:200]}"
    if name == "print":
        inner = (data or {}).get("data") or {}
        if inner.get("success"):
            return f"✅ 打印完成：{inner.get('printed', 0)} 张"
        msg = (data or {}).get("user_message") or (data or {}).get("error")
        return f"❌ 打印失败：{msg or json.dumps(data, ensure_ascii=False)[:300]}"
    if name == "quick_pricing":
        items = (data or {}).get("items") or []
        out = [f"=== 批量报价（共 {len(items)} 项）==="]
        for it in items:
            q, qty = it.get("query"), it.get("qty")
            if it.get("matched_name"):
                flag = " ⚠未定价" if it.get("needs_review") else ""
                out.append(f"✓ {q} ×{qty} = {it.get('matched_name')} "
                           f"¥{it.get('price')} 库{it.get('stock')} [{it.get('sku')}] "
                           f"小计¥{it.get('subtotal')}{flag}")
            else:
                out.append(f"✗ {q} ×{qty} → {it.get('reason') or '未命中'}")
        out.append(f"合计（已定价项）：¥{(data or {}).get('grand_total', 0)}")
        review = (data or {}).get("needs_review") or []
        if review:
            out.append("⚠ 需人工复核：" + "、".join(review))
        return "\n".join(out)
    if name == "todos":
        rows = data.get("data") or []
        return "\n".join(f"  [{t.get('id')}] {t.get('title')}" for t in rows) or "（无待办）"
    if name == "debts":
        out = [f"挂账 {data.get('pending_count', 0)} 单，欠款合计 ¥{data.get('total_outstanding', 0)}"]
        for t in data.get("top") or []:
            out.append(f"  {t.get('name')} ¥{t.get('amount')}")
        if data.get("hint"):
            out.append(f"（{data['hint']}）")
        return "\n".join(out)
    if name == "governance":
        marks = data.get("data_marks") or {}
        return "\n".join([
            f"副码绑定: {data.get('barcode_alias', 0)} 条",
            f"多码裁决记录: {data.get('alias_audit', 0)} 条",
            f"无码标记: {json.dumps(marks, ensure_ascii=False) if marks else '暂无'}",
            f"（{data.get('hint', '')}）",
        ])
    if name == "reco":
        out = []
        for r in data.get("rules") or []:
            out.append(f"配 {r.get('trigger')}：{r.get('reason') or ''}（推荐 {'、'.join(r.get('recommended') or [])}）")
        for it in data.get("items") or []:
            if it.get("matched_name"):
                out.append(f"  {it.get('query')} → {it.get('matched_name')} ¥{it.get('price')} 库存{it.get('stock')} [{it.get('sku')}]")
            else:
                out.append(f"  {it.get('query')} → {it.get('hint') or '未命中'}")
        return "\n".join(out) or "无搭配规则命中"
    if name == "identify":
        out = ["识货候选："]
        for c in data.get("candidates") or []:
            out.append(f"  {c.get('matched_name')} ¥{c.get('price')} 库存{c.get('stock')} [{c.get('sku')}]")
        um = data.get("unmatched") or []
        if um:
            out.append("未命中：" + "、".join(um))
        return "\n".join(out)
    if name == "low_stock":
        rows = data.get("data") or []
        return "\n".join(f"  {r.get('name')} 库存{r.get('stock_qty')}" for r in rows[:10]) or "（无预警）"
    return json.dumps(data, ensure_ascii=False)[:600]


def main(argv):
    if not argv or argv[0] in ("-h", "--help", "帮助"):
        print("用法: sx <操作> [k=v ...]   操作:", ", ".join(sorted(set(
            list(ALIASES) + list(OPS)))))
        return 0
    cmd = ALIASES.get(argv[0], argv[0])
    if cmd not in OPS:
        print(f"未知操作: {cmd}（可用: {', '.join(sorted(OPS))}）")
        return 2
    result = run_op(cmd, parse_args(argv[1:]))
    print(render(cmd, result))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
