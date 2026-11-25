"""S17 网关上游同步作业器单测（注入假 fetcher；不联网、不打真实上游）。

跑法：``python -m unittest discover -s src -p "test_*.py"``（qa.ps1 Gate 2 自动收集）。
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import time
import unittest

_SRC = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

import sqlite3  # noqa: E402

from gateway import upstream_sync as us  # noqa: E402
from utils.pinyintools import full_pinyin, initials  # noqa: E402


def up_item(i: int, sell: float = 10.0, cost: float = 5.0, name: str | None = None) -> dict:
    """仿真上游商品实体（单规格，字段名与真实上游一致）。"""
    return {
        "id": str((i + 1) << 22),
        "code": "10.01.%05d" % i,
        "name": name or ("商品%d" % i),
        "barCode": None,
        "spec": "规格%d" % i,
        "isActive": True,
        "productCategoryIdName": "五金杂品类",
        "brandName": "",
        "baseProductStandardList": [
            {"standardName": "Pcs", "buyingPrice": cost, "retailPrice": sell, "barCode": None}
        ],
    }


def local_row(i: int, sell: float = 10.0, name: str | None = None) -> tuple:
    name = name or ("商品%d" % i)
    spec = "规格%d" % i
    src = "%s %s" % (name, spec)
    return ("10.01.%05d" % i, None, name, spec, None, "Pcs", "五金杂品类", None,
            5.0, sell, 3.0, "熊掌柜同步", full_pinyin(src), initials(src),
            "2026-09-11 00:00:00")


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def make_db(path: str, entries: list) -> None:
    """建最小 products 表并写入基线行（SQL 全部字面量内联；无拼装）。"""
    conn = sqlite3.connect(path)
    try:
        conn.execute(
            "CREATE TABLE products (id INTEGER PRIMARY KEY AUTOINCREMENT, sku TEXT,"
            " barcode TEXT, name TEXT, spec TEXT, model TEXT, unit TEXT, category TEXT, brand TEXT,"
            " cost_price REAL, sell_price REAL, stock_qty REAL, description TEXT,"
            " pinyin TEXT, initials TEXT, updated_at TEXT)")
        for i in entries:
            conn.execute(
                "INSERT INTO products VALUES (NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", local_row(i))
        conn.commit()
    finally:
        conn.close()


class RunOnceTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="s17-root-")
        self.db = os.path.join(self.root, "data", "wujin.db")
        os.makedirs(os.path.dirname(self.db), exist_ok=True)
        make_db(self.db, range(3))

    def _fetch(self, items, blob=None):
        return us.UpstreamFetch(items=items, stock_blob=blob, total_count=len(items),
                                fetch_path="parallel", fetch_seconds=0.01)

    def test_no_changes_zero_write_zero_backup(self):
        """全量一致 → 不写库、不建备份、applied=False。"""
        before = sha256_of(self.db)
        stats = us.run_once(lambda: self._fetch([up_item(i) for i in range(3)]),
                            self.db, self.root)
        self.assertFalse(stats["applied"])
        self.assertEqual(stats["changed_skus"], 0)
        self.assertEqual(before, sha256_of(self.db))
        self.assertEqual([d for d in os.listdir(self.root) if d.startswith("backup-")], [])

    def test_change_backs_up_then_applies(self):
        """有变更 → 先备份（含 SHA256 清单）再落库；变更行按上游口径写入。"""
        items = [up_item(i) for i in range(3)]
        items[1]["baseProductStandardList"][0]["retailPrice"] = 12.5
        stats = us.run_once(lambda: self._fetch(items), self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["updated_rows"], 1)
        self.assertTrue(stats["backup_dir"])
        self.assertTrue(os.path.isfile(os.path.join(stats["backup_dir"], "wujin.db")))
        self.assertTrue(os.path.isfile(os.path.join(stats["backup_dir"], "SHA256SUMS.txt")))

        conn = sqlite3.connect(self.db)
        try:
            row = conn.execute("SELECT sell_price FROM products WHERE sku = ?",
                               ("10.01.00001",)).fetchone()
        finally:
            conn.close()
        self.assertAlmostEqual(row[0], 12.5)

    def test_new_product_inserted(self):
        """上游新增 → 插入新行（inserts 计数 + 落库）。"""
        items = [up_item(i) for i in range(4)]
        stats = us.run_once(lambda: self._fetch(items), self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["inserts"], 1)
        conn = sqlite3.connect(self.db)
        try:
            n = conn.execute("SELECT COUNT(*) FROM products").fetchone()[0]
        finally:
            conn.close()
        self.assertEqual(n, 4)

    def test_fetch_failure_zero_write(self):
        """拉取失败 → 抛错且零写库（无备份、库哈希不变）。"""
        before = sha256_of(self.db)

        def boom():
            raise RuntimeError("上游网络错误")

        with self.assertRaises(RuntimeError):
            us.run_once(boom, self.db, self.root)
        self.assertEqual(before, sha256_of(self.db))
        self.assertEqual([d for d in os.listdir(self.root) if d.startswith("backup-")], [])

    def test_empty_items_refused(self):
        """上游返回空列表 → 拒绝落库（防上游异常清库）。"""
        with self.assertRaises(RuntimeError):
            us.run_once(lambda: self._fetch([]), self.db, self.root)

    def test_unparsable_stock_report_zero_write(self):
        """库存报表无法解析 → 抛错且零写库。"""
        before = sha256_of(self.db)
        with self.assertRaises(Exception):
            us.run_once(lambda: self._fetch([up_item(i) for i in range(3)], blob=b"not-a-zip"),
                        self.db, self.root)
        self.assertEqual(before, sha256_of(self.db))

    def test_missing_db_refused(self):
        """商品库不存在 → 拒绝执行（不静默建空库）。"""
        with self.assertRaises(RuntimeError):
            us.run_once(lambda: self._fetch([up_item(0)]),
                        os.path.join(self.root, "data", "nope.db"), self.root)


class SingleFlightTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="s17-sf-")
        self.db = os.path.join(self.root, "data", "wujin.db")
        os.makedirs(os.path.dirname(self.db), exist_ok=True)
        make_db(self.db, range(2))
        self.status = os.path.join(os.path.dirname(self.db), "upstream-sync-status.json")

    def _slow_fetch(self):
        time.sleep(0.4)
        return us.UpstreamFetch(items=[up_item(i) for i in range(2)], total_count=2,
                                fetch_path="parallel", fetch_seconds=0.4)

    def test_second_start_is_rejected_and_status_persisted(self):
        first = us.start_sync(fetcher=self._slow_fetch, db_path=self.db, root=self.root,
                              status_path=self.status, do_backup=False)
        self.assertTrue(first["starting"])
        second = us.start_sync(fetcher=self._slow_fetch, db_path=self.db, root=self.root,
                               status_path=self.status, do_backup=False)
        self.assertFalse(second["starting"])
        self.assertTrue(second.get("already_running"))

        deadline = time.time() + 10
        while time.time() < deadline:
            if not us.get_status(self.status).get("running"):
                break
            time.sleep(0.1)
        final = us.get_status(self.status)
        self.assertFalse(final["running"])
        self.assertTrue(final["ok"])
        self.assertEqual(final["phase"], us.PHASE_DONE)

        # 磁盘状态文件是重启后的回落源：终态落盘是异步的，允许极短延迟（Windows 上
        # 读写并发时 os.replace 可能瞬时被拒，落盘本身是 best-effort、失败只告警）
        disk = {}
        disk_deadline = time.time() + 5
        while time.time() < disk_deadline:
            try:
                with open(self.status, "r", encoding="utf-8") as fh:
                    disk = json.load(fh)
                if disk.get("ok") is not None:
                    break
            except (OSError, ValueError):
                pass
            time.sleep(0.05)
        self.assertTrue(disk["ok"])
        self.assertFalse(disk["running"])
        self.assertEqual(disk["stats"]["fetched"], 2)

    def test_failed_job_records_error(self):
        def boom():
            raise RuntimeError("模拟上游挂掉")

        us.start_sync(fetcher=boom, db_path=self.db, root=self.root,
                      status_path=self.status, do_backup=False)
        deadline = time.time() + 10
        while time.time() < deadline:
            if not us.get_status(self.status).get("running"):
                break
            time.sleep(0.1)
        final = us.get_status(self.status)
        self.assertFalse(final["ok"])
        self.assertEqual(final["phase"], us.PHASE_FAILED)
        self.assertIn("模拟上游挂掉", final["error"])


class PathGuardTest(unittest.TestCase):
    def test_safe_path_blocks_escape(self):
        root = tempfile.mkdtemp(prefix="s17-guard-")
        with self.assertRaises(ValueError):
            us.safe_path(os.path.join(root, "..", "evil.json"), root)
        with self.assertRaises(ValueError):
            us.safe_path(os.path.join(root, "a", "..", "..", "evil.json"), root)
        self.assertTrue(us.safe_path(os.path.join(root, "ok.json"), root).startswith(
            os.path.realpath(root)))

    def test_backup_dir_stays_inside_root(self):
        root = tempfile.mkdtemp(prefix="s17-guard2-")
        db = os.path.join(root, "data", "wujin.db")
        os.makedirs(os.path.dirname(db), exist_ok=True)
        make_db(db, range(1))
        dest = us.backup_wujin(db, root)
        self.assertTrue(os.path.realpath(dest).startswith(os.path.realpath(root)))


def up_customer(cid: int, name: str = "散客", code: str = "0001", **extra) -> dict:
    """仿真上游客户档案（Selector/Customer/GetAll item，字段名与真实上游一致）。"""
    item = {
        "id": cid, "code": code, "name": name, "fullName": name,
        "pinYinCode": "SK", "tel": "", "tel2": "", "tel3": "",
        "contacts": "", "address": "", "companyCategoryName": "零售客户",
        "remark": None,
    }
    item.update(extra)
    return item


def up_stock_item(i: int, qty: float = 7.0, cost: float = 4.5) -> dict:
    """仿真上游「库存状况表 JSON」行（Report/Stock/StockCondition/Get item）。"""
    return {
        "productId": str((i + 1) << 22),
        "productCode": "10.01.%05d" % i,
        "productName": "商品%d" % i,
        "barCode": None,
        "standardName": "Pcs",
        "stockQty": qty,
        "costPrice": cost,
        "costTotal": qty * cost,
    }


class StockJsonChannelTest(unittest.TestCase):
    """XZG-API-RO：S17 作业的库存通道默认走 JSON（stock_items），xlsx 为回退。"""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="xzg-stockjson-root-")
        self.db = os.path.join(self.root, "data", "wujin.db")
        os.makedirs(os.path.dirname(self.db), exist_ok=True)
        make_db(self.db, range(3))

    def _fetch(self, items, stock_items=None):
        return us.UpstreamFetch(items=items, stock_items=stock_items,
                                total_count=len(items), fetch_path="parallel",
                                fetch_seconds=0.01,
                                extra={"stock_source": "json" if stock_items else "xlsx"})

    def _local(self, sku):
        conn = sqlite3.connect(self.db)
        try:
            return conn.execute(
                "SELECT stock_qty, cost_price FROM products WHERE sku = ?", (sku,)).fetchone()
        finally:
            conn.close()

    def test_json_通道落库存与成本(self):
        """JSON 行喂进作业：库存/成本按上游覆盖，且 stock_source 记为 json。"""
        stats = us.run_once(
            lambda: self._fetch([up_item(i) for i in range(3)],
                                stock_items=[up_stock_item(i, qty=7.0, cost=4.5)
                                             for i in range(3)]),
            self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["stock_source"], "json")
        self.assertEqual(stats["stock_rows"], 3)
        self.assertEqual(stats["stock_updated"], 3)
        self.assertEqual(stats["cost_updated"], 3)
        self.assertEqual(self._local("10.01.00001"), (7.0, 4.5))

    def test_json_无变化零写库(self):
        """库存/成本与本地一致 → 不写库（与 xlsx 通道同规则）。"""
        # 先把本地刷成与上游一致
        us.run_once(
            lambda: self._fetch([up_item(i) for i in range(3)],
                                stock_items=[up_stock_item(i) for i in range(3)]),
            self.db, self.root)
        before = sha256_of(self.db)
        stats = us.run_once(
            lambda: self._fetch([up_item(i) for i in range(3)],
                                stock_items=[up_stock_item(i) for i in range(3)]),
            self.db, self.root)
        self.assertFalse(stats["applied"])
        self.assertEqual(before, sha256_of(self.db))

    def test_解析为0行拒绝落库(self):
        """JSON 返回空行集 → 整体失败零落库（防上游异常刷库存）。"""
        before = sha256_of(self.db)
        with self.assertRaises(Exception):
            us.run_once(lambda: self._fetch([up_item(i) for i in range(3)], stock_items=[]),
                        self.db, self.root)
        self.assertEqual(before, sha256_of(self.db))

    def test_不传库存时跳过库存比对(self):
        """stock_items 与 stock_blob 都为空 → 只比商品，不碰库存列。"""
        stats = us.run_once(lambda: self._fetch([up_item(i) for i in range(3)]),
                            self.db, self.root)
        self.assertFalse(stats["applied"])
        self.assertEqual(stats["stock_rows"], 0)


class CustomerSyncTest(unittest.TestCase):
    """S18 客户档案随真实上游同步作业一起拉取与落库。"""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="s18-root-")
        self.db = os.path.join(self.root, "data", "wujin.db")
        os.makedirs(os.path.dirname(self.db), exist_ok=True)
        make_db(self.db, range(3))

    def _fetch(self, items, customers=None, blob=None):
        return us.UpstreamFetch(items=items, stock_blob=blob, total_count=len(items),
                                fetch_path="parallel", fetch_seconds=0.01,
                                customers=customers or [])

    def _local_customers(self):
        conn = sqlite3.connect(self.db)
        try:
            try:
                rows = conn.execute(
                    "SELECT upstream_id, name, code FROM customers ORDER BY upstream_id").fetchall()
            except sqlite3.OperationalError:
                return None  # 表不存在
        finally:
            conn.close()
        return rows

    def test_customers_applied_with_products(self):
        """客档随商品同批落库：计数 + 表内容 + applied=True。"""
        stats = us.run_once(
            lambda: self._fetch([up_item(i) for i in range(3)],
                                customers=[up_customer(11, name="客户甲", code="0001"),
                                           up_customer(22, name="客户乙", code="0002", tel="139")]),
            self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["customers_fetched"], 2)
        self.assertEqual(stats["customers_inserted"], 2)
        rows = self._local_customers()
        self.assertEqual(rows, [("11", "客户甲", "0001"), ("22", "客户乙", "0002")])
        # 雪花 id 以 TEXT 落库（防 JSON 数值精度丢失）
        conn = sqlite3.connect(self.db)
        try:
            types = [t for (t,) in conn.execute("SELECT typeof(upstream_id) FROM customers")]
        finally:
            conn.close()
        self.assertEqual(set(types), {"text"})

    def test_customer_only_change_still_backs_up_and_applies(self):
        """商品零变更、仅客档有变化 → 照样「先备份后落库」（不漏写客户）。"""
        stats = us.run_once(lambda: self._fetch([up_item(i) for i in range(3)],
                                                customers=[up_customer(11)]),
                            self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["changed_skus"], 0)
        self.assertEqual(stats["customers_inserted"], 1)
        self.assertTrue(stats["backup_dir"])

    def test_customer_no_change_zero_write(self):
        """客档一致 → 零写库不建备份；suspicious 计数照常返回。"""
        fetch = lambda: self._fetch(  # noqa: E731
            [up_item(i) for i in range(3)],
            customers=[up_customer(11), up_customer(22, name="脏行", code="", fullName="")])
        us.run_once(fetch, self.db, self.root)
        stats2 = us.run_once(fetch, self.db, self.root)
        self.assertFalse(stats2["applied"])
        self.assertEqual(stats2["customers_inserted"], 0)
        self.assertEqual(stats2["customers_updated"], 0)
        self.assertEqual(stats2["customers_suspicious"], 2)  # 两条都没电话，只打标不篡改
        # 幂等：表里仍只有 2 行
        self.assertEqual(len(self._local_customers()), 2)

    def test_customer_dirty_row_reported_and_kept(self):
        """空名脏数据原样落库 + suspicious 单列（铁律 12：只容错不篡改）。"""
        stats = us.run_once(
            lambda: self._fetch([up_item(i) for i in range(3)],
                                customers=[up_customer(33, name="", fullName="", code="0003")]),
            self.db, self.root)
        self.assertEqual(stats["customers_suspicious"], 1)
        self.assertEqual(stats["customers_inserted"], 1)
        rows = self._local_customers()
        self.assertEqual(rows, [("33", "", "0003")])

    def test_no_customers_field_still_works(self):
        """旧 fetcher（不带 customers）兼容：客档计数 0，商品照常落库。"""
        items = [up_item(i) for i in range(4)]
        stats = us.run_once(lambda: self._fetch(items), self.db, self.root)
        self.assertTrue(stats["applied"])
        self.assertEqual(stats["customers_fetched"], 0)
        self.assertIn(stats["inserted_rows"], (1,))  # 新增 1 商品


if __name__ == "__main__":
    unittest.main()
