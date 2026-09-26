# ADR 0003 · API 为主契约，MCP 为可选适配器

- **状态**：已采纳
- **日期**：2026-09-26
- **依赖**：[ADR 0001](0001-server-authoritative.md)

## 背景

对外交付可以是自研 HTTP API，也可以是 MCP，或两者并行。工程上两者的接入成本都不高，所以真正的决策不是"选哪个"，而是**哪一个当主契约**。

MCP 有官方规范与生态，但它的协议演进非常快。

## 决定

**API 是主契约**（四个读接口 + 服务端检索排序 + 鉴权）。

**MCP 是可选适配器**，按需再加。并且接口形状**刻意对齐** MCP 的 Skills 扩展，使将来的适配器是薄封装而不是重写。

## 理由

- **协议 churn 有实证。** MCP 从上一个修订版（2025-11-25）到 2026-07-28 经历过一次**破坏性重写**：移除协议级会话与 `Mcp-Session-Id`、移除 `initialize` 握手、服务端不得再发起 JSON-RPC 请求、elicitation 改由 MRTR 承载。Roots / Sampling / Logging 进入废弃；OAuth DCR 被 CIMD 取代。规范还正式引入了功能生命周期与废弃策略（最短 12 个月窗口 + 废弃登记表）——**协议会持续演进是设计意图，不是意外**。
- **客户端侧还要额外承担兼容成本。** Claude Code 有两个 MCP runtime（v1 基于 TS SDK 1.x；v2 基于 SDK 2.0 才支持 2026-07-28），且**每次启动自己挑一个**——服务端必须跨修订可用。
- **API 完全可控。** 没有协议协商、能力声明、版本兼容矩阵；而且 console / CLI / CI 本来就需要它。**API 是资产，MCP 只是可能的客户端。**
- **安全面更小。** MCP 把凭据存放在第三方客户端里，怎么存、怎么刷新、泄露面多大都由客户端决定；纯 API 下凭据由我们自己的 CLI 持有（[ADR 0007](0007-self-built-oauth-as.md)）。
- **对齐形状几乎免费。** `/v1/skills` ≡ `skills/list`，`/body` + `/files` ≡ `resources/read`，连 401 的形状都照 MCP 的 `WWW-Authenticate` + `resource_metadata` 来。

## 后果

- 将来加 MCP 适配器时只需三件事：能力协商、方法映射（`skills/list` / `skills/get` / `resources/read`）、RFC 9728 元数据文档。复用同一个 AS。
- 客户端不支持 MCP 时，用户仍有 CLI，**功能不减**——MCP 是体验增强而非依赖项。
- 需要自己承担 API 的版本演进与向后兼容（这是可控的代价，且比跟着别人的破坏性重写走更便宜）。
- 参考：MCP 的 Skills 扩展（`io.modelcontextprotocol/skills`，SEP-2640 已 Final）在形态上与本项目模型几乎逐条对应，但**支持矩阵里 Claude 系客户端全部空白**，只有 ChatGPT / fast-agent / MCP Inspector 标 Partial。这进一步说明它不能当主契约。
