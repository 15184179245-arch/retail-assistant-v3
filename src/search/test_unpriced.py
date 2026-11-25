"""S11 无售价商品清单 —— 过滤口径单测。

只测纯函数（``src/search/unpriced.py``），用临时 sqlite 建库：
不 import search_server（省 jieba 词典加载）、不碰线上 data/wujin.db、不起 HTTP 服务。
"""
import os
import sqlite3
import sys
import tempfile
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _ROOT)
sys.path.insert(0, os.path.join(_ROOT, "src"))

from search.unpriced import (  # noqa: E402
    UNPRICED_COLS,
    UNPRICED_MAX_PAGE_SIZE,
    query_unpriced,
    unpriced_where,
    _like_param,
)

_COLS = ["id", "barcode", "sku", "name", "spec", "unit", "category", "brand",
         "sell_price", "stock_qty", "location", "sale_status"]

# (id, barcode, sku, name, spec, unit, category, brand, sell_price, stock_qty, location, sale_status)
_ROWS = [
    (1, "BC001", "S-001", "零价在售品", "规格甲", "个", "五金杂品类", "默认品牌", 0.0, 5, "A-1-1-1", "active"),
    (2, "BC002", "S-002", "负价冻结品", "规格乙", "个", "灯具照明", "默认品牌", -1.5, 0, "A-1-1-2", "frozen"),
    (3, "BC003", "S-003", "有价正常品", "规格丙", "个", "五金杂品类", "默认品牌", 9.9, 3, "A-1-1-3", "active"),
    (4, "BC004", "S-004", "空价清退品", "规格丁", "个", "水暖管件", "默认品牌", None, 1, "A-1-1-4", "clearable"),
    (5, "BC005", "S-005", "零价无状态品", "规格戊", "个", "五金杂品类", "默认品牌", 0.0, 7, None, None),
]


class _UnpricedDbCase(unittest.TestCase):
    """公共夹具：临时 sqlite 建 5 条样本，测完即毁（绝不碰线上 data/wujin.db）。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = sqlite3.connect(os.path.join(self.tmp.name, "wujin.db"))
        self.db.row_factory = sqlite3.Row
        self.db.execute("CREATE TABLE products (%s)" % ", ".join(
            (c + " INTEGER PRIMARY KEY") if c == "id" else c for c in _COLS))
        self.db.executemany(
            "INSERT INTO products (%s) VALUES (%s)" % (", ".join(_COLS), ", ".join("?" * len(_COLS))),
            _ROWS,
        )
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def names(self, **kw) -> set:
        return {i["name"] for i in query_unpriced(self.db, **kw)["items"]}


class 无售价口径Spec(_UnpricedDbCase):
    """过滤口径：什么算「无售价」，frozen 算不算。"""

    def test_等于0与小于0都算无售价(self):
        names = self.names()
        self.assertIn("零价在售品", names, "sell_price=0 必须计入")
        self.assertIn("负价冻结品", names, "sell_price<0 必须计入")

    def test_有价不计入(self):
        self.assertNotIn("有价正常品", self.names(), "sell_price>0 不该出现")

    def test_空价视同无价(self):
        self.assertIn("空价清退品", self.names(), "sell_price 为 NULL 视同无价")

    def test_冻结品默认计入_与CSV对账口径一致(self):
        """默认全量计入（含 frozen），这是与 _reports/unpriced-20260905.csv 对账的口径。"""
        got = query_unpriced(self.db)
        self.assertEqual(got["total"], 4, "5 条里 4 条无价（含 frozen 与 NULL 状态）")
        self.assertIn("负价冻结品", {i["name"] for i in got["items"]})

    def test_sale_status参数可只看在售(self):
        got = query_unpriced(self.db, sale_status="active")
        self.assertEqual(got["total"], 2, "active 状态 2 条（含 sale_status 为 NULL 的）")
        self.assertNotIn("负价冻结品", {i["name"] for i in got["items"]})

    def test_sale_status参数可只看冻结(self):
        got = query_unpriced(self.db, sale_status="frozen")
        self.assertEqual(got["total"], 1)
        self.assertEqual(got["items"][0]["name"], "负价冻结品")

    def test_sale_status为NULL视同active(self):
        names = {i["name"] for i in query_unpriced(self.db, sale_status="active")["items"]}
        self.assertIn("零价无状态品", names, "sale_status 为 NULL 视同 active")


class 关键词搜索Spec(_UnpricedDbCase):
    def test_按名称搜(self):
        got = query_unpriced(self.db, kw="零价")
        self.assertEqual(got["total"], 2)
        self.assertTrue(all("零价" in i["name"] for i in got["items"]))

    def test_按规格搜(self):
        got = query_unpriced(self.db, kw="规格乙")
        self.assertEqual(got["total"], 1)
        self.assertEqual(got["items"][0]["name"], "负价冻结品")

    def test_按sku搜(self):
        got = query_unpriced(self.db, kw="S-004")
        self.assertEqual(got["total"], 1)
        self.assertEqual(got["items"][0]["name"], "空价清退品")

    def test_按条码搜(self):
        got = query_unpriced(self.db, kw="BC002")
        self.assertEqual(got["total"], 1)
        self.assertEqual(got["items"][0]["name"], "负价冻结品")

    def test_关键词与状态可叠加(self):
        self.assertEqual(query_unpriced(self.db, kw="零价", sale_status="frozen")["total"], 0)
        self.assertEqual(query_unpriced(self.db, kw="零价", sale_status="active")["total"], 2)

    def test_通配符被转义不当通配符(self):
        """搜 % 只能命中名字里真有 % 的，不能退化成全表。"""
        self.assertEqual(query_unpriced(self.db, kw="%")["total"], 0)
        self.assertEqual(query_unpriced(self.db, kw="_")["total"], 0)

    def test_关键词无命中返回空(self):
        got = query_unpriced(self.db, kw="根本不存在的商品名")
        self.assertEqual(got["total"], 0)
        self.assertEqual(got["items"], [])


class 分页与字段Spec(_UnpricedDbCase):
    def test_返回结构四项齐全(self):
        got = query_unpriced(self.db)
        self.assertEqual(set(got.keys()), {"items", "total", "page", "page_size"})
        self.assertEqual(got["page"], 1)
        self.assertEqual(got["page_size"], 50)

    def test_分页切片(self):
        p1 = query_unpriced(self.db, page=1, page_size=2)
        p2 = query_unpriced(self.db, page=2, page_size=2)
        self.assertEqual(len(p1["items"]), 2)
        self.assertEqual(len(p2["items"]), 2)
        self.assertEqual(p1["total"], p2["total"], "total 是满足条件的总数，不是本页条数")
        self.assertEqual(p2["page"], 2)
        ids1 = {i["id"] for i in p1["items"]}
        ids2 = {i["id"] for i in p2["items"]}
        self.assertFalse(ids1 & ids2, "两页不该有重复")

    def test_越界分页夹紧不报错(self):
        got = query_unpriced(self.db, page=0, page_size=0)
        self.assertEqual(got["page"], 1)
        self.assertEqual(got["page_size"], 1)

    def test_page_size上限夹紧(self):
        got = query_unpriced(self.db, page_size=99999)
        self.assertEqual(got["page_size"], UNPRICED_MAX_PAGE_SIZE)

    def test_超出末页返回空但total不变(self):
        got = query_unpriced(self.db, page=99, page_size=2)
        self.assertEqual(got["items"], [])
        self.assertEqual(got["total"], 4)

    def test_字段与DTO契约一致(self):
        item = query_unpriced(self.db, page_size=1)["items"][0]
        expected = [c.strip() for c in UNPRICED_COLS.split(",")]
        self.assertEqual(set(item.keys()), set(expected),
                         "后端 SELECT 列与 Android UnpricedProductItem 字段必须同增同减")


class Where片段Spec(unittest.TestCase):
    def test_无关键词无条件(self):
        where, params = unpriced_where()
        self.assertEqual(where, "IFNULL(sell_price, 0) <= 0")
        self.assertEqual(params, [])

    def test_带状态(self):
        where, params = unpriced_where(sale_status="frozen")
        self.assertIn("IFNULL(sale_status, 'active') = ?", where)
        self.assertEqual(params, ["frozen"])

    def test_带关键词四个占位符(self):
        where, params = unpriced_where(kw="螺钉")
        self.assertEqual(where.count("?"), 4, "name/spec/sku/barcode 四个 LIKE")
        self.assertEqual(params, ["%螺钉%"] * 4)

    def test_like参数转义(self):
        self.assertEqual(_like_param("a%b"), "%a\\%b%")
        self.assertEqual(_like_param("a_b"), "%a\\_b%")
        self.assertEqual(_like_param("a\\b"), "%a\\\\b%")


# 注：此处原有「CSV对账Spec」把线上 data/wujin.db 的无售价总数与
# _reports/unpriced-20260905.csv（S11 2026-09-05 验收快照）行数绑死对账。
# S17 真同步上线后售价由上游合法回填、活库计数必然漂移（2026-09-11 实证 1967≠1972 常红），
# 且与本文件「不碰线上 data/wujin.db」的契约冲突，故撤下该断言；
# 当日快照 CSV 留档 _reports/ 作历史证据，计数口径由上方 无售价口径Spec 覆盖。


if __name__ == "__main__":
    unittest.main()
