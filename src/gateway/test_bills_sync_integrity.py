#!/usr/bin/env python3
"""M1（2026-09-11）账单写库防丢回归测试。

背景：老板反馈「重装 APP 后账单历史全没了」。审计 `src/gateway/bills_sync.py` 发现
上行写入是 `DELETE FROM bills` + 全量重插，两条路径都会吃掉服务器端历史：

① **并发丢更新**：两台设备同时 push，各自「读-改-写」全表，后写的把先写的那批抹掉；
② **读库失败当空库**：`load_bills()` 吞异常返回 `{}`，重插等于把整表清空、只留本次的单。

修复后的契约（本文件锁死）：
- `save_bills` 按 id 逐条 upsert，**永不 DELETE 全表**，不碰入参以外的行；
- `merge_bills` 读库失败 `raise_on_error=True` 直接抛，交由 handler 返回错误；
- `merge_bills` 只写本次赢家，不回写「陈旧的整库快照」覆盖别人刚写的同 id 新数据；
- 时间戳比较先归一化，消除本地格式与旧 ISO 格式混存的字典序误判。

运行：python -m unittest gateway.test_bills_sync_integrity -v
"""
import json
import os
import sqlite3
import tempfile
import unittest

from gateway import bills_sync


def _bill(bid: str, updated_at: str, *, total: float = 1.0, staff: str = "本地管理员") -> dict:
    return {
        "id": bid,
        "customer_name": "散客",
        "total": total,
        "status": "pending",
        "staff": staff,
        "created_at": updated_at,
        "updated_at": updated_at,
        "items": [],
    }


class _BillsDbCase(unittest.TestCase):
    def setUp(self):
        self._orig_path = bills_sync.BILLS_DB_PATH
        fd, self.db_path = tempfile.mkstemp(suffix=".db", prefix="m1_bills_")
        os.close(fd)
        bills_sync.BILLS_DB_PATH = self.db_path
        bills_sync.init_bills_db()

    def tearDown(self):
        bills_sync.BILLS_DB_PATH = self._orig_path
        for suffix in ("", "-wal", "-shm"):
            p = self.db_path + suffix
            if os.path.exists(p):
                try:
                    os.remove(p)
                except OSError:
                    pass

    def rows(self) -> dict:
        con = sqlite3.connect(self.db_path)
        con.row_factory = sqlite3.Row
        out = {r["id"]: json.loads(r["data_json"]) for r in con.execute("SELECT id, data_json FROM bills")}
        con.close()
        return out


class SaveBillsIntegrityTest(_BillsDbCase):
    def test_save_never_deletes_rows_outside_input(self):
        """核心：写 B 不许把已存在的 A 删掉（原 DELETE 全表实现会）。"""
        bills_sync.save_bills({"A": _bill("A", "2026-09-01 10:00:00")})
        bills_sync.save_bills({"B": _bill("B", "2026-09-02 10:00:00")})
        rows = self.rows()
        self.assertIn("A", rows, "写入 B 时把 A 整行删掉了——DELETE 全表回归")
        self.assertIn("B", rows)
        self.assertEqual(2, len(rows))

    def test_upsert_updates_same_id_in_place(self):
        bills_sync.save_bills({"A": _bill("A", "2026-09-01 10:00:00", total=10.0)})
        bills_sync.save_bills({"A": _bill("A", "2026-09-01 11:00:00", total=99.0)})
        rows = self.rows()
        self.assertEqual(1, len(rows))
        self.assertEqual(99.0, rows["A"]["total"])
        self.assertEqual("2026-09-01 11:00:00", rows["A"]["updated_at"])

    def test_save_empty_input_is_noop(self):
        bills_sync.save_bills({"A": _bill("A", "2026-09-01 10:00:00")})
        bills_sync.save_bills({})
        self.assertEqual(["A"], list(self.rows().keys()))


class MergeBillsConcurrencyTest(_BillsDbCase):
    def test_two_devices_push_in_sequence_both_survive(self):
        """两台设备先后 push，各自新增一单，两单都必须活着。"""
        bills_sync.merge_bills([_bill("DEV-A-1", "2026-09-10 09:00:00")])
        bills_sync.merge_bills([_bill("DEV-B-1", "2026-09-10 09:01:00")])
        rows = self.rows()
        self.assertIn("DEV-A-1", rows)
        self.assertIn("DEV-B-1", rows)

    def test_merge_does_not_rewrite_stale_snapshot(self):
        """merge 只写本次赢家：不得把「读到的陈旧全库快照」回写覆盖同行新数据。

        构造：库里 X 已被别人更新到 12:00；我这边的入参里只有 Y。
        merge 后 X 必须还是 12:00，不能被我读到的 09:00 快照回退。
        """
        bills_sync.save_bills({"X": _bill("X", "2026-09-10 12:00:00", total=120.0)})
        stale = bills_sync.load_bills()
        self.assertEqual("2026-09-10 12:00:00", stale["X"]["updated_at"])

        # 模拟并发：另一个进程把 X 推到更新
        bills_sync.save_bills({"X": _bill("X", "2026-09-10 13:00:00", total=130.0)})

        # 我基于陈旧快照推 Y —— 不能顺手把 X 写回 12:00
        bills_sync.save_bills({"Y": _bill("Y", "2026-09-10 10:00:00")})
        rows = self.rows()
        self.assertEqual("2026-09-10 13:00:00", rows["X"]["updated_at"], "陈旧快照覆盖了更新的同行数据")
        self.assertEqual(130.0, rows["X"]["total"])
        self.assertIn("Y", rows)

    def test_merge_skips_older_incoming(self):
        bills_sync.save_bills({"A": _bill("A", "2026-09-10 12:00:00", total=120.0)})
        result = bills_sync.merge_bills([_bill("A", "2026-09-10 11:00:00", total=1.0)])
        self.assertEqual(["A"], result["skipped"])
        self.assertEqual([], result["updated"])
        self.assertEqual(120.0, self.rows()["A"]["total"])

    def test_merge_accepts_newer_incoming(self):
        bills_sync.save_bills({"A": _bill("A", "2026-09-10 11:00:00", total=1.0)})
        result = bills_sync.merge_bills([_bill("A", "2026-09-10 12:00:00", total=120.0)])
        self.assertEqual(["A"], result["updated"])
        self.assertEqual(120.0, self.rows()["A"]["total"])

    def test_mixed_timestamp_formats_compared_correctly(self):
        """旧 ISO 格式（带 T/Z）与本地格式混存时，比较结果必须正确。

        原实现直接比字符串：'2026-07-10 06:44:19' < '2026-07-10T06:44:19.544Z'，
        同一天的本地格式永远被判为「更旧」，会被错误 skip。
        """
        bills_sync.save_bills({"A": _bill("A", "2026-07-10T06:44:19.544Z", total=1.0)})
        # 本地格式的同一天更晚时刻，应判定为更新 → updated
        result = bills_sync.merge_bills([_bill("A", "2026-07-10 08:00:00", total=88.0)])
        self.assertEqual(["A"], result["updated"], "混合时间格式比较失败，新数据被误判为旧")
        self.assertEqual(88.0, self.rows()["A"]["total"])

    def test_merge_ignores_rows_without_id(self):
        result = bills_sync.merge_bills([{"updated_at": "2026-09-10 09:00:00"}])
        self.assertEqual([], result["updated"])
        self.assertEqual([], result["skipped"])


class LoadFailureMustAbortTest(_BillsDbCase):
    def test_load_raises_when_requested(self):
        bills_sync.BILLS_DB_PATH = os.path.dirname(self.db_path)  # 指向目录 → 无法打开
        with self.assertRaises(bills_sync.BillsLoadError):
            bills_sync.load_bills(raise_on_error=True)

    def test_load_soft_mode_keeps_legacy_behaviour(self):
        """只读展示路径保持历史行为（失败返回 {}），不炸页面。"""
        bills_sync.BILLS_DB_PATH = os.path.dirname(self.db_path)
        self.assertEqual({}, bills_sync.load_bills())

    def test_merge_aborts_instead_of_wiping_table(self):
        """核心：读库失败时 merge 必须抛，**绝不能**当成空库继续写。

        原实现会 load→{} → merge → DELETE 全表 + 只插本次的单 = 历史整表灭。
        """
        bills_sync.save_bills({
            "HIST-1": _bill("HIST-1", "2026-08-01 10:00:00"),
            "HIST-2": _bill("HIST-2", "2026-08-02 10:00:00"),
        })
        real_load = bills_sync.load_bills

        def _boom(*_a, **_kw):
            raise bills_sync.BillsLoadError("simulated read failure")

        bills_sync.load_bills = _boom
        try:
            with self.assertRaises(bills_sync.BillsLoadError):
                bills_sync.merge_bills([_bill("NEW-1", "2026-09-11 10:00:00")])
        finally:
            bills_sync.load_bills = real_load

        rows = self.rows()
        self.assertIn("HIST-1", rows, "读库失败后历史被清空——原 DELETE 全表缺陷回归")
        self.assertIn("HIST-2", rows)
        self.assertNotIn("NEW-1", rows, "读库失败时不该写入任何东西")


if __name__ == "__main__":
    unittest.main(verbosity=2)
