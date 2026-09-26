# skill-platform 系统架构设计

> 最后更新：2026-09-26
> 状态：技术设计稿，尚未进入实现
> 所属版本：[v1-hosting](README.md)——版本由文件夹承担，本文不再自带版本号
> **定位：skill 的托管与远程加载服务。** skill 全部活在服务端；用户通过网关 skill 与 CLI 远程搜索、读取、使用 skill，**本地不留 skill 副本**。
> **本版不含收费**：无定价、无权益、无门控、无钱包。已设计过的渐进付费方案见附录 B（推迟，非废弃）。
> 上一轮修订把架构从「下载到本地 + 客户端原生加载」改为「服务端权威 + 网关 skill + API」，并按此写出表结构与接口。
> 第 1 章是核实过的调研事实，与架构选择无关，改动架构时不必重做。**架构决策已独立成 [ADR](../../decisions/README.md)**，本文只链接、不复述理由。

---

## 第 0 章 · 结论摘要

### 三条结论

1. **不做「下载到本地再靠客户端原生加载」。** 本项目采用**服务端权威**：skill 的目录与文件都在服务器上，客户端通过远程地址按需读取。好处是**渐进加载天然成立**（搜索只给 L1、正文接口只给 L2、文件接口只给 L3，服务端在接口层强制），且**本地不落任何 skill 副本**。

2. **唯一需要本地安装的是一个「网关 skill」+ 一个 CLI。** 网关 skill 的 frontmatter 是常驻上下文的全部成本（≈80 tokens——它是我们自己写的纯英文 skill），它的正文记录服务地址与调用协议；CLI 负责登录与持有凭据。目录**不常驻**——搜索、排序、查找全部在服务端。

3. **一个网关 skill 解决了 L1 天花板。** 本机 30 个真实 SKILL.md 实测：每个 skill 的 L1 平均 318 字符，**其中约 33% 是 CJK 字符**——按 4 字符/token 折算约 80 tokens，但 CJK 接近 1 token/字符，实际**约 160 tokens**。500 个 skill 全量安装 = **约 8 万 tokens 常驻**（口径见 §1.6）。网关模型下常驻的只有网关一个，**目录成本从 O(N) 降到 O(1)**。

### 架构总览

```
┌──────────────────────── 服务端（唯一的 skill 真相）────────────────────────┐
│                                                                            │
│   ┌─ 身份与鉴权 ─────────────┐   ┌─ 内容 ──────────────────────────────┐   │
│   │ user / credential       │   │ namespace / skill / skill_version   │   │
│   │ identity（预留 SSO）     │   │ version_file / blob（内容寻址）      │   │
│   │ oauth_client / token    │   │                                     │   │
│   └─────────────────────────┘   └─────────────────────────────────────┘   │
│                                                                            │
│   ┌─ 检索 ───────────────┐      ┌─ 分发 ──────────────────────────────┐   │
│   │ 只索引 L1            │      │ /v1/skills          （搜索）        │   │
│   │ 搜索 / 排序 / 查找    │      │ /v1/skills/{id}     （详情+清单）    │   │
│   └──────────────────────┘      │ /v1/skills/{id}/body       （L2）   │   │
│                                 │ /v1/skills/{id}/files/{p}  （L3）   │   │
│                                 │ /.well-known/...  （只发布网关）     │   │
│                                 └─────────────────────────────────────┘   │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              ▼                                           ▼
   ┌─ CLI（普适客户端）──────────┐          ┌─ MCP 适配器（可选，以后加）─┐
   │ login / setup / search /   │          │ skills/list ≡ /v1/skills    │
   │ show / get                 │          │ resources/read ≡ body+files │
   │ 持有凭据，不进模型上下文    │          │ 复用同一个 AS               │
   └────────────┬───────────────┘          └─────────────────────────────┘
                │ 安装一个网关 skill
                ▼
   ┌─ 网关 skill（唯一的本地产物）───────────────────────────────────────┐
   │ frontmatter：常驻上下文（≈80 tokens），description 写得足够广        │
   │ 正文：服务地址 + 调用协议（先搜索 → 看清单 → 按需读正文/文件）        │
   └──────────────────────────────────────────────────────────────────────┘
                │ agent 依协议调用
                ▼
        远程取到 skill 内容 → 使用
```

### 已锁定的架构决策

| 决策 | 内容 |
|---|---|
| 定位 | 托管 + 远程加载 + 使用；**不含收费** |
| 权威位置 | **服务端**；本地不落 skill 副本 |
| 主契约 | **API**（四个读接口 + 服务端搜索排序）；MCP 是可选适配器 |
| 客户端 | **自研 CLI**，持有凭据；普适（交互式 loopback PKCE + 无人值守 Client Credentials） |
| 网关粒度 | **一个通用网关 skill** |
| 搜索排序 | **全部在服务端**（目录不常驻，服务端就是索引） |
| 鉴权 | **自建登录 + 自建令牌签发**（OAuth 2.1 AS，支持 CIMD + DCR + 预注册） |
| skill 主键 | **不透明的 `id`**，永不变；`name` 是属性，唯一性约束在 `(namespace_id, name)` |
| 脚本执行 | P0 不做 |

---

## 第 1 章 · 调研结论（可核实的事实）

本章只写有出处的事实，推断单独标注。**每条结论都给了出处**，核实到 2026-09-26；个别客户端细节的把握程度见 §1.7 的信心说明——**不要把这一章读成"每条都验证到底"**。

### 1.1 Agent Skills 是真实的开放标准

出处：[agentskills.io/specification](https://agentskills.io/specification)、[客户端实现指南](https://agentskills.io/client-implementation/adding-skills-support)。

- 一个 skill = 一个目录，至少含 `SKILL.md`（YAML frontmatter + Markdown 正文），可选 `scripts/`、`references/`、`assets/`。
- frontmatter 约束（规范原文）：

| 字段 | 必填 | 约束 |
|---|---|---|
| `name` | 是 | ≤64 字符；仅小写字母数字与连字符；不得以连字符开头/结尾；不得连续连字符；**必须等于父目录名** |
| `description` | 是 | 1–1024 字符，非空 |
| `license` | 否 | 许可证名或文件引用 |
| `compatibility` | 否 | ≤500 字符 |
| `metadata` | 否 | string→string 映射（**实际生态里会被嵌套**，见 1.5 的飞书案例） |
| `allowed-tools` | 否 | 空格分隔的预批准工具（实验性） |

- 三层渐进加载（规范原文的分层与 token 指引）：
  1. **Metadata**（~50–100 tokens）：`name` + `description`，启动时为**所有** skill 加载
  2. **Instructions**（建议 <5000 tokens）：SKILL.md 正文，skill 被激活时加载
  3. **Resources**（按需）：`scripts/` `references/` `assets/` 里的文件，仅在正文引用到时加载

- **`.agents/skills/` 是跨客户端约定**（`~/.agents/skills/` 与 `<project>/.agents/skills/`），实现指南明确列出该路径用于跨客户端互操作。
- 客户端实现指南给了**两种激活模式**：文件读取激活，与**专用工具激活**（如 `activate_skill`）。指南说专用工具在"模型无法直接读文件"时是**必需**的、在能读时"可选但有用"，并明确"两种做法在实践中都可行"——**它没有把哪一种排在前面**。专用工具的好处包括：控制返回内容、用结构化标签包裹、列出附属资源、执行权限控制或请求用户同意、记录激活用于分析。
- 实现指南明确云端/沙箱 agent 的处境：没有本地文件系统时，"需要一个替代的发现机制——一个 API、一个远程 registry，或者内置资源"。**这正是本项目的立足点。**
- 过滤器规则：被排除的 skill 必须整个从目录里隐藏，而不是列出来再在激活时拦截。

> **对方案的直接意义**：标准只规定 skill 目录里放什么，**不规定它从哪来**。所以"服务端托管 + 远程读取"在标准层面完全开放——我们不需要自创概念。

### 1.2 Claude Code 的实际加载行为

出处：[code.claude.com/docs/en/skills](https://code.claude.com/docs/en/skills)、[plugins/host-marketplace](https://code.claude.com/docs/en/plugins/host-marketplace)、[mcp](https://code.claude.com/docs/en/mcp)、[issue #11054](https://github.com/anthropics/claude-code/issues/11054)。

**加载位置**（至少这五个）：企业托管目录、`~/.claude/skills/`、`<project>/.claude/skills/`、plugin 内的 `skills/`、claude.ai 同步的 `~/.claude/skills/synced/`。官方表还列了嵌套的 `<subdir>/.claude/skills/` 与 `--add-dir` 指定的目录，共七行。

**三层的实际成本**：
- frontmatter 的 `description` + `when_to_use` 每轮常驻，**截断于 1536 字符**
- 正文在被调用时加载，且**此后整个会话常驻**
- 附属文件按需读取

**MCP 的能力边界（决定了 MCP 只能是适配器）**：
- **MCP 无法注入 skill。** skill 是宿主特性，SDK 不提供编程注册接口。
- MCP resources **不会自动注入**，只在模型调用 `ListMcpResourcesTool` / `ReadMcpResourceTool` 或用户 `@server:uri` 时可见。
- MCP prompts **对模型完全不可见**（issue #11054）。
- MCP 只能"把 skill 文本作为工具结果返回"，模型把它当数据读——没有自动调用，没有原生三层披露。

**官方支持的、能落盘的远程路径：plugin marketplace**
- `marketplace.json` 可托管在**一个普通 HTTPS URL** 上，且**只下载那一个文件** → 支持按请求动态生成。
- entry 的 `archive` source 支持 `sha256` 固定，**拒绝 digest 不匹配的下载**（需 Claude Code ≥ v2.1.224）。
- 认证 header 放在 **marketplace 的 `url` source** 上；放到 entry 的 `headersHelper` 上会**弄坏后台自动更新**。
- 自动更新对第三方 marketplace **默认关闭**。

> **本项目不使用 marketplace 通道**（它意味着把内容下载到本地）。记录在此是因为 MCP 适配器将来若要支持"原生安装体验"会用到。

### 1.3 MCP 的 Skills 扩展（就是我们的 API 形状）

出处：[extensions/skills/overview](https://modelcontextprotocol.io/extensions/skills/overview)、[SEP-2640](https://modelcontextprotocol.io/seps/2640-skills-extension)、[客户端支持矩阵](https://modelcontextprotocol.io/extensions/client-matrix)。

- 标识符 `io.modelcontextprotocol/skills`，**SEP-2640 已 Final**。方法：`skills/list`、`skills/get`（**必须**实现）、`resources/read`、可选的 `resources/directory/read`。

**与我们的接口逐条对应**（所以 MCP 适配器会很薄）：

| 扩展机制 | 我们的接口 |
|---|---|
| `skills/list` → `frontmatter` + `uri` + 完整文件清单 | `GET /v1/skills`（搜索）+ `GET /v1/skills/{id}`（清单） |
| `resources/read` on `skill://<name>/SKILL.md` | `GET /v1/skills/{id}/body` |
| `resources/read` on 清单里的每个 URI | `GET /v1/skills/{id}/files/{relpath}` |

**规范中我们直接采用的条款**（原文）：

- **"Hosts MUST NOT retrieve files ahead of need, including on connection, listing, or approval."** —— 渐进披露是协议强制的。
- 清单**必须**含 `SKILL.md` 与每个附属文件，每项带 URI、SHA-256 digest、字节大小。**清单让客户端无需下载即可枚举 L3。**
- 服务端 **SHOULD NOT** 超过 **每 skill 512 文件 / 16 MiB**（含 SKILL.md）。→ **成为我们上传校验的上限依据。**
- **"Persisted approval MUST bind to the complete set of file URIs and digests. A changed, added, or removed file revokes that approval."** → **发布新版本即改变 digest 集合，客户端据此重新取得批准。这层完整性机制不用我们做。**
- `SKILL.md` 父目录路径的最后一段 **MUST** 等于 `name`。
- 未知 skill/文件 → `-32602`。
- 安全：宿主 **MUST** 防止同名 skill 静默互相替换；**MUST** 把 skill 内容视为不可信输入。

**客户端支持现状**：矩阵里 Claude (web)/Desktop、Cursor、VS Code Copilot、Microsoft 365 Copilot、Goose、Postman 等**全空白**；只有 ChatGPT、fast-agent、MCP Inspector 标 Partial。**扩展已 Final，宿主没跟上。**

### 1.4 MCP 协议现状与鉴权（为什么 MCP 只当适配器）

出处：[2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)、[authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)。

**协议 churn 是实证的**：**上一个修订版 2025-11-25 → 2026-07-28 是一次破坏性重写**——移除协议级会话与 `Mcp-Session-Id`、移除 `initialize` 握手、服务端不得再发起 JSON-RPC 请求、elicitation 改由 MRTR 承载。（changelog 的原话是"自上一个修订版以来"；2025-06-18 是更早的版本，不要拿它当基线。）Roots/Sampling/Logging 进入废弃；OAuth DCR 被 CIMD 取代（仅保留兼容）。规范还引入了功能生命周期与废弃策略（最短 12 个月窗口 + 废弃登记表）。**Claude Code 侧的具体成本**：它有两个 runtime（v1 基于 TS SDK 1.x；v2 基于 SDK 2.0 才支持 2026-07-28），**每次启动自己挑一个** → 服务端必须跨修订可用。

**鉴权规范的关键约束**（若将来做 MCP 适配器，这些是 MUST）：

- MCP server = OAuth 2.1 **resource server**；MCP client = OAuth 2.1 **client**（**客户端做 OAuth 流程**）
- server **MUST** 实现 RFC 9728 Protected Resource Metadata；client **MUST** 用它做 AS 发现
- AS **MUST** 至少提供 RFC 8414 AS Metadata 或 OIDC Discovery 之一
- client **MUST** 实现 RFC 8707 resource indicators；server **MUST** 校验 token audience，**MUST NOT** 中转任何其他 token
- 注册方式：CIMD **SHOULD**（推荐默认）；DCR **MAY** 但已废弃；另有预注册
- token 走 `Authorization: Bearer`，**MUST NOT** 出现在 query string
- 授权是可选的："Authorization is **OPTIONAL** for MCP implementations"

### 1.5 现成的远程托管约定：well-known 索引

**消费方已查清：`skills`（Vercel Labs）。**

- npm 包 `skills`（bin `skills`/`add-skill`），仓库 [vercel-labs/skills](https://github.com/vercel-labs/skills)，支持 80 个 agent（含 Claude Code、Codex、Cursor）。安装命令 `npx skills add <url>`。**symlink 模式是该 CLI 推荐的安装模式。**
- lock 路径：`$XDG_STATE_HOME/skills/.skill-lock.json`，否则 `~/.agents/.skill-lock.json`。
- lock 字段：`source`、`sourceType`、`sourceUrl`、`sourceBaseUrl`、`wellKnownDigest`、`installedAt`、`updatedAt`、`skillFolderHash`。

**协议**（`/.well-known/agent-skills/` 首选，`/.well-known/skills/` 是 v0.1.0 遗留回退）：

| | 形状 | 更新检测 |
|---|---|---|
| **V1** | `{ skills: [{ name, description, files: string[] }] }`——逐文件，客户端逐个拉取 | **客户端自行计算**：按路径排序后对 `path` + `\0` + 字节 + `\0` 逐项 sha256。**分隔符是 NUL，不是空格**（已对本机缓存的 CLI 1.5.24 反查 `computeWellKnownSkillDigest` 确认） |
| **V2** | `{ $schema, skills: [{ name, type: "skill-md"\|"archive", description, url, digest }] }` | 直接取 entry 的 `digest` |

解析顺序：`agent-skills/index.json` → `skills/index.json`；**先按路径相对、再按根**。该 provider 刻意拒绝在带 scope 的路径没内容时回退到根索引。

**这不是 Agent Skills 标准的一部分**——`agentskills.io/llms.txt` 的文档索引里**没有 discovery/publishing/hosting 章节**，它是这个 CLI 的私有约定。一个例外值得记：V2 索引的 `$schema` 指向 `agentskills.io` 域下的 `discovery/0.2.0` 草案，CLI 源码称其为 "the v0.2.0 draft"——所以它挂在标准轨道边上，但**仍是草案、未进标准**（该 schema URL 目前 DNS 不解析，内容无法核实）。**所以它随时可能变，要当外部依赖管理。**

**飞书是这个通道的真实生产用户**（2026-09-26 本机实测）：

| URL | 结果 |
|---|---|
| `open.feishu.cn/.well-known/skills/index.json` | **200，`application/json`，39,955 B，28 条**，entry 为 `{name, description, files[]}` → **V1** |
| `open.feishu.cn/.well-known/skills/lark-approval/SKILL.md` | 200，`text/markdown`，6,969 B |
| `open.feishu.cn/.well-known/agent-skills/index.json` | **200 但 `text/html`** —— SPA catch-all，**不是发布的索引** |

**两个必须记住的坑**：

1. **首选路径在不发布它的主机上返回 200 + HTML**（不是 404）。所以我们要发布时**两条路径都发**，且**自己未发布时必须返回真 404**。
2. 旧版 CLI 只认 legacy 路径——首选路径是 2026-03-23 才加进 CLI 的，**分界在 1.4.5**：实测 npm 上 **1.4.5 及以前没有**该路径，**1.4.6 起才有**。本机缓存的 1.5.12 与 1.5.24 都已认（已反查确认），所以"本机版本"不是风险来源，风险来自更老的客户端。**两条路径都发仍然是对的，但理由是兼容 1.4.5 及以前**，不是"本机版本可能不认"。

飞书用的 `version`、`metadata.requires.bins`、`metadata.cliHelp` 都是**发布方扩展**，不属标准字段集（且 `metadata` 实际是嵌套的）。**我们要原样透传未知字段**，否则会丢作者的元数据。

**两条硬约束**：**没有认证**（provider 里 `authorization`/`Bearer` 零出现）；**没有任何门控概念**（全仓搜 entitlement/paywall/billing 无结果）。→ **本项目的用法：这条通道只用来发布「网关 skill」这一个公开产物**，其余全部走鉴权后的 API。

### 1.6 实测数据（决定设计的数字）

对本机 30 个真实 SKILL.md 的测量（`~/.agents/skills/lark-*` 等 28 个，加 `~/.claude/skills/lark/` 与 `lark/feishu-bridge/`）。**每行都写明口径，以便复现**：

| 指标 | 口径 | 数值 |
|---|---|---|
| L1（frontmatter）平均 | `---` 之间的内容，不含分隔符 | **318 字符** |
| 其中 CJK 字符占比 | 同上；CJK＝表意文字 + CJK 标点 + 全角 + 假名 | **32.7%**（`lark-task` 最高 58.7%） |
| L1 tokens / skill | 4 字符/token（只对拉丁文本成立） | ≈80 |
| L1 tokens / skill | CJK 按 ~1 token/字符折算 | **≈160** ← 本样本的正确量级 |
| L1 / 全部内容 | L1 ÷（L1 + 正文） | **4.2%** |
| 500 个 skill 全装时的 L1 常驻 | 按 CJK 折算 | **≈ 8 万 tokens** |
| L2 正文 中位数 / 最大 | SKILL.md 去掉 frontmatter，字符 | 3,962 / 25,213 |
| L3 单文件字节 中位数 / 最大 | 每个非 `SKILL.md` 文件 | 4,362 / 746,862 |
| L3 每 skill 合计字节 中位数 / 最大 | 一个 skill 的全部非 `SKILL.md` 文件之和；**9 个无 L3 的按 0 计入** | 29,736 / 1,570,809 |
| ↳ 同上，只算有 L3 的 21 个 skill | 剔除那 9 个 0 | 148,017 |
| 没有 L3 文件的 skill | — | 9 / 30（4 个正文仅 **133** 字符，是指针型；另 5 个有实质正文） |
| `name` 与父目录名不符 | — | `lark/im/`（name `lark-im`）等**不符**；`~/.agents/skills/lark-*` 扁平布局**符合** |

**两条推论**：

- **L1 是天花板。** 单个 skill 的 L1 看着很小（本机样本约 160 tokens），但它在"所有已安装 skill"上线性常驻——500 个就是 8 万。**→ 这是网关模型的直接理由**：常驻 1 个网关，而不是 N 个 skill。
- **命名合规不是形式问题。** 标准要求 `name` 等于父目录名。服务端托管时这个约束作用在**导出/打包**上；内部存储用 `relpath`，不受影响。

### 1.7 MCP 鉴权的客户端支持现状（决定「CLI 是普适客户端」）

出处：MCP 扩展支持矩阵、各客户端官方文档（详见附录 C）。

| 客户端 | 浏览器 OAuth | 静态 header |
|---|---|---|
| Claude Code | ✅ `/mcp`、`claude mcp login/logout` | ✅ `--header` / `headersHelper` |
| Claude Desktop / Claude.ai (web) | ✅ | — |
| ChatGPT | ✅ OAuth 2.1 + PKCE，**CIMD 优先** | API key 与其 OAuth connector 不原生兼容 |
| Cursor | ✅ | ✅ `headers` |
| VS Code / Copilot | ✅ 由 VS Code 跑流程 | ✅ `${input:}` 安全提示 |
| Codex CLI | ✅ `codex mcp login`，**仅 HTTP** | 走 config |
| Gemini CLI | ✅ **DCR**；文档明说无浏览器环境不工作 | ✅ `-H` |
| OpenCode / Antigravity | ✅ | — |
| Zed / Cline / Continue / Open WebUI / Goose / LibreChat | 部分 | — |
| Windsurf / Amazon Q | 未知 / 未实现 | — |

**两类结论**：

1. **交互式鉴权：覆盖广。** 主流客户端都做浏览器 OAuth。但**连接方式受限**——Codex 是 HTTP-only；Gemini CLI 明说「无浏览器、远程 SSH 无 X11 转发、无浏览器的容器环境都不工作」。且**静态 header 是人人都有、但没有一个是"登录体验"**——都要用户去配置文件粘贴 token。

2. **无人值守 / M2M：现在没人支持。** OAuth **Client Credentials 扩展还是 Draft，客户端矩阵里只有 Archestra.AI 打勾**。官方 SDK 已实现（TS/Python 都有），所以**服务端容易做**，但**没有现成 agent 能用**。调研原话：**「CI/headless 需要一个基于 SDK 自研的客户端，而不是现成 agent。」**

> **对方案的直接意义**：**CLI 不是 MCP 的退路，它是唯一覆盖无人值守场景的客户端。** 分工是——交互式用户可用 MCP OAuth（体验好），**无人值守只有 CLI 能覆盖**。

**一个实现上的坑：CIMD 支持很薄。** 已确认发布 CIMD 的只有 Claude Code、VS Code、ChatGPT；Cursor / Gemini CLI / Codex 未确认（Gemini CLI 文档压根没提，明确说走 DCR）；且 2025-12 时 Auth0、Okta、Cognito、Entra、Google Identity **都还没实现 CIMD**。→ **我们的 AS 必须同时支持 CIMD + DCR + 预注册三种注册方式**，否则会卡死一批客户端。

**信心说明**：本节部分依赖社区维护的矩阵与文档镜像（Codex 官方文档与部分厂商文档抓取 403，只能靠二手）。**「交互式覆盖广、M2M 无人支持」这两个结论稳；「每个客户端的具体细节」按需再核。**

---

## 第 2 章 · 系统架构

### 2.1 三个平面

```
管理面 ── 上传 / 校验 / 版本 / 命名空间 / 成员 / 审计
   │ 发布：不可变版本 + digest
存储面 ── blob（按 sha256 内容寻址）/ skill / version / manifest
   │ 检索与分发
分发面 ── 搜索 / 详情 / 正文 / 文件 / 鉴权 / （可选）MCP 适配器
   │
客户端 ── CLI（持有凭据）→ 安装网关 skill → agent 依协议按需读取
```

**最重要的边界：服务端是唯一的 skill 真相。** 本地只有两样东西——CLI 与网关 skill。**skill 的正文与文件永远不落到本地**（agent 为完成任务的中间产物落盘不算，那是两回事，见 4.6）。

### 2.2 组件

| 组件 | 职责 | 起步形态 |
|---|---|---|
| **API（资源服务器）** | 搜索、详情、正文、文件；校验令牌、按 subject 过滤 | FastAPI + SQLite |
| **AS（授权服务器）** | 登录、授权、令牌签发与刷新、撤销 | 与 API 同进程（规范允许），用 `authlib` |
| **Blob Store** | 按 sha256 存文件字节 | 本地目录 |
| **Blob GC** | 回收无版本引用的 blob | 后台任务 |
| **CLI** | 登录、装网关、搜/看/取；**持有凭据** | 扩展现有 `skill_platform` 包 |
| **MCP 适配器** | 把 `/v1/*` 包成 `skills/list` + `resources/read` | P2，能力协商 |

**起步全部可以跑在一个进程 + SQLite 上**，不要过早拆服务。

### 2.3 一次读的完整路径

```
用户提问
   ↓
网关 skill 的 description 命中（常驻上下文，≈80 tokens）
   ↓
agent 读网关正文（L2，本地）→ 知道服务地址与调用协议
   ↓
CLI search "<关键词>"  →  GET /v1/skills?q=...   （只返回 L1 卡片）
   ↓
挑中一个 → CLI show <id>  →  GET /v1/skills/{id}  （L1 + 文件清单，零内容）
   ↓
判断正文相关 → CLI get <id>  →  GET /v1/skills/{id}/body   （L2）
   ↓
正文引用了某个文件 → CLI get <id> <relpath>  →  GET .../files/{relpath}  （L3）
   ↓
agent 依 skill 指示完成任务
```

**服务端在接口层强制分层**：搜索接口物理上拿不到正文，正文接口拿不到文件，文件接口只给单个文件。**这就是渐进加载的落实方式**——不靠模型自觉，靠接口形状。

---

## 第 3 章 · 领域模型与表结构

SQLite 起步；列类型与索引同时考虑将来迁 Postgres。时间一律 RFC3339 UTC 字符串；主键一律 ULID（不透明、可按时间排序、无自增泄露）。

### 3.1 身份与鉴权

```sql
CREATE TABLE user (
  id           TEXT PRIMARY KEY,              -- ULID
  handle       TEXT NOT NULL UNIQUE,          -- 登录名，也用作个人命名空间的 slug
  display_name TEXT NOT NULL DEFAULT '',
  email        TEXT UNIQUE,
  status       TEXT NOT NULL DEFAULT 'active',-- active | suspended
  created_at   TEXT NOT NULL
);

CREATE TABLE credential (                     -- 自建登录
  user_id     TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,                  -- password | totp
  secret_hash TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (user_id, type)
);

CREATE TABLE identity (                       -- 预留 SSO，成本为零
  provider    TEXT NOT NULL,                  -- lark | github | google
  external_id TEXT NOT NULL,
  user_id     TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  linked_at   TEXT NOT NULL,
  PRIMARY KEY (provider, external_id)
);
```

`identity` 现在就留着：产品设计第 2.1 章明确要求**把身份提供方降级成一行数据**，将来接 SSO 只是加一行，不动主键。

```sql
CREATE TABLE browser_session (                -- 登录页的浏览器会话
  session_id TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE oauth_client (
  client_id          TEXT PRIMARY KEY,        -- CIMD 时是一个 HTTPS URL
  name               TEXT NOT NULL,
  registration       TEXT NOT NULL,           -- cimd | dcr | preregistered
  redirect_uris      TEXT NOT NULL,           -- JSON 数组
  grant_types        TEXT NOT NULL,           -- JSON 数组
  client_secret_hash TEXT,                    -- 机密客户端才有
  metadata           TEXT NOT NULL DEFAULT '{}',
  created_at         TEXT NOT NULL
);

CREATE TABLE auth_code (
  code_hash      TEXT PRIMARY KEY,
  client_id      TEXT NOT NULL REFERENCES oauth_client(client_id),
  user_id        TEXT NOT NULL REFERENCES user(id),
  redirect_uri   TEXT NOT NULL,
  scope          TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  method         TEXT NOT NULL,               -- S256
  resource       TEXT,                        -- RFC 8707 audience
  expires_at     TEXT NOT NULL,
  used_at        TEXT
);

CREATE TABLE access_token (
  token_hash TEXT PRIMARY KEY,                -- 只存哈希，永不存明文
  client_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES user(id),
  scope      TEXT NOT NULL,
  audience   TEXT NOT NULL,                   -- 必须校验
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_at_expiry ON access_token(expires_at);

CREATE TABLE refresh_token (
  token_hash   TEXT PRIMARY KEY,
  client_id    TEXT NOT NULL,
  user_id      TEXT NOT NULL REFERENCES user(id),
  scope        TEXT NOT NULL,
  expires_at   TEXT NOT NULL,
  revoked_at   TEXT,
  rotated_from TEXT,                          -- 轮换链，便于检出重放
  created_at   TEXT NOT NULL
);
```

**三条实现约束**：

1. **令牌只存哈希**（`sha256`）。数据库泄露不等于令牌泄露。
2. **`audience` 必须校验**：只接受签给本服务的令牌。这是 MCP 规范的 MUST，在纯 API 下同样是对的。
3. **refresh token 轮换**：每次刷新签发新的并置 `rotated_from`；旧 token 被再次使用即视为重放，整链撤销。

### 3.2 命名空间与成员

```sql
CREATE TABLE namespace (
  id            TEXT PRIMARY KEY,
  slug          TEXT NOT NULL UNIQUE,         -- URL 里用
  title         TEXT NOT NULL DEFAULT '',
  owner_user_id TEXT NOT NULL REFERENCES user(id),
  visibility    TEXT NOT NULL DEFAULT 'private', -- public | unlisted | private
  created_at    TEXT NOT NULL
);

CREATE TABLE namespace_member (
  namespace_id TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  role         TEXT NOT NULL,                 -- owner | editor | viewer
  added_at     TEXT NOT NULL,
  PRIMARY KEY (namespace_id, user_id)
);
```

**注册时自动为每个用户建一个个人命名空间**（`slug = handle`）。这样 `(namespace_id, name)` 唯一这一个约束**同时覆盖了「个人内不重名」与「团队内不重名」**——正是你提的 `user_id + skill_name` 的意图，且将来升级成团队命名空间不需要迁移。

### 3.3 Skill 与版本

```sql
CREATE TABLE skill (
  id                 TEXT PRIMARY KEY,        -- ULID，永不变
  namespace_id       TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  name               TEXT NOT NULL,           -- 作者的 name，标准约束
  title              TEXT NOT NULL DEFAULT '',
  description        TEXT NOT NULL,           -- 热路径，冗余自 frontmatter
  frontmatter        TEXT NOT NULL,           -- 原始 frontmatter（JSON，透传未知字段）
  visibility         TEXT NOT NULL DEFAULT 'private',
  current_version_id TEXT,                    -- 指向当前版本
  created_by         TEXT NOT NULL REFERENCES user(id),
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL,
  deleted_at         TEXT,                    -- 软删
  UNIQUE (namespace_id, name)
);
CREATE INDEX idx_skill_ns  ON skill(namespace_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_skill_vis ON skill(visibility)   WHERE deleted_at IS NULL;

CREATE TABLE skill_version (
  id           TEXT PRIMARY KEY,
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  digest       TEXT NOT NULL,                 -- 文件集合的确定性摘要
  file_count   INTEGER NOT NULL,
  total_bytes  INTEGER NOT NULL,
  changelog    TEXT NOT NULL DEFAULT '',
  source       TEXT NOT NULL DEFAULT '',      -- 来源描述（上传/zip/git url）
  published_by TEXT NOT NULL REFERENCES user(id),
  published_at TEXT NOT NULL,
  UNIQUE (skill_id, digest)                   -- 幂等发布：同样内容不产生新版本
);
CREATE INDEX idx_ver_skill ON skill_version(skill_id, published_at DESC);

CREATE TABLE version_file (
  version_id  TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  relpath     TEXT NOT NULL,                  -- 相对 skill 根，POSIX 分隔符，字典序
  blob_sha256 TEXT NOT NULL REFERENCES blob(sha256),
  size        INTEGER NOT NULL,
  is_binary   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (version_id, relpath)
);

CREATE TABLE blob (
  sha256      TEXT PRIMARY KEY,
  size        INTEGER NOT NULL,
  storage_ref TEXT NOT NULL,                  -- blob store 里的 key
  created_at  TEXT NOT NULL
);
```

**五个关键设计点**：

1. **`id` 是身份，`name` 是属性。** 改名只 `UPDATE skill.name`，不影响任何引用、任何已发出的 URL、任何审计记录。这就是选不透明主键而非 `(owner, name)` 的理由。
2. **`UNIQUE(skill_id, digest)` 直接实现幂等发布**——同样内容重复发布不产生新版本。这也是"内容不变 → digest 不变"这条不变量的落点。
3. **manifest 就是 `version_file` 的投影**，按 `relpath` 字典序排序即规范要求的确定性顺序。**顺序必须显式排序，不能依赖数据库返回顺序。**
4. **`frontmatter` 存原始 JSON 并透传未知字段。** 飞书的 `metadata.requires.bins` 是嵌套的，现有 `parse_frontmatter` 会丢——必须换更完整的 YAML 解析，且**解析失败不得静默降级**。
5. **`blob` 只增，删除版本不立刻删 blob**（可能被其他版本引用），靠后台 GC 比对引用计数。内容寻址天然去重——不同 skill 共享同一份 `references/` 时只存一份。

**digest 算法（必须确定性）**：

```
digest = sha256( concat( for f in files_sorted_by_relpath:
                          f.relpath + "\0" + f.blob_sha256 + "\0" ) )
```

不依赖文件系统遍历顺序，不依赖时间戳。**同一份内容永远得到同一个 digest**——这是更新检测与幂等发布的前提。

### 3.4 检索与统计

```sql
CREATE VIRTUAL TABLE skill_fts USING fts5(
  name, title, description,
  content='skill', content_rowid='rowid',
  tokenize='trigram'                          -- 对中文子串匹配可用；需实测 SQLite 构建支持
);

CREATE TABLE skill_stat (
  skill_id       TEXT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
  search_hits    INTEGER NOT NULL DEFAULT 0,  -- 出现在搜索结果里
  detail_views   INTEGER NOT NULL DEFAULT 0,  -- 被看了详情
  body_reads     INTEGER NOT NULL DEFAULT 0,  -- 正文被读
  file_reads     INTEGER NOT NULL DEFAULT 0,
  last_access_at TEXT
);
```

**只索引 L1**（标准的 `name` + `description`，外加平台自己的 `title` 元数据字段），**绝不索引正文或文件**。这既是性能考虑，也是安全约束：正文一旦进全文索引，就可能通过片段检索被反推出来。

**排序公式（P0，必须可解释）**：

```
score = w1 * text_relevance(FTS5 bm25) + w2 * freshness(updated_at) + w3 * manual_weight
```

P0 没有使用数据，只能用文本相关性 + 新鲜度 + 人工权重；`skill_stat` 是为 P1 的排序准备的。**排序质量在这个模型下就是产品的核心**——目录不常驻，「这个任务正好有个 skill 能用」完全靠它，所以要**可解释、可调、可回归测试**。

### 3.5 审计

```sql
CREATE TABLE audit_event (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_user_id TEXT,
  action        TEXT NOT NULL,                -- publish|delete|rollback|token_issue|...
  target_type   TEXT NOT NULL,
  target_id     TEXT,
  detail        TEXT NOT NULL DEFAULT '{}',
  at            TEXT NOT NULL
);
CREATE INDEX idx_audit_at ON audit_event(at DESC);
```

skill 的内容会被客户端取走、在客户端环境里使用，事后追溯是底线。**发布、删除、回滚、令牌签发与撤销一律留痕。**

---

## 第 4 章 · 接口定义

### 4.1 通用约定

- 基址 `https://<host>/v1`
- **认证**：每个请求带 `Authorization: Bearer <access_token>`。**令牌绝不放在 query string**（规范要求，也防日志泄露）。
- **未认证/令牌无效** → `401` + `WWW-Authenticate: Bearer resource_metadata="https://<host>/.well-known/oauth-protected-resource"`
- **scope 不足** → `403` + `WWW-Authenticate: Bearer error="insufficient_scope", scope="...", resource_metadata="..."`
- **分页**：`?limit=&cursor=`，响应带 `next_cursor`（不透明游标，不用 offset）
- **时间**：RFC3339 UTC

**为什么连纯 API 也照 MCP 的错误形状来**：将来加 MCP 适配器时不用改错误语义，客户端也不会遇到"两套 401"。

### 4.2 四个读接口

#### `GET /v1/skills` —— 搜索（只返回 L1）

| 参数 | 说明 |
|---|---|
| `q` | 关键词；空则按 `sort` 返回 |
| `namespace` | 限定命名空间 slug |
| `sort` | `relevance`（默认）\| `recent` |
| `limit` | 默认 20，上限 100 |
| `cursor` | 分页游标 |

```json
{
  "skills": [
    { "id": "01J...", "name": "pdf-tools", "title": "PDF 工具",
      "description": "…", "namespace": "acme", "visibility": "public",
      "digest": "sha256:…", "updated_at": "2026-09-26T10:00:00Z" }
  ],
  "next_cursor": null
}
```

**只返回 L1 字段**：不含正文、不含文件清单、不含文件内容。这是渐进加载的第一道闸。

#### `GET /v1/skills/{id}` —— 详情 + 文件清单（零内容）

```json
{
  "id": "01J...", "name": "pdf-tools", "title": "PDF 工具",
  "description": "…",
  "namespace": { "slug": "acme", "title": "Acme 团队" },
  "visibility": "public",
  "frontmatter": { "name": "pdf-tools", "description": "…", "metadata": { "…": "原样透传" } },
  "version": {
    "digest": "sha256:…", "published_at": "2026-09-26T10:00:00Z",
    "file_count": 25, "total_bytes": 364869
  },
  "files": [
    { "relpath": "SKILL.md", "sha256": "sha256:…", "size": 15251, "is_binary": false },
    { "relpath": "references/checklist.md", "sha256": "sha256:…", "size": 4096, "is_binary": false }
  ],
  "resources": {
    "body": "/v1/skills/{id}/body",
    "file": "/v1/skills/{id}/files/{relpath}"
  }
}
```

**这是整个设计的枢纽**：给出**完整文件清单但零内容**——正是 MCP 扩展里 `skills/list` 的 `resources` 语义（"清单让客户端无需下载即可枚举 L3"）。`resources` 给的是模板化 URL，agent 据此按需取。它让 agent 能**在不取任何内容的前提下**判断"这个 skill 里有没有我要的东西"。

**可见性过滤在服务端算**：`private`/`unlisted` 的 skill，无权者得到 `404`（不是 `403`——不泄露存在性）。

#### `GET /v1/skills/{id}/body` —— L2

返回**原始 SKILL.md 字节**（含 frontmatter），`Content-Type: text/markdown`。不做任何改写——托管要保真。

#### `GET /v1/skills/{id}/files/{relpath}` —— L3

返回**单个文件的原始字节**，`Content-Type` 按扩展名（未知则 `application/octet-stream`）。

**必须实现的约束：`relpath` 必须精确匹配当前版本 manifest 里的某一项，否则 `404`。** 绝不能用 `relpath` 直接拼路径去读磁盘——那是路径穿越漏洞。这一条要写成测试。

### 4.3 写接口（管理）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/skills` | 发布新 skill（上传目录树 / zip / git 源） |
| `POST` | `/v1/skills/{id}/versions` | 发布新版本 |
| `PATCH` | `/v1/skills/{id}` | 改元数据（title / description / visibility） |
| `DELETE` | `/v1/skills/{id}` | 软删 |
| `POST` | `/v1/skills/{id}/restore` | 恢复 |
| `GET` | `/v1/skills/{id}/versions` | 版本历史 |
| `POST` | `/v1/skills/{id}/rollback` | 回滚（指针前移，不删版本） |

**元数据变更不产生新版本**（`title`/`description`/`visibility` 不在 skill 文件内），但会触发搜索索引更新。

### 4.4 鉴权接口（自建 AS）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/login`、`POST /login` | 登录页 |
| `POST` | `/logout` | 登出 |
| `GET` | `/oauth/authorize` | 授权端点（PKCE 必需） |
| `POST` | `/oauth/token` | `authorization_code` / `refresh_token` / `client_credentials` |
| `POST` | `/oauth/register` | DCR |
| `GET` | `/.well-known/oauth-authorization-server` | RFC 8414 |
| `GET` | `/.well-known/openid-configuration` | OIDC discovery（可选） |
| `GET` | `/.well-known/oauth-protected-resource` | RFC 9728 |
| `GET` | `/.well-known/jwks.json` | 若用 JWT 签名 |

**三种客户端注册方式都要支持**（1.7 的结论）：CIMD（识别 URL 形式的 `client_id`，去那个 URL 取元数据）、DCR（`/oauth/register`）、预注册。只做 CIMD 会卡死一批客户端。

### 4.5 网关 skill 的分发接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/.well-known/agent-skills/index.json` | V2，**只含网关 skill 一个 entry** |
| `GET` | `/.well-known/skills/index.json` | V1，同上（旧版 CLI 用） |
| `GET` | `/.well-known/skills/skill-platform/<relpath>` | V1 的逐文件拉取 |
| `GET` | `/gateway/SKILL.md` | 网关 skill 正文（CLI `setup` 用） |

**未发布时必须返回真 404**（见 1.5 的坑）。**网关 skill 发布在一个保留命名空间里**（如 `skill-platform`），它本身也是一个普通 skill，有版本、有 digest。

### 4.6 CLI 命令

| 命令 | 作用 |
|---|---|
| `skill-platform login` | loopback PKCE 登录，令牌存 keychain |
| `skill-platform login --client-credentials` | 无人值守（CI），用 Client Credentials |
| `skill-platform logout` | 撤销并清除本地令牌 |
| `skill-platform setup` | 检测本机 agent → 装网关 skill → 软链 |
| `skill-platform search <q>` | 调 `/v1/skills` |
| `skill-platform show <id>` | 调 `/v1/skills/{id}` |
| `skill-platform get <id> [relpath]` | 取正文或单个文件，落到临时目录 |
| `skill-platform publish <path>` | 管理端：发布 |

**两个设计点**：

- **agent 用 CLI 而不是裸 curl**：令牌由 CLI 从 keychain 读，**永不进模型上下文、不进命令记录**。裸 curl 会让令牌出现在 Bash 命令里。
- **`get` 落到临时目录是允许的**——那是 agent 完成任务的中间产物，与"把 skill 下载到本地"是两回事。前者用完即弃，后者是一份可长期阅读的副本。

---

## 第 5 章 · 网关 skill

### 5.1 它是什么

一个普通 skill，但有特殊职责：**它是本地唯一常驻的东西，也是协议本身。**

```markdown
---
name: skill-platform
description: 访问团队的 skill 库。当任务需要特定领域能力（文档处理、数据分析、
  飞书操作、代码规范等）时，先用它检索团队已托管的 skill，再按检索结果使用。
metadata:
  platform_api_version: "1"
---

# 团队 Skill 库

服务地址：`https://<host>`

## 使用协议（按顺序，不要跳步）

1. 检索：`skill-platform search "<关键词>"`
   → 只返回名称与描述。**先看描述判断相关性，不要一次搜很多词。**
2. 详情：`skill-platform show <id>`
   → 返回完整文件清单但**没有内容**。据此判断这个 skill 有没有你要的那部分。
3. 取正文：`skill-platform get <id>`
4. 取文件：正文里引用到的文件才取 `skill-platform get <id> <relpath>`

## 停止条件

- 搜索结果没有相关项 → 直接告诉用户没找到，**不要**逐个试。
- 清单里有文件但正文没引用 → **不要取**。
```

### 5.2 三条设计约束

1. **`description` 决定了整个发现体验。** 目录不常驻，所以"该不该去查 skill 库"全靠这句。它必须写得**广**（覆盖多类任务），而不是窄（只描述某一个场景）。这是全项目最需要打磨的一段文字。

2. **正文必须短且强指令。** 它每次被触发都会进上下文并**在整个会话常驻**。冗长的协议说明会持续占用预算。上面那份大约 200 tokens，是合适的量级。

3. **它必须能更新。** 网关正文就是协议——如果 API 变了而用户本地的网关是旧的，agent 会按旧协议调用。所以：
   - 网关 frontmatter 里带 `metadata.platform_api_version`
   - `GET /gateway/SKILL.md` 永远返回最新
   - **CLI `setup` 时比对版本，不一致就更新本地网关**——这就是「安装必须由 CLI 主导」的理由（well-known 那条路没有可靠的更新机制）

---

## 第 6 章 · 与现有代码的关系

现有 `skill_platform/` 是可用种子，分层也对。逐项：

| 现有 | 处置 |
|---|---|
| `importer.parse_frontmatter` | **必须换**。只支持扁平 `key: value` 与缩进列表，而 `metadata` 实际是嵌套的（飞书案例），会静默丢字段。改用完整 YAML 解析，**解析失败不得静默降级** |
| `importer.discover_skills` / `_collect_files` | **复用**为上传校验骨架。需加：标准字段校验、512 文件 / 16 MiB 上限、符号链接与路径穿越检查 |
| `importer._extract_links` | 复用，用于"正文引用了不存在的文件"这类警告级诊断 |
| `store.py` | **扩**：namespace / skill / version / version_file / blob / user / token 等表。现有"每操作开新连接 + WAL + busy_timeout"可留 |
| `store.permissions_version` + `permissions.PermissionCache` | **保留复用**——"版本计数器失效 + TTL 兜底 + 失败 fail-closed"正好是搜索索引缓存需要的机制 |
| `materializer.py` | **基本作废**。本项目不再把 skill 落到本地。归档/导出功能若将来需要，再从它改 |
| `permissions.py` | **退化**：不做门控，只做**可见性**（public/unlisted/private）与命名空间成员。family 匹配（`lark` 覆盖 `lark-*`）对集合型 skill 仍有用 |
| `__main__.py` | 拆成两组子命令：**客户端**（login/setup/search/show/get）与**管理端**（publish/versions/...） |
| `schema.py` | 加表；保留 `meta` + schema_version 迁移机制 |

**要新增的核心能力**：完整 YAML 解析器、严格校验器、digest 计算、blob store + GC、搜索与排序、OAuth AS、HTTP 服务、CLI 登录流程、网关 skill 的安装与更新。

---

## 第 7 章 · 分期

### P0 · 服务端 + CLI + 网关，跑通一次远程读取

- 表结构落地（第 3 章）+ 严格校验 + digest + 不可变版本
- 四个读接口 + 服务端搜索排序
- **鉴权用一把静态令牌先行**（AS 放 P1），把链路跑通
- CLI：`setup` / `search` / `show` / `get`
- 网关 skill 发布到 well-known 索引（V2 + V1 两条路径）
- **验收**：在一个干净环境里 `setup` 装上网关，然后在 agent 里问一个需要某 skill 的任务，agent 能自己搜到、读到正文、按需取文件并完成任务——**且全程没有任何 skill 内容被当作副本落到本地**（`get` 落到临时目录是 agent 的中间产物，不算，见 §4.6）

### P1 · 自建登录与令牌

- OAuth 2.1 AS：`/login`、`/oauth/authorize`、`/oauth/token`、三种注册方式（CIMD + DCR + 预注册）
- CLI `login`（loopback PKCE）+ 无人值守 `--client-credentials`
- 命名空间成员与可见性生效；审计日志
- 管理端写接口

### P2 · MCP 适配器与检索质量

- MCP 适配器：`skills/list` / `skills/get` / `resources/read`（能力协商，客户端不支持则不影响 API）
- 排序用上 `skill_stat` 的信号；检索质量回归测试
- 版本历史 / diff / 回滚

### P3 · 之后

- 渐进付费（附录 B）

---

## 第 8 章 · 开放问题

按需要先解决的顺序：

1. **网关 skill 的 `description` 怎么写。** 它决定发现体验的上限，且没有数据可依。建议先写，然后用真实任务集做回归（"给这 20 个任务，看它该不该去查 skill 库"）。
2. **排序公式的权重与可解释性。** P0 纯启发式，必须可解释（作者/用户要能理解为什么排这个位置），且要能回归测试。
3. **搜索的中文分词。** 计划用 FTS5 + `trigram`，**需实测目标 SQLite 构建是否带该 tokenizer**；不带则退回 `LIKE` 或引入外部索引。
4. **`relpath` 的取值规范。** 允许哪些字符、是否允许非 ASCII、大小写敏感性——一旦发布就不好改，且它进 URL。
5. **无人值守的 Client Credentials 怎么发放**：谁有权创建、绑哪个用户身份、scope 怎么限。
6. **blob GC 的策略**：延迟多久回收、是否需要"回收前先归档"。
7. **是否支持从别的 registry 镜像**（如把飞书的 `lark-*` 导入进来）。

---

## 附录 A · 架构决策

本设计里的**决策不写在这里**，而是独立成编号 ADR —— 决策跨版本存活，不该随设计文档一起被重写。

完整索引见 [`decisions/README.md`](../../decisions/README.md)。与本版相关的九条：

| ADR | 决策 | 本文对应章节 |
|---|---|---|
| [0001](../../decisions/0001-server-authoritative.md) | 服务端权威，本地不落 skill 副本 | 第 0、2 章 |
| [0002](../../decisions/0002-gateway-skill-and-cli.md) | 交付形态：一个通用网关 skill + 自研 CLI | 第 5 章 |
| [0003](../../decisions/0003-api-primary.md) | API 为主契约，MCP 为可选适配器 | 第 4 章 |
| [0004](../../decisions/0004-opaque-id-primary-key.md) | 身份模型：不透明 `id` 作主键，`name` 为属性 | 3.2、3.3 |
| [0005](../../decisions/0005-content-addressing.md) | 内容寻址与幂等发布 | 3.3 |
| [0006](../../decisions/0006-search-server-side.md) | 检索在服务端，且只索引 L1 | 3.4、4.2 |
| [0007](../../decisions/0007-self-built-oauth-as.md) | 自建 OAuth 2.1 AS，支持三种客户端注册方式 | 3.1、4.4、4.6 |
| [0008](../../decisions/0008-no-script-execution.md) | P0 不做脚本执行 | 第 7 章 |
| [0009](../../decisions/0009-drop-l2n.md) | 砍掉 `l2#n`，付费边界只落在层与层之间 | 附录 B |

**本文只链接、不复述理由。** 若发现正文里重复解释了某条决策的原因，那是需要清理的重复——理由只有一处权威来源，就是 ADR 本身。

---

## 附录 B · 已推迟：渐进付费

**不是废弃。** 记录要点供日后重启时不必重推。

- **服务端门控是唯一正确的门控位置**——内容一旦落到客户端，门控只能靠"不下发这些字节"。本项目的服务端权威模型天然满足这一点，**接入付费的成本比"下载到本地"模型低得多**。
- **MCP Skills 扩展已规定好完整性语义**：approval 必须绑定完整 URI+digest 集合，文件增删改即吊销。不需要自创版本锁定。
- **支付必须走 URL mode elicitation**，form mode 被规范明令禁止承载支付凭据（原文核实）。
- **业界现状**：只有整包买断（Agensi、ClawHub）与按次计费（腾讯 SkillPay、x402、Cloudflare `paidTool`、Stripe MPP）两类，**无人做"按内容层级计费"**。既是空白，也无转化率证据。
- **`l2#n` 已否决**：平台自定义概念，标准只有三层。标准对"想少加载正文"给的答案是**把深度内容移到 `references/`**（即 L3）。付费边界只应落在层与层之间。
- **接入方式**：在 `version_file` 上挂节点价格，在四个读接口加权益判定，在 `auth_code`/`access_token` 之外加钱包与订单表。

---

## 附录 C · 来源与出处

> 下面绝大多数是一手来源；标了"社区维护"的那条是二手，只作参考。

- Agent Skills 规范：https://agentskills.io/specification
- Agent Skills 客户端实现指南：https://agentskills.io/client-implementation/adding-skills-support
- MCP Skills 扩展：https://modelcontextprotocol.io/extensions/skills/overview
- MCP 扩展支持矩阵：https://modelcontextprotocol.io/extensions/client-matrix
- MCP 2026-07-28 变更：https://modelcontextprotocol.io/specification/2026-07-28/changelog
- MCP authorization：https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
- Claude Code skills：https://code.claude.com/docs/en/skills
- Claude Code MCP：https://code.claude.com/docs/en/mcp
- Claude Code marketplace 托管：https://code.claude.com/docs/en/plugins/host-marketplace
- MCP prompts 对模型不可见：https://github.com/anthropics/claude-code/issues/11054
- well-known 通道的消费方（Vercel `skills` CLI）：https://github.com/vercel-labs/skills
- MCP 客户端 OAuth 支持矩阵（社区维护，供参考）：https://www.redcaller.com/docs/references/mcp-client-oauth-refresh-token-support
- Cursor MCP：https://cursor.com/docs/context/mcp
- VS Code MCP 配置：https://code.visualstudio.com/docs/agents/reference/mcp-configuration
- Gemini CLI MCP：https://google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html
