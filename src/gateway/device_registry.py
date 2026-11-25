#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""设备登记与在线感知（2026-09-11 老板令：哪台设备绑哪个店员、谁在线，账单数据才好管理）。

devices 表（bills.db，与 employees 同库同管）：
  device_id    客户端首启生成并持久化的 UUID（安装维度身份，重装换新）
  staff_id     绑定的员工 id（名册绑定后非空；离线手输名为空，账单归属仍记显示名）
  staff_name   绑定时的显示名快照（人员册改名不回填，以 checkin 刷新为准）
  model / app_version  排障用环境快照
  last_seen    最近一次报到时间

在线判定：30 分钟内报过到=在线。WorkManager 周期下限 15 分钟，叠加 Doze 抖动，
窗口收得比 15 分钟紧就会假掉线，故定 30 分钟；查看端同时回 last_seen 原值。
"""
import logging
import sqlite3
import time

from gateway.router import BILLS_DB_PATH

ONLINE_WINDOW_SEC = 30 * 60


def init_devices_db():
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                staff_id TEXT NOT NULL DEFAULT '',
                staff_name TEXT NOT NULL DEFAULT '',
                model TEXT NOT NULL DEFAULT '',
                app_version TEXT NOT NULL DEFAULT '',
                first_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.commit()
        logging.info("devices database initialized")
    except Exception as e:
        logging.error(f"devices init failed: {e}")
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def checkin_device(device_id: str, staff_id: str = "", staff_name: str = "",
                   model: str = "", app_version: str = "") -> dict:
    """设备报到：存在则刷新绑定与环境快照 + last_seen，不存在即建档（发身份）。"""
    device_id = str(device_id or "").strip()
    if not device_id:
        return {"success": False, "error": "缺少 device_id", "code": "BAD_REQUEST"}
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH, timeout=10)
        conn.execute(
            """
            INSERT INTO devices (device_id, staff_id, staff_name, model, app_version, last_seen)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(device_id) DO UPDATE SET
                staff_id = excluded.staff_id,
                staff_name = excluded.staff_name,
                model = CASE WHEN excluded.model != '' THEN excluded.model ELSE devices.model END,
                app_version = CASE WHEN excluded.app_version != '' THEN excluded.app_version ELSE devices.app_version END,
                last_seen = CURRENT_TIMESTAMP
            """,
            (device_id, str(staff_id or "").strip(), str(staff_name or "").strip(),
             str(model or "").strip(), str(app_version or "").strip()),
        )
        conn.commit()
        return {
            "success": True,
            "data": {"device_id": device_id, "bound_staff_id": str(staff_id or "").strip(),
                     "server_time": time.strftime("%Y-%m-%d %H:%M:%S")},
        }
    except Exception as e:
        logging.error(f"device checkin failed: {e}")
        return {"success": False, "error": str(e)}
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def list_devices(online_window_sec: int = ONLINE_WINDOW_SEC) -> dict:
    """全量设备清单 + 在线布尔（按 last_seen 窗口），最近报到的排前。"""
    conn = None
    try:
        conn = sqlite3.connect(BILLS_DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT device_id, staff_id, staff_name, model, app_version, first_seen, last_seen "
            "FROM devices ORDER BY last_seen DESC"
        ).fetchall()
        conn.close()
        now = time.time()
        data = []
        for r in rows:
            d = dict(r)
            seen = _ts_key(d.get("last_seen"))
            d["online"] = bool(seen) and (now - seen) <= online_window_sec
            d["online_window_sec"] = online_window_sec
            data.append(d)
        return {"success": True, "data": data}
    except Exception as e:
        logging.error(f"device list failed: {e}")
        return {"success": False, "error": str(e)}
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass


def _ts_key(value) -> float:
    """设备表时间戳 → epoch。SQLite CURRENT_TIMESTAMP 是 UTC，必须按 UTC 解析
    （本地 mktime 在 UTC+8 会凭空差 8 小时，刚报到的设备被判假离线）；坏值返回 0（视为离线）。"""
    import calendar
    s = str(value or "").strip()
    if not s:
        return 0.0
    try:
        return calendar.timegm(time.strptime(s[:19], "%Y-%m-%d %H:%M:%S"))
    except ValueError:
        return 0.0
