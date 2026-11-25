"""A/B 双路汇合策略（S4 Step3，纯函数、零依赖、可单测）。

语义（设计说明 §2）：
- A 路（SQL 快通道）有结果 → 立即返回；B 路（LLM 改写）回来后只做消歧提示，不强制重排
- A 路 0 结果 → 用 B 路 rewrite 词逐个兜底重查一次（纯 SQL，不再触发 LLM）
- B 路超时/异常（b_info=None）→ 静默降级：A 结果就是全部，用户无感知，绝不抛错

search_term_fn 由 search_server 注入（闭包，内部 _skip_llm=True 防递归调 LLM），
本模块不 import search_server、不碰数据库——保持纯函数便于测试。
"""
from typing import Any, Callable


def _dedupe(rows_a: list[dict], rows_b: list[dict]) -> list[dict]:
    seen = {r.get("id") for r in rows_a}
    out = list(rows_a)
    for r in rows_b:
        if r.get("id") not in seen:
            seen.add(r.get("id"))
            out.append(r)
    return out


def merge_dual_paths(
    a_results: list[dict[str, Any]],
    b_info: dict | None,
    search_term_fn: Callable[[str], list[dict[str, Any]]],
    limit: int = 50,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """汇合 A/B 两路，返回 (结果列表, extra 字段)。

    b_info: {"rewrite": [...], "verdict": "has"|"none"|...}；None 表示 B 路不可用。
    extra 可能包含：
      disambiguation  {"ask": str, "term": str}  —— B 给了不同词时的"您要找 X？"提示
      llm_fallback    bool —— A 空结果、由 B 扩展词兜底命中
      llm_degraded    bool —— B 不可用（超时/异常/降级）
    """
    extra: dict[str, Any] = {}
    rewrite = [w for w in ((b_info or {}).get("rewrite") or []) if isinstance(w, str) and w.strip()]

    if a_results:
        # A 有结果：B 只做消歧提示（A 结果名不含原词时才有提示价值，避免噪音）
        if b_info and len(rewrite) > 1:
            alt = rewrite[1]
            names = " ".join(str(r.get("name") or "") for r in a_results[:5])
            if alt != rewrite[0] and alt not in names:
                extra["disambiguation"] = {"ask": f"您要找「{alt}」吗？", "term": alt}
        return a_results[:limit], extra

    # A 空结果：B 扩展词逐个兜底重查（纯 SQL，防 LLM 递归由调用方保证）
    if b_info and rewrite:
        combined: list[dict[str, Any]] = []
        for term in rewrite[1:] or rewrite:
            if not term or term == rewrite[0]:
                continue
            combined = _dedupe(combined, search_term_fn(term))
            if len(combined) >= limit:
                break
        if combined:
            extra["llm_fallback"] = True
            return combined[:limit], extra

    # B 也不可用/无扩展：空结果交回上层（never_say_no 兜底与 llm_degraded 标志由调用点决定）
    return [], extra
