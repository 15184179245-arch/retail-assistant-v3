#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-11 老板令：设备↔店员绑定登记 + 在线感知。

锁定 device_registry 三条契约：
① checkin：新设备建档（发身份）、老设备刷新绑定/环境快照/last_seen，缺 device_id 拒收；
② list_devices：按 last_seen 倒序，online=窗口内报过到，坏时间戳视为离线；
③ 库级隔离：与 employees 一样落 bills.db（BILLS_DB_PATH 环境注入，测试用临时库）。
"""
import os
import sqlite3
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from gateway import device_registry as dr  # noqa: E402


class DeviceRegistryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sx-device-test-")
        self._orig = dr.BILLS_DB_PATH
        dr.BILLS_DB_PATH = os.path.join(self.tmp, "bills.db")  # 模块属性注入（沿 bills_sync 测试手法）
        dr.init_devices_db()

    def tearDown(self):
        dr.BILLS_DB_PATH = self._orig

    def _conn(self):
        return sqlite3.connect(dr.BILLS_DB_PATH)

    def test_checkin_creates_identity_for_new_device(self):
        r = dr.checkin_device("DEV-A", staff_id="EMP-001", staff_name="孙文龙",
                              model="HONOR K990", app_version="2.5.7")
        self.assertTrue(r["success"])
        self.assertEqual(r["data"]["device_id"], "DEV-A")
        self.assertEqual(r["data"]["bound_staff_id"], "EMP-001")
        rows = dr.list_devices()["data"]
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["staff_name"], "孙文龙")
        self.assertTrue(rows[0]["online"])  # 刚报到必在线

    def test_checkin_refreshes_binding_and_snapshot(self):
        dr.checkin_device("DEV-B", staff_name="老板", model="old-model", app_version="2.5.6")
        dr.checkin_device("DEV-B", staff_id="EMP-002", staff_name="李永",
                          model="new-model", app_version="2.5.7")
        row = dr.list_devices()["data"][0]
        self.assertEqual(row["staff_id"], "EMP-002")       # 绑定可改
        self.assertEqual(row["staff_name"], "李永")
        self.assertEqual(row["model"], "new-model")        # 快照刷新
        self.assertEqual(row["app_version"], "2.5.7")

    def test_checkin_blank_env_fields_keep_old_snapshot(self):
        dr.checkin_device("DEV-C", model="keep-me", app_version="2.5.6")
        dr.checkin_device("DEV-C")  # 只心跳：不带环境字段
        row = dr.list_devices()["data"][0]
        self.assertEqual(row["model"], "keep-me")
        self.assertEqual(row["app_version"], "2.5.6")

    def test_checkin_requires_device_id(self):
        r = dr.checkin_device("")
        self.assertFalse(r["success"])
        self.assertEqual(r.get("code"), "BAD_REQUEST")

    def test_list_orders_by_last_seen_desc(self):
        dr.checkin_device("DEV-OLD")
        time.sleep(1.1)  # 时间戳列秒级精度，隔开先后
        dr.checkin_device("DEV-NEW")
        rows = dr.list_devices()["data"]
        self.assertEqual([r["device_id"] for r in rows], ["DEV-NEW", "DEV-OLD"])

    def test_stale_timestamp_counts_offline(self):
        dr.checkin_device("DEV-STALE")
        conn = self._conn()
        conn.execute("UPDATE devices SET last_seen = '2000-01-01 00:00:00' WHERE device_id='DEV-STALE'")
        conn.commit()
        conn.close()
        row = dr.list_devices()["data"][0]
        self.assertFalse(row["online"])

    def test_garbage_timestamp_counts_offline_not_crash(self):
        dr.checkin_device("DEV-BAD")
        conn = self._conn()
        conn.execute("UPDATE devices SET last_seen = 'not-a-time' WHERE device_id='DEV-BAD'")
        conn.commit()
        conn.close()
        row = dr.list_devices()["data"][0]
        self.assertFalse(row["online"])

    def test_multi_device_snapshot_isolated(self):
        dr.checkin_device("DEV-1", staff_name="孙文龙")
        dr.checkin_device("DEV-2", staff_name="老板")
        names = {r["device_id"]: r["staff_name"] for r in dr.list_devices()["data"]}
        self.assertEqual(names, {"DEV-1": "孙文龙", "DEV-2": "老板"})


if __name__ == "__main__":
    unittest.main()
