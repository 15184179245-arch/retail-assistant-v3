"""S11 无售价商品清单 —— 只读查询纯函数（可单测，不依赖服务进程）。

与 S2 的 ``sale_status.py`` 同构：只放纯函数与常量，服务入口负责取连接（``get_db()``），
这样单测不必 import search_server（省去 jieba 词典加载与 load_env 副作用）。

口径（测试反馈 2026-09-05 拍板：售价=0 如实同步如实显示）：
    无售价 = ``IFNULL(sell_price, 0) <= 0``，=0 与 <0 都算，NULL 视同无价。
    ``sale_status`` 为空时**全量计入（含 frozen 呆滞货）**，与
    ``合成测试对照表`` 的 1972 条对账口径一致；
    传 active / frozen / clearable 则只看该状态（供需要只看在售时用）。
"""
import sqlite3

# APP 列表要显示 名称/规格/单位/sku/条码/库存/货位/状态，价格留着做兜底展示
UNPRICED_COLS = ("id, barcode, sku, name, spec, unit, category, brand, "
                 "sell_price, stock_qty, location, sale_status")
UNPRICED_DEFAULT_PAGE_SIZE = 50
UNPRICED_MAX_PAGE_SIZE = 200


def _like_param(kw: str) -> str:
    """关键词 → LIKE 通配参数。

    转义 ``%`` ``_`` ``\\``，避免用户输入的通配符被当成 SQL 通配符（否则搜 ``%`` 会全表命中）。
    """
    escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


def unpriced_where(kw: str = "", sale_status: str = "") -> tuple[str, list]:
    """拼出无售价清单的 WHERE 条件（纯函数，单测直接打这里）。

    Args:
        kw: 关键词，命中 name/spec/sku/barcode 任一子串；空串=不过滤。
        sale_status: 为空=不过滤状态（默认全量，含冻结）；否则按该状态精确过滤。

    Returns:
        ``(WHERE 片段, 绑定参数列表)``，片段不含 ``WHERE`` 关键字本身。
    """
    conds = ["IFNULL(sell_price, 0) <= 0"]
    params: list = []
    if sale_status:
        conds.append("IFNULL(sale_status, 'active') = ?")
        params.append(sale_status)
    if kw:
        like = _like_param(kw)
        conds.append("(name LIKE ? ESCAPE '\\' OR spec LIKE ? ESCAPE '\\'"
                     " OR sku LIKE ? ESCAPE '\\' OR barcode LIKE ? ESCAPE '\\')")
        params.extend([like, like, like, like])
    return " AND ".join(conds), params


def query_unpriced(db: sqlite3.Connection, kw: str = "", page: int = 1,
                   page_size: int = UNPRICED_DEFAULT_PAGE_SIZE,
                   sale_status: str = "") -> dict:
    """查无售价商品清单（只读）。

    Args:
        db: 已打开的 sample.sqlite 连接（需 ``row_factory = sqlite3.Row``）。
        kw / sale_status: 见 :func:`unpriced_where`。
        page: 页码，从 1 起；小于 1 就地夹到 1。
        page_size: 每页条数；小于 1 夹到 1，大于 ``UNPRICED_MAX_PAGE_SIZE`` 夹到上限。

    Returns:
        ``{"items": [ {...}, ... ], "total": int, "page": int, "page_size": int}``
    """
    page = max(1, int(page))
    page_size = min(max(1, int(page_size)), UNPRICED_MAX_PAGE_SIZE)
    where, params = unpriced_where(kw=kw, sale_status=sale_status)
    rows = db.execute(
        f"SELECT {UNPRICED_COLS} FROM products WHERE {where} "
        f"ORDER BY name, id LIMIT ? OFFSET ?",
        params + [page_size, (page - 1) * page_size],
    ).fetchall()
    total = db.execute(f"SELECT COUNT(*) FROM products WHERE {where}", params).fetchone()[0]
    col_names = [c.strip() for c in UNPRICED_COLS.split(",")]
    items = []
    for r in rows:
        if isinstance(r, sqlite3.Row) or hasattr(r, "keys"):
            items.append(dict(r))
        else:
            items.append(dict(zip(col_names, r)))
    return {"items": items, "total": total, "page": page, "page_size": page_size}
