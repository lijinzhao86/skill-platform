from skill_platform_server.permissions import (
    PermissionCache,
    PermissionSnapshot,
    resolve,
)


def make_snapshot(
    *,
    global_default: str = "private",
    skill_access: dict | None = None,
    grants: dict | None = None,
    denials: dict | None = None,
    owner: str = "ou_owner",
) -> PermissionSnapshot:
    return PermissionSnapshot(
        owner_open_id=owner,
        global_default=global_default,
        skill_access=skill_access or {},
        grants=grants or {},
        denials=denials or {},
    )


def test_owner_always_allowed():
    snap = make_snapshot(skill_access={"lark": "owner_only"})
    assert resolve(snap, "ou_owner", "lark") is True


def test_deny_beats_grant_and_access():
    snap = make_snapshot(
        skill_access={"lark-im": "public"},
        grants={"ou_bob": {"lark-im"}},
        denials={"ou_bob": {"lark-im"}},
    )
    assert resolve(snap, "ou_bob", "lark-im") is False


def test_family_deny_covers_subskill():
    snap = make_snapshot(
        grants={"ou_bob": {"lark-im"}},
        denials={"ou_bob": {"lark"}},
    )
    assert resolve(snap, "ou_bob", "lark-im") is False


def test_owner_only_denied_for_others():
    snap = make_snapshot(skill_access={"lark": "owner_only"})
    assert resolve(snap, "ou_bob", "lark") is False


def test_public_allowed():
    snap = make_snapshot(skill_access={"lark": "public"})
    assert resolve(snap, "ou_bob", "lark") is True


def test_access_is_exact_per_skill():
    # public `lark` does NOT make the unknown sub-skill `lark-im` public.
    snap = make_snapshot(skill_access={"lark": "public"})
    assert resolve(snap, "ou_bob", "lark-im") is False


def test_grant_family_covers_subskills():
    snap = make_snapshot(grants={"ou_bob": {"lark"}})
    assert resolve(snap, "ou_bob", "lark-im") is True


def test_longest_pattern_wins():
    snap = make_snapshot(
        grants={"ou_bob": {"lark"}},
        denials={"ou_bob": {"lark-im"}},
    )
    assert resolve(snap, "ou_bob", "lark") is True
    assert resolve(snap, "ou_bob", "lark-im") is False


def test_private_denied_for_others():
    snap = make_snapshot(skill_access={"lark": "private"})
    assert resolve(snap, "ou_bob", "lark") is False


def test_global_default_public_opens_unknown_skills():
    snap = make_snapshot(global_default="public")
    assert resolve(snap, "ou_bob", "brand-new-skill") is True


def test_global_default_private_closes_unknown_skills():
    snap = make_snapshot(global_default="private")
    assert resolve(snap, "ou_bob", "brand-new-skill") is False


def test_wildcard_pattern():
    snap = make_snapshot(grants={"ou_bob": {"lark-*"}})
    assert resolve(snap, "ou_bob", "lark-mail") is True


def test_degraded_snapshot_fails_closed():
    snap = PermissionSnapshot.degraded("ou_owner")
    assert resolve(snap, "ou_owner", "anything") is True
    assert resolve(snap, "ou_bob", "anything") is False


def test_cache_reloads_on_permission_change(tmp_path):
    store = _make_store(tmp_path)
    store.upsert_skill("lark", "Lark", "router", "# lark")
    store.set_skill_access("lark", "public")
    store.upsert_user("ou_bob")
    cache = PermissionCache(store, ttl_seconds=30.0)

    snap = cache.snapshot()
    assert resolve(snap, "ou_bob", "lark") is True

    store.set_skill_access("lark", "private")
    snap = cache.snapshot()
    assert resolve(snap, "ou_bob", "lark") is False


def _make_store(tmp_path):
    from skill_platform_server.store import SkillStore

    return SkillStore(str(tmp_path / "test.db"), owner_open_id="ou_owner")
