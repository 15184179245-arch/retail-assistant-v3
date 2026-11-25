#!/usr/bin/env python3
"""示例零售 - 库存管理服务 (port 18604)
5层货架管理: 楼层 → 分区 → 货架号 → 层 → 货位
"""
import sqlite3, time, os, sys, threading, logging, json, signal, uuid, subprocess, re
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs
from typing import Any
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from utils.errors import ok_response, err_response, setup_logging
from utils.validate import parse_json_body, validate_fields
from utils.logging import set_request_id

DB_PATH = os.environ.get("INVENTORY_DB_PATH", "/data/inventory.db")
START_TIME = time.time()
IMAGES_DIR = os.environ.get("IMAGES_DIR", "/data/images")

_local = threading.local()
_metrics_lock = threading.Lock()
_request_count = 0
_error_count = 0


def _inc_req():
    global _request_count
    with _metrics_lock:
        _request_count += 1

def _inc_err():
    global _error_count
    with _metrics_lock:
        _error_count += 1

def _ok(data, **extra):
    _inc_req()
    return ok_response(data, **extra)

def _err(msg, code="UNKNOWN", user_message="系统异常，请稍后再试"):
    _inc_err()
    return err_response(msg, code, user_message)


def _error_status_for(result: dict) -> int:
    """I19：统一信封里 success=False 的业务失败 → HTTP 4xx，网关日志才分得出成败。

    响应体格式不变（第 1 条技术约定）；APP 端本就判 success 标志，状态码收紧不改变其行为。
    """
    return 200 if isinstance(result, dict) and result.get("success") else 400


def get_db() -> sqlite3.Connection:
    if not hasattr(_local, "conn") or _local.conn is None:
        conn = sqlite3.connect(DB_PATH, timeout=0.5, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA cache_size=-8000")
        conn.execute("PRAGMA mmap_size=268435456")
        conn.execute("PRAGMA temp_store=MEMORY")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA busy_timeout=3000")
        _local.conn = conn
    return _local.conn


# ─── Product cache sync ──────────────────────────────────────

def sync_product_cache():
    """Sync product data from wujin.db to inventory.db periodically."""
    db = None  # I30/A3：except 里要 rollback，先置 None 防源库阶段失败时引用未绑定名
    try:
        src = sqlite3.connect(os.environ.get("WUJIN_DB_PATH", "/data/wujin.db"), timeout=3)
        src.row_factory = sqlite3.Row
        rows = src.execute("SELECT id, name, sku, barcode, spec, sell_price, cost_price, stock_qty, unit, location FROM products").fetchall()
        src.close()

        db = get_db()
        db.execute("BEGIN")
        for r in rows:
            db.execute(
                "INSERT OR REPLACE INTO product_cache (id, name, sku, barcode, spec, sell_price, cost_price, stock_qty, unit, location, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                (r["id"], r["name"], r["sku"] or "", r["barcode"] or "", r["spec"] or "", r["sell_price"] or 0, r["cost_price"] or 0, r["stock_qty"] or 0, r["unit"] or "", r["location"] or "")
            )
        # 2026-09-10 排雷①：上游已删商品从缓存剔除（cache 只增不清曾膨胀到 31765 行
        # vs 上游 12631，货位搜索还能搜到上游已删商品）。派生缓存自净，不碰 wujin.db。
        db.execute("CREATE TEMP TABLE IF NOT EXISTS _sync_ids (id INTEGER PRIMARY KEY)")
        db.execute("DELETE FROM _sync_ids")
        db.executemany("INSERT OR IGNORE INTO _sync_ids VALUES (?)", [(r["id"],) for r in rows])
        cur = db.execute("DELETE FROM product_cache WHERE id NOT IN (SELECT id FROM _sync_ids)")
        pruned = cur.rowcount
        db.commit()
        logging.info(f"Synced {len(rows)} products from wujin.db, pruned {pruned} stale cache rows")
    except Exception as e:
        logging.warning(f"Product sync warning: {e}")
        if db is not None:
            try:
                db.rollback()
            except Exception:
                pass  # 回滚失败=连接已坏，仅兜底不掩盖（bare except 会连 Ctrl+C 一起吞）


# ─── DB init ─────────────────────────────────────────────────

def init_db():
    """Create 5-level location tables. Uses individual executes to avoid lock conflicts."""
    db = get_db()
    db.isolation_level = None  # Manual transaction control
    tables = [
        "CREATE TABLE IF NOT EXISTS inventory_floors (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, description TEXT DEFAULT '', sort_order INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS inventory_zones (id INTEGER PRIMARY KEY AUTOINCREMENT, floor_id INTEGER NOT NULL, name TEXT NOT NULL, description TEXT DEFAULT '', sort_order INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(floor_id, name))",
        "CREATE TABLE IF NOT EXISTS inventory_racks (id INTEGER PRIMARY KEY AUTOINCREMENT, zone_id INTEGER NOT NULL, name TEXT NOT NULL, description TEXT DEFAULT '', sort_order INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(zone_id, name))",
        "CREATE TABLE IF NOT EXISTS inventory_levels (id INTEGER PRIMARY KEY AUTOINCREMENT, rack_id INTEGER NOT NULL, name TEXT NOT NULL, description TEXT DEFAULT '', sort_order INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(rack_id, name))",
        "CREATE TABLE IF NOT EXISTS inventory_cells (id INTEGER PRIMARY KEY AUTOINCREMENT, level_id INTEGER NOT NULL, name TEXT NOT NULL, code TEXT UNIQUE NOT NULL, description TEXT DEFAULT '', sort_order INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(level_id, name))",
        "CREATE TABLE IF NOT EXISTS custom_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE NOT NULL, description TEXT DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS inventory_movements (id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, product_name TEXT DEFAULT '', product_sku TEXT DEFAULT '', change_qty INTEGER NOT NULL, new_stock_qty INTEGER NOT NULL, movement_type TEXT NOT NULL, note TEXT DEFAULT '', operator TEXT DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS product_images (id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, filename TEXT NOT NULL, url TEXT NOT NULL, is_primary INTEGER DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS product_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, location_code TEXT NOT NULL, location_type TEXT NOT NULL DEFAULT '库存', qty INTEGER DEFAULT 0, note TEXT DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS product_cache (id INTEGER PRIMARY KEY, name TEXT DEFAULT '', sku TEXT DEFAULT '', barcode TEXT DEFAULT '', spec TEXT DEFAULT '', sell_price REAL DEFAULT 0, cost_price REAL DEFAULT 0, stock_qty INTEGER DEFAULT 0, unit TEXT DEFAULT '', location TEXT DEFAULT '', updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, note TEXT DEFAULT '', priority TEXT DEFAULT 'medium', status TEXT DEFAULT 'pending', created_by TEXT DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, completed_at TIMESTAMP DEFAULT NULL, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
    ]
    indexes = [
        "CREATE INDEX IF NOT EXISTS idx_movements_product ON inventory_movements(product_id)",
        "CREATE INDEX IF NOT EXISTS idx_movements_time ON inventory_movements(created_at)",
        "CREATE INDEX IF NOT EXISTS idx_cells_code ON inventory_cells(code)",
        "CREATE INDEX IF NOT EXISTS idx_product_images_product ON product_images(product_id)",
        "CREATE INDEX IF NOT EXISTS idx_prodloc_product ON product_locations(product_id)",
        "CREATE INDEX IF NOT EXISTS idx_prodloc_location ON product_locations(location_code)",
        "CREATE INDEX IF NOT EXISTS idx_product_cache_name ON product_cache(name)",
        "CREATE INDEX IF NOT EXISTS idx_product_cache_sku ON product_cache(sku)",
        "CREATE INDEX IF NOT EXISTS idx_product_cache_stock ON product_cache(stock_qty)",
    ]
    
    for sql in tables + indexes:
        for attempt in range(5):
            try:
                db.execute(sql)
                break
            except Exception:
                if attempt < 4:
                    time.sleep(1)
                    continue
                logging.warning(f"Could not execute: {sql[:50]}...")
                break
    
    db.commit()
    logging.info("DB tables ready")
    
    fixed_floors = ["一楼", "二楼", "三楼"]
    for name in fixed_floors:
        db.execute("INSERT OR IGNORE INTO inventory_floors (name, description) VALUES (?, ?)", (name, ""))
    placeholders = ",".join("?" for _ in fixed_floors)
    db.execute(f"DELETE FROM inventory_floors WHERE name NOT IN ({placeholders})", fixed_floors)
    db.commit()
    logging.info(f"Fixed floors ensured: {fixed_floors}")
    
    # Migrate old data
    try:
        _migrate_product_locations(db)
        _migrate_old_locations(db)
    except Exception as e:
        logging.warning(f"Migration warning: {e}")

    # Sync product cache from main DB
    sync_product_cache()

def _migrate_old_locations(db):
    """Migrate old flat location codes (A-1-2-3) into the new hierarchy tables."""
    cnt = db.execute("SELECT COUNT(*) FROM inventory_floors").fetchone()[0]
    if cnt > 0:
        return  # Already migrated

    # Get unique location codes from products
    rows = db.execute("SELECT DISTINCT location FROM product_cache WHERE location IS NOT NULL AND location != ''").fetchall()
    migrated = 0
    for r in rows:
        code = r["location"]
        parts = code.split("-")
        if len(parts) < 2:
            continue
        # Parts are: floor, zone, rack, level, cell (or fewer)
        floor_name = parts[0] if len(parts) > 0 else "默认"
        zone_name = parts[1] if len(parts) > 1 else "默认"
        rack_name = parts[2] if len(parts) > 2 else "默认"
        level_name = parts[3] if len(parts) > 3 else "默认"
        cell_name = parts[4] if len(parts) > 4 else "默认"

        try:
            db.execute("INSERT OR IGNORE INTO inventory_floors (name) VALUES (?)", (floor_name,))
            fid = db.execute("SELECT id FROM inventory_floors WHERE name=?", (floor_name,)).fetchone()[0]
            db.execute("INSERT OR IGNORE INTO inventory_zones (floor_id, name) VALUES (?, ?)", (fid, zone_name))
            zid = db.execute("SELECT id FROM inventory_zones WHERE floor_id=? AND name=?", (fid, zone_name)).fetchone()[0]
            db.execute("INSERT OR IGNORE INTO inventory_racks (zone_id, name) VALUES (?, ?)", (zid, rack_name))
            rid = db.execute("SELECT id FROM inventory_racks WHERE zone_id=? AND name=?", (zid, rack_name)).fetchone()[0]
            db.execute("INSERT OR IGNORE INTO inventory_levels (rack_id, name) VALUES (?, ?)", (rid, level_name))
            lid = db.execute("SELECT id FROM inventory_levels WHERE rack_id=? AND name=?", (rid, level_name)).fetchone()[0]
            db.execute("INSERT OR IGNORE INTO inventory_cells (level_id, name, code) VALUES (?, ?, ?)", (lid, cell_name, code))
            migrated += 1
        except Exception:
            pass
    if migrated:
        db.commit()
        logging.info(f"Migrated {migrated} old locations to hierarchy")


def _migrate_product_locations(db):
    """Migrate products.location field to product_locations table."""
    cnt = db.execute("SELECT COUNT(*) FROM product_locations").fetchone()[0]
    if cnt > 0:
        return  # Already migrated
    rows = db.execute("SELECT id, name, location, stock_qty FROM product_cache WHERE location IS NOT NULL AND location != ''").fetchall()
    migrated = 0
    for r in rows:
        try:
            db.execute(
                "INSERT OR IGNORE INTO product_locations (product_id, location_code, location_type, qty) VALUES (?, ?, '库存', ?)",
                (r["id"], r["location"], r["stock_qty"] or 0)
            )
            migrated += 1
        except Exception:
            pass
    if migrated:
        db.commit()
        logging.info(f"Migrated {migrated} product locations")


# ─── API Functions ────────────────────────────────────────────

# --- Floors ---
def list_floors():
    db = get_db()
    rows = db.execute("SELECT * FROM inventory_floors ORDER BY sort_order, name").fetchall()
    return _ok([dict(r) for r in rows])

def create_floor(name, description=""):
    return _err("楼层已固定为一楼/二楼/三楼，不可添加", "FORBIDDEN")

def delete_floor(name):
    return _err("楼层不可删除", "FORBIDDEN")

# --- Zones ---
def list_zones(floor_id=None):
    db = get_db()
    if floor_id:
        rows = db.execute("SELECT z.*, f.name as floor_name FROM inventory_zones z JOIN inventory_floors f ON z.floor_id=f.id WHERE z.floor_id=? ORDER BY z.sort_order, z.name", (floor_id,)).fetchall()
    else:
        rows = db.execute("SELECT z.*, f.name as floor_name FROM inventory_zones z JOIN inventory_floors f ON z.floor_id=f.id ORDER BY f.sort_order, f.name, z.sort_order, z.name").fetchall()
    return _ok([dict(r) for r in rows])

def create_zone(floor_id, name, description=""):
    db = get_db()
    cursor = db.execute("INSERT OR IGNORE INTO inventory_zones (floor_id, name, description) VALUES (?, ?, ?)", (floor_id, name, description))
    db.commit()
    if cursor.rowcount == 0:
        return _err(f"分区 '{name}' 在该楼层下已存在", "DUPLICATE", "该名称已存在")
    return _ok({"name": name}, message="分区已创建")

def delete_zone(zone_id):
    db = get_db()
    db.execute("DELETE FROM inventory_cells WHERE level_id IN (SELECT id FROM inventory_levels WHERE rack_id IN (SELECT id FROM inventory_racks WHERE zone_id=?))", (zone_id,))
    db.execute("DELETE FROM inventory_levels WHERE rack_id IN (SELECT id FROM inventory_racks WHERE zone_id=?)", (zone_id,))
    db.execute("DELETE FROM inventory_racks WHERE zone_id=?", (zone_id,))
    db.execute("DELETE FROM inventory_zones WHERE id=?", (zone_id,))
    db.commit()
    return _ok({"id": zone_id}, message="分区已删除")

# --- Racks ---
def list_racks(zone_id=None):
    db = get_db()
    if zone_id:
        rows = db.execute("SELECT r.*, z.name as zone_name FROM inventory_racks r JOIN inventory_zones z ON r.zone_id=z.id WHERE r.zone_id=? ORDER BY r.sort_order, r.name", (zone_id,)).fetchall()
    else:
        rows = db.execute("SELECT r.*, z.name as zone_name FROM inventory_racks r JOIN inventory_zones z ON r.zone_id=z.id ORDER BY z.sort_order, z.name, r.sort_order, r.name").fetchall()
    return _ok([dict(r) for r in rows])

def create_rack(zone_id, name, description=""):
    db = get_db()
    cursor = db.execute("INSERT OR IGNORE INTO inventory_racks (zone_id, name, description) VALUES (?, ?, ?)", (zone_id, name, description))
    db.commit()
    if cursor.rowcount == 0:
        return _err(f"货架 '{name}' 在该分区下已存在", "DUPLICATE", "该名称已存在")
    return _ok({"name": name}, message="货架已创建")

def delete_rack(rack_id):
    db = get_db()
    db.execute("DELETE FROM inventory_cells WHERE level_id IN (SELECT id FROM inventory_levels WHERE rack_id=?)", (rack_id,))
    db.execute("DELETE FROM inventory_levels WHERE rack_id=?", (rack_id,))
    db.execute("DELETE FROM inventory_racks WHERE id=?", (rack_id,))
    db.commit()
    return _ok({"id": rack_id}, message="货架已删除")

# --- Levels ---
def list_levels(rack_id=None):
    db = get_db()
    if rack_id:
        rows = db.execute("SELECT l.*, r.name as rack_name FROM inventory_levels l JOIN inventory_racks r ON l.rack_id=r.id WHERE l.rack_id=? ORDER BY l.sort_order, l.name", (rack_id,)).fetchall()
    else:
        rows = db.execute("SELECT l.*, r.name as rack_name FROM inventory_levels l JOIN inventory_racks r ON l.rack_id=r.id ORDER BY r.sort_order, r.name, l.sort_order, l.name").fetchall()
    return _ok([dict(r) for r in rows])

def create_level(rack_id, name, description=""):
    db = get_db()
    cursor = db.execute("INSERT OR IGNORE INTO inventory_levels (rack_id, name, description) VALUES (?, ?, ?)", (rack_id, name, description))
    db.commit()
    if cursor.rowcount == 0:
        return _err(f"层 '{name}' 在该货架下已存在", "DUPLICATE", "该名称已存在")
    return _ok({"name": name}, message="层已创建")

def delete_level(level_id):
    db = get_db()
    db.execute("DELETE FROM inventory_cells WHERE level_id=?", (level_id,))
    db.execute("DELETE FROM inventory_levels WHERE id=?", (level_id,))
    db.commit()
    return _ok({"id": level_id}, message="层已删除")

# --- Cells ---
def list_cells(level_id=None):
    db = get_db()
    if level_id:
        rows = db.execute("SELECT c.*, l.name as level_name FROM inventory_cells c JOIN inventory_levels l ON c.level_id=l.id WHERE c.level_id=? ORDER BY c.sort_order, c.name", (level_id,)).fetchall()
    else:
        rows = db.execute("SELECT c.*, l.name as level_name FROM inventory_cells c JOIN inventory_levels l ON c.level_id=l.id ORDER BY l.sort_order, l.name, c.sort_order, c.name").fetchall()
    return _ok([dict(r) for r in rows])

def create_cell(level_id, name, code, description=""):
    db = get_db()
    cursor = db.execute("INSERT OR IGNORE INTO inventory_cells (level_id, name, code, description) VALUES (?, ?, ?, ?)", (level_id, name, code, description))
    db.commit()
    if cursor.rowcount == 0:
        return _err(f"货位 '{name}' 在该层下已存在", "DUPLICATE", "该名称或编码已存在")
    return _ok({"code": code, "name": name}, message="货位已创建")

def delete_cell(cell_id):
    db = get_db()
    db.execute("DELETE FROM inventory_cells WHERE id=?", (cell_id,))
    db.commit()
    return _ok({"id": cell_id}, message="货位已删除")

# --- Product-Location binding ---
def get_cell_products(code):
    """Get all products assigned to a cell location via product_locations."""
    db = get_db()
    rows = db.execute(
        "SELECT pl.id as pl_id, pl.location_type, pl.qty as loc_qty, pl.note as loc_note, "
        "p.id, p.barcode, p.sku, p.name, p.spec, '' as category, p.sell_price, p.cost_price, p.stock_qty, p.unit, p.location, "
        "(SELECT pi.url FROM product_images pi WHERE pi.product_id=p.id ORDER BY pi.is_primary DESC, pi.created_at DESC LIMIT 1) as image_url "
        "FROM product_locations pl JOIN product_cache p ON pl.product_id=p.id "
        "WHERE pl.location_code=? ORDER BY pl.location_type, p.name",
        (code,)
    ).fetchall()
    return _ok([dict(r) for r in rows], total=len(rows), location=code)

def get_product_locations(product_id):
    """Get all locations where this product is stored."""
    db = get_db()
    rows = db.execute(
        "SELECT pl.id, pl.location_code, pl.location_type, pl.qty, pl.note, pl.created_at, pl.updated_at, "
        "(SELECT description FROM inventory_cells WHERE code=pl.location_code) as cell_description "
        "FROM product_locations pl WHERE pl.product_id=? ORDER BY pl.location_type, pl.location_code",
        (product_id,)
    ).fetchall()
    return _ok([dict(r) for r in rows], total=len(rows))

def update_product_location(pl_id, qty=None, note=None, location_type=None):
    """Update a product-location binding's qty, note, or type."""
    db = get_db()
    row = db.execute("SELECT * FROM product_locations WHERE id=?", (pl_id,)).fetchone()
    if not row:
        return _err("绑定记录不存在", "NOT_FOUND")
    
    updates = []
    params = []
    if qty is not None:
        updates.append("qty=?")
        params.append(int(qty))
    if note is not None:
        updates.append("note=?")
        params.append(note)
    if location_type is not None:
        updates.append("location_type=?")
        params.append(location_type)
    updates.append("updated_at=CURRENT_TIMESTAMP")
    
    if len(updates) > 1:
        params.append(pl_id)
        db.execute(f"UPDATE product_locations SET {', '.join(updates)} WHERE id=?", params)
        db.commit()
    
    return _ok({"id": pl_id}, message="已更新")

def set_product_cell(product_id, cell_code, location_type="库存", qty=0, note=""):
    """Assign a product to a cell location with type and note.
    location_type: '卖场' (showroom) or '库存' (stockroom)"""
    db = get_db()
    product = db.execute("SELECT id, name FROM product_cache WHERE id=?", (product_id,)).fetchone()
    if not product:
        return _err("商品不存在", "NOT_FOUND")
    
    # Check if already exists
    existing = db.execute(
        "SELECT id FROM product_locations WHERE product_id=? AND location_code=? AND location_type=?",
        (product_id, cell_code, location_type)
    ).fetchone()
    
    if existing:
        # Update qty and note
        db.execute(
            "UPDATE product_locations SET qty=?, note=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
            (qty, note, existing["id"])
        )
    else:
        db.execute(
            "INSERT INTO product_locations (product_id, location_code, location_type, qty, note) VALUES (?, ?, ?, ?, ?)",
            (product_id, cell_code, location_type, qty, note)
        )
    
    # Update legacy location field in cache
    db.execute("UPDATE product_cache SET location=? WHERE id=?", (cell_code, product_id))
    db.commit()
    return _ok({
        "product_id": product_id,
        "product_name": product["name"],
        "location_code": cell_code,
        "location_type": location_type
    }, message="商品已绑定库位")

def unset_product_cell(pl_id):
    """Remove a product-location binding by its ID."""
    db = get_db()
    row = db.execute("SELECT product_id, location_code FROM product_locations WHERE id=?", (pl_id,)).fetchone()
    if not row:
        return _err("绑定记录不存在", "NOT_FOUND")
    db.execute("DELETE FROM product_locations WHERE id=?", (pl_id,))
    # Update legacy location field in cache
    remaining = db.execute("SELECT location_code FROM product_locations WHERE product_id=? LIMIT 1", (row["product_id"],)).fetchone()
    if remaining:
        db.execute("UPDATE product_cache SET location=? WHERE id=?", (remaining["location_code"], row["product_id"]))
    else:
        db.execute("UPDATE product_cache SET location='' WHERE id=?", (row["product_id"],))
    db.commit()
    return _ok({"id": pl_id}, message="已解绑")

def search_products_for_location(query, limit=20):
    """Search products that don't have a location or match query, for binding UI."""
    db = get_db()
    like = f"%{query}%"
    rows = db.execute(
        "SELECT id, barcode, sku, name, spec, sell_price, stock_qty, location, '' as images "
        "FROM product_cache WHERE name LIKE ? OR sku LIKE ? OR barcode LIKE ? ORDER BY name LIMIT ?",
        (like, like, like, limit)
    ).fetchall()
    return _ok([dict(r) for r in rows], total=len(rows))

# --- Stock adjustment ---
def adjust_stock(product_id, qty, movement_type="adjust", note="", operator=""):
    db = get_db()
    product = db.execute("SELECT id, name, sku, stock_qty FROM product_cache WHERE id=?", (product_id,)).fetchone()
    if not product:
        return _err("商品不存在", "NOT_FOUND")
    old_qty = product["stock_qty"] or 0
    new_qty = max(0, old_qty + qty)
    db.execute("UPDATE product_cache SET stock_qty=? WHERE id=?", (new_qty, product_id))
    db.execute(
        "INSERT INTO inventory_movements (product_id, product_name, product_sku, change_qty, new_stock_qty, movement_type, note, operator) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (product_id, product["name"], product["sku"] or "", qty, new_qty, movement_type, note, operator)
    )
    db.commit()
    # 同步写入 wujin.db（短超时）。2026-09-09 排雷 B1：此前 3 次重试全败仍按
    # 成功返回，inventory.db 与 wujin.db 从此静默分叉——现在必须显式暴露。
    synced = False
    sync_error = ""
    for _attempt in range(3):
        try:
            wdb = sqlite3.connect(os.environ.get("WUJIN_DB_PATH", "/data/wujin.db"), timeout=5)
            wdb.execute("PRAGMA busy_timeout=5000")
            wdb.execute("UPDATE products SET stock_qty=?, updated_at=datetime('now','localtime') WHERE id=?", (new_qty, product_id))
            wdb.commit()
            wdb.close()
            synced = True
            break
        except Exception as e:
            sync_error = str(e)
            logging.error(f"Failed to sync stock (attempt {_attempt + 1}): {e}")
            try:
                wdb.close()
            except Exception:
                pass
            time.sleep(0.5)
    if not synced:
        logging.error(
            "[adjust_stock] wujin.db 同步 3 次全败，两库已分叉：product_id=%s new_qty=%s err=%s",
            product_id, new_qty, sync_error,
        )
    payload = {
        "product_id": product_id,
        "product_name": product["name"],
        "old_stock": old_qty,
        "new_stock": new_qty,
        "wujin_sync": "ok" if synced else "failed",
    }
    if not synced:
        payload["wujin_sync_error"] = sync_error
    return _ok(payload, message="库存已调整" if synced else "库存已调整（上游管家婆同步失败，待下次同步对齐）")


def checkout_items(items, staff="", note=""):
    """逐项扣减（挂单出库）。2026-09-09 排雷 B2：此前失败项的 data=None 也被
    计入 total 且整体恒 success=True，缺货/错 ID 永远不会在对账时暴露——
    现在成功与失败分列，部分失败时 success=False 并带逐项原因。"""
    results, failed = [], []
    for item in items or []:
        pid = item.get("product_id") if isinstance(item, dict) else None
        try:
            qty = int(item.get("qty", 0)) if isinstance(item, dict) else 0
        except (TypeError, ValueError):
            qty = 0
        if not pid or qty <= 0:
            failed.append({"product_id": pid, "qty": item.get("qty") if isinstance(item, dict) else None, "error": "商品ID或数量无效"})
            continue
        r = adjust_stock(pid, -qty, "sale", note, staff)
        if isinstance(r, dict) and r.get("success"):
            results.append(r.get("data"))
        else:
            reason = "扣减失败"
            if isinstance(r, dict):
                # 业务原因优先取 error（如「商品不存在」），user_message 多为通用话术
                reason = r.get("error") or r.get("user_message") or reason
            failed.append({"product_id": pid, "error": reason})
    n_ok, n_fail = len(results), len(failed)
    resp = ok_response(
        data=results,
        message=("已处理 %d 个商品" % n_ok) if n_fail == 0 else ("成功 %d 个，失败 %d 个" % (n_ok, n_fail)),
        total=n_ok, succeeded=n_ok, failed=failed, failed_count=n_fail,
    )
    if n_fail:
        resp.update({
            "success": False,
            "code": "PARTIAL_CHECKOUT" if n_ok else "CHECKOUT_FAILED",
            "error": "部分商品扣减失败" if n_ok else "全部商品扣减失败",
            "user_message": "有商品未能扣减库存，请查看失败清单",
            "hint": "失败明细见 failed 列表；成功项已入账，请勿整单重试以免重复扣减",
        })
    return resp

# --- Low stock ---
def get_low_stock(threshold=5, offset=0, limit=50, include_zero=False):
    offset, limit = _clamp_page(offset, limit, default_limit=50)
    db = get_db()
    cols = "id, barcode, sku, name, spec, '' as category, sell_price, cost_price, stock_qty, unit, location, '' as images"
    if include_zero:
        rows = db.execute(
            f"SELECT {cols} FROM product_cache WHERE stock_qty < ? AND stock_qty >= 0 ORDER BY stock_qty ASC LIMIT ? OFFSET ?",
            (threshold, limit, offset)
        ).fetchall()
        total = db.execute("SELECT COUNT(*) as c FROM product_cache WHERE stock_qty < ? AND stock_qty >= 0", (threshold,)).fetchone()["c"]
    else:
        rows = db.execute(
            f"SELECT {cols} FROM product_cache WHERE stock_qty > 0 AND stock_qty < ? ORDER BY stock_qty ASC LIMIT ? OFFSET ?",
            (threshold, limit, offset)
        ).fetchall()
        total = db.execute("SELECT COUNT(*) as c FROM product_cache WHERE stock_qty > 0 AND stock_qty < ?", (threshold,)).fetchone()["c"]
    return _ok([dict(r) for r in rows], total=total, offset=offset, limit=limit, threshold=threshold)

def get_zero_stock(offset=0, limit=50):
    offset, limit = _clamp_page(offset, limit, default_limit=50)
    db = get_db()
    cols = "id, barcode, sku, name, spec, '' as category, sell_price, cost_price, stock_qty, unit, location, '' as images"
    rows = db.execute(
        f"SELECT {cols} FROM product_cache WHERE stock_qty = 0 OR stock_qty IS NULL ORDER BY name ASC LIMIT ? OFFSET ?",
        (limit, offset)
    ).fetchall()
    total = db.execute("SELECT COUNT(*) as c FROM product_cache WHERE stock_qty = 0 OR stock_qty IS NULL").fetchone()["c"]
    return _ok([dict(r) for r in rows], total=total, offset=offset, limit=limit)


def _qs_int(qs, key, default):
    """查询串整数容错（2026-09-11 bug猎杀）：垃圾值回默认，原 int() 直接 ValueError→500。"""
    try:
        return int(qs.get(key, [default])[0])
    except (TypeError, ValueError):
        return default


def _clamp_page(offset, limit, default_limit=100, max_limit=500):
    """分页参数夹紧（2026-09-11 bug猎杀）：负数/非数/超大值归位。

    sqlite 的 `LIMIT -1` 语义是"无限行"——不夹负数时一个 `?limit=-1` 请求
    就能逼服务吐全表；`offset=-9e18` 也能把查询打挂。
    """
    try:
        offset = max(0, int(offset))
    except (TypeError, ValueError):
        offset = 0
    try:
        limit = int(limit)
    except (TypeError, ValueError):
        limit = default_limit
    return offset, max(1, min(limit, max_limit))


def get_data_anomalies(category=None, offset=0, limit=100):
    """Query wujin.db for data anomalies: 一品多码, 价格未设置, 价格异常, 条码缺失."""
    # 2026-09-11 bug猎杀：原实现 wdb 只在正常路径 close（任一查询抛异常即漏连接），
    # counts 循环还每类新开 wdb2——改为全程复用 wdb + finally 兜底关闭。
    offset, limit = _clamp_page(offset, limit, default_limit=100)
    wdb = None
    try:
        wdb = sqlite3.connect(os.environ.get("WUJIN_DB_PATH", "/data/wujin.db"), timeout=3)
        wdb.row_factory = sqlite3.Row

        results = {}

        # 1) 一品多码: same name, different SKU
        if not category or category == "duplicate_name":
            rows = wdb.execute("""
                SELECT p1.name, p1.sku as sku1, p1.barcode as barcode1,
                       p2.sku as sku2, p2.barcode as barcode2,
                       p1.sell_price as price1, p2.sell_price as price2
                FROM products p1
                JOIN products p2 ON p1.name = p2.name AND p1.sku < p2.sku
                ORDER BY p1.name LIMIT ? OFFSET ?
            """, (limit, offset)).fetchall()
            results["duplicate_name"] = [dict(r) for r in rows]

        # 2) 价格未设置
        if not category or category == "no_price":
            rows = wdb.execute("""
                SELECT id, sku, barcode, name, sell_price, cost_price, stock_qty
                FROM products WHERE sell_price IS NULL OR sell_price = 0
                ORDER BY name LIMIT ? OFFSET ?
            """, (limit, offset)).fetchall()
            results["no_price"] = [dict(r) for r in rows]

        # 3) 价格异常 (天价或负价)
        if not category or category == "price_anomaly":
            rows = wdb.execute("""
                SELECT id, sku, barcode, name, sell_price, cost_price, stock_qty
                FROM products WHERE sell_price < 0 OR sell_price > 999999
                ORDER BY sell_price DESC LIMIT ? OFFSET ?
            """, (limit, offset)).fetchall()
            results["price_anomaly"] = [dict(r) for r in rows]

        # 4) 条码缺失
        if not category or category == "no_barcode":
            rows = wdb.execute("""
                SELECT id, sku, barcode, name, sell_price, cost_price, stock_qty
                FROM products WHERE barcode IS NULL OR barcode = ''
                ORDER BY name LIMIT ? OFFSET ?
            """, (limit, offset)).fetchall()
            results["no_barcode"] = [dict(r) for r in rows]

        # 5) 进价未设置
        if not category or category == "no_cost":
            rows = wdb.execute("""
                SELECT id, sku, barcode, name, sell_price, cost_price, stock_qty
                FROM products WHERE cost_price IS NULL OR cost_price = 0
                ORDER BY name LIMIT ? OFFSET ?
            """, (limit, offset)).fetchall()
            results["no_cost"] = [dict(r) for r in rows]

        # Count totals（复用同一连接）
        counts = {}
        for cat in ["duplicate_name", "no_price", "price_anomaly", "no_barcode", "no_cost"]:
            if category and category != cat:
                continue
            if cat == "duplicate_name":
                c = wdb.execute("SELECT COUNT(*) FROM (SELECT p1.name FROM products p1 JOIN products p2 ON p1.name=p2.name AND p1.sku<p2.sku GROUP BY p1.name)").fetchone()[0]
            elif cat == "no_price":
                c = wdb.execute("SELECT COUNT(*) FROM products WHERE sell_price IS NULL OR sell_price=0").fetchone()[0]
            elif cat == "price_anomaly":
                c = wdb.execute("SELECT COUNT(*) FROM products WHERE sell_price<0 OR sell_price>999999").fetchone()[0]
            elif cat == "no_barcode":
                c = wdb.execute("SELECT COUNT(*) FROM products WHERE barcode IS NULL OR barcode=''").fetchone()[0]
            elif cat == "no_cost":
                c = wdb.execute("SELECT COUNT(*) FROM products WHERE cost_price IS NULL OR cost_price=0").fetchone()[0]
            counts[cat] = c

        return {"success": True, "data": results, "counts": counts, "offset": offset, "limit": limit}
    except Exception as e:
        return {"success": False, "error": f"数据审计查询失败：{e}"}
    finally:
        if wdb is not None:
            try:
                wdb.close()
            except Exception:
                pass



# --- Full hierarchy tree ---
def get_full_tree():
    """Return the full location hierarchy as a nested tree."""
    db = get_db()
    tree = []
    floors = db.execute("SELECT * FROM inventory_floors ORDER BY sort_order, name").fetchall()
    for f in floors:
        floor_node = dict(f)
        floor_node["children"] = []
        zones = db.execute("SELECT * FROM inventory_zones WHERE floor_id=? ORDER BY sort_order, name", (f["id"],)).fetchall()
        for z in zones:
            zone_node = dict(z)
            zone_node["children"] = []
            racks = db.execute("SELECT * FROM inventory_racks WHERE zone_id=? ORDER BY sort_order, name", (z["id"],)).fetchall()
            for r in racks:
                rack_node = dict(r)
                rack_node["children"] = []
                levels = db.execute("SELECT * FROM inventory_levels WHERE rack_id=? ORDER BY sort_order, name", (r["id"],)).fetchall()
                for lv in levels:
                    lv_node = dict(lv)
                    lv_node["children"] = []
                    cells = db.execute("SELECT c.*, (SELECT COUNT(*) FROM product_cache WHERE location=c.code) as product_count FROM inventory_cells c WHERE c.level_id=? ORDER BY c.sort_order, c.name", (lv["id"],)).fetchall()
                    for c in cells:
                        c_node = dict(c)
                        lv_node["children"].append(c_node)
                    rack_node["children"].append(lv_node)
                zone_node["children"].append(rack_node)
            floor_node["children"].append(zone_node)
        tree.append(floor_node)

    # Add custom locations section
    custom_rows = db.execute("SELECT c.*, (SELECT COUNT(*) FROM product_cache WHERE location='CUSTOM-' || c.name) as product_count FROM custom_locations c ORDER BY c.name").fetchall()
    if custom_rows:
        custom_section = {"id": -1, "name": "自定义位置", "description": "未上架的非固定位置", "children": []}
        for c in custom_rows:
            c_node = dict(c)
            c_node["code"] = f"CUSTOM-{c['name']}"
            c_node["product_count"] = db.execute("SELECT COUNT(*) FROM product_cache WHERE location=?", (f"CUSTOM-{c['name']}",)).fetchone()[0]
            custom_section["children"].append(c_node)
        tree.append(custom_section)
    return _ok(tree)

# --- Health ---
def health_check():
    db = get_db()
    pc = db.execute("SELECT COUNT(*) FROM product_cache").fetchone()[0]
    fc = db.execute("SELECT COUNT(*) FROM inventory_floors").fetchone()[0]
    cc = db.execute("SELECT COUNT(*) FROM inventory_cells").fetchone()[0]
    up = int(time.time() - START_TIME)
    h, m, s = up // 3600, (up % 3600) // 60, up % 60
    return _ok({"status": "healthy", "uptime": f"{h}h {m}m {s}s", "products": pc, "floors": fc, "cells": cc, "requests_total": _request_count})


# --- Custom Locations ---
def list_custom_locations():
    db = get_db()
    rows = db.execute(
        "SELECT c.*, (SELECT COUNT(*) FROM product_cache WHERE location='CUSTOM-' || c.name) as product_count "
        "FROM custom_locations c ORDER BY c.name"
    ).fetchall()
    return _ok([dict(r) for r in rows])

def create_custom_location(name, description=""):
    db = get_db()
    code = f"CUSTOM-{name}"
    db.execute("INSERT OR IGNORE INTO custom_locations (name, description) VALUES (?, ?)", (name, description))
    db.commit()
    return _ok({"name": name, "code": code}, message="自定义货位已创建")

def delete_custom_location(name):
    db = get_db()
    code = f"CUSTOM-{name}"
    # Clear products assigned to this custom location
    db.execute("UPDATE product_cache SET location='' WHERE location=?", (code,))
    db.execute("DELETE FROM custom_locations WHERE name=?", (name,))
    db.commit()
    return _ok({"name": name}, message="自定义货位已删除")


# --- Product Images ---

def _ensure_images_dir():
    os.makedirs(IMAGES_DIR, exist_ok=True)


def _f6c(p, base=None):
    """F6 路径守卫：realpath 归一 + 根前缀 + .. 分量显式检查，返回值即落盘路径。"""
    import os as _os
    _root = _os.path.realpath(base or _os.environ.get("retaildemo_PATH_BASE") or _os.getcwd())
    _rp = _os.path.normpath(p)
    if not _os.path.isabs(_rp):
        _rp = _os.path.normpath(_os.path.join(_root, _rp))
    try:
        if _os.path.commonpath([_rp, _root]) != _root:
            raise ValueError("path outside sandbox: " + _rp)
    except ValueError:
        if not _rp.startswith(_root + _os.sep):
            raise ValueError("path outside sandbox: " + _rp)
    if ".." in _os.path.relpath(_rp, _root).split(_os.sep):
        raise ValueError("path traversal blocked: " + _rp)
    return _rp

def upload_product_image(product_id, image_data_b64, filename="photo.jpg"):
    """Upload a base64-encoded image for a product."""
    try:
        if not product_id:
            return _err("缺少 product_id", "BAD_REQUEST")
        if not image_data_b64:
            return _err("缺少图片数据", "BAD_REQUEST")
        
        _ensure_images_dir()
        
        # Decode base64
        import base64
        # 2026-09-11 老板实测修复：APP 端发的是 dataURL 形式（"data:image/jpeg;base64,xxxx"），
        # 原实现整串 b64decode → 解出乱码 → 魔数校验必失败 → 永远报「仅支持 JPEG/PNG 图片」，
        # 但 HTTP 仍 200，照片永远留在手机「待上传」。两种形式都收，前缀存在则剥离。
        raw_b64 = image_data_b64
        if isinstance(raw_b64, str) and raw_b64.startswith("data:") and "," in raw_b64:
            raw_b64 = raw_b64.split(",", 1)[1]
        image_bytes = base64.b64decode(raw_b64)

        # I19：大小与魔数校验（对齐 agent_bridge.save_image 的 F9 口径）——仅收 JPEG/PNG，≤8MB
        if len(image_bytes) > 8 * 1024 * 1024:
            return _err("图片超过 8MB 上限", "BAD_REQUEST", "图片太大，请重拍或压缩后再传")
        if not (image_bytes[:3] == b"\xff\xd8\xff" or image_bytes[:8] == b"\x89PNG\r\n\x1a\n"):
            return _err("仅支持 JPEG/PNG 图片", "BAD_REQUEST", "只支持 JPG/PNG 格式的照片")

        # Generate unique filename
        ext = os.path.splitext(filename)[1] or ".jpg"
        if not re.fullmatch(r"[.][A-Za-z0-9]{1,5}", ext):
            ext = ".jpg"  # F6: ext whitelist blocks path fragments
        unique_name = "%d_%s%s" % (int(product_id), uuid.uuid4().hex[:8], ext)
        _img_root = os.path.normpath(IMAGES_DIR)
        try:
            filepath = _f6c(os.path.join(_img_root, unique_name), base=_img_root)  # F6 path guard：返回值即落盘路径
        except ValueError:
            return _err("非法图片路径", "BAD_REQUEST")  # F6 path guard
        
        # Save file
        filepath = os.path.realpath(filepath)  # F6: sink 前规范化
        with open(filepath, "wb") as f:
            f.write(image_bytes)
        
        # Record in database
        url = f"/images/{unique_name}"
        db = get_db()
        # Check if this is the first image for this product
        existing = db.execute("SELECT COUNT(*) as cnt FROM product_images WHERE product_id=?", (product_id,)).fetchone()
        is_primary = 1 if existing["cnt"] == 0 else 0
        
        db.execute(
            "INSERT INTO product_images (product_id, filename, url, is_primary) VALUES (?, ?, ?, ?)",
            (product_id, unique_name, url, is_primary)
        )
        db.commit()
        
        return _ok({"product_id": product_id, "url": url, "filename": unique_name, "is_primary": is_primary}, message="图片已上传")
    except Exception as e:
        return _err(str(e))


def get_product_images(product_id):
    """Get all images for a product."""
    try:
        if not product_id:
            return _err("缺少 product_id", "BAD_REQUEST")
        db = get_db()
        rows = db.execute("SELECT id, url, filename, is_primary, created_at FROM product_images WHERE product_id=? ORDER BY is_primary DESC, created_at DESC", (product_id,)).fetchall()
        return _ok([dict(r) for r in rows])
    except Exception as e:
        return _err(str(e))


def delete_product_image(image_id):
    """Delete a product image."""
    try:
        if not image_id:
            return _err("缺少 image_id", "BAD_REQUEST")
        db = get_db()
        row = db.execute("SELECT product_id, filename FROM product_images WHERE id=?", (image_id,)).fetchone()
        if not row:
            return _err("图片不存在", "NOT_FOUND")
        
        # Delete file
        filepath = os.path.join(IMAGES_DIR, row["filename"])
        if os.path.isfile(filepath):
            os.remove(filepath)
        
        db.execute("DELETE FROM product_images WHERE id=?", (image_id,))
        db.commit()
        return _ok({"image_id": image_id}, message="图片已删除")
    except Exception as e:
        return _err(str(e))


def get_all_products_with_images(query="", limit=50):
    """Get products with their primary image URL, for inventory display."""
    db = get_db()
    like = f"%{query}%"
    rows = db.execute(
        "SELECT p.id, p.barcode, p.sku, p.name, p.spec, '' as category, p.sell_price, p.cost_price, p.stock_qty, p.unit, p.location, "
        "(SELECT pi.url FROM product_images pi WHERE pi.product_id=p.id ORDER BY pi.is_primary DESC, pi.created_at DESC LIMIT 1) as image_url "
        "FROM product_cache p WHERE p.name LIKE ? OR p.sku LIKE ? OR p.barcode LIKE ? ORDER BY p.name LIMIT ?",
        (like, like, like, limit)
    ).fetchall()
    return _ok([dict(r) for r in rows])


# ─── Todo Functions ────────────────────────────────────────────
def list_todos(status=None):
    db = get_db()
    if status:
        rows = db.execute("SELECT * FROM todos WHERE status=? ORDER BY CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, created_at DESC", (status,)).fetchall()
    else:
        rows = db.execute("SELECT * FROM todos ORDER BY CASE WHEN status='completed' THEN 1 ELSE 0 END, CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, created_at DESC").fetchall()
    return _ok([dict(r) for r in rows])

def create_todo(title, note="", priority="medium", created_by=""):
    if not title or not title.strip():
        return _err("标题不能为空")
    db = get_db()
    db.execute("INSERT INTO todos (title, note, priority, created_by) VALUES (?, ?, ?, ?)", (title.strip(), note, priority, created_by))
    db.commit()
    return _ok({"title": title.strip()}, message="待办已创建")

def update_todo(id, title=None, note=None, priority=None):
    db = get_db()
    row = db.execute("SELECT * FROM todos WHERE id=?", (id,)).fetchone()
    if not row:
        return _err("待办不存在", "NOT_FOUND")
    updates = []
    params = []
    if title is not None:
        updates.append("title=?")
        params.append(title)
    if note is not None:
        updates.append("note=?")
        params.append(note)
    if priority is not None:
        updates.append("priority=?")
        params.append(priority)
    updates.append("updated_at=CURRENT_TIMESTAMP")
    if updates:
        params.append(id)
        db.execute(f"UPDATE todos SET {', '.join(updates)} WHERE id=?", params)
        db.commit()
    return _ok({"id": id}, message="已更新")

def toggle_todo(id):
    db = get_db()
    row = db.execute("SELECT * FROM todos WHERE id=?", (id,)).fetchone()
    if not row:
        return _err("待办不存在", "NOT_FOUND")
    if row["status"] == "pending":
        db.execute("UPDATE todos SET status='completed', completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?", (id,))
        msg = "已完成"
    else:
        db.execute("UPDATE todos SET status='pending', completed_at=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?", (id,))
        msg = "已重新打开"
    db.commit()
    return _ok({"id": id, "status": "pending" if row["status"] == "completed" else "completed"}, message=msg)

def delete_todo(id):
    db = get_db()
    db.execute("DELETE FROM todos WHERE id=?", (id,))
    db.commit()
    return _ok({"id": id}, message="已删除")


# ─── HTTP Handler ──────────────────────────────────────────────

class ThreadedInventoryHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True

class InventoryHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_json(self, data, status=200, request_id=""):
        if request_id and isinstance(data, dict):
            data["_request_id"] = request_id
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def _parse_path(self):
        p = urlparse(self.path)
        return p.path.rstrip("/"), parse_qs(p.query)

    def _db_execute(self, sql, params=None):
        return get_db().execute(sql, params or [])

    def do_OPTIONS(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        self._send_json({}, request_id=self.request_id)

    def do_GET(self):
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        path, qs = self._parse_path()

        # Serve uploaded images
        if path.startswith("/images/") or path.startswith("/api/inventory/images/"):
            filename = path.split("/")[-1]
            filepath = os.path.join(IMAGES_DIR, filename)
            if os.path.isfile(filepath):
                with open(filepath, "rb") as f:
                    data = f.read()
                ext = os.path.splitext(filename)[1].lower()
                mime = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}.get(ext.lstrip("."), "application/octet-stream")
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "max-age=86400")
                self.end_headers()
                self.wfile.write(data)
                return
            self._send_json({"success": False, "error": "图片不存在"}, 404, request_id=self.request_id)
            return

        if path == "/health":
            return self._send_json(health_check(), request_id=self.request_id)

        # Full hierarchy tree
        if path in ("/api/inventory/tree", "/tree"):
            return self._send_json(get_full_tree(), request_id=self.request_id)

        # Floors
        if path in ("/api/inventory/floors", "/floors"):
            return self._send_json(list_floors(), request_id=self.request_id)

        # Zones
        if path in ("/api/inventory/zones", "/zones"):
            fid = qs.get("floor_id", [None])[0]
            return self._send_json(list_zones(int(fid) if fid else None), request_id=self.request_id)

        # Racks
        if path in ("/api/inventory/racks", "/racks"):
            zid = qs.get("zone_id", [None])[0]
            return self._send_json(list_racks(int(zid) if zid else None), request_id=self.request_id)

        # Levels
        if path in ("/api/inventory/levels", "/levels"):
            rid = qs.get("rack_id", [None])[0]
            return self._send_json(list_levels(int(rid) if rid else None), request_id=self.request_id)

        # Cells
        if path in ("/api/inventory/cells", "/cells"):
            lid = qs.get("level_id", [None])[0]
            return self._send_json(list_cells(int(lid) if lid else None), request_id=self.request_id)

        # Cell products
        if path.startswith("/api/inventory/cell/") or path.startswith("/cell/"):
            code = path.split("/")[-1]
            if code in ("products",):
                return self._send_json(get_cell_products(qs.get("code", [""])[0]), request_id=self.request_id)
            return self._send_json(get_cell_products(code), request_id=self.request_id)

        # Search products for binding
        if path in ("/api/inventory/search-products", "/search-products"):
            q = qs.get("q", [""])[0]
            limit = _qs_int(qs, "limit", 20)
            return self._send_json(search_products_for_location(q, limit), request_id=self.request_id)

        # Low stock (GET - supports query params for backward compat)
        if path in ("/api/inventory/low-stock", "/low-stock"):
            t = _qs_int(qs, "threshold", 5)
            offset = _qs_int(qs, "offset", 0)
            limit = _qs_int(qs, "limit", 50)
            include_zero = qs.get("include_zero", ["0"])[0] in ("1", "true", "yes")
            return self._send_json(get_low_stock(threshold=t, offset=offset, limit=limit, include_zero=include_zero), request_id=self.request_id)

        # Data anomalies (GET)
        if path in ("/api/inventory/data-anomalies", "/data-anomalies"):
            cat = qs.get("category", [None])[0]
            offset = _qs_int(qs, "offset", 0)
            limit = _qs_int(qs, "limit", 100)
            return self._send_json(get_data_anomalies(cat, offset, limit), request_id=self.request_id)

        # Custom locations
        if path in ("/api/inventory/custom-locations", "/custom-locations"):
            return self._send_json(list_custom_locations(), request_id=self.request_id)

        # Movements
        if path in ("/api/inventory/movements", "/movements"):
            pid = qs.get("product_id", [None])[0]
            try:
                pid_val = int(pid) if pid not in (None, "") else None
            except (TypeError, ValueError):
                pid_val = None  # 垃圾输入按未指定处理（原 int() 直接 ValueError→500）
            rows = list(self._db_execute("SELECT * FROM inventory_movements" + (" WHERE product_id=?" if pid_val else "") + " ORDER BY created_at DESC LIMIT ?", (pid_val, 50) if pid_val else (50,)))
            return self._send_json(_ok([dict(r) for r in rows], total=len(rows)), request_id=self.request_id)

        # Product images
        if path in ("/api/inventory/product/images", "/product/images"):
            pid = qs.get("product_id", [None])[0]
            try:
                pid_val = int(pid) if pid not in (None, "") else None
            except (TypeError, ValueError):
                pid_val = None
            return self._send_json(get_product_images(pid_val), request_id=self.request_id)
        
        if path in ("/api/inventory/products-with-images", "/products-with-images"):
            q = qs.get("q", [""])[0]
            limit = int(qs.get("limit", ["50"])[0])
            return self._send_json(get_all_products_with_images(q, limit), request_id=self.request_id)

        # Product locations (multi-location)
        if path in ("/api/inventory/product/locations", "/product/locations"):
            pid = qs.get("product_id", [None])[0]
            return self._send_json(get_product_locations(int(pid) if pid else None), request_id=self.request_id)

        # Todos
        if path in ("/api/todos", "/todos"):
            s = qs.get("status", [None])[0]
            return self._send_json(list_todos(s), request_id=self.request_id)
        if path in ("/api/todos/pending", "/todos/pending"):
            return self._send_json(list_todos("pending"), request_id=self.request_id)

        self._send_json({"success": False, "error": f"未知路径: {path}"}, 404, request_id=self.request_id)

    def do_POST(self):
        cl = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(cl) if cl else b"{}"
        self.request_id = self.headers.get("X-Request-Id", "")
        set_request_id(self.request_id)
        path, _ = self._parse_path()

        data, err = parse_json_body(body)
        if err:
            return self._send_json(err, 400, request_id=self.request_id)

        # Floor CRUD
        if path in ("/api/inventory/floor/create", "/floor/create"):
            cleaned, err = validate_fields(data, {"name": {"required": True, "message": "请填写楼层名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_floor(cleaned["name"], data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/floor/delete", "/floor/delete"):
            cleaned, err = validate_fields(data, {"name": {"required": True, "message": "请填写楼层名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_floor(cleaned["name"]), request_id=self.request_id)

        # Zone CRUD
        if path in ("/api/inventory/zone/create", "/zone/create"):
            cleaned, err = validate_fields(data, {"floor_id": {"required": True, "type": int, "message": "请选择楼层"}, "name": {"required": True, "message": "请填写分区名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_zone(cleaned["floor_id"], data.get("name",""), data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/zone/delete", "/zone/delete"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择分区"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_zone(cleaned["id"]), request_id=self.request_id)

        # Rack CRUD
        if path in ("/api/inventory/rack/create", "/rack/create"):
            cleaned, err = validate_fields(data, {"zone_id": {"required": True, "type": int, "message": "请选择分区"}, "name": {"required": True, "message": "请填写货架名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_rack(cleaned["zone_id"], data.get("name",""), data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/rack/delete", "/rack/delete"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择货架"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_rack(cleaned["id"]), request_id=self.request_id)

        # Level CRUD
        if path in ("/api/inventory/level/create", "/level/create"):
            cleaned, err = validate_fields(data, {"rack_id": {"required": True, "type": int, "message": "请选择货架"}, "name": {"required": True, "message": "请填写层名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_level(cleaned["rack_id"], data.get("name",""), data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/level/delete", "/level/delete"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择层"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_level(cleaned["id"]), request_id=self.request_id)

        # Cell CRUD
        if path in ("/api/inventory/cell/create", "/cell/create"):
            cleaned, err = validate_fields(data, {"level_id": {"required": True, "type": int, "message": "请选择层"}, "name": {"required": True, "message": "请填写货位名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_cell(cleaned["level_id"], data.get("name",""), data.get("code",""), data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/cell/delete", "/cell/delete"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择货位"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_cell(cleaned["id"]), request_id=self.request_id)

        # Product-Location binding
        if path in ("/api/inventory/product/bind", "/product/bind"):
            cleaned, err = validate_fields(data, {"product_id": {"required": True, "type": int, "message": "请选择商品"}, "cell_code": {"required": True, "message": "请填写货位编号"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(set_product_cell(
                cleaned["product_id"], cleaned["cell_code"],
                data.get("location_type", "库存"),
                int(data.get("qty", 0)),
                data.get("note", "")
            ), request_id=self.request_id)
        if path in ("/api/inventory/product/unbind", "/product/unbind"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择绑定记录"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(unset_product_cell(cleaned["id"]), request_id=self.request_id)
        if path in ("/api/inventory/product/location/update", "/product/location/update"):
            return self._send_json(update_product_location(
                data.get("id"),
                data.get("qty"),
                data.get("note"),
                data.get("location_type")
            ), request_id=self.request_id)

        # Custom location CRUD
        if path in ("/api/inventory/custom-location/create", "/custom-location/create"):
            cleaned, err = validate_fields(data, {"name": {"required": True, "message": "请填写自定义库位名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_custom_location(cleaned["name"], data.get("description","")), request_id=self.request_id)
        if path in ("/api/inventory/custom-location/delete", "/custom-location/delete"):
            cleaned, err = validate_fields(data, {"name": {"required": True, "message": "请填写自定义库位名称"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_custom_location(cleaned["name"]), request_id=self.request_id)

        # Stock adjustment
        if path in ("/api/inventory/adjust", "/adjust"):
            cleaned, err = validate_fields(data, {
                "product_id": {"required": True, "type": int, "message": "请选择商品"},
                "qty": {"required": True, "type": int, "message": "请填写调整数量"},
                "type": {"default": "adjust"},
                "note": {"default": ""},
                "operator": {"default": ""},
            })
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(adjust_stock(cleaned["product_id"], cleaned["qty"], cleaned["type"], cleaned["note"], cleaned["operator"]), request_id=self.request_id)

        # Low stock with pagination (POST)
        if path in ("/api/inventory/low-stock", "/low-stock"):
            return self._send_json(get_low_stock(
                threshold=data.get("threshold", 5),
                offset=data.get("offset", 0),
                limit=data.get("limit", 50),
                include_zero=data.get("include_zero", False)
            ), request_id=self.request_id)
        if path in ("/api/inventory/zero-stock", "/zero-stock"):
            return self._send_json(get_zero_stock(
                offset=data.get("offset", 0),
                limit=data.get("limit", 50)
            ), request_id=self.request_id)
        # Data anomalies (POST)
        if path in ("/api/inventory/data-anomalies", "/data-anomalies"):
            return self._send_json(get_data_anomalies(
                data.get("category"),
                data.get("offset", 0),
                data.get("limit", 100)
            ), request_id=self.request_id)

        # Checkout (bill + stock deduction)
        if path in ("/api/inventory/checkout", "/checkout"):
            cleaned, err = validate_fields(data, {"items": {"required": True, "type": list, "message": "缺少商品列表"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            items = cleaned["items"]
            staff = data.get("staff", "")
            note = data.get("note", "")
            if not items:
                return self._send_json({"success": False, "error": "商品列表为空"}, 400, request_id=self.request_id)
            return self._send_json(checkout_items(items, staff, note), request_id=self.request_id)

        # Product image upload
        if path in ("/api/inventory/product/image/upload", "/product/image/upload"):
            cleaned, err = validate_fields(data, {"product_id": {"required": True, "type": int, "message": "请选择商品"}, "image_data": {"required": True, "message": "请提供图片数据"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = upload_product_image(cleaned["product_id"], cleaned["image_data"], data.get("filename", "photo.jpg"))
            # I19 运维可见性：业务失败（超限/魔数拒绝）不再伪装成 HTTP 200——
            # 网关日志此前全是 200，老板上午 6 次上传被拒在日志里完全不可见
            return self._send_json(result, _error_status_for(result), request_id=self.request_id)

        if path in ("/api/inventory/product/image/delete", "/product/image/delete"):
            cleaned, err = validate_fields(data, {"image_id": {"required": True, "type": int, "message": "请选择图片"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            result = delete_product_image(cleaned["image_id"])
            return self._send_json(result, _error_status_for(result), request_id=self.request_id)

        # Todos
        if path in ("/api/todos/create", "/todos/create"):
            cleaned, err = validate_fields(data, {"title": {"required": True, "message": "请填写待办标题"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(create_todo(cleaned["title"], data.get("note",""), data.get("priority","medium"), data.get("created_by","")), request_id=self.request_id)
        if path in ("/api/todos/update", "/todos/update"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择待办"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(update_todo(cleaned["id"], data.get("title"), data.get("note"), data.get("priority")), request_id=self.request_id)
        if path in ("/api/todos/toggle", "/todos/toggle"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择待办"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(toggle_todo(cleaned["id"]), request_id=self.request_id)
        if path in ("/api/todos/delete", "/todos/delete"):
            cleaned, err = validate_fields(data, {"id": {"required": True, "type": int, "message": "请选择待办"}})
            if err:
                return self._send_json(err, 400, request_id=self.request_id)
            return self._send_json(delete_todo(cleaned["id"]), request_id=self.request_id)

        self._send_json({"success": False, "error": f"未知路径: {path}"}, 404, request_id=self.request_id)


def _run_server(host=None, port=18604):
    # 2026-09-09 审计：下游服务默认只绑回环（仅网关本机调用），0.0.0.0 需显式环境变量。
    host = host or os.environ.get("INVENTORY_HOST", "127.0.0.1")
    setup_logging("inventory")
    init_db()

    server = ThreadedInventoryHTTPServer((host, port), InventoryHandler)
    def _sigterm(s, f):
        logging.info("SIGTERM received...")
        server.shutdown()
    signal.signal(signal.SIGTERM, _sigterm)
    logging.info(f"Server listening on {host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Shutting down...")
        server.server_close()


if __name__ == "__main__":
    _run_server()
