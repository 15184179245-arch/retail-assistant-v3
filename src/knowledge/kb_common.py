"""Public schema migration; no private article corpus is bundled."""
import sqlite3

_DDL = {
    "source": "ALTER TABLE knowledge_base ADD COLUMN source TEXT DEFAULT 'manual'",
    "review_status": "ALTER TABLE knowledge_base ADD COLUMN review_status TEXT DEFAULT 'approved'",
    "risk_level": "ALTER TABLE knowledge_base ADD COLUMN risk_level TEXT DEFAULT ''",
    "sku": "ALTER TABLE knowledge_base ADD COLUMN sku TEXT DEFAULT ''",
}


def ensure_kb_columns(db: sqlite3.Connection) -> int:
    seen = {row[1] for row in db.execute("PRAGMA table_info(knowledge_base)")}
    missing = [statement for name, statement in _DDL.items() if name not in seen]
    for statement in missing:
        db.execute(statement)
    if missing:
        db.commit()
    return len(missing)
