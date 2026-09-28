# v1-hosting · 测试方案

> **最后更新**：2026-09-28
> **状态**：**部分执行**。P0a 与 P0c（服务端）已实现并自测通过；**T0–T4 属于 P0b**（需要 CLI），尚未执行
> **所属版本**：[`README.md`](README.md)

## 验收映射

[`prd.md`](prd.md) §验收与指标 的每一条都在这里映射到用例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 用户能注册并登录、拿到令牌 | T0 | **P0b**（注册属 P1） |
| #2 `setup` 能装上网关 skill | T1 | **P0b** |
| #3 agent 自己搜到需要的 skill | T2 | **P0b** |
| #4 读到正文、按需取文件并完成任务 | T3 | **P0b** |
| #5 **全程无 skill 副本落盘**（成立条件） | T4（+ T4b 做反证） | **P0b** |

**为什么这五条是 P0b 而不是「未执行」**：它们全部要在 agent 里、经 CLI 走一遍，而 CLI 不存在（见 [`technical-design.md`](technical-design.md) §7 的分期说明）。把它们留在「未执行」会读成「还没做」，实际是「这一轮不做」。

以下用例不对应验收标准，但验证接口契约的安全性。**这三条只依赖服务端，所以 P0a 就执行了**：

| 契约 | 对应用例 | 状态 |
|---|---|---|
| 未认证 → `401` 且形状符合规范 | T5 | **通过**（`AuthContractIT`、`InsufficientScopeIT`） |
| 无权者访问私有 skill → `404`（不泄露存在性） | T6 | **通过**（`SkillDetailIT`、`SkillContentIT`） |
| 中文检索可用 | T7 | **部分通过 / 阻塞**——见下 |

**T7 为什么是「部分通过 / 阻塞」而不是「通过」**：功能层已通过——2 字中文查询能命中，`SkillSearchIT` 钉住了这一条。但 T7 的原意是验证**选定的索引**在目标实例上可用，而 P0a 根本没有用索引（`LIKE` 直查，`pg_bigm` 后置为纯优化）。所以「索引是否可用」仍未验证，而它依赖阿里云 RDS 是否支持 `pg_bigm`——这一条仍未核实（第 8 章开放问题 3）。**功能不是靠索引做到的，这一点要说清楚**：`LIKE` 解决的是延迟不是相关性，所以 T7 通过不构成「索引可用」的证据。

## 用例

### T0 · 注册与登录（**P0b**——需要 CLI）

- **前置**：干净环境，服务端可访问。
- **步骤**：注册一个用户 → 登录 → CLI 拿到令牌 → 用它调一个需鉴权的接口（如 `GET /v1/skills`）。
- **期望**：注册即得到**个人命名空间**；登录后令牌可用；**令牌不出现在命令行历史或模型上下文里**（由 CLI 从系统 keychain 读）。
- **判定**：通过／失败。

### T1 · setup 装上网关 skill（**P0b**——需要 CLI）

- **前置**：干净环境，没有装过任何 skill。
- **步骤**：跑 `setup`；检查网关 skill 的落点与 frontmatter。
- **期望**：网关 skill 落地；frontmatter 常驻成本 < 200 tokens；**除网关外没有别的 skill 被装上**。
- **判定**：通过／失败。

### T2 · agent 自主搜索命中（**P0b**——需要 CLI）

- **前置**：已 setup 并 login；服务端已托管至少一个含 L3 的真实 skill。
- **步骤**：在 agent 里提出一个**不点名 skill** 的任务；观察 agent 是否通过网关 skill 的 `description` 命中并调用 CLI `search`。
- **期望**：agent 自己搜到正确 skill；`search` 只返回 L1 卡片（响应里不含正文或文件内容）。
- **判定**：通过／失败。失败时要记录 agent 实际说了什么、调了什么。

### T3 · 按需读取并完成任务（**P0b**——需要 CLI）

- **前置**：同 T2，已 `show` 到目标 skill。
- **步骤**：agent 读正文（`/body`）→ 需要时取文件（`/files/{relpath}`）→ 完成任务。
- **期望**：任务产出正确；**L2 与 L3 是在需要时才被取用的**，不是一次性拉全。
- **判定**：通过／失败。

### T4 · 全程无本地副本（成立条件；**P0b**——需要 CLI）

- **前置**：同 T1，干净环境。
- **步骤**：
  1. 记录跑之前的 `~/.agents/`、`~/.claude/skills/` 快照（若不存在则记为空）；
  2. 完整跑 T1 → T3；
  3. 再记录一次快照并比对。
- **期望**：新增内容里**不存在任何托管 skill 的完整副本**——只有网关 skill、CLI 的凭据与配置、以及任务中间产物（临时目录）。
- **判定**：通过／失败。**这一条失败则整版不成立**，即使 T1–T3 全通过。

### T4b · 反证：副本检测确实能发现副本（**P0b**——需要 CLI）

- **目的**：T4 是一条"没发现即通过"的断言，必须证明检测手段**不是永远通过**。
- **步骤**：故意用替代做法（把某个 skill 下载到 `~/.agents/skills/`）造一个副本，再跑 T4 的比对。
- **期望**：检测**报告出**这个副本。
- **判定**：通过／失败。**T4b 不过则 T4 的结论无效。**

### T5 · 未认证访问

- **步骤**：不带 `Authorization` 头请求 `/v1/skills`；再带一个无效 token 请求一次。
- **期望**：`401` + `WWW-Authenticate: Bearer resource_metadata="…"`；带无效 token 时**不泄露**任何资源信息。
- **判定**：通过／失败。

### T6 · 私有 skill 对无权者返回 404

- **前置**：存在一个 `private` skill，请求者不是该命名空间的所有者（即另一个用户）。
- **步骤**：请求 `GET /v1/skills/{ns}/{name}`，指向另一个用户的 skill。
- **期望**：**`404`（不是 `403`）**——不泄露该 skill 是否存在。
- **判定**：通过／失败。

### T7 · 中文检索

- **目的**：验证中文检索在**选定的 PostgreSQL 实例上**真的可用（[`technical-design.md`](technical-design.md) §8 开放问题 3）。SQLite 时代的方案已实测不可行，理由见 [ADR 0010](../../decisions/0010-storage-in-postgres.md)。
- **前置**：索引扩展在目标实例上可用，且已加进 `shared_preload_libraries`——**这一条本身要先验，它可能直接阻塞本用例**。
- **步骤**：托管一个中文 `description` 的 skill；分别用 **2 字**关键词与 3 字以上关键词 `search`。
- **期望**：两者都能命中。**2 字查询是重点**——它是中文最常见的一次查询，也正是 SQLite 方案失效的地方。
- **判定**：通过／失败／阻塞（目标实例不支持该索引扩展）。

### 服务端用例（P0a 新增）

T0–T7 是**端到端验收**，服务的**契约**要另有一层。P0a 与 P0c 加的这一批不替代上面的用例：它们把端点逐个钉住，好让 T0–T4 在 P0b 失败时能指出是服务端还是 CLI。

全部打**真 PostgreSQL**（本机 16.14，`scripts/init-test-db.sh` 建库）与**真 Tomcat 随机端口**——不用 MockMvc，因为其中一部分断言的就是容器的行为（编码斜杠怎么处理、穿越会不会在路由前被规范化、防火墙在选 controller 之前拒绝请求会变成什么）。**Docker 不可用，所以没有 Testcontainers**；连不上库时是**失败**并给出指引，不是跳过——静默跳过的集成测试就是会烂掉的集成测试。

| 用例 | 钉住什么 |
|---|---|
| `AuthContractIT`、`InsufficientScopeIT` | T5：401 的精确挑战形状、坏令牌不泄露、scope 不足 403 |
| `SkillPublishIT` | 发布幂等（同内容不产生新版本、指针不前移）、**序号按「不同内容」计数且重发不消耗**、**并发发布各拿各的号**、blob 去重、GC 接缝、软删、跨用户拒绝、错误信封、multipart 上限 |
| `SkillDetailIT` | T6：他人的 private skill 是 404 而非 403，且与「不存在」**逐字节**不可区分；清单顺序与每项 `uri` 已钉版本；`resources` 只剩 `body`；frontmatter 嵌套与未知字段透传 |
| `SkillContentIT` | L2/L3 逐字节相同；`../`、`%2e%2e%2f`、编码斜杠一律 400/404，**绝不 200 或 5xx** |
| `SkillSearchIT` | T7：2 字中文命中；他人 skill 不出现；`namespace` 不能当越权开关；`%`/`_` 按字面搜；**卡片字段集固定且 `version` 嵌套**；排序分层；游标翻页无重复无跳过；畸形游标与异排序游标都 400 |
| `GatewayIT` | 未发布时四条路径**全真 404**；两条索引都发；`digest` 不是版本 digest；三个别名同字节、别的 base 404；重启重发不产生新版本 |
| `ServerSmokeIT` | 端到端一遍：发布 → 搜到 → 详情（零内容）→ **照抄详情给的 `uri`** 取正文与文件 → 删除后从搜索里也消失 |
| `SkillVersionPinIT`（P0c 新增） | `@序号` 与 `@sha256:` 两种钉法；`is_latest` 的两种取值；**发布新版本后旧清单的 `uri` 仍取得到旧字节**（ADR 0012 那个 bug 的回归）；钉住的版本随软删一起 404；四类「解析不出东西」的回答逐字节相同；写地址不接受版本后缀 |
| `SkillVersionServiceIT`（P0c 新增） | **所有权谓词在 M7 自己那一层**：直接传一个不属于调用者的 namespace id，读与删都是空。经 HTTP 测不到它——用例层会在 M7 之前就把地址挡掉。**审计轮加强**：钉住「序号是按 skill 计的」——两个**都活着**的 skill 拿**不同**的序号，交叉查必须都落空（原先两个 fixture 同号又被软删，空结果来自 `deleted_at` 而不是版本查找） |
| `SkillAddressTest`（P0c 新增，纯单测） | 地址语法：`@0`/`@+3`/`@03`/长度不对的 digest/大写 hex/第二个 `@` 都不解析 |

审计轮还加/改了这一批，全部是**先能复现旧行为**才留下的：

| 用例 | 钉住什么 |
|---|---|
| `SkillContentIT`（审计轮加强） | 断言到**错误码**：清单里没有的 `relpath` 是 `file_not_found`，他人的 skill 仍是 `skill_not_found`（后者要是变成前一个，就等于确认了这个 skill 存在） |
| `SkillSearchIT`（审计轮加强） | 上限**真的撞到**（插 105 条，`limit=101`/`limit=100000` 都回 100——原先插 25 条断言 25，删掉 clamp 也照样通过）；`namespace` 两个方向都验（只插他人 skill 时，「空」分不出「过滤」与「参数被忽略」）；游标三处：**键内容不合法是 400 而不是 500**（`k0` 会被 `CAST(… AS integer)`）、**时间戳非规范写法被拒**（键是按文本比较的）、**非 ASCII 数字被拒**（`Integer.parseInt` 收 Unicode 数字，`CAST` 只收 ASCII）；**同宽度不同权重的游标被拒**（原先那条只证到了宽度检查） |
| `SkillUploadValidatorTest`（审计轮加强） | 含 `%` 或 `;` 的名字被拒；**空值 frontmatter 键（`license:`）不再 500**；`title: ""` 回落到 name；**自引用 YAML 别名被拒**（否则 frontmatter 是无限结构，Jackson 抛异常 → 500）；**重复 `relpath` 被拒**；**字节上限打在「边读边计数」上**（声明大小是谎的 zip——原先那条被声明大小先挡下，删掉计数界也照样通过） |
| `SkillPublishIT`（审计轮新增） | 两条**并发**回归：不同 skill 的并发发布不死锁（锁位置）；**共享文件、zip 内顺序相反**的两个发布不死锁（blob 的存储顺序） |
| `InsufficientScopeIT`（审计轮加强） | HEAD 走**读** scope 而不是写：只有写 scope 的令牌请求 HEAD 得 403，且挑战里写的是 `skills:read` |
| `TableOwnershipTest`（审计轮加强） | 与**全部** `V*.sql` 迁移**双向**对表（原先只数 `ModuleMap` 自己的条目；后来只读 V1、且正则区分大小写，都被突变测试证伪后修掉）；另加一条正向断言钉住「表名匹配器本身认得 SQL」——否则「无违规」在什么都不匹配时也成立 |
| `SkillmasterPropertiesTest`（审计轮新增，纯单测） | `public-base-url` 结尾的斜杠被去掉——留着会发出一条 `//` 的取不到的 URL |
| `RelevanceWeightsTest`（审计轮新增，纯单测） | 三个权重之和超出 int4 时在**启动时**失败，而不是让每次检索都 500 |

## 环境与数据

- **环境怎么造**：一台**没有装过任何 skill** 的机器或容器——`~/.agents/` 与 `~/.claude/skills/` 不存在或为空。这是 T1/T4 能成立的前提；在不干净的环境里跑，T4 的结论无意义。
- **数据**：至少一个**真实的、含 L3 文件**的 skill 被托管。建议用本机已有的 `lark-*` 系列（其 L1 约三分之一是中文，口径见 [`technical-design.md`](technical-design.md) §1.6，同时覆盖 T7）。不要用手写的玩具 skill——它测不出渐进加载的真实收益。
- **清理**：每次跑完记录快照；两次快照都要留档，作为 T4 的证据。

## 结果

**P0a 已执行**（2026-09-28）。`JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -B verify` → **BUILD SUCCESS，138 个测试通过**（72 单测 + 66 集成）。**T0–T4b 未执行**，属 P0b。

**P0c 已执行**（2026-09-28，同一条命令）→ **BUILD SUCCESS，159 个测试通过**（80 单测 + 79 集成）。这一轮的动作有两半：把 P0a 那批**断言旧寻址的测试逐条重写**成 `namespace/name[@版本]`（旧契约的测试全绿不是新契约成立的证据，所以没有一条是靠打补丁留下的），以及**新增** `SkillVersionPinIT`、`SkillVersionServiceIT`、`SkillAddressTest` 三个类钉住序号语义、钉版与地址语法。**T0–T4b 仍未执行**，仍属 P0b。

**P0c 之后的循环审计已执行**（2026-09-28，`clean verify`）→ **BUILD SUCCESS，172 个测试通过**（89 单测 + 83 集成）。共四轮独立审计，范围是整条 `p0a-server` 分支相对 `main` 的全部改动；每一轮换一批**全新的** agent，主 agent 逐条复核后才动手。

修掉的 high 有六条，其中两条是**第一轮的修法自己引入的**（这正是循环审计要抓的东西）：

| 缺陷 | 怎么发现的 |
|---|---|
| 合法 SKILL.md 里一个空值 frontmatter 键（`license:`）→ 发布 500——`Map.copyOf` 拒绝 null 值 | 第 1 轮 |
| 畸形游标的第一段会被 `CAST(… AS integer)` → 500，而 §4.1 说该是 400 | 第 1 轮（复现） |
| blob 回收与并发发布之间的快照窗口 | 第 1 轮 |
| `%` 或 `;` 出现在 skill 名里 → 发得出去、**取不到、也删不掉** | 第 1 轮 |
| **修法引入**：第 1 轮那把锁放在 sweep 里太晚，两个**不同** skill 的并发发布互相等对方的插入锁 → 死锁 | 第 2 轮（`deadlock detected` 实测复现） |
| **修法引入**：第 2 轮把锁提前了，但仍没覆盖 blob 写入阶段——两个发布若共享 ≥2 个文件且 zip 内顺序相反，在 `ON CONFLICT DO NOTHING` 上 ABBA 死锁（这条 P0a 起就有） | 第 3 轮（实测复现） |

另有若干 medium（框架 404 回错码并丢掉 405 的 `Allow`、zip 里重复 `relpath` 撞主键变 500、`title: ""` 不回落到 name、`file_not_found` 定义了却从不发出、**自引用 YAML 别名**让 frontmatter 变成无限结构 → 500）与一批 low（注释与代码不符、测试名不副实）。

**每一条修复都配了会失败的回归测试**，其中三条关键的另做了突变验证（把生产代码改回旧行为，确认测试确实变红）：边读边计数的字节上限、锁的位置、blob 的存储顺序。测试数见本节末尾关于「数字从哪儿来」的说明。

**测试数一律取当次 `verify` 的输出。** 上面 P0c 那一行照抄的 159 与本轮 `clean verify` 重跑的结果对不上；**它当初怎么来的已经无从还原，此处不编一个原因**。能记下的是方法上的一条：`target/surefire-reports/` 会留下历史报告（本轮就有一个早已删除的临时探针类留下的报告，时间戳是几小时前的），**按报告文件数去数会数进历史遗留**，所以这一节的数字只能从命令输出读，不要沿用上一行。

| 用例 | 结果 | 证据 |
|---|---|---|
| T0 | 未执行（P0b） | — |
| T1 | 未执行（P0b） | — |
| T2 | 未执行（P0b） | — |
| T3 | 未执行（P0b） | — |
| T4 | 未执行（P0b） | — |
| T4b | 未执行（P0b） | — |
| T5 | **通过** | `AuthContractIT` 6 例、`InsufficientScopeIT` 2 例（含审计轮加的 HEAD 走读 scope） |
| T6 | **通过** | `SkillDetailIT.anotherUsersSkillIsNotFoundRatherThanForbidden`、`SkillContentIT.anotherUsersFileIsNotFound` |
| T7 | **部分通过 / 阻塞** | 功能：`SkillSearchIT.aTwoCharacterChineseQueryFindsTheSkill` 通过；索引：P0a 未用索引。`pg_bigm` 的可用性与其剩余待验项见 [`technical-design.md`](technical-design.md) §8 开放问题 3（同一个事实的权威在那里） |

**本轮证据是自动化测试，不是手工快照**——P0a 交付的是服务端契约，它们全都被打真库真容器的集成测试钉住了。**CLI 与 agent 侧的一切都没有证据**，因为那一层还不存在。

跑完后逐行填，**每条都要有证据**（命令输出、快照 diff、日志片段）。失败或阻塞的用例单列说明，不要埋在表格里。

**未验证、且本轮无法验证的**（单列，因为埋在表格里会被读成已通过）：

- Docker 镜像——本机无 Docker，`Dockerfile` 仍从未构建过
- `pg_bigm` 在阿里云 RDS **基础版**是否可用（P0a 不依赖它，但 T7 的长期形态依赖）
- 阿里云 RDS 是否允许用户表空间（`ALTER TABLE blob_content SET TABLESPACE`）
- **well-known 索引的 digest 是否符合真实客户端期望**、V2 的 `$schema` 是什么 URL、逐文件拉取的 `<base>` 该是哪几个——三者都要等 **P0b 的 CLI 拉一次真服务器**才能判定

## 已知问题

**本版范围内**的未修缺陷。

| # | 问题 | 状态 |
|---|---|---|
| 1 | **文档与实现不一致：寻址。** [ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) 已定 `namespace/name[@版本]`，而 P0a 的实现与全部集成测试仍是 `/v1/skills/{id}`、无版本。 | **已修复**（P0c，2026-09-28）：代码与测试都改到新寻址，`SkillVersionPinIT` 与 `SkillAddressTest` 钉住新契约 |
| 2 | **L2/L3 在旧寻址下钉不住版本。** 三个端点各自解析 `current_version_id`，看完清单再取文件会拿到另一个版本，且不报错。 | **已修复**（P0c）：详情把解析出的版本写进每条 `uri`，回归测试是 `SkillVersionPinIT.aManifestStaysReadableAfterSomebodyPublishesAgain` |
| 3 | **`file_not_found` 这个码定义了、但从不发出。** TD §4.1 给了它「清单里没有这个 `relpath`」的语义，而 L3 把「版本读不到」与「`relpath` 不存在」折叠成同一个 404 `skill_not_found`。 | **已修复**（2026-09-28 审计轮）：M9 的 `fileOf` 改返回 sealed `FileLookup`（`Found` / `NotFoundInManifest`），L3 路由按它分别给 `skill_not_found` 与 `file_not_found`。**不泄露任何东西**：能走到 `file_not_found` 的调用者，其版本已经解析成功、也就是本来就看得见这个 skill，而 L1 详情早把清单里每个 `relpath` 都给了它。回归测试是 `SkillContentIT.aFileThatIsNotInTheManifestIsNotFound`（断言到码）与 `anotherUsersFileIsNotFound`（断言他人 skill 仍是 `skill_not_found`） |
| 4 | **`relpath` 含 `%`、`;` 或 `.` 段会得到一条取不到的 `uri`。** 详情发出的 `uri` 里 `%` 与 `;` 要百分号编码（`%25`、`%3B`），而 Spring Security 的 `StrictHttpFirewall` 把两种写法**都**拒绝；`. ` 段则被它的路径规范化检查（`/./`）拒绝——照抄那条 `uri` 就得到 `400`。**实测**（2026-09-28，本机、真 Tomcat、临时集成测试）：`uri` = `/v1/skills/demo/probe@1/files/references/a;b.md` → 跟随它 `400`；`a%3Bb.md` → `400`。**同日复核**（对着 `spring-security-web-7.1.1` 的字节码）：`encodedUrlBlocklist` 由 `;`/`%3b`、`%2f`、`//`、`\`/`%5c`、`%00`、`%0a`、`%0d` **加一条显式的 `%25`** 组成，所以 `%` 与 `;` 同等。 | **部分修复**：`name` 那一半已由 M5 收口（含 `%` 或 `;` 的名字一律拒绝，错误码 `name_contains_unaddressable_char`）——那一半更严重，因为它会让 skill **连详情与正文都取不到、删除也取不到**。**`relpath` 那一半仍未修复，且是有意不修**：收紧它意味着含这类字符的**文件**会让整个 zip 上传失败，而用户 2026-09-28 明确选择只收紧 `name`。代价是这三类文件名各自会得到一条取不到的 `uri` |
| 5 | **错误信封总是带 `details`，而 TD §4.1 说它「只在字段级错误上出现」。** `ApiError.Error` 的 `details` 默认为空列表并被序列化，于是 404/401/`invalid_request` 这些非字段级错误也带一个 `"details":[]`。**实测**（2026-09-28，手工走查）：未知版本 → `{"error":{"code":"skill_not_found","message":"no skill at that address","details":[]}}`。一行注解（`details` 非空才序列化）就能改对。 | **未修复**（既有行为，P0c 之前所有错误码都如此，P0c 的 `skill_not_found` 只是照既有写法实现；修它要动**所有**错误响应的线格式，不属于寻址，所以本轮没顺手改） |

| 6 | **`namespace` 指到别人的命名空间时，游标不再被校验。** `?namespace=other&cursor=<垃圾>` 得 `200` 加一个空页，而同一个游标在 `?namespace=demo` 下是 `400`（§4.1 说读不懂的游标是 400）。**审计轮实测**（第 4 轮）。**无后果**：这不是他命名空间的请求本来就该是空页，客户端读到的结论与它应得的一致，也不会翻页循环；所以这是契约上的一处不一致，不是会造成错误行为的缺陷。修它要把「命名空间过滤」这件事下移进 M8，或者让用例层重做一遍游标的解析——为一个没有后果的不一致改模块边界，不值得。 | **未修复**（有意；见左栏理由） |

未验证项（上面单列的那几条）**不在此表**——它们是「没有证据」，不是「已知有问题」。

**本版范围外的**历史代码缺陷不写在这里，在 [`known-issues.md`](known-issues.md)：那是首次提交（`c8a7362`）对**重写前的基线代码**的审计结论（**条数与分布见该文件开头**），随架构重写一并处理。

> 两者的分工：本版交付范围内的缺陷 → 本节；范围外、本版决定不改的历史缺陷 → `known-issues.md`。
