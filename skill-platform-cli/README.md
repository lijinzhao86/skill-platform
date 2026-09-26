# skill-platform CLI

The client that holds credentials and fetches skills on demand. It is the only
component that covers unattended use, and it is what installs the gateway skill.

**Not implemented, and its language is not yet decided.** The technical design says
what it must do — `login` / `setup` / `search` / `show` / `get`, credentials kept out
of the model's context, see
[`docs/versions/v1-hosting/technical-design.md`](../docs/versions/v1-hosting/technical-design.md)
§4.6 — but not what to write it in. This directory therefore holds no build
configuration: adding one here would decide the language by accident.

When the stack is chosen, this directory becomes a self-contained project with its
own build config and tests, plus a workflow of its own in the **repository root's**
`.github/workflows/` — workflows can only live there. The rest of the repository does
not need to change: that is the point of the layout.
