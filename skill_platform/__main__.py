"""skill-platform CLI: import / materialize / grant / revoke / resolve.

All subcommands talk to the same store. The DB path defaults to
`SKILL_PLATFORM_DB` then `/opt/data/skill-platform/skill_platform.db`.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from .importer import import_source
from .materializer import materialize
from .permissions import resolve
from .store import SkillStore

DEFAULT_DB = "/opt/data/skill-platform/skill_platform.db"


def _store(args: argparse.Namespace) -> SkillStore:
    return SkillStore(args.db or os.environ.get("SKILL_PLATFORM_DB") or DEFAULT_DB)


def _cmd_import(args: argparse.Namespace) -> int:
    store = _store(args)
    report = import_source(store, args.source, imported_from=args.from_label)
    print(f"imported {report.imported} skills, deleted {len(report.deleted)}")
    for warning in report.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    for source, target, relation in report.dangling_links:
        print(f"dangling {relation}: {source} -> {target}", file=sys.stderr)
    return 0


def _cmd_materialize(args: argparse.Namespace) -> int:
    store = _store(args)
    report = materialize(store, args.dest, names=args.names, prune=args.prune)
    print(
        f"materialized {report.written} skills, {report.files} files"
        + (f", pruned {len(report.removed)}" if report.removed else "")
    )
    for entry in report.removed:
        print(f"pruned: {entry}")
    return 0


def _cmd_grant(args: argparse.Namespace) -> int:
    store = _store(args)
    store.upsert_user(args.user)
    store.add_grant(args.user, args.skill, granted_by=args.granted_by or "cli")
    print(f"granted {args.skill} to {args.user}")
    return 0


def _cmd_revoke(args: argparse.Namespace) -> int:
    store = _store(args)
    store.remove_grant(args.user, args.skill)
    print(f"revoked {args.skill} from {args.user}")
    return 0


def _cmd_deny(args: argparse.Namespace) -> int:
    store = _store(args)
    store.upsert_user(args.user)
    store.add_denial(args.user, args.skill)
    print(f"denied {args.skill} for {args.user}")
    return 0


def _cmd_allow(args: argparse.Namespace) -> int:
    store = _store(args)
    store.remove_denial(args.user, args.skill)
    print(f"removed denial on {args.skill} for {args.user}")
    return 0


def _cmd_resolve(args: argparse.Namespace) -> int:
    store = _store(args)
    snapshot = store.load_snapshot()
    allowed = resolve(snapshot, args.user, args.skill)
    print(f"{'ALLOW' if allowed else 'DENY'} {args.user} -> {args.skill}")
    return 0 if allowed else 1


def _cmd_access(args: argparse.Namespace) -> int:
    store = _store(args)
    store.set_skill_access(args.skill, args.access)
    print(f"{args.skill} access = {args.access}")
    return 0


def _cmd_skills(args: argparse.Namespace) -> int:
    store = _store(args)
    skills = store.list_skills()
    if args.json:
        print(json.dumps(skills, ensure_ascii=False, indent=2))
        return 0
    for skill in skills:
        print(
            f"{skill['name']:<24} {skill['access']:<10} "
            f"{skill['path']:<24} {skill['title']}"
        )
    return 0


def _cmd_users(args: argparse.Namespace) -> int:
    store = _store(args)
    for user in store.list_users():
        grants = store.list_grants(user["open_id"])
        names = ", ".join(g["skill_name"] for g in grants) or "-"
        print(f"{user['open_id']}  grants: {names}")
    return 0


def _cmd_links(args: argparse.Namespace) -> int:
    store = _store(args)
    links = store.find_dangling_links() if args.dangling else store.list_links()
    for link in links:
        print(f"{link['source']} -[{link['relation_type']}]-> {link['target']}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="skill-platform",
        description="skill-platform CLI",
    )
    parser.add_argument("--db", default=None, help="SQLite db path")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("import", help="import skills from a source directory")
    p.add_argument("source", help="source root containing SKILL.md dirs / files")
    p.add_argument("--from", dest="from_label", default=None, help="source label")
    p.set_defaults(func=_cmd_import)

    p = sub.add_parser("materialize", help="render the catalog to a folder tree")
    p.add_argument("--dest", required=True, help="destination root")
    p.add_argument("--prune", action="store_true", help="remove stale skill dirs")
    p.add_argument("names", nargs="*", help="restrict to these skills")
    p.set_defaults(func=_cmd_materialize)

    p = sub.add_parser("grant", help="grant a skill (or family) to a user")
    p.add_argument("user")
    p.add_argument("skill")
    p.add_argument("--granted-by", default=None)
    p.set_defaults(func=_cmd_grant)

    p = sub.add_parser("revoke", help="revoke a grant")
    p.add_argument("user")
    p.add_argument("skill")
    p.set_defaults(func=_cmd_revoke)

    p = sub.add_parser("deny", help="deny a skill to a user (overrides grants)")
    p.add_argument("user")
    p.add_argument("skill")
    p.set_defaults(func=_cmd_deny)

    p = sub.add_parser("allow", help="remove a denial")
    p.add_argument("user")
    p.add_argument("skill")
    p.set_defaults(func=_cmd_allow)

    p = sub.add_parser("resolve", help="check whether a user may use a skill")
    p.add_argument("user")
    p.add_argument("skill")
    p.set_defaults(func=_cmd_resolve)

    p = sub.add_parser("access", help="set a skill's access level")
    p.add_argument("skill")
    p.add_argument("access", choices=("public", "private", "owner_only"))
    p.set_defaults(func=_cmd_access)

    p = sub.add_parser("skills", help="list skills")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=_cmd_skills)

    p = sub.add_parser("users", help="list users and their grants")
    p.set_defaults(func=_cmd_users)

    p = sub.add_parser("links", help="list skill links")
    p.add_argument("--dangling", action="store_true", help="only dangling links")
    p.set_defaults(func=_cmd_links)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except Exception as exc:  # noqa: BLE001 - CLI surfaces errors plainly
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
