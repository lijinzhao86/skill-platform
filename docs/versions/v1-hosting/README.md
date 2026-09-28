# v1-hosting · skill 托管与远程加载

> **最后更新**：2026-09-28
> **状态**：进行中。设计已定稿；**服务端 P0a 与 P0c 都已实现**——skill 的地址是 `namespace/name[@版本]`（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md)），文档与代码在寻址上已经一致。CLI 与网关安装属 **P0b**，尚未实现。
> **含收费**：否

<!--
这是版本记录主页，不是详述。每个问题只写一句话 + 链接；
需求细节在 prd.md，设计细节在 technical-design.md。约定见 docs-architecture skill。
-->

## 目标

把「**用户把 skill 托管上来，agent 通过远程服务搜索、读取、使用它**」这一条链路跑通。

skill 全部活在服务端，**本地不留 skill 副本**；客户端侧只装一个通用网关 skill 与一个自研 CLI。渐进加载由服务端**在接口层强制**（[ADR 0001](../../decisions/0001-server-authoritative.md)、[ADR 0002](../../decisions/0002-gateway-skill-and-cli.md)）。

完整需求见 [`prd.md`](prd.md) §用户与场景。

## 验收标准

一句话：**在一个干净环境里，agent 自己搜到、读到、用到某个 skill，且全程没有任何 skill 副本落到本地。**

完整五条与判定方式见 [`prd.md`](prd.md) §验收与指标。**其中第 5 条（无本地副本）是成立条件**——它不是附带要求，验的是"服务端权威"能不能真的兑现。

## 这一版明确不做什么

最要紧的三条；**完整范围与每条的理由见 [`prd.md`](prd.md) §范围**。

- 不做**收费与门控**（先把托管跑通）
- 不做**脚本执行**（独立硬工程 → [ADR 0008](../../decisions/0008-no-script-execution.md)）
- 不做 **MCP 适配器**（API 为主契约 → [ADR 0003](../../decisions/0003-api-primary.md)）

## 与目标态的关系

[`target/design.md`](../../target/design.md) 描述的是**完整形态**：公开市场 + 渐进付费，带 M1 / M2 / M3 路线。

**v1-hosting 不等于那份文档里的 M1。** 那份 M1 是"私有托管 **+ 渐进付费**"；本版是"托管，**不含收费**"。两者已经分叉——这正是本版独立成文件夹、不再挂在旧路线里的原因。

本版是目标态的**真子集**：交付形态（网关 skill + API + 自建鉴权）会保留，收费与公开市场留给后续版本。**决策记录（[`decisions/`](../../decisions/)）跨版本共用**，不需要重复。

> 目标态文档里仍有已被 ADR 推翻的机制（`l2#n`、模块 2.5 的本地 Gate 模型）。**逐条差异见 [`target/README.md`](../../target/README.md)**——读 `target/` 之前先读它。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置。**需求的权威来源** |
| [`technical-design.md`](technical-design.md) | 系统架构、表结构、接口定义、分期与开放问题。**设计主体**；概念模型已移到 [`architecture/`](../../architecture/README.md) |
| [`test-plan.md`](test-plan.md) | 验收映射、用例（含成立条件的反证）、环境与数据、结果。**尚未执行** |
| [`known-issues.md`](known-issues.md) | 首次提交（`c8a7362`）对**重写前基线代码**的审计：**条数与分布见该文件开头**，随架构重写一并处理 |
| [`iterations/`](iterations/) | 小版本迭代记录（只增不改） |

## 影响最大的未决问题

完整列表与当前倾向见 [`technical-design.md`](technical-design.md) §开放问题。最要紧的三条：

1. **网关 skill 的 `description` 怎么写**——目录不常驻，这句决定发现体验的上限，且没有数据可依。
2. **检索与排序的权重**——在这个模型下排序**就是**产品核心（[ADR 0006](../../decisions/0006-search-server-side.md)）。
3. **中文检索的索引形态**——原计划（SQLite FTS5 + `trigram`）已实测不可行，方向转为 PostgreSQL 的 `pg_bigm`，**待验证**（[ADR 0010](../../decisions/0010-storage-in-postgres.md)）。

## 下一版可能是什么

尚未决定。候选方向：组织与成员体系、脚本执行形态、MCP 适配器、公开市场与付费。

任何一条都要先在本目录旁边新建 `versions/vN-主题/`，并按 [`.claude/skills/docs-architecture/`](../../../.claude/skills/docs-architecture/SKILL.md) 的四件套补齐，然后跑一次 `check_docs.py`。
