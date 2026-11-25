"""拼音检索工具：商品名 → 全拼 / 首字母缩写（纯字母数字化，单一事实源）。

使用方：
- src/search/search_server.py（查询词转换）
- scripts/fix-pinyin.py（重建 wujin.db 的 pinyin/initials 列）

输出约定（2026-09-02 规范化，见 docs/CONVENTIONS.md）：
- pinyin   : 小写字母/数字 token 以单个空格连接，如 "fang shui jiao bu da juan"、"led deng pao"、"tvskzjdq" → "tvs kzjdq"
- initials : 全小写字母/数字连写，如 "fsjbdj"、"leddp"
- 所有标点（中英文）、空白、符号一律剔除——历史数据曾含 "fsjb（dj） " 类噪音导致等值/前缀匹配全部失效
"""
import re
import unicodedata

import pypinyin

_TOKEN_KEEP = re.compile(r"[^a-z0-9]+")


def _clean_token(tok: str) -> str:
    """token 只保留 [a-z0-9]，小写化。"""
    return _TOKEN_KEEP.sub("", tok.lower())


def full_pinyin(text: str) -> str:
    """商品名 → 全拼（空格分隔 token，纯字母数字）。非空保证返回小写。"""
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text.strip())
    if not any("\u4e00" <= ch <= "\u9fff" for ch in text):
        # 纯英文/数字名：压空白即可，标点剔除
        return _clean_token(re.sub(r"\s+", " ", text))
    tokens = (_clean_token(t) for t in pypinyin.lazy_pinyin(text))
    return " ".join(t for t in tokens if t)


def initials(text: str) -> str:
    """商品名 → 首字母缩写（连写，纯字母数字）。汉字 token 取首字母，英文/数字 token 保留整体。"""
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text.strip())
    has_chinese = any("\u4e00" <= ch <= "\u9fff" for ch in text)
    if has_chinese:
        parts = (_clean_token(t) for t in pypinyin.lazy_pinyin(text, style=pypinyin.Style.FIRST_LETTER))
        return "".join(parts)
    return _clean_token(re.sub(r"\s+", "", text))


def clean_query(text: str) -> str:
    """用户查询词 → 与 initials 同规则的连写小写（用于 LIKE 前缀匹配）。"""
    return _clean_token(re.sub(r"\s+", "", text or ""))


# ---------------------------------------------------------------------------
# 口音模糊拼音归一（S14）：平翘舌/前后鼻音/n-l 不分的容错检索。
# 核心不变量：查询侧 canonical_fuzzy(查询全拼) 与列侧 canon_sql(pinyin 列) 用
# 【同一条有序替换链】，两侧同构即可互配——列数据零改动（数据铁律 12）。
# 链序不可调换：声母双拼 zh/ch/sh 必须在鼻音收尾前、n→l 必须最后。
# 注意 SQLite REPLACE 区分大小写，列契约本就是小写（见文件头输出约定）。
# ---------------------------------------------------------------------------
_FUZZY_COLLAPSE = (
    (" ", ""),      # 全拼列是空格分 token，先压平
    ("zh", "z"),    # 平翘舌：z/zh 一组
    ("ch", "c"),    # c/ch 一组
    ("sh", "s"),    # s/sh 一组
    ("ang", "an"),  # 前后鼻音：an/ang 一组
    ("eng", "en"),  # en/eng 一组
    ("ing", "in"),  # in/ing 一组
    ("n", "l"),     # n/l 不分（声母），收尾统一折叠到 l
)


def canonical_fuzzy(text: str) -> str:
    """全拼串（[a-z0-9 ]）→ 口音归一形（纯 [a-z0-9]）。

    折叠是有损单向归一，只用于互配，不还原读音：
    canonical_fuzzy("shui zuan ji") == canonical_fuzzy("suizuanji") == "suizualji"。
    """
    s = (text or "").lower()
    for a, b in _FUZZY_COLLAPSE:
        s = s.replace(a, b)
    return s


def canon_sql(col: str) -> str:
    """生成与 canonical_fuzzy 同序的 SQLite REPLACE 嵌套链（包住列表达式）。

    与 Python 侧共用 _FUZZY_COLLAPSE，防两侧链序漂移。
    """
    expr = col
    for a, b in _FUZZY_COLLAPSE:
        expr = "REPLACE({},'{}','{}')".format(expr, a, b)
    return expr
