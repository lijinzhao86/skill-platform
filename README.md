# skill-platform

A hosting platform for AI-agent [skills](https://agentskills.io). The design keeps
skills on the server and exposes them over a remote API — agents search, read, and
use them on demand, so progressive disclosure is enforced server-side and no skill
copy is installed on the client.

**Status: designed, not yet implemented.** The product and technical documents under
[`docs/`](docs/) are the source of truth — start at [`docs/README.md`](docs/README.md).

## Layout

- `skill_platform/` — pre-rewrite baseline package (SQLite store, permission
  resolution, materializer). Its architecture predates the current design; see
  [ADR 0001](docs/decisions/0001-server-authoritative.md), which makes the
  materializer largely obsolete.
- `tests/` — unit tests for the baseline package.
- `docs/` — product and technical documentation.
- `.github/workflows/ci.yml` — lint + tests on every PR, image build/push on `main`.

## Quick start

```bash
uv run skill-platform --help
```
