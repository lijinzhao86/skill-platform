"""SQLite schema for the skill-platform skill store."""

SCHEMA_VERSION = 1

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS skills (
    name TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    access TEXT NOT NULL DEFAULT 'private'
        CHECK (access IN ('public', 'private', 'owner_only')),
    content TEXT NOT NULL DEFAULT '',
    path TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS skill_files (
    skill_name TEXT NOT NULL REFERENCES skills(name) ON DELETE CASCADE,
    relpath TEXT NOT NULL,
    content BLOB NOT NULL,
    is_binary INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (skill_name, relpath)
);

CREATE TABLE IF NOT EXISTS users (
    open_id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS grants (
    open_id TEXT NOT NULL REFERENCES users(open_id) ON DELETE CASCADE,
    skill_name TEXT NOT NULL,
    granted_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (open_id, skill_name)
);

CREATE TABLE IF NOT EXISTS denials (
    open_id TEXT NOT NULL REFERENCES users(open_id) ON DELETE CASCADE,
    skill_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (open_id, skill_name)
);

CREATE TABLE IF NOT EXISTS skill_links (
    source TEXT NOT NULL REFERENCES skills(name) ON DELETE CASCADE,
    target TEXT NOT NULL,
    relation_type TEXT NOT NULL DEFAULT 'reference',
    PRIMARY KEY (source, target, relation_type)
);

CREATE INDEX IF NOT EXISTS idx_skill_links_target ON skill_links(target);

CREATE INDEX IF NOT EXISTS idx_grants_skill ON grants(skill_name);
CREATE INDEX IF NOT EXISTS idx_denials_skill ON denials(skill_name);
"""

META_SCHEMA_VERSION = "schema_version"
META_OWNER_OPEN_ID = "owner_open_id"
META_GLOBAL_DEFAULT = "global_default"
META_IMPORTED_FROM = "imported_from"
META_IMPORTED_AT = "imported_at"
# Incremented in the same transaction as any permission-affecting write; the
# runtime gate polls it to invalidate its in-memory snapshot. A normal row read
# (unlike PRAGMA data_version) sees WAL-committed changes immediately.
META_PERMISSIONS_VERSION = "permissions_version"


def ensure_schema(conn) -> None:
    """Create tables if missing and record the schema version."""
    conn.executescript(SCHEMA_SQL)
    row = conn.execute(
        "SELECT value FROM meta WHERE key = ?", (META_SCHEMA_VERSION,)
    ).fetchone()
    if row is None:
        conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?)",
            (META_SCHEMA_VERSION, str(SCHEMA_VERSION)),
        )
