import pytest

from skill_platform.importer import (
    discover_skills,
    import_source,
    parse_frontmatter,
)
from skill_platform.store import SkillStore


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_parse_frontmatter_scalars():
    text = "---\nname: lark\ndescription: 'a quoted value'\nversion: 1\n---\nbody"
    fm, body = parse_frontmatter(text)
    assert fm == {"name": "lark", "description": "a quoted value", "version": "1"}
    assert body == "body"


def test_parse_frontmatter_bracket_and_block_lists():
    text = (
        "---\n"
        "name: morning-brief\n"
        "depends_on: [lark-base, lark-calendar, lark-im]\n"
        "channels:\n"
        "  - lark\n"
        "  - web\n"
        "---\nbody\n"
    )
    fm, body = parse_frontmatter(text)
    assert fm["depends_on"] == ["lark-base", "lark-calendar", "lark-im"]
    assert fm["channels"] == ["lark", "web"]
    assert body == "body\n"  # trailing newline preserved


def test_no_frontmatter_returns_body_unchanged():
    text = "just a body\n"
    assert parse_frontmatter(text) == ({}, text)


def test_dir_skills_with_nested_subskill(tmp_path):
    write(
        tmp_path / "lark" / "SKILL.md",
        "---\nname: lark\ndescription: router\n---\n# lark\n\n[im](im/SKILL.md)\n",
    )
    write(tmp_path / "lark" / "im" / "SKILL.md", "---\nname: lark-im\n---\n# im\n")
    write(tmp_path / "lark" / "im" / "run.py", "print('im')\n")
    write(tmp_path / "lark" / "im" / ".hidden", "secret\n")

    specs, warnings = discover_skills(str(tmp_path))
    names = {s.name for s in specs}
    assert names == {"lark", "lark-im"}
    assert warnings == []

    lark = next(s for s in specs if s.name == "lark")
    assert lark.path == "lark"
    # Parent's file set prunes the im/ subtree and carries no duplicate SKILL.md.
    assert [f[0] for f in lark.files] == []

    im = next(s for s in specs if s.name == "lark-im")
    assert im.path == "lark/im"
    # SKILL.md is carried as content, not a file; dotfiles excluded.
    assert sorted(f[0] for f in im.files) == ["run.py"]
    # Routing-table link resolves to the sub-skill by name.
    assert ("lark-im", "reference") in lark.links


def test_standalone_skill(tmp_path):
    write(
        tmp_path / "morning-brief.md",
        "---\nname: morning-brief\ndepends_on: [lark-im]\n---\n# brief\n",
    )
    specs, _ = discover_skills(str(tmp_path))
    assert len(specs) == 1
    spec = specs[0]
    assert spec.name == "morning-brief"
    assert spec.path == "morning-brief"
    assert spec.files == []  # content is the whole file
    assert spec.links == [("lark-im", "depends_on")]


def test_depends_on_and_reference_links(tmp_path):
    write(
        tmp_path / "lark" / "SKILL.md",
        "---\nname: lark\n---\n# lark\n\n[calendar](calendar/SKILL.md)\n",
    )
    write(
        tmp_path / "lark" / "calendar" / "SKILL.md",
        "---\nname: lark-calendar\n---\n# calendar\n",
    )
    write(
        tmp_path / "brief.md",
        "---\nname: brief\ndepends_on: [lark, lark-calendar]\n---\n# brief\n",
    )
    specs, _ = discover_skills(str(tmp_path))
    by_name = {s.name: s for s in specs}
    assert sorted(by_name["brief"].links) == sorted(
        [("lark", "depends_on"), ("lark-calendar", "depends_on")]
    )
    assert ("lark-calendar", "reference") in by_name["lark"].links


def test_duplicate_name_raises(tmp_path):
    write(tmp_path / "a" / "SKILL.md", "---\nname: dup\n---\n# a\n")
    write(tmp_path / "b" / "SKILL.md", "---\nname: dup\n---\n# b\n")
    with pytest.raises(ValueError, match="duplicate skill name"):
        discover_skills(str(tmp_path))


def test_no_skills_raises(tmp_path):
    with pytest.raises(ValueError, match="no skills"):
        discover_skills(str(tmp_path))


def test_import_source_roundtrip(tmp_path):
    write(tmp_path / "lark" / "SKILL.md", "---\nname: lark\n---\n# lark\n")
    write(tmp_path / "brief.md", "---\nname: brief\ndepends_on: [lark]\n---\n# brief\n")
    store = SkillStore(str(tmp_path / "out" / "platform.db"), owner_open_id="ou_owner")

    report = import_source(store, str(tmp_path), imported_from="fixture")
    assert report.imported == 2
    assert report.deleted == []
    assert report.dangling_links == []
    assert store.get_skill("lark") is not None
    assert store.get_skill("brief") is not None
    # The link target `lark` exists, so nothing dangles.
    assert store.find_dangling_links() == []


def test_import_reports_dangling_links(tmp_path):
    write(
        tmp_path / "brief.md", "---\nname: brief\ndepends_on: [ghost]\n---\n# brief\n"
    )
    store = SkillStore(str(tmp_path / "out" / "platform.db"), owner_open_id="ou_owner")
    report = import_source(store, str(tmp_path))
    assert report.dangling_links == [("brief", "ghost", "depends_on")]
