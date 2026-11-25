#!/usr/bin/env python3
"""ASR 领域热词表生成器（2026-09-10 老板令「提示词全量覆盖优化」）。

从 wujin.db 真实商品数据自动生成语音识别热词表，替代手写小词表：
  - 类目全量（33 个 category 原样收录）
  - 商品高频词：统计全量 products.jieba_tokens（搜索层同源分词）+ jieba 对去重品名补充分词，
    按词频降序取 top N（默认 300，纯中文 2-8 字，滤规格/数字/停用词）
输出 data/asr_hotwords.txt（asr_server 启动/热加载读取拼进 MiMo system 提示）。
词表可随时重生成：python -m src.asr.gen_asr_prompt [--top 300] [--out data/asr_hotwords.txt]

数据铁律：只读，绝不写 wujin.db。
"""
import argparse
import collections
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))

DEFAULT_DB = os.environ.get("WUJIN_DB_PATH",
                            os.path.join("data", "wujin.db"))
DEFAULT_OUT = os.environ.get("ASR_HOTWORDS_FILE",
                             os.path.join("data", "asr_hotwords.txt"))

# 无检索意义的通用词/助词/量词，不入热词表
STOPWORDS = frozenset("""
的 了 个 件 装 用 上 下 中 大 小 号 型 新 老 只 支 把 条 根 张 台 米 卷 桶 盒 包
系列 规格 类型 其他 其它 通用 专用 配件 附件 产品 商品 正品 批发 零售 厂家 直销
国标 非标 加厚 加长 加大 特价 热卖 畅销 优质 高级 精品 全套 组合 套装 单个 整箱
""".split())

_CJK_WORD_RE = re.compile(r"^[\u4e00-\u9fff]{2,8}$")  # 纯中文 2-8 字

_REPO_ROOT = os.path.realpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def guard_path(p: str, flag: str) -> str:
    """路径守卫（Mimosa L2）：realpath 归一 + 禁 .. 穿越 + 限制在仓库子树内。"""
    rp = os.path.realpath(os.path.abspath(p))
    if rp != _REPO_ROOT and not rp.startswith(_REPO_ROOT + os.sep):
        raise ValueError(f"{flag} 越出仓库目录（禁止路径穿越）: {p}")
    return rp


def tokenize_names(names: list[str]) -> list[str]:
    """jieba 对去重品名分词（补充 jieba_tokens 列未覆盖的新词）。"""
    try:
        import jieba
    except ImportError:
        return []
    words: list[str] = []
    for name in names:
        words.extend(w.strip() for w in jieba.cut(name) if w.strip())
    return words


def collect_tokens(rows: list[tuple], extra_words: list[str]) -> collections.Counter:
    """统计词频：rows = [(jieba_tokens|None,)…]，空格分隔；extra_words 为补充分词。

    过滤：停用词、纯数字/含字母规格词、非纯中文（2-8 字）。
    """
    counter: collections.Counter = collections.Counter()
    raw = (r[0] for r in rows)
    for tok in list(raw) + extra_words:
        for w in (tok or "").split():
            w = w.strip()
            if not w or w in STOPWORDS:
                continue
            if not _CJK_WORD_RE.fullmatch(w):
                continue
            counter[w] += 1
    return counter


def render_hotwords(categories: list[str], counter: collections.Counter,
                    top_n: int = 300) -> str:
    """渲染词表文件内容：# 注释分段，词按词频降序空格分隔。"""
    top = [w for w, _ in counter.most_common(top_n)]
    lines = [
        "# ASR 领域热词表（由 gen_asr_prompt.py 从商品库自动生成，勿手改）",
        "# CATEGORIES 全部商品类目",
        " ".join(c for c in categories if c and c != "未归类"),
        "# HOTWORDS 商品高频词(词频降序)",
        " ".join(top),
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 ASR 领域热词表")
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--top", type=int, default=300, help="高频词收录条数")
    args = ap.parse_args()
    db_path = guard_path(args.db, "--db")
    out_path = guard_path(args.out, "--out")

    conn = sqlite3.connect(db_path)
    try:
        cur = conn.cursor()
        cur.execute("SELECT DISTINCT category FROM products ORDER BY 1")
        categories = [r[0] for r in cur.fetchall()]
        cur.execute("SELECT jieba_tokens FROM products")
        rows = cur.fetchall()
        cur.execute("SELECT DISTINCT name FROM products WHERE name IS NOT NULL")
        names = [r[0] for r in cur.fetchall()]
    finally:
        conn.close()

    counter = collect_tokens(rows, tokenize_names(names))
    content = render_hotwords(categories, counter, args.top)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(content)

    n_hot = content.splitlines()[-1].count(" ") + 1
    print(f"热词表已生成: {args.out}")
    print(f"  类目 {len(categories)} 个（未归类除外 {len([c for c in categories if c != '未归类'])}）")
    print(f"  高频词 top{args.top}（实际 {n_hot}，总词种 {len(counter)}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
