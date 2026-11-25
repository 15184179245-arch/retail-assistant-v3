"""sale_stats 出库频次聚合单测（05 查价页「昨天出 3 件」数据源）。"""
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from search.sale_stats import (  # noqa: E402
    DEFAULT_TZ_OFFSET_MINUTES,
    item_key,
    item_qty,
    load_sale_stats,
    local_day,
    tz_offset_minutes_from_env,
)

TZ = DEFAULT_TZ_OFFSET_MINUTES  # 东八区


class 纯函数Spec(unittest.TestCase):
    def test_商品键优先sku再退条码(self):
        self.assertEqual(item_key({"sku": "10.01", "barcode": "X1"}), "10.01")
        self.assertEqual(item_key({"sku": "", "barcode": "X1"}), "X1")
        self.assertEqual(item_key({"sku": "  ", "barcode": " X1 "}), "X1")
        self.assertEqual(item_key({"barcode": "X1"}), "X1")
        self.assertEqual(item_key({"price": 1}), "", "无键返回空串")
        self.assertEqual(item_key(None), "", "非 dict 不崩")

    def test_件数两套结构加兜底(self):
        self.assertEqual(item_qty({"quantity": 3}), 3.0, "新结构 quantity 优先")
        self.assertEqual(item_qty({"qty": 2}), 2.0, "旧结构 qty 兜底")
        self.assertEqual(item_qty({"quantity": 3, "qty": 9}), 3.0, "两者并存取 quantity")
        self.assertEqual(item_qty({"sku": "a"}), 1.0, "都缺按 1 计（与 BillRepo 一致）")
        self.assertEqual(item_qty({"quantity": "2.5"}), 2.5, "字符串数字可解析")
        self.assertEqual(item_qty({"quantity": "乱码", "qty": 4}), 4.0, "坏 quantity 退 qty")
        self.assertEqual(item_qty(None), 1.0, "非 dict 不崩")

    def test_本地自然日按时区切(self):
        # UTC 2026-09-15 23:30 = 北京 2026-09-16 07:30 → 本地日必须是 16 号
        dt = datetime(2026, 9, 15, 23, 30, tzinfo=timezone.utc)
        self.assertEqual(local_day(dt, TZ), "2026-09-16")
        self.assertEqual(local_day(dt, 0), "2026-09-15", "偏移 0 = 纯 UTC 日")
        # 无时区信息按 UTC 处理，不抛
        self.assertEqual(local_day(datetime(2026, 9, 15, 23, 30), TZ), "2026-09-16")

    def test_时区偏移环境变量容错(self):
        self.assertEqual(tz_offset_minutes_from_env({}), DEFAULT_TZ_OFFSET_MINUTES)
        self.assertEqual(tz_offset_minutes_from_env({"WUJIN_SALE_TZ_OFFSET": ""}),
                         DEFAULT_TZ_OFFSET_MINUTES)
        self.assertEqual(tz_offset_minutes_from_env({"WUJIN_SALE_TZ_OFFSET": "乱码"}),
                         DEFAULT_TZ_OFFSET_MINUTES)
        self.assertEqual(tz_offset_minutes_from_env({"WUJIN_SALE_TZ_OFFSET": "0"}), 0)
        self.assertEqual(tz_offset_minutes_from_env({"WUJIN_SALE_TZ_OFFSET": "-300"}), -300)


class 聚合Spec(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "bills.db")
        conn = sqlite3.connect(self.path)
        conn.execute("create table bills (id TEXT, data_json TEXT,"
                     " created_at TIMESTAMP, updated_at TIMESTAMP)")

        def bill(bid, status, paid_at, items):
            data = {"id": bid, "status": status, "paid_at": paid_at, "items": items}
            conn.execute("insert into bills values (?,?,?,?)",
                         (bid, json.dumps(data), paid_at, paid_at))

        # A：同一天两单（且跨 UTC 日但同本地日）→ 合并 2+1=3
        bill("b1", "paid", "2026-09-15T23:30:00Z", [{"sku": "A", "quantity": 2}])
        bill("b2", "paid", "2026-09-16T01:00:00Z", [{"sku": "A", "quantity": 1}])
        # A：更早的一单（旧结构 qty），不得覆盖最近日
        bill("b3", "paid", "2026-09-10T02:00:00Z", [{"sku": "A", "qty": 5}])
        # 非 paid 不算销售
        bill("b4", "pending", "2026-09-16T03:00:00Z", [{"sku": "B", "quantity": 9}])
        bill("b5", "draft", "2026-09-16T03:00:00Z", [{"sku": "B", "quantity": 9}])
        # 无 sku 退条码键
        bill("b6", "paid", "2026-09-16T03:00:00Z", [{"barcode": "ONLY-BC", "quantity": 4}])
        # 件数两套都缺 → 兜底 1
        bill("b7", "paid", "2026-09-16T03:00:00Z", [{"sku": "C"}])
        # items 不是列表 → 不崩、不产出
        bill("b8", "paid", "2026-09-16T03:00:00Z", "不是列表")
        # 零件：忠实记录，不在后端做显示层裁剪（UI 自行隐藏 qty<=0）
        bill("b9", "paid", "2026-09-16T03:00:00Z", [{"sku": "D", "quantity": 0}])
        # 坏 JSON 行
        conn.execute("insert into bills values ('nul', '{坏json', null, null)")
        conn.commit()
        conn.close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_同日多单合并且取最近日(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertEqual(stats["A"].last_sale_day, "2026-09-16",
                         "UTC 15 号 23:30 + 16 号 01:00 都落在本地 16 号")
        self.assertEqual(stats["A"].qty, 3.0, "同本地日两单合并 2+1，且不被更早的 5 覆盖")

    def test_非paid不算销售(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertNotIn("B", stats, "pending/draft 不算销售")

    def test_无sku退条码键(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertEqual(stats["ONLY-BC"].qty, 4.0)

    def test_缺件数兜底1(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertEqual(stats["C"].qty, 1.0)

    def test_零件忠实记录(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertEqual(stats["D"].qty, 0.0, "后端不裁剪，显示层自行隐藏")

    def test_坏数据不崩且不产出(self):
        stats = load_sale_stats(self.path, tz_offset_minutes=TZ)
        self.assertEqual(sorted(stats.keys()), ["A", "C", "D", "ONLY-BC"],
                         "坏 items / 坏 JSON 既不崩也不产出条目")

    def test_时区偏移影响归日(self):
        """同一份库，切日偏移不同 → 本地日不同（证明时区真的生效）。"""
        utc = load_sale_stats(self.path, tz_offset_minutes=0)
        self.assertEqual(utc["A"].last_sale_day, "2026-09-16",
                         "A 最近一单是 UTC 16 号 01:00，偏移 0 时归 16 号")
        # b1 那单在 UTC 是 15 号：构造只含它的库，验证偏移 0 → 15 号
        only = os.path.join(self.tmp.name, "only.db")
        conn = sqlite3.connect(only)
        conn.execute("create table bills (id TEXT, data_json TEXT,"
                     " created_at TIMESTAMP, updated_at TIMESTAMP)")
        data = {"id": "x", "status": "paid", "paid_at": "2026-09-15T23:30:00Z",
                "items": [{"sku": "E", "quantity": 2}]}
        conn.execute("insert into bills values ('x', ?, null, null)", (json.dumps(data),))
        conn.commit()
        conn.close()
        self.assertEqual(load_sale_stats(only, tz_offset_minutes=TZ)["E"].last_sale_day,
                         "2026-09-16", "东八区归 16 号")
        self.assertEqual(load_sale_stats(only, tz_offset_minutes=0)["E"].last_sale_day,
                         "2026-09-15", "UTC 归 15 号")

    def test_库不存在返回空(self):
        self.assertEqual(load_sale_stats(self.path + ".nope"), {})


if __name__ == "__main__":
    unittest.main()
