#!/usr/bin/env python3
"""P-IMPORT 收银台插件领单端点回归测试（`router._pos_pending_bills`）。

背景：店里结账仍在管家婆收银端完成，但逐条扫码 + 改价 + 改量太慢。新插件列出 APP
待出库单，选中后把 商品/数量/成交价 灌进收银端自己的购物车，收银员核对完再点结算。
本端点是插件唯一的数据来源，因此契约必须锁死：

- **只读**：调用前后 bills 表逐字节不变（绝不顺手写 recorded/needsSync 之类标记）；
- B12 两态口径：只给「**stage=ready（待出库）** + pending + 未回收」的单，
  且**取消当天限制**（昨天没出完的今天更要能领）；挂账（stage 缺键/draft/脏值）
  一律不出现——老数据不做批量猜测；
- 行项按收银端能直接用的形状给出（sku/barcode/名称/规格/单位/数量/单价/行小计）；
- 无码货（自定义/串货 TMP-）标 importable=false，由插件提示人工处理，不静默丢行；
- 脏数值只容错不篡改（数据铁律 12）：price=None / qty="3" 这类不许抛异常；
- 日期参数强校验保留（防垃圾输入），但不再参与筛选。

运行：python -m unittest gateway.test_pos_pending_bills -v
"""
import json
import os
import sqlite3
import tempfile
import unittest

from gateway import bills_sync, router

FIXED_DAY = "2026-09-19"


def _item(sku="192.0.2.10", barcode="1002140001", name="插头", qty=2, price=3.5, **extra):
    it = {"sku": sku, "barcode": barcode, "productName": name, "spec": "鸿雁T2-10FS",
          "unit": "Pcs", "quantity": qty, "price": price}
    it.update(extra)
    return it


def _bill(bid, *, status="pending", created_at=FIXED_DAY + " 10:00:00", items=None,
          recycled=False, customer="散客", stage="ready"):
    """默认 stage=ready（多数用例要它出现在结果里）；显式传 stage 验证排除分支。"""
    b = {"id": bid, "customer_name": customer, "staff": "本地管理员",
         "created_at": created_at, "updated_at": created_at, "status": status,
         "note": "", "recycled": recycled, "items": items if items is not None else []}
    if stage is not None:
        b["stage"] = stage
    return b


class PosPendingBillsCase(unittest.TestCase):
    def setUp(self):
        self._orig_path = bills_sync.BILLS_DB_PATH
        fd, self.db_path = tempfile.mkstemp(suffix=".db", prefix="pimport_")
        os.close(fd)
        os.remove(self.db_path)                     # 让 init_bills_db 自己建库
        bills_sync.BILLS_DB_PATH = self.db_path
        bills_sync.init_bills_db()

    def tearDown(self):
        bills_sync.BILLS_DB_PATH = self._orig_path
        if os.path.exists(self.db_path):
            os.remove(self.db_path)

    def _write(self, *bills):
        conn = sqlite3.connect(self.db_path)
        for b in bills:
            conn.execute("INSERT OR REPLACE INTO bills (id, data_json) VALUES (?, ?)",
                         (b["id"], json.dumps(b, ensure_ascii=False)))
        conn.commit()
        conn.close()

    def _raw_rows(self):
        conn = sqlite3.connect(self.db_path)
        rows = conn.execute("SELECT id, data_json FROM bills ORDER BY id").fetchall()
        conn.close()
        return rows

    def _get(self, qs="date=" + FIXED_DAY):
        return router._pos_pending_bills(qs)

    # ---------------------------------------------------------------- 筛选
    def test_ready_only_draft_excluded(self):
        """B12 核心口径：只给 stage=ready；挂账（draft/缺键/脏值）与已出库/回收站一律不出现。"""
        self._write(
            _bill("A-ready", items=[_item()]),
            _bill("B-draft", stage="draft", items=[_item()]),
            _bill("C-nostage", stage=None, items=[_item()]),          # 老数据缺键 → draft
            _bill("D-dirtystage", stage="READY", items=[_item()]),    # 脏值折叠回 draft
            _bill("E-paid", status="paid", items=[_item()]),
            _bill("F-recycled", recycled=True, items=[_item()]),
        )
        data = self._get()["data"]
        self.assertEqual([b["id"] for b in data["bills"]], ["A-ready"])
        self.assertEqual(data["bill_count"], 1)

    def test_cross_day_ready_included(self):
        """B12 取消当天限制：跨天的待出库单也要能领（昨天没出完的今天更要出）。"""
        self._write(
            _bill("Y-old-ready", created_at="2026-09-01 09:00:00", items=[_item()]),
        )
        data = self._get()["data"]
        self.assertEqual([b["id"] for b in data["bills"]], ["Y-old-ready"])

    def test_date_param_no_longer_filters(self):
        """date 参数保留（校验+回显）但不再筛单：任意日期参数下 ready 单都在。"""
        self._write(_bill("A-ready", items=[_item()]))
        for qs in ("date=" + FIXED_DAY, "date=2020-01-01", ""):
            data = self._get(qs=qs)["data"]
            self.assertEqual([b["id"] for b in data["bills"]], ["A-ready"], qs)

    def test_missing_status_treated_as_not_pending(self):
        b = _bill("E-nostatus")
        b.pop("status")
        self._write(b)
        self.assertEqual(self._get()["data"]["bill_count"], 0)

    # ---------------------------------------------------------------- 金额
    def test_line_and_bill_totals(self):
        self._write(_bill("M1", items=[_item(qty=3, price=2.5), _item(sku="192.0.2.10",
                                                                     barcode="1001010002",
                                                                     qty=1, price=10)]))
        bill = self._get()["data"]["bills"][0]
        self.assertEqual([i["line_total"] for i in bill["items"]], [7.5, 10.0])
        self.assertEqual(bill["total"], 17.5)
        self.assertEqual(bill["item_count"], 2)
        self.assertEqual(self._get()["data"]["total_amount"], 17.5)

    def test_dirty_numbers_tolerated_not_corrupted(self):
        """脏值按 0 处理并原样列出该行——绝不为「算得出金额」去改数据本身（铁律 12）。"""
        self._write(_bill("M2", items=[_item(qty=None, price=None),
                                       _item(sku="192.0.2.10", barcode="1001010003",
                                             qty="2", price="abc")]))
        items = self._get()["data"]["bills"][0]["items"]
        self.assertEqual(items[0]["qty"], 0.0)
        self.assertEqual(items[0]["line_total"], 0.0)
        self.assertEqual(items[1]["qty"], 2.0)        # 数字字符串可用
        self.assertEqual(items[1]["price"], 0.0)      # 非数字降级为 0，行不丢

    # ---------------------------------------------------------------- 行形状
    def test_unmapped_line_flagged_not_importable(self):
        self._write(_bill("U1", items=[_item(sku="", barcode=""), _item()]))
        items = self._get()["data"]["bills"][0]["items"]
        self.assertFalse(items[0]["importable"])
        self.assertTrue(items[1]["importable"])

    def test_temp_code_passed_through_but_still_not_importable(self):
        """串货/自定义行的 TMP- 码原样透出（插件建档后绑副码要用），但仍不可直接导入。

        TMP- 号段设计上与管家婆 code 体系物理隔离（TempProductRepo.nextCode 注释），
        所以透出 ≠ 放行：importable 判据只看 sku/barcode，不能被 temp_code 抬高。
        """
        self._write(_bill("T1", items=[
            _item(sku="", barcode="", name="串货·空开", temp_code="TMP-20260920-s001"),
            _item(),
        ]))
        items = self._get()["data"]["bills"][0]["items"]
        self.assertEqual(items[0]["temp_code"], "TMP-20260920-s001")
        self.assertFalse(items[0]["importable"])
        self.assertEqual(items[1]["temp_code"], "")      # 老数据无此键 → 空串，不给 None
        self.assertTrue(items[1]["importable"])

    def test_item_fields_are_pos_ready(self):
        self._write(_bill("U2", items=[_item(qty=2, price=3.5)]))
        it = self._get()["data"]["bills"][0]["items"][0]
        self.assertEqual(it["sku"], "192.0.2.10")
        self.assertEqual(it["barcode"], "1002140001")
        self.assertEqual(it["name"], "插头")
        self.assertEqual(it["spec"], "鸿雁T2-10FS")
        self.assertEqual(it["unit"], "Pcs")

    # ---------------------------------------------------------------- 输入校验
    def test_bad_date_rejected(self):
        for bad in ("date=2026-9-1", "date=%27or%271%3D1", "date=2026-09-19;drop", "date=x"):
            res = self._get(qs=bad)
            self.assertFalse(res["success"], bad)
            self.assertEqual(res["error"], "BAD_DATE", bad)

    # ---------------------------------------------------------------- 只读性
    def test_endpoint_never_writes(self):
        self._write(_bill("R1", items=[_item()]), _bill("R2", status="paid", items=[_item()]))
        before = self._raw_rows()
        for qs in ("date=" + FIXED_DAY, "", "date=2026-01-01"):
            self._get(qs=qs)
        self.assertEqual(self._raw_rows(), before, "只读端点不得改动账单库任何一行")

    def test_empty_db_returns_ok_not_500(self):
        res = self._get()
        self.assertTrue(res["success"])
        self.assertEqual(res["data"]["bills"], [])
        self.assertEqual(res["data"]["total_amount"], 0)

    # ---------------------------------------------------------------- 回收单
    def test_recycled_bill_excluded(self):
        """回收站里的待出库单不给：APP「一键恢复」会清 recycled（BillRepo.restore），
        所以正确流程是先恢复、再来领，端点不为回收单开口子。"""
        self._write(_bill("X1", recycled=True, items=[_item()]))
        self.assertEqual(self._get()["data"]["bill_count"], 0)
        self.assertEqual(self._get(qs="date=%s&include_recycled=1" % FIXED_DAY)["data"]
                         ["bill_count"], 0, "不得存在绕过回收站的参数")

    # ---------------------------------------------------------------- 路由登记
    def test_route_registered(self):
        self.assertIn("/api/pos/pending-bills", router.ROUTES_GET_WITH_QS)
        self.assertIn("/pos/pending-bills", router.ROUTES_GET_WITH_QS)


if __name__ == "__main__":
    unittest.main()
