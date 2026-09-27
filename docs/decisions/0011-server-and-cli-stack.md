# ADR 0011 · 技术栈：服务端 Java + Spring，CLI 用 Go

- **状态**：已采纳
- **日期**：2026-09-27
- **依赖**：[ADR 0002](0002-gateway-skill-and-cli.md)、[ADR 0007](0007-self-built-oauth-as.md)、[ADR 0010](0010-storage-in-postgres.md)
- **后续**：§后果 里留白的实现细节已定—— **Java 25（LTS）+ Maven**，包名 **`com.skillmasterai`**（取自自有域名 `skillmasterai.com`），`mvnw` 随仓库走（CI、本机、镜像三处版本一致）。Python 基线**已归档**到 `skillmaster-server/reference-python/`，仍是设计参考、仍不删。
- **同日后产品名为 `SkillMaster`**，仓库、目录与网关 skill 随之改名：`skillmaster-server/`、`skillmaster-cli/`、`gateway/skillmaster/`（skill 的 `name` 也改成 `skillmaster`）。**因此上面 §后果 正文里指旧名的那一处路径已过期**——这是 ADR 不可变的既定代价。**正文四段保持原样**。

## 背景

三个子项目里真正要选语言的是**服务端**——它是 API + AS + Blob Store + GC 的同进程组合（[`technical-design.md`](../versions/v1-hosting/technical-design.md) §2.2）。已经定死的前提把它框得很窄：

- **PostgreSQL 是唯一的状态存储**（[ADR 0010](0010-storage-in-postgres.md)），所以有分量的逻辑大多落在 SQL 与表约束里，应用层是薄的。
- 部署平台是**阿里云**，应用跑在一台 **`ecs.t6-c1m2.large`（2 vCPU / 4 GB，t6 突发性能型，持续基线只有 0.4 vCPU）**上；数据库是独立的托管 RDS。
- 服务端**将来要能多副本**。
- AS 最终要支持 CIMD + DCR + 预注册（[ADR 0007](0007-self-built-oauth-as.md)）。
- CLI 是独立子项目（[ADR 0002](0002-gateway-skill-and-cli.md)），技术栈原先未定（§2.4）。

基线是 **1285 行 Python**（四个模块 + 测试），其中**值得在 Java 里重建的大约只有 150–250 行**。口径与逐项理由见 [`technical-design.md`](../versions/v1-hosting/technical-design.md) §6：那四块行为即使搬过去也要重写——frontmatter 解析要换实现、权限要收窄、连接管理是 SQLite 的、迁移机制并不存在；而同一批代码里还压着未修缺陷（条数与分布见 [`known-issues.md`](../versions/v1-hosting/known-issues.md) 开头）。

候选：Python（TD §2.2 原先写的 FastAPI + `authlib`）、Java（Spring Boot + Spring Security）、以及 CLI 侧的 Go / Node / Rust。

## 决定

1. **服务端是 Java + Spring Boot 4.x + Spring Security 7**，AS 用它的 Authorization Server（Nimbus JOSE 打底）。**不用** Quarkus / Micronaut / Javalin 这类精简栈。
2. **CLI 是 Go**，单静态二进制。
3. **v1 的 AS 只做预注册**；CIMD 推到 P2（MCP 适配器），DCR 只作兼容、**v1 不启用**。
4. **客户端查找在 v1 就通过接口**（Spring Security 的 `RegisteredClientRepository`），不要硬编码成「反正只有一个客户端」。
5. **脚本执行不在本 ADR 范围内**——按 [ADR 0008](0008-no-script-execution.md)，执行形态仍是未决岔路，需要时另开 ADR。

## 理由

**为什么是 Java，以及为什么理由不是通常那几条。** 诚实说：**对这个负载，Java 的技术优势很薄。** 我们的负载是 I/O 密集、单次 4 KB、读多写少——落在 async Python 的甜点区。而那三条常被拿来支持 Java 的理由，经查都站不住：**账务**要的是本地事务 + 幂等键 + outbox + saga，那是 PostgreSQL 的纪律、**框架无关**；**审核**若涉及模型分类反而偏 Python；**高并发**的瓶颈在 PostgreSQL 的连接与 IO（2核4G 上限 400 连接），靠加只读副本与缓存解决，不是语言问题。

**真正的理由两条**：**团队与招聘**——国内后端人才池 Java 最深，「产品做起来之后招聘才是真约束」；**长寿服务 + 多人维护**——强类型、成熟模式、DI 与可观测性让团队一致性更好。**第三条是决策本身**：既然终局是 Java，现在用 Python 就是**将来付一次迁移**，而 Python 侧值得带走的东西只有那 150–250 行。**消除迁移本身就是最便宜的一次迁移。**

**为什么不选精简栈。** 「先精简、后上 Spring」是**假经济**：Javalin 这类不提供 DI / 安全 / 可观测（"your code is the framework now"），而这几样正是长寿服务迟早要的，也正是会逼出第二次迁移的东西。**内存不是约束**：Spring Boot 约 390 MB RSS，机器有 4 GB。（口径提醒：RSS 数字来源冲突，另有测量给出 61–112 MiB，差异来自应用规模，未调和。）**GraalVM native 不用**——它的收益只是启动速度，而我们不缺（冷启动约 1 个 CPU 积分）；且 nimbus-jose-jwt 的 9.x/10.x 元数据未确认、反射问题「缓解但未解决」、构建要 ~7 GB 内存。**CDS/AOT 提启动不提 RSS；CRaC 不适合同进程 AS**（它的快照可能泄漏签名密钥）。

**AS 为什么用 Spring Security 的 AS，而不是买产品或从零写。** **没有任何主流产品生产可用 CIMD**：Spring Security 7 有 DCR、**没有 CIMD**（issue 长期未派）；Keycloak 是 **experimental**；ORY Hydra 没有。所以 **CIMD 那一层无论哪门语言都要自己写**。用它的 AS，authorize / token / JWKS / discovery / DCR **全是现成的**，自建的份额只剩**CIMD 的客户端查找 + 一个防 SSRF 的抓取器**。（安全提醒：Spring 的 DCR 端点出过 **CVE-2026-22752，CVSS 9.6**（客户端元数据校验不足）→ **必须 pin 已修版本**；v1 不启用 DCR 也顺带没有这个攻击面。）

**为什么 v1 只做预注册。** v1 的客户端**只有一个**：我们自己的 CLI（[ADR 0002](0002-gateway-skill-and-cli.md)、[`prd.md`](../versions/v1-hosting/prd.md) §范围 做 #5）。**一方客户端用预注册**就够——`client_id` 随 CLI 发布，public client + PKCE + loopback，正是 RFC 8252 对原生应用的推荐；无人值守的 CI 客户端同样预注册。**需要 CIMD 的是第三方客户端**（Claude Code / VS Code / ChatGPT），而它们只出现在 **MCP 适配器（P2）**。而且 **MCP 规范 2026-07-28 已把 DCR 标为 deprecated**，指定 CIMD 作为新实现的路径——所以「三种都支持」的最终要求不变，变的是**什么时候要**。（CIMD 本身**仍是 Internet-Draft**：`draft-ietf-oauth-client-id-metadata-document-02`。）这砍掉了 P1 的一大块工作，也让 CIMD 的风险推迟到它真正被需要时。

**为什么 CLI 用 Go。** 单静态二进制、无运行时依赖、启动快——而 **agent 一个任务会调它好几次**，启动延迟直接进体验；Java 在这里不合适（每次调用付 JVM 启动税，还要在用户机器上带 JRE）。keychain 可用且**无 CGO**（`zalando/go-keyring` 依赖纯 Go 的 wincred / godbus），静态构建成立。**否掉 Node**：`keytar` 已归档（2022-12，prebuilds 停在 Node 17 ABI）；「生态用 npx 分发」那条优势也被 GoReleaser 出 npm wrapper 抵消。**否掉 Rust**：可行，但分发更麻烦。

## 后果

- **两门语言的代价要说清**：两套工具链、两条 CI、两个依赖生态。更要紧的是**校验逻辑会重复**——CLI 发布前想校验，服务端上传时也必须校验（上传上限 512 文件 / 16 MiB，口径见 [`technical-design.md`](../versions/v1-hosting/technical-design.md) §1.3；另有 frontmatter、符号链接与路径穿越）→ **约束：服务端是唯一权威，CLI 的校验只能是「提示级」，不能是「信任级」。**
- **不要指望跨语言共享核心代码。** 规则要往**数据库与契约**里推——这正是 [ADR 0010](0010-storage-in-postgres.md) 那层 `BlobStore` 接缝、以及决定 4 那个客户端查找接口的意义。
- **`skill-platform-server/` 的构建配置要换**：`pyproject.toml` / `uv.lock` → Maven 或 Gradle；`Dockerfile` 从 `python:3.13-slim` 换成 temurin 基础镜像；`.github/workflows/server.yml` 随之改（**它当前是停用状态**）。
- **`technical-design.md` 第 6 章已按此重写**：那四个模块的定位从「原样搬迁、可复用、行号仍有效」变为「**设计参考**，Java 重写」；[`known-issues.md`](../versions/v1-hosting/known-issues.md) 里指向它们的锚点（条数见该文件开头）随之成为历史记录。
- **Python 基线暂不删除**，留到 Java 覆盖同一片语义之后再删——它是 `_collect_files` 的行为、权限族匹配这些边角语义的**唯一可执行记录**（TD 是散文，不是可运行的）。
- **[ADR 0007](0007-self-built-oauth-as.md) 的范围被本 ADR 收窄**（v1 只做预注册）。它的正文不改，只在文件头加了引用——这是 ADR 不可变的代价。
- **脚本执行的形态仍按 [ADR 0008](0008-no-script-execution.md) 未决**，本 ADR 不涉及。但其中一件**现在就落在桌上**：ADR 0008 说执行形态影响**上传校验策略**——v1 虽然不执行脚本，**已经要决定「收不收 `scripts/`、要不要扫、限不限类型」**。
