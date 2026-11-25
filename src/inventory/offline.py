"""Offline warehouse engine. Every connection is local and in memory."""
import sqlite3
import time
import logging
import threading

_local = threading.local()

def get_db():
    if not hasattr(_local, "conn"):
        _local.conn = sqlite3.connect(":memory:")
        _local.conn.row_factory = sqlite3.Row
    return _local.conn

def _ok(data, **extra):
    return {"success": True, "data": data, **extra}

def _err(msg, code="UNKNOWN", user_message="系统异常，请稍后再试"):
    return {"success": False, "error": msg, "code": code, "user_message": user_message}

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


def list_floors():
    db = get_db()
    rows = db.execute("SELECT * FROM inventory_floors ORDER BY sort_order, name").fetchall()
    return _ok([dict(r) for r in rows])

def create_floor(name, description=""):
    return _err("演示楼层由合成数据预置，不可添加", "FORBIDDEN")

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
