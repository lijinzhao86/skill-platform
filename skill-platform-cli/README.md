# skill-platform CLI

The client that holds credentials and fetches skills on demand. It is the only
component that covers unattended use, and it is what installs the gateway skill.

**Not implemented yet; the language is decided — Go**
([ADR 0011](../docs/decisions/0011-server-and-cli-stack.md)). The technical design says
what it must do — `login` / `setup` / `search` / `show` / `get`, credentials kept out
of the model's context, see
[`docs/versions/v1-hosting/technical-design.md`](../docs/versions/v1-hosting/technical-design.md)
§4.6. This directory still holds no build configuration because there is **no code yet**:
adding one now would only produce a workflow that looks green while building nothing.

When the code lands, this directory becomes a self-contained project with its
own build config and tests, plus a workflow of its own in the **repository root's**
`.github/workflows/` — workflows can only live there. The rest of the repository does
not need to change: that is the point of the layout.
