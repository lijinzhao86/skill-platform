"""SQLite access layer for the skill-platform skill store.

A fresh connection is opened per operation so the store is safe to share
across threads (async gate, async console). WAL + busy_timeout let the
console process write while the runtime gate reads.
"""

from __future__ import annotations

import os
import sqlite3
from collections.abc import Iterable, Iterator, Mapping, Sequence
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from .schema import (
    META_GLOBAL_DEFAULT,
    META_IMPORTED_AT,
    META_IMPORTED_FROM,
    META_OWNER_OPEN_ID,
    META_PERMISSIONS_VERSION,
    ensure_schema,
)

if TYPE_CHECKING:
    from .permissions import PermissionSnapshot

DEFAULT_OWNER_OPEN_ID = "ou_fc30b218dc0bb9652f4989df2830a0de"

# Meta keys whose change must invalidate the permission snapshot.
_PERMISSION_META_KEYS = {META_GLOBAL_DEFAULT, META_OWNER_OPEN_ID}


def _bump_permissions_version(conn: sqlite3.Connection) -> None:
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?, '1') "
        "ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1",
        (META_PERMISSIONS_VERSION,),
    )


def utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def _row_to_dict(row: sqlite3.Row) -> dict:
    return {key: row[key] for key in row.keys()}


class SkillStore:
    """CRUD over the skill platform database."""

    def __init__(self, db_path: str, owner_open_id: str | None = None) -> None:
        self.db_path = db_path
        self.owner_open_id = (
            owner_open_id
            or os.environ.get("MOSS_OWNER_OPEN_ID")
            or DEFAULT_OWNER_OPEN_ID
        )

    def _connect(self) -> sqlite3.Connection:
        os.makedirs(os.path.dirname(self.db_path) or ".", exist_ok=True)
        conn = sqlite3.connect(self.db_path, timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA foreign_keys=ON")
        ensure_schema(conn)
        return conn

    @contextmanager
    def connection(self) -> Iterator[sqlite3.Connection]:
        conn = self._connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def permissions_version(self) -> int:
        """Permission revision counter; the gate polls this to invalidate its snapshot."""
        with self.connection() as conn:
            row = conn.execute(
                "SELECT value FROM meta WHERE key = ?", (META_PERMISSIONS_VERSION,)
            ).fetchone()
        return int(row[0]) if row else 0

    # ---------- meta ----------

    def get_meta(self, key: str) -> str | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT value FROM meta WHERE key = ?", (key,)
            ).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
            if key in _PERMISSION_META_KEYS:
                _bump_permissions_version(conn)

    # ---------- skills ----------

    def upsert_skill(
        self,
        name: str,
        title: str,
        description: str,
        content: str,
        path: str = "",
        updated_at: str | None = None,
    ) -> None:
        with self.connection() as conn:
            conn.execute(
                "INSERT INTO skills "
                "(name, title, description, content, path, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET "
                "title = excluded.title, description = excluded.description, "
                "content = excluded.content, path = excluded.path, "
                "updated_at = excluded.updated_at",
                (name, title, description, content, path, updated_at or utc_now()),
            )
            _bump_permissions_version(conn)

    def set_skill_access(self, name: str, access: str) -> None:
        with self.connection() as conn:
            conn.execute("UPDATE skills SET access = ? WHERE name = ?", (access, name))
            _bump_permissions_version(conn)

    def get_skill(self, name: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM skills WHERE name = ?", (name,)
            ).fetchone()
        return _row_to_dict(row) if row else None

    def list_skills(self) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT name, title, description, access, path, updated_at "
                "FROM skills ORDER BY name"
            ).fetchall()
        return [_row_to_dict(r) for r in rows]

    def delete_skill(self, name: str) -> None:
        with self.connection() as conn:
            conn.execute("DELETE FROM skills WHERE name = ?", (name,))
            _bump_permissions_version(conn)

    # ---------- skill files ----------

    def replace_skill_files(
        self,
        skill_name: str,
        files: Sequence[tuple[str, bytes, bool]],
    ) -> None:
        """Replace every file of a skill atomically. Each item is
        (relpath, content_bytes, is_binary)."""
        with self.connection() as conn:
            conn.execute("DELETE FROM skill_files WHERE skill_name = ?", (skill_name,))
            conn.executemany(
                "INSERT INTO skill_files (skill_name, relpath, content, is_binary) "
                "VALUES (?, ?, ?, ?)",
                [
                    (skill_name, relpath, content, 1 if is_binary else 0)
                    for relpath, content, is_binary in files
                ],
            )

    def list_skill_files(self, skill_name: str) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT relpath, is_binary, length(content) AS size "
                "FROM skill_files WHERE skill_name = ? ORDER BY relpath",
                (skill_name,),
            ).fetchall()
        return [_row_to_dict(r) for r in rows]

    def get_skill_file(
        self, skill_name: str, relpath: str
    ) -> tuple[bytes, bool] | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT content, is_binary FROM skill_files "
                "WHERE skill_name = ? AND relpath = ?",
                (skill_name, relpath),
            ).fetchone()
        return (bytes(row["content"]), bool(row["is_binary"])) if row else None

    # ---------- users ----------

    def upsert_user(self, open_id: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO users (open_id, created_at) VALUES (?, ?)",
                (open_id, utc_now()),
            )

    def list_users(self) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT u.open_id, u.created_at, "
                "(SELECT COUNT(*) FROM grants g WHERE g.open_id = u.open_id) AS grant_count "
                "FROM users u ORDER BY u.open_id"
            ).fetchall()
        return [_row_to_dict(r) for r in rows]

    # ---------- grants / denials ----------

    def add_grant(self, open_id: str, skill_name: str, granted_by: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "INSERT INTO grants (open_id, skill_name, granted_by, created_at) "
                "VALUES (?, ?, ?, ?) ON CONFLICT(open_id, skill_name) DO NOTHING",
                (open_id, skill_name, granted_by, utc_now()),
            )
            _bump_permissions_version(conn)

    def remove_grant(self, open_id: str, skill_name: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "DELETE FROM grants WHERE open_id = ? AND skill_name = ?",
                (open_id, skill_name),
            )
            _bump_permissions_version(conn)

    def add_denial(self, open_id: str, skill_name: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "INSERT INTO denials (open_id, skill_name, created_at) "
                "VALUES (?, ?, ?) ON CONFLICT(open_id, skill_name) DO NOTHING",
                (open_id, skill_name, utc_now()),
            )
            _bump_permissions_version(conn)

    def remove_denial(self, open_id: str, skill_name: str) -> None:
        with self.connection() as conn:
            conn.execute(
                "DELETE FROM denials WHERE open_id = ? AND skill_name = ?",
                (open_id, skill_name),
            )
            _bump_permissions_version(conn)

    def list_grants(self, open_id: str | None = None) -> list[dict]:
        sql = "SELECT * FROM grants"
        params: tuple = ()
        if open_id is not None:
            sql += " WHERE open_id = ?"
            params = (open_id,)
        sql += " ORDER BY open_id, skill_name"
        with self.connection() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_dict(r) for r in rows]

    def list_denials(self, open_id: str | None = None) -> list[dict]:
        sql = "SELECT * FROM denials"
        params: tuple = ()
        if open_id is not None:
            sql += " WHERE open_id = ?"
            params = (open_id,)
        sql += " ORDER BY open_id, skill_name"
        with self.connection() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_dict(r) for r in rows]

    def import_skills(
        self,
        records: Sequence[Mapping],
        imported_from: str,
        imported_at: str | None = None,
    ) -> dict:
        """Atomically replace the skill catalog with `records` (single source
        of truth). Each record carries name/title/description/content/path,
        `files` (list of (relpath, bytes, is_binary)) and `links` (list of
        (target, relation_type)). Access levels of existing skills are kept;
        new skills default to 'private'. Skills no longer in the source are
        deleted. Raises if `records` is empty so a bad source never wipes the
        catalog.
        """
        if not records:
            raise ValueError("no skills to import")
        names = [rec["name"] for rec in records]
        placeholders = ",".join("?" * len(names))
        now = imported_at or utc_now()
        with self.connection() as conn:
            for rec in records:
                conn.execute(
                    "INSERT INTO skills (name, title, description, content, path, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(name) DO UPDATE SET "
                    "title = excluded.title, description = excluded.description, "
                    "content = excluded.content, path = excluded.path, "
                    "updated_at = excluded.updated_at",
                    (
                        rec["name"],
                        rec.get("title", ""),
                        rec.get("description", ""),
                        rec.get("content", ""),
                        rec.get("path", ""),
                        now,
                    ),
                )
                conn.execute(
                    "DELETE FROM skill_files WHERE skill_name = ?", (rec["name"],)
                )
                conn.executemany(
                    "INSERT INTO skill_files (skill_name, relpath, content, is_binary) "
                    "VALUES (?, ?, ?, ?)",
                    [
                        (rec["name"], relpath, content, 1 if is_binary else 0)
                        for relpath, content, is_binary in rec.get("files", ())
                    ],
                )
                conn.execute("DELETE FROM skill_links WHERE source = ?", (rec["name"],))
                conn.executemany(
                    "INSERT INTO skill_links (source, target, relation_type) "
                    "VALUES (?, ?, ?) ON CONFLICT(source, target, relation_type) DO NOTHING",
                    [
                        (rec["name"], target, relation_type)
                        for target, relation_type in rec.get("links", ())
                    ],
                )
            stale = conn.execute(
                f"SELECT name FROM skills WHERE name NOT IN ({placeholders})", names
            ).fetchall()
            conn.execute(
                f"DELETE FROM skills WHERE name NOT IN ({placeholders})", names
            )
            _bump_permissions_version(conn)
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (META_IMPORTED_FROM, imported_from),
            )
            conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (META_IMPORTED_AT, now),
            )
        return {"imported": len(records), "deleted": [row["name"] for row in stale]}

    # ---------- skill links ----------

    def replace_skill_links(
        self, source: str, links: Iterable[tuple[str, str]]
    ) -> None:
        """Replace all outgoing links of a skill. Each item is (target, relation_type)."""
        with self.connection() as conn:
            conn.execute("DELETE FROM skill_links WHERE source = ?", (source,))
            conn.executemany(
                "INSERT INTO skill_links (source, target, relation_type) "
                "VALUES (?, ?, ?) ON CONFLICT(source, target, relation_type) DO NOTHING",
                [(source, target, relation_type) for target, relation_type in links],
            )

    def list_links(self, source: str | None = None) -> list[dict]:
        sql = "SELECT source, target, relation_type FROM skill_links"
        params: tuple = ()
        if source is not None:
            sql += " WHERE source = ?"
            params = (source,)
        sql += " ORDER BY source, target"
        with self.connection() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [_row_to_dict(r) for r in rows]

    def find_dangling_links(self) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT source, target, relation_type FROM skill_links "
                "WHERE target NOT IN (SELECT name FROM skills) ORDER BY source"
            ).fetchall()
        return [_row_to_dict(r) for r in rows]

    # ---------- permission snapshot ----------

    def load_snapshot(self) -> PermissionSnapshot:
        from .permissions import PermissionSnapshot

        owner = self.get_meta(META_OWNER_OPEN_ID) or self.owner_open_id
        global_default = self.get_meta(META_GLOBAL_DEFAULT) or "private"
        with self.connection() as conn:
            skills = conn.execute("SELECT name, access FROM skills").fetchall()
            grants = conn.execute("SELECT open_id, skill_name FROM grants").fetchall()
            denials = conn.execute("SELECT open_id, skill_name FROM denials").fetchall()
        return PermissionSnapshot(
            owner_open_id=owner,
            global_default=global_default,
            skill_access={row["name"]: row["access"] for row in skills},
            grants=_index_by_user(grants),
            denials=_index_by_user(denials),
        )


def _index_by_user(rows: Sequence[sqlite3.Row]) -> dict[str, set[str]]:
    indexed: dict[str, set[str]] = {}
    for row in rows:
        indexed.setdefault(row["open_id"], set()).add(row["skill_name"])
    return indexed
