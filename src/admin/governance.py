# -*- coding: utf-8 -*-
"""I5 网页后台·治理数据读取（纯函数、只读）。

数据源：
- wujin.db：synonym_groups（同义词词典）、usage_stats（查询日志/问了没货）、
  barcode_alias/alias_audit（别名改判留痕）
- data/mirror.db（S13 镜像）：temp_products（串货/无码货台账）、product_supplements（待补全）

红线：全部只读；写操作仅限同义词确认接口且经统一包络+鉴权。
"""
import json
import os
import sqlite3

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WUJIN_DB = os.environ.get("WUJIN_DB_PATH") or os.path.join(_ROOT, "data", "wujin.db")
MIRROR_DB = os.environ.get("MIRROR_DB_PATH") or os.path.join(_ROOT, "data", "mirror.db")


def _ro(path):
    if not os.path.exists(path):
        return None
    return sqlite3.connect(f"file:{os.path.abspath(path).replace(os.sep, '/')}?mode=ro", uri=True)


def synonym_groups(limit=200):
    con = _ro(WUJIN_DB)
    if con is None:
        return []
    try:
        rows = con.execute(
            "SELECT canonical, synonyms_json, category FROM synonym_groups "
            "ORDER BY rowid DESC LIMIT ?", (limit,),
        ).fetchall()
        out = []
        for canonical, sj, cat in rows:
            try:
                syns = json.loads(sj or "[]")
            except Exception:
                syns = []
            out.append({"canonical": canonical, "synonyms": syns, "category": cat})
        return out
    except sqlite3.Error:
        return []
    finally:
        con.close()


def add_synonym(canonical, synonyms, category=""):
    """同义词确认写库（S4 设计的人工确认通道，I5 后台操作入口）。

    canonical 已存在则合并去重；成功返回 True。绝不动 products 等上游数据。
    """
    if not canonical or not synonyms:
        return False
    con = sqlite3.connect(os.path.abspath(WUJIN_DB))
    try:
        con.execute("PRAGMA busy_timeout=5000")
        old = con.execute(
            "SELECT synonyms_json FROM synonym_groups WHERE canonical = ?", (canonical,),
        ).fetchone()
        if old:
            merged = json.loads(old[0] or "[]")
            for s in synonyms:
                if s not in merged:
                    merged.append(s)
            con.execute(
                "UPDATE synonym_groups SET synonyms_json = ? WHERE canonical = ?",
                (json.dumps(merged, ensure_ascii=False), canonical),
            )
        else:
            con.execute(
                "INSERT INTO synonym_groups (canonical, synonyms_json, category) VALUES (?, ?, ?)",
                (canonical, json.dumps(synonyms, ensure_ascii=False), category),
            )
        con.commit()
        return True
    except sqlite3.Error:
        return False
    finally:
        con.close()


def query_log(limit=100, zero_only=False):
    con = _ro(WUJIN_DB)
    if con is None:
        return []
    try:
        where = "WHERE tool_name='search_products'" + (" AND result_count=0" if zero_only else "")
        rows = con.execute(
            f"SELECT id, keyword, result_count, latency_ms, timestamp FROM usage_stats "
            f"{where} ORDER BY id DESC LIMIT ?", (limit,),
        ).fetchall()
        return [
            {"id": r[0], "keyword": r[1], "result_count": r[2],
             "latency_ms": r[3], "timestamp": r[4]}
            for r in rows
        ]
    except sqlite3.Error:
        return []
    finally:
        con.close()


def zero_hit_top(limit=15):
    con = _ro(WUJIN_DB)
    if con is None:
        return []
    try:
        rows = con.execute(
            "SELECT keyword, COUNT(*) c FROM usage_stats "
            "WHERE tool_name='search_products' AND result_count=0 "
            "GROUP BY keyword ORDER BY c DESC LIMIT ?", (limit,),
        ).fetchall()
        return [{"keyword": r[0], "count": r[1]} for r in rows]
    except sqlite3.Error:
        return []
    finally:
        con.close()


def strade_ledger():
    con = _ro(MIRROR_DB)
    if con is None:
        return []
    try:
        rows = con.execute(
            "SELECT temp_code, name, sell_price, strade_price, source_vendor, "
            "reimburse_status, created_at FROM temp_products ORDER BY created_at DESC LIMIT 200",
        ).fetchall()
        return [
            {"temp_code": r[0], "name": r[1], "sell_price": r[2], "strade_price": r[3],
             "source_vendor": r[4], "reimburse_status": r[5], "created_at": r[6]}
            for r in rows
        ]
    except sqlite3.Error:
        return []
    finally:
        con.close()


def pending_custom_queue():
    con = _ro(MIRROR_DB)
    if con is None:
        return []
    try:
        rows = con.execute(
            "SELECT sku, name, brand, spec, note, pending_barcode, updated_at "
            "FROM product_supplements ORDER BY updated_at DESC LIMIT 200",
        ).fetchall()
        return [
            {"sku": r[0], "name": r[1], "brand": r[2], "spec": r[3],
             "note": r[4], "pending_barcode": r[5], "updated_at": r[6]}
            for r in rows
        ]
    except sqlite3.Error:
        return []
    finally:
        con.close()


def alias_recent(limit=50):
    con = _ro(WUJIN_DB)
    if con is None:
        return []
    try:
        rows = con.execute(
            "SELECT barcode, sku, source, created_by, created_at FROM barcode_alias "
            "ORDER BY created_at DESC LIMIT ?", (limit,),
        ).fetchall()
        return [
            {"barcode": r[0], "sku": r[1], "source": r[2],
             "created_by": r[3], "created_at": r[4]}
            for r in rows
        ]
    except sqlite3.Error:
        return []
    finally:
        con.close()
