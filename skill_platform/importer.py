"""Import skills from a source directory into the store.

A skill is either a directory containing a `SKILL.md` (a dir-skill) or a
standalone `.md` file at the source root that declares a `name:` in its
frontmatter (a single-file skill). Skill files are the directory contents,
minus sub-trees that are themselves skills and minus other single-file skills.

Skill links are extracted at import time: `depends_on` frontmatter entries
become `depends_on` edges, and markdown links to other skills' `SKILL.md`
files become `reference` edges.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .store import SkillStore

_MD_LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
_HIDDEN_COMPONENT = re.compile(r"(^|/)\.")


@dataclass
class SkillSpec:
    name: str
    path: str
    title: str
    description: str
    content: str
    files: list[tuple[str, bytes, bool]] = field(default_factory=list)
    links: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class ImportReport:
    imported: int
    deleted: list[str]
    warnings: list[str] = field(default_factory=list)
    dangling_links: list[tuple[str, str, str]] = field(default_factory=list)


def _parse_scalar(value: str):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        if not inner:
            return []
        return [part.strip().strip("'\"") for part in inner.split(",")]
    return value


def parse_frontmatter(text: str) -> tuple[dict, str]:
    """Return (frontmatter, body). Handles flat `key: value` and block values
    with indented `- item` lists. Nested maps are ignored."""
    lines = text.split("\n")
    if not lines or lines[0].strip() != "---":
        return {}, text
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        return {}, text
    body = "\n".join(lines[end + 1 :]).lstrip("\n")
    fm: dict = {}
    i = 1
    while i < end:
        line = lines[i]
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            i += 1
            continue
        if line[:1] in (" ", "\t"):
            i += 1
            continue
        if ":" in stripped:
            key, _, value = stripped.partition(":")
            key = key.strip()
            value = value.strip()
            if value:
                fm[key] = _parse_scalar(value)
                i += 1
            else:
                items: list[str] = []
                i += 1
                while i < end and lines[i][:1] in (" ", "\t"):
                    item = lines[i].strip()
                    if item.startswith("- "):
                        items.append(item[2:].strip())
                    i += 1
                fm[key] = items if items else ""
            continue
        i += 1
    return fm, body


def _is_binary(data: bytes) -> bool:
    try:
        data.decode("utf-8")
        return False
    except UnicodeDecodeError:
        return True


def _read_bytes(path: str) -> bytes:
    with open(path, "rb") as fh:
        return fh.read()


def _read_text(path: str) -> str:
    return _read_bytes(path).decode("utf-8", errors="replace")


def _iter_skill_md_dirs(source_root: str) -> set[str]:
    dirs: set[str] = set()
    for dirpath, dirnames, filenames in os.walk(source_root):
        dirnames[:] = [d for d in dirnames if not _HIDDEN_COMPONENT.match(d)]
        if "SKILL.md" in filenames:
            dirs.add(dirpath)
    return dirs


def _collect_files(
    skill_dir: str,
    all_skill_dirs: set[str],
    excluded_files: set[str],
) -> list[tuple[str, bytes, bool]]:
    files: list[tuple[str, bytes, bool]] = []
    for dirpath, dirnames, filenames in os.walk(skill_dir):
        dirnames[:] = [
            d
            for d in dirnames
            if not _HIDDEN_COMPONENT.match(d)
            and os.path.join(dirpath, d) not in all_skill_dirs
        ]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            if full == os.path.join(skill_dir, "SKILL.md"):
                continue
            if fn.startswith(".") or full in excluded_files:
                continue
            data = _read_bytes(full)
            files.append((os.path.relpath(full, skill_dir), data, _is_binary(data)))
    files.sort(key=lambda item: item[0])
    return files


def _extract_links(
    fm: dict,
    body: str,
    skill_dir: str,
    name_map: dict[str, str],
) -> list[tuple[str, str]]:
    links: list[tuple[str, str]] = []
    for dep in fm.get("depends_on") or []:
        links.append((str(dep), "depends_on"))
    for match in _MD_LINK_RE.finditer(body):
        href = match.group(1).strip().split("#")[0].split("?")[0]
        if not href.lower().endswith(".md"):
            continue
        target_dir = os.path.normpath(os.path.join(skill_dir, os.path.dirname(href)))
        if target_dir in name_map:
            links.append((name_map[target_dir], "reference"))
    return links


def discover_skills(source_root: str) -> tuple[list[SkillSpec], list[str]]:
    """Scan `source_root` and return (specs, warnings)."""
    source_root = os.path.abspath(source_root)
    warnings: list[str] = []

    skill_dirs = _iter_skill_md_dirs(source_root)

    # Standalone single-file skills directly under the source root.
    standalone: dict[str, tuple[str, str, dict]] = {}
    for fn in sorted(os.listdir(source_root)):
        full = os.path.join(source_root, fn)
        if not os.path.isfile(full) or not fn.lower().endswith(".md"):
            continue
        text = _read_text(full)
        fm, _ = parse_frontmatter(text)
        name = fm.get("name")
        if name:
            standalone[full] = (str(name), text, fm)

    if not skill_dirs and not standalone:
        raise ValueError(f"no skills found under {source_root}")

    # abs skill dir -> canonical name, from frontmatter or the dir basename.
    name_map: dict[str, str] = {}
    for skill_dir in skill_dirs:
        text = _read_text(os.path.join(skill_dir, "SKILL.md"))
        fm, _ = parse_frontmatter(text)
        name = str(fm.get("name") or os.path.basename(skill_dir))
        name_map[skill_dir] = name

    specs: list[SkillSpec] = []
    seen_names: set[str] = set()
    excluded_files = set(standalone.keys())

    for skill_dir in sorted(skill_dirs):
        name = name_map[skill_dir]
        if name in seen_names:
            raise ValueError(f"duplicate skill name {name!r}")
        seen_names.add(name)
        rel = os.path.relpath(skill_dir, source_root)
        path = "" if rel == "." else rel
        content = _read_text(os.path.join(skill_dir, "SKILL.md"))
        fm, body = parse_frontmatter(content)
        spec = SkillSpec(
            name=name,
            path=path,
            title=str(fm.get("title") or name),
            description=str(fm.get("description") or ""),
            content=content,
            files=_collect_files(skill_dir, skill_dirs, excluded_files),
            links=_extract_links(fm, body, skill_dir, name_map),
        )
        specs.append(spec)

    for full, (name, text, fm) in standalone.items():
        if name in seen_names:
            raise ValueError(f"duplicate skill name {name!r}")
        seen_names.add(name)
        stem = os.path.splitext(os.path.basename(full))[0]
        links = _extract_links(fm, text, source_root, name_map)
        spec = SkillSpec(
            name=name,
            path=stem,
            title=str(fm.get("title") or name),
            description=str(fm.get("description") or ""),
            content=text,
            files=[],
            links=links,
        )
        specs.append(spec)

    return specs, warnings


def import_source(
    store: SkillStore,
    source_root: str,
    imported_from: str | None = None,
) -> ImportReport:
    """Import all skills under `source_root` into `store`, atomically."""
    specs, warnings = discover_skills(source_root)
    records = [
        {
            "name": spec.name,
            "title": spec.title,
            "description": spec.description,
            "content": spec.content,
            "path": spec.path,
            "files": spec.files,
            "links": spec.links,
        }
        for spec in specs
    ]
    result = store.import_skills(records, imported_from or os.path.abspath(source_root))
    imported_names = {spec.name for spec in specs}
    dangling = [
        (spec.name, target, relation)
        for spec in specs
        for target, relation in spec.links
        if target not in imported_names
    ]
    return ImportReport(
        imported=result["imported"],
        deleted=result["deleted"],
        warnings=warnings,
        dangling_links=dangling,
    )
