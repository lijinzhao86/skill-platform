"""Permission resolution for skill-platform.

The runtime gate and the console both resolve access through this module.
Granting/denying a router skill (`lark`) covers its family (`lark-*`);
the longest matching pattern wins.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from time import monotonic
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .store import SkillStore

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PermissionSnapshot:
    owner_open_id: str
    global_default: str
    skill_access: dict[str, str]
    grants: dict[str, set[str]]
    denials: dict[str, set[str]]

    @classmethod
    def degraded(cls, owner_open_id: str) -> PermissionSnapshot:
        """Fail-closed snapshot used when the DB cannot be read."""
        return cls(
            owner_open_id=owner_open_id,
            global_default="private",
            skill_access={},
            grants={},
            denials={},
        )


def _matches(skill: str, pattern: str) -> bool:
    if pattern == skill:
        return True
    if pattern.endswith("*"):
        return skill.startswith(pattern[:-1])
    return skill.startswith(pattern + "-")


def _best_match(skill: str, patterns: set[str]) -> str | None:
    best = None
    for pattern in patterns:
        if _matches(skill, pattern):
            if best is None or len(pattern) > len(best):
                best = pattern
    return best


def resolve(snapshot: PermissionSnapshot, open_id: str, skill_name: str) -> bool:
    """Return whether `open_id` may invoke `skill_name`.

    Decision order: owner → deny → owner_only → public → grant → private →
    global_default. Denials are checked before grants, so a denial always wins.
    """
    if open_id == snapshot.owner_open_id:
        return True
    if _best_match(skill_name, snapshot.denials.get(open_id, ())) is not None:
        return False
    access = snapshot.skill_access.get(skill_name)
    if access == "owner_only":
        return False
    if access == "public":
        return True
    if _best_match(skill_name, snapshot.grants.get(open_id, ())) is not None:
        return True
    if access == "private":
        return False
    return snapshot.global_default == "public"


class PermissionCache:
    """In-memory snapshot invalidated by the store's permission version, with a
    hard TTL fallback. Reload failures keep the last good snapshot (or a
    fail-closed one) and are surfaced through logging rather than raising.
    """

    def __init__(self, store: SkillStore, ttl_seconds: float = 30.0) -> None:
        self._store = store
        self._ttl = ttl_seconds
        self._snapshot: PermissionSnapshot | None = None
        self._version: int | None = None
        self._loaded_at = 0.0

    def snapshot(self) -> PermissionSnapshot:
        now = monotonic()
        try:
            if self._snapshot is None or now - self._loaded_at > self._ttl:
                self._reload()
            elif self._store.permissions_version() != self._version:
                self._reload()
        except Exception as exc:  # noqa: BLE001 - permission gate must fail closed
            logger.error("permission snapshot reload failed, failing closed: %s", exc)
        return (
            self._snapshot
            if self._snapshot is not None
            else PermissionSnapshot.degraded(self._store.owner_open_id)
        )

    def _reload(self) -> None:
        self._snapshot = self._store.load_snapshot()
        self._version = self._store.permissions_version()
        self._loaded_at = monotonic()
