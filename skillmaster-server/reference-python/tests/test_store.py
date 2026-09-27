import pytest

from skill_platform_server.permissions import resolve
from skill_platform_server.store import SkillStore


@pytest.fixture
def store(tmp_path):
    return SkillStore(str(tmp_path / "test.db"), owner_open_id="ou_owner")


def test_upsert_and_get(store):
    store.upsert_skill("lark", "Lark", "router skill", "# lark", path="lark")
    skill = store.get_skill("lark")
    assert skill["name"] == "lark"
    assert skill["content"] == "# lark"
    assert skill["access"] == "private"


def test_upsert_preserves_access_on_reimport(store):
    store.upsert_skill("lark", "Lark", "router skill", "# v1", path="lark")
    store.set_skill_access("lark", "public")
    store.upsert_skill("lark", "Lark", "router skill", "# v2", path="lark")
    assert store.get_skill("lark")["access"] == "public"
    assert store.get_skill("lark")["content"] == "# v2"


def test_skill_files_replace(store):
    store.upsert_skill("lark", "Lark", "d", "# lark", path="lark")
    store.replace_skill_files("lark", [("run.py", b"print(1)", False)])
    assert store.get_skill_file("lark", "run.py") == (b"print(1)", False)
    store.replace_skill_files("lark", [])
    assert store.get_skill_file("lark", "run.py") is None


def test_delete_skill_cascades_files(store):
    store.upsert_skill("lark", "Lark", "d", "# lark", path="lark")
    store.replace_skill_files("lark", [("a.txt", b"x", False)])
    store.delete_skill("lark")
    assert store.get_skill("lark") is None
    assert store.list_skill_files("lark") == []


def test_grant_and_denial_bump_permissions_version(store):
    store.upsert_skill("lark", "Lark", "d", "# lark")
    store.upsert_user("ou_bob")
    v0 = store.permissions_version()
    store.add_grant("ou_bob", "lark", granted_by="test")
    assert store.permissions_version() > v0
    v1 = store.permissions_version()
    store.add_denial("ou_bob", "lark")
    assert store.permissions_version() > v1


def test_import_skills_atomic_replace(store):
    store.upsert_skill("obsolete", "Old", "gone", "# old")
    store.set_skill_access("keep", "public")  # no-op, skill doesn't exist yet
    records = [
        {
            "name": "keep",
            "title": "Keep",
            "description": "kept",
            "content": "# keep",
            "path": "keep",
            "files": [("a.txt", b"x", False)],
            "links": [("lark", "depends_on")],
        },
        {
            "name": "lark",
            "title": "Lark",
            "description": "router",
            "content": "# lark",
            "path": "lark",
            "files": [],
            "links": [],
        },
    ]
    result = store.import_skills(records, imported_from="/src")
    assert result == {"imported": 2, "deleted": ["obsolete"]}
    assert store.get_skill("obsolete") is None
    assert store.get_skill("keep")["path"] == "keep"
    assert store.get_skill_file("keep", "a.txt") == (b"x", False)
    assert store.list_links("keep")[0]["target"] == "lark"
    assert store.get_meta("imported_from") == "/src"


def test_import_keeps_access_levels(store):
    store.upsert_skill("lark", "Lark", "d", "# v1", path="lark")
    store.set_skill_access("lark", "owner_only")
    store.import_skills(
        [
            {
                "name": "lark",
                "title": "Lark",
                "description": "d",
                "content": "# v2",
                "path": "lark",
            }
        ],
        imported_from="/src",
    )
    assert store.get_skill("lark")["access"] == "owner_only"


def test_import_empty_records_raises(store):
    with pytest.raises(ValueError):
        store.import_skills([], imported_from="/src")


def test_dangling_links(store):
    store.upsert_skill("lark", "Lark", "d", "# lark")
    store.replace_skill_links("lark", [("missing", "reference")])
    assert [link["target"] for link in store.find_dangling_links()] == ["missing"]


def test_snapshot_resolution(store):
    store.upsert_skill("lark", "Lark", "d", "# lark")
    store.set_skill_access("lark", "public")
    store.upsert_user("ou_bob")
    store.add_grant("ou_bob", "lark", granted_by="test")
    snap = store.load_snapshot()
    assert resolve(snap, "ou_bob", "lark") is True
    assert resolve(snap, "ou_stranger", "lark") is True  # public
    store.set_skill_access("lark", "private")
    snap = store.load_snapshot()
    assert resolve(snap, "ou_stranger", "lark") is False
    assert resolve(snap, "ou_bob", "lark") is True  # explicit grant


def test_owner_fallback_from_env(tmp_path, monkeypatch):
    monkeypatch.setenv("MOSS_OWNER_OPEN_ID", "ou_env_owner")
    store = SkillStore(str(tmp_path / "env.db"))
    assert store.owner_open_id == "ou_env_owner"
