# skill-platform

A hosting platform for AI-agent [skills](https://agentskills.io). The design keeps
skills on the server and exposes them over a remote API — agents search, read, and
use them on demand, so progressive disclosure is enforced server-side and no skill
copy is installed on the client.

**Status: designed, not yet implemented.** The product and technical documents under
[`docs/`](docs/) are the source of truth — start at [`docs/README.md`](docs/README.md).

## Layout

One repository, several independently built subprojects. Each subproject owns its
toolchain, its tests, and its workflow, and **nothing at the repository root assumes
a language** — so a subproject can be Python, Node, or anything else without
disturbing the others.

- `skill-platform-server/` — the API and authorization server, one process on SQLite
  to start. Self-contained: its own `pyproject.toml`, `Dockerfile`, `tests/`. Its CI is
  `.github/workflows/server.yml` at the repository root — workflows can only live there.
  **Not runnable yet:** no entry point.
- `skill-platform-cli/` — the client that holds credentials and fetches skills on
  demand. **Not implemented, and its stack is not yet decided** — see its README.
- `gateway/skill-platform/` — source of the gateway skill: the only skill installed
  locally, and the protocol agents follow to fetch the rest. Shared by the server
  (which serves it) and the CLI (which installs it).
- `.claude/skills/docs-architecture/` — the documentation convention: the rules, the
  templates, and (under `scripts/`) the validator that enforces them plus its tests.
  Repository-wide, and part of no subproject.
- `docs/` — product and technical documentation.
- `.github/workflows/` — one workflow serving each subproject, plus one for `docs/` and
  `.claude/` (which belong to no subproject). Each is gated by path filters, so a change
  to one does not run another's CI.

## Quick start

```bash
cd skill-platform-server
uv sync
uv run pytest
uv run ruff check .
```

Nothing is runnable end to end yet — the server has no entry point and the CLI does
not exist. What P0 delivers is in the
[v1-hosting technical design](docs/versions/v1-hosting/technical-design.md).
