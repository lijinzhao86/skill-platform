"""Materialize the skill catalog into a folder tree the runtime can load.

Each skill renders into `dest_root` at the directory recorded in its `path`
column (e.g. `lark` -> `<dest>/lark/`, `lark/im` -> `<dest>/lark/im/`), so
relative links inside SKILL.md routing tables stay valid. Standalone skills
(path is just the file stem) render under their `name` instead.

Pruning is conservative: `prune=True` removes top-level entries of `dest_root`
that carry a SKILL.md but are not produced by the current catalog. Entries the
catalog still produces are left untouched even if they contain foreign files.
"""

from __future__ import annotations

import os
import shutil
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .store import SkillStore


@dataclass
class MaterializeReport:
    written: int
    files: int = 0
    removed: list[str] = field(default_factory=list)


def _skill_target_dir(dest_root: str, path: str, name: str) -> str:
    rel = path or name
    target = os.path.normpath(os.path.join(dest_root, rel))
    if not target.startswith(dest_root):
        raise ValueError(f"skill path escapes dest_root: {path!r}")
    return target


def _expected_top_levels(skills: list[dict]) -> set[str]:
    top: set[str] = set()
    for skill in skills:
        rel = skill["path"] or skill["name"]
        top.add(rel.split(os.sep)[0])
    return top


def materialize(
    store: SkillStore,
    dest_root: str,
    names: list[str] | None = None,
    prune: bool = False,
) -> MaterializeReport:
    """Render all (or `names`) skills under `dest_root`."""
    if names:
        skills = [store.get_skill(n) for n in names]
        skills = [s for s in skills if s]
    else:
        skills = store.list_skills()
    # list_skills() omits content; fetch full rows so rendering is self-contained.
    skills = [store.get_skill(s["name"]) for s in skills]

    report = MaterializeReport(written=len(skills))
    for skill in skills:
        target = _skill_target_dir(dest_root, skill["path"], skill["name"])
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "SKILL.md"), "w", encoding="utf-8") as fh:
            fh.write(skill["content"])
        report.files += 1
        for file in store.list_skill_files(skill["name"]):
            relpath = file["relpath"]
            if relpath == "SKILL.md":
                continue  # already rendered from the skill's content
            content, is_binary = store.get_skill_file(skill["name"], relpath)
            full = os.path.join(target, relpath)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            mode = "wb" if is_binary else "w"
            with open(full, mode) as fh:
                if is_binary:
                    fh.write(content)
                else:
                    fh.write(content.decode("utf-8", errors="replace"))
            report.files += 1

    if prune:
        keep = _expected_top_levels(skills)
        for entry in sorted(os.listdir(dest_root)):
            full = os.path.join(dest_root, entry)
            if entry in keep or not os.path.isdir(full):
                continue
            if not os.path.isfile(os.path.join(full, "SKILL.md")):
                continue
            shutil.rmtree(full)
            report.removed.append(entry)
    return report
