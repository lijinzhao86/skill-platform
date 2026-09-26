import pytest

from skill_platform.materializer import materialize
from skill_platform.store import SkillStore


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


@pytest.fixture
def store(tmp_path):
    store = SkillStore(str(tmp_path / "platform.db"), owner_open_id="ou_owner")
    store.import_skills(
        [
            {
                "name": "lark",
                "title": "Lark",
                "description": "router",
                "content": "# lark\n\n[im](im/SKILL.md)\n",
                "path": "lark",
                "files": [
                    ("im/SKILL.md", b"# im\n", False),
                    ("data.bin", b"\x00\x01\xff", True),
                ],
                "links": [],
            },
            {
                "name": "brief",
                "title": "Brief",
                "description": "standalone",
                "content": "# brief\n",
                "path": "brief",
                "files": [("SKILL.md", b"# brief\n", False)],
                "links": [],
            },
        ],
        imported_from="fixture",
    )
    return store


def test_materialize_preserves_layout(store, tmp_path):
    dest = tmp_path / "out"
    report = materialize(store, str(dest))
    assert report.written == 2
    assert (dest / "lark" / "SKILL.md").exists()
    assert (dest / "lark" / "im" / "SKILL.md").read_text() == "# im\n"
    assert (dest / "lark" / "data.bin").read_bytes() == b"\x00\x01\xff"
    assert (dest / "brief" / "SKILL.md").read_text() == "# brief\n"


def test_materialize_prunes_stale_skill_dirs(store, tmp_path):
    dest = tmp_path / "out"
    write(dest / "lark" / "SKILL.md", "# old lark\n")
    write(dest / "lark" / "im" / "SKILL.md", "# old im\n")
    write(dest / "stale" / "SKILL.md", "# stale\n")
    write(dest / "unrelated" / "readme.txt", "keep me\n")

    report = materialize(store, str(dest), prune=True)
    assert report.removed == ["stale"]
    assert (dest / "lark" / "SKILL.md").exists()  # catalog still owns it
    assert (dest / "unrelated").exists()  # no SKILL.md -> never touched


def test_materialize_restrict_names(store, tmp_path):
    dest = tmp_path / "out"
    report = materialize(store, str(dest), names=["brief"])
    assert report.written == 1
    assert (dest / "brief" / "SKILL.md").exists()
    assert not (dest / "lark").exists()


def test_materialize_rejects_escaping_path(tmp_path):
    store = SkillStore(str(tmp_path / "platform.db"), owner_open_id="ou_owner")
    store.import_skills(
        [
            {
                "name": "evil",
                "title": "Evil",
                "description": "",
                "content": "# evil",
                "path": "../escape",
                "files": [],
                "links": [],
            }
        ],
        imported_from="fixture",
    )
    with pytest.raises(ValueError, match="escapes dest_root"):
        materialize(store, str(tmp_path / "out"))
