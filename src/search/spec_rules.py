"""五金高频规格映射（S4 Step1，纯函数、零依赖）。

正则先行、LLM 只处理长尾（p0-data-foundation-design §2）：
"4分 ↔ 1/2"、"6分 ↔ 3/4"、"1寸 ↔ 1\"" 这类口径在五金行业互认，
A 路检索前用本表做确定性扩展，不耗 LLM 调用。

与 search_server 内既有 SPEC_ALIASES（4分→DN15 的管材公称口径）互补：
本表面向「同义写法互查」，SPEC_ALIASES 面向「行业代号」，两者都进 A 路。
"""

# 双向映射：键→值，成对书写保证正反查都在表内
_SPEC_MAP = {
    "4分": "1/2",
    "1/2": "4分",
    "6分": "3/4",
    "3/4": "6分",
    "1寸": '1"',
    '1"': "1寸",
    "2寸": '2"',
    '2"': "2寸",
}


def normalize_spec(text: str) -> str:
    """整串精确映射（查表归一）；未命中返回原文（strip 后）。"""
    if not text:
        return text
    return _SPEC_MAP.get(text.strip(), text.strip())


def expand_spec_terms(keyword: str) -> list[str]:
    """子串替换扩展：keyword 命中映射键则追加同义写法，去重保序。

    "4分堵头" → ["4分堵头", "1/2堵头"]；无映射原样返回 [keyword]。
    """
    if not keyword:
        return [keyword]
    out = [keyword]
    for k, v in _SPEC_MAP.items():
        if k in keyword:
            alt = keyword.replace(k, v)
            if alt not in out:
                out.append(alt)
    return out
