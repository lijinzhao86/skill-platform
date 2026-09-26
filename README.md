# skill-platform

Skill management platform for AI agents. Skills are stored like a database,
managed through a web console, and loaded progressively at runtime.

## Layout

- `skill_platform/` — core package: SQLite store, permission resolution,
  git importer, and DB → folder materializer. Single source of truth shared
  by the runtime gate and the console.
- `tests/` — unit tests for the core package.

## Quick start

```bash
uv run skill-platform --help
```
