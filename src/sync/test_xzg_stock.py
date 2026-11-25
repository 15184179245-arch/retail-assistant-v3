"""xzg_stock 单测：库存状况表解析 + S7 拍板口径 diff + apply 红线。"""
import os
import sqlite3
import unittest
import zipfile
import xml.etree.ElementTree as ET
from io import BytesIO

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from sync.xzg_stock import (  # noqa: E402
    SNAPSHOT_TABLE,
    StockRow,
    apply_stock,
    diff_stock,
    fnum,
    parse_stock_condition,
)

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "fixtures", "stock_sample.xlsx")

SCHEMA = """
CREATE TABLE products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    barcode TEXT, name TEXT, spec TEXT, unit TEXT DEFAULT '个',
    category TEXT, brand TEXT,
    cost_price REAL, sell_price REAL,
    stock_qty INTEGER DEFAULT 0,
    min_stock INTEGER, warehouse TEXT, description TEXT,
    pinyin TEXT, initials TEXT, location TEXT,
    updated_at TEXT, jieba_tokens TEXT, sku TEXT
);
"""


def fixture_blob():
    with open(FIXTURE, "rb") as fh:
        return fh.read()


class 解析规格(unittest.TestCase):
    def test_真实样本列对齐(self):
        rows, bad = parse_stock_condition(fixture_blob())
        self.assertEqual(len(rows), 20, "20 数据行，合计行必须被跳过")
        first = rows[0]
        self.assertEqual(first.sku, "192.0.2.10")
        self.assertEqual(first.barcode, "1001010001")
        self.assertEqual(first.name, "按钮")
        self.assertEqual(first.unit, "Pcs")
        self.assertEqual(first.stock_qty, 4.0)
        self.assertAlmostEqual(first.cost_unit, 13.21)
        self.assertAlmostEqual(first.cost_amount, 52.84)

    def test_缺列直接报错不静默(self):
        # 把表头里的“成本单价”改名，解析必须抛 ValueError
        buf = BytesIO()
        zin = zipfile.ZipFile(BytesIO(fixture_blob()))
        NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
        ss = zin.read("xl/sharedStrings.xml")
        root = ET.fromstring(ss)
        for si in root:
            for t in si.iter(NS + "t"):
                if t.text == "成本单价":
                    t.text = "成本价X"
        new_ss = ET.tostring(root)
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = new_ss if item.filename == "xl/sharedStrings.xml" else zin.read(item.filename)
                zout.writestr(item, data)
        with self.assertRaises(ValueError) as cm:
            parse_stock_condition(buf.getvalue())
        self.assertIn("成本单价", str(cm.exception))

    def test_fnum容错(self):
        self.assertIsNone(fnum(""))
        self.assertIsNone(fnum("abc"))
        self.assertEqual(fnum("1,234.5"), 1234.5)
        self.assertEqual(fnum("0"), 0.0)


def _conn_with_local():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    conn.execute(
        "INSERT INTO products (sku, name, cost_price, sell_price, stock_qty,"
        " min_stock, warehouse, location, jieba_tokens, updated_at)"
        " VALUES ('192.0.2.10','按钮',9.99,25.0,99,5,'总部','A-1-2-3','按钮','2020-01-01 00:00:00')")
    conn.execute(
        "INSERT INTO products (sku, name, cost_price, sell_price, stock_qty,"
        " min_stock, warehouse, location, jieba_tokens)"
        " VALUES ('192.0.2.10','按钮',37.54,60.0,8,2,'总部','A-1-2-4','按钮')")
    conn.execute(
        "INSERT INTO products (sku, name, cost_price, sell_price, stock_qty)"
        " VALUES ('192.0.2.10','按钮盒',NULL,NULL,NULL)")
    conn.execute(
        "INSERT INTO products (sku, name, cost_price, sell_price, stock_qty)"
        " VALUES ('99.本地独有','老货',10.0,20.0,7)")
    conn.commit()
    return conn


ROWS = [
    StockRow("192.0.2.10", cost_unit=13.21, stock_qty=4.0),   # 成本+库存都改
    StockRow("192.0.2.10", cost_unit=37.54, stock_qty=8.0),   # 成本同值(容差内) 库存同值
    StockRow("192.0.2.10", cost_unit=None, stock_qty=None),   # 报表双空
    StockRow("99.报表独有", cost_unit=1.0, stock_qty=2.0),       # 本地无此 sku
]


class 差异规格(unittest.TestCase):
    def setUp(self):
        self.conn = _conn_with_local()
        self.local = {r["sku"]: dict(r) for r in self.conn.execute("select * from products")}

    def tearDown(self):
        self.conn.close()

    def test_成本与库存按拍板口径diff(self):
        diff = diff_stock(self.local, ROWS)
        # 成本：192.0.2.10 变；0002 容差内不变；0003 空跳过；报表独有 sku 本地无不进 changes
        self.assertEqual([c["sku"] for c in diff.cost_changes], ["192.0.2.10"])
        self.assertEqual(diff.cost_skipped_zero, 1)
        # 库存：0001 99→4；0002 同值不变；0003 None→按0（本地为 NULL 也算变化）
        self.assertEqual([c["sku"] for c in diff.stock_changes],
                         ["192.0.2.10", "192.0.2.10"])
        self.assertEqual(diff.stock_null_as_zero, 1)
        self.assertEqual(diff.unmatched_sku, ["99.报表独有"])

    def test_dryrun不写任何字节(self):
        diff = diff_stock(self.local, ROWS)
        before = self.conn.execute(
            "select cost_price, stock_qty, updated_at, min_stock, location, warehouse,"
            " jieba_tokens from products order by sku").fetchall()
        apply_stock(self.conn, diff, ROWS, apply=False)
        after = self.conn.execute(
            "select cost_price, stock_qty, updated_at, min_stock, location, warehouse,"
            " jieba_tokens from products order by sku").fetchall()
        self.assertEqual([tuple(r) for r in before], [tuple(r) for r in after])
        tables = self.conn.execute(
            "select name from sqlite_master where type='table'").fetchall()
        self.assertNotIn(SNAPSHOT_TABLE, [t[0] for t in tables])


class 落库规格(unittest.TestCase):
    def setUp(self):
        self.conn = _conn_with_local()

    def tearDown(self):
        self.conn.close()

    def test_apply覆盖成本库存并留快照(self):
        local = {r["sku"]: dict(r) for r in self.conn.execute("select * from products")}
        diff = diff_stock(local, ROWS)
        result = apply_stock(self.conn, diff, ROWS, apply=True, ts="2026-09-04 06:00:00")

        self.assertEqual(result.cost_updated, 1)
        self.assertEqual(result.stock_updated, 2)
        self.assertEqual(result.snapshot_rows, 4)

        row = dict(self.conn.execute(
            "select * from products where sku='192.0.2.10'").fetchone())
        self.assertAlmostEqual(row["cost_price"], 13.21)   # 报表口径覆盖
        self.assertEqual(row["stock_qty"], 4.0)           # 库存同步
        self.assertEqual(row["sell_price"], 25.0)         # 售价不在本通道，不动
        # 本地自有列零改动
        self.assertEqual(row["min_stock"], 5)
        self.assertEqual(row["location"], "A-1-2-3")
        self.assertEqual(row["warehouse"], "总部")
        self.assertEqual(row["jieba_tokens"], "按钮")

        snap = self.conn.execute(
            "select count(*), max(synced_at) from %s" % SNAPSHOT_TABLE).fetchone()
        self.assertEqual(snap[0], 4)
        self.assertEqual(snap[1], "2026-09-04 06:00:00")

        # 本地独有行不被触碰
        own = dict(self.conn.execute(
            "select * from products where sku='99.本地独有'").fetchone())
        self.assertEqual(own["stock_qty"], 7)
        self.assertAlmostEqual(own["cost_price"], 10.0)

    def test_二次apply幂等(self):
        local = {r["sku"]: dict(r) for r in self.conn.execute("select * from products")}
        diff = diff_stock(local, ROWS)
        apply_stock(self.conn, diff, ROWS, apply=True)
        local2 = {r["sku"]: dict(r) for r in self.conn.execute("select * from products")}
        diff2 = diff_stock(local2, ROWS)
        self.assertEqual(len(diff2.cost_changes), 0)
        self.assertEqual(len(diff2.stock_changes), 0)

    def test_快照表重建不留旧行(self):
        local = {r["sku"]: dict(r) for r in self.conn.execute("select * from products")}
        diff = diff_stock(local, ROWS)
        apply_stock(self.conn, diff, ROWS, apply=True)
        apply_stock(self.conn, diff, ROWS[:1], apply=True)
        cnt = self.conn.execute("select count(*) from %s" % SNAPSHOT_TABLE).fetchone()[0]
        self.assertEqual(cnt, 1, "快照表整表重建，不残留历史行")


class 真实全量形状规格(unittest.TestCase):
    """可选：真实报表在位时跑一次全量解析（缺失则跳过，不拖累门禁）。"""

    def test_全量报表解析(self):
        real = os.path.join(os.path.dirname(FIXTURE), "..", "..", "..",
                            "_reports", "stock_condition.xlsx")
        if not os.path.exists(real):
            self.skipTest("真实报表不在位（_reports 不入库）")
        with open(real, "rb") as fh:
            rows, bad = parse_stock_condition(fh.read())
        self.assertGreater(len(rows), 12000)
        skus = [r.sku for r in rows]
        self.assertEqual(len(skus), len(set(skus)), "sku 必须唯一")
        self.assertTrue(all(not ("合计" in s or s == "") for s in skus))


if __name__ == "__main__":
    unittest.main()
