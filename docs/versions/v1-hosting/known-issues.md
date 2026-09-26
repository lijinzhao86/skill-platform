# 已知问题

> 日期：2026-09-26 · 对应首次提交
> 方法：提交前独立代码审计（fresh agent，无上下文）→ 主 agent **逐条复现验证**
> 处置：**先提交基线，暂不修复**。原因是发现**大多落在即将重写或废弃的模块**（`materializer` 已由 [ADR 0001](../../decisions/0001-server-authoritative.md) 判为基本作废，其余多集中在 `importer` / `store` / `permissions` 的旧模型上——见 [`technical-design.md`](technical-design.md) 第 6 章），现在修等于写即将删除的代码。**但不是全部**：文档与 Dockerfile 类的条目（L4、L5）与架构无关，重写不会自动消掉。逐条保留复现方式，便于重写时对照。

## 状态标注

| 标注 | 含义 |
|---|---|
| **已复现** | 我用脚本实际跑出来过 |
| **部分复现** | 只在特定条件下触发 |
| **未验证** | 审计报告提出，但我没有独立复现（保留供参考） |
| **已修复** | 已被后续改动解决（注明日期与改动） |

**分布（15 条，权威口径）**：9 已复现 + **1 两半混合**（M2：符号链接已复现、`dest_root` 缺失仅部分复现）+ 1 部分复现 + 3 未验证 + 1 已修复 = 15。

> 本文件是这组计数的**唯一权威来源**；`README.md` 与 `test-plan.md` 只链接，不复述。

---

## high

### H1 · `materializer` 的包含性检查双向失效

`skill_platform/materializer.py:31-36` · **已复现**

`_skill_target_dir` 用 `target.startswith(dest_root)` 判断是否越界，其中 `target` 是 `normpath` 后的，而 `dest_root` **从未规范化**。两个方向的错：

- **该拒的没拒（兄弟目录逃逸）**：`dest_root=/base/out`，skill 的 `path="../out-other"` → target `/base/out-other`，`startswith("/base/out")` 为真 → **`SKILL.md` 被写到 `dest_root` 之外**。这正是该检查存在的目的。
- **该放的没放（误拒）**：`dest_root="."` 时，`normpath("lark")` 不以 `"."` 开头 → **每个 skill 都抛** `ValueError: skill path escapes dest_root`。

现有测试 `tests/test_materializer.py:75` 只覆盖了"兄弟目录**不**共享前缀"的情形，因此给出虚假信心。

**修复方向**：拒绝绝对路径；用 `os.path.realpath` + `os.path.commonpath([root, target]) != root` 做带目录边界的比较。

### H2 · `--prune` 配不匹配的 `names` 会删光目录

`skill_platform/materializer.py:84-93` · **已复现**

`keep` 只来自被选中的 skill，而 `names` 里未知的名字在 `materialize` 的第 56 行被**静默丢弃**（第 55 行取回，第 56 行滤掉 `None`）。于是 `keep` 为空 → prune 删掉 `dest_root` 下**每一个**含 `SKILL.md` 的顶层目录。

复现：先 materialize `lark` + `brief`，再 `materialize(store, dest, names=["brieff"], prune=True)`（打错一个字母）→ `removed: ['brief', 'lark']`，`dest_root` **被清空**。目录里的外来文件也一并销毁。

从 CLI 可达：`skill-platform materialize --dest X --prune brieff`。

**修复方向**：`names` 先与 `list_skills()` 校验，未知即报错；给定 `names` 时 prune 只作用于这些名字，绝不作用于"除它们之外的一切"。

---

## medium

### M1 · 文件级 `relpath` 完全没有包含性检查

`skill_platform/materializer.py:74-82` · **已复现**

`full = os.path.join(target, relpath)`，其中 `relpath` 来自 `skill_files` 表，**没有任何校验**。复现：写一条 `relpath="../../pwned.txt"` 的记录 → 文件落到 `dest_root` 之外。

`importer` 产生不出这种 relpath（`os.path.relpath` 的结果不以 `..` 开头），所以只能经由公开的 store API（`import_skills` / `replace_skill_files`）注入——也就是"谁能写目录树"。

注意它与目录级 `path` 的不一致：目录级检查了，文件级没检查。而 `technical-design.md` §4.2 已经把"绝不拿 `relpath` 拼路径读磁盘"写成了规则——这条规则在新增的 API 里必须落实。

### M2 · prune 遇到普通文件系统状态就不健壮

`skill_platform/materializer.py:86-92` · **符号链接已复现**；`dest_root` 不存在**部分复现**

- **符号链接目录**（正是 Vercel `skills` CLI 推荐的安装模式）：`shutil.rmtree` 拒绝符号链接 → `OSError`，prune 中途中断。
- **`dest_root` 不存在**：只在**空目录（目录里没有任何 skill）**时触发——有 skill 时写循环会先把它建出来，所以我的测试未复现；空目录下 `os.listdir` 抛 `FileNotFoundError`，CLI 表面成 `error: [Errno 2] ...` 而不是"没有可清理的内容"。

**修复方向**：缺失的 root 视为空；用 `os.path.islink` + `os.unlink`，或只对真实目录用 `rmtree`。

### M3 · 导入单个 skill 目录必定失败

`skill_platform/importer.py:174-192` · **已复现**

`_iter_skill_md_dirs` 在 `SKILL.md` 位于顶层时会记录根目录本身（`path` 为 `""`），而第 183 行的独立文件扫描又把同一个 `SKILL.md` 当成"根级单文件 skill"，于是 `seen_names` 撞名。

复现：`skill-platform import <一个-skill-目录>` → `ValueError: duplicate skill name 'root'`。它在写库前就中止，不会丢数据，但**"导入这一个 skill"这个最自然的调用方式不可用**，且错误信息误导。

### M4 · 标量 `depends_on` 被逐字符当成依赖

`skill_platform/importer.py:162`（配合 `_parse_scalar`，46-55） · **已复现**

`for dep in fm.get("depends_on") or []` 直接迭代**字符串**。复现：`depends_on: lark` 在 `brief.md` 里 → 写入四条 `skill_links`，target 分别是 `a`、`k`、`l`、`r`，并产生四条假的悬空链接告警。

单值 `depends_on` 是完全自然的写法，而测试只覆盖了列表形式。

### M5 · 手写 frontmatter 解析会静默损坏值

`skill_platform/importer.py:46-55, 62, 83-98` · **三条均已复现**

| 输入 | 解析结果 | 后果 |
|---|---|---|
| `name: lark  # the router` | `'lark  # the router'` | DB 里的名字匹配不上 `resolve(..., "lark")`，**被授权的用户会被拒**；materialize 还会建出含 `#` 的目录名 |
| `description: \|`（字面块，极常见） | `'\|'` | 描述文本**整段丢失**（`>` → `'>'`，`\|-` → `'\|-'`） |
| `metadata:` 嵌套映射 | `''` | 字段丢失（设计文档已指出） |
| SKILL.md 带 UTF-8 BOM | 整个 frontmatter 被忽略 | `lines[0].strip() != "---"` → 对独立文件而言该 skill 被**静默跳过，无任何告警** |

`technical-design.md` §3.3 / §6 已要求换成完整 YAML 解析器且**不得静默降级**。在换掉之前，至少应剥离行内注释、并对无法处理的构造**显式报错**。

### M6 · 把容器目录传给 `import` 会清空整个目录

`skill_platform/store.py:333-338` · **已复现**

`import_skills` 的语义是"用新结果整体替换目录"，而唯一的保护是"非空"。复现：库里有 `a` `b` `c`，改指向 `import <src>/container`（容器目录，或纯粹打错的路径，其顶层没有 `SKILL.md`，所以 M3 那条不会先拦住）→ 只导入 1 个，**`a` `b` `c` 及其全部文件被级联删除**。源真相被覆盖且**没有备份路径，不可逆**。

附带发现：`grants` / `denials` 对 `skills` **没有外键**，所以已删 skill 的授权会存活，日后重新导入会**静默恢复旧授权**。

**修复方向**：加显式的 `--replace` 开关；新集合若导致目录显著缩小则拒绝（除非强制）；CLI 在提交前先把将被删除的名字打印出来。

### M7 · `*` 形式的族名盖不住族根

`skill_platform/permissions.py:41-46` · **已复现**

`_matches('lark', 'lark-*') = False`——因为 `pattern.endswith("*")` 走的是 `skill.startswith(pattern[:-1])`，即要求字面量 `lark-` 前缀。

复现（`global_default="public"`，`skill_access` 无 `lark` 条目）：
- 否决写成 `lark-*` → `resolve(u, 'lark')` = **True**（意外的 ALLOW），而 `lark-im` / `lark-mail` 被正确拒绝。
- 否决写成 `lark` → 三者全被拒绝。

因为 `lark-*` **看起来**比 `lark` 更宽，运维写它就会在 router skill 上留下一个意外放行。

**修复方向**：把结尾的 `-*` 归一化成裸族名，或在授权/否决入口直接拒绝通配符写法。

### M8 · `access` 命令对不存在的 skill 静默报成功

`skill_platform/__main__.py:87-91` · **已复现**

`set_skill_access` 是一条 `UPDATE`，匹配 0 行，而**没有任何地方检查 `rowcount`**（`tests/test_store.py:57` 甚至注释了 "no-op, skill doesn't exist yet"）。运维被告知权限已变更，实际什么都没发生。

---

## low

### L1 · 跨文件引用链接丢失

`skill_platform/importer.py:164-170` · **未验证**

根级独立 skill 之间的引用链接会被静默丢弃：href 的 dirname 是 `""`，解析到 `source_root`，而 `name_map` 只收录**目录型** skill。

### L2 · `schema_version` 写入后从不与代码常量比对

`skill_platform/schema.py:74-84` · **部分复现（读代码确认）**

该值被写入，也被读取——但只用于判断"是否已初始化"，**从不与 `SCHEMA_VERSION` 常量比较**。配合 `CREATE TABLE IF NOT EXISTS`，将来改 schema 会**静默地把老库留在老形状上**。

另外 77-84 行是 `SELECT` 后再 `INSERT`，**没有 `ON CONFLICT`**（与 `store.py` 里其它 meta 写入不一致），两个进程同时打开一个全新 DB 可能撞 `UNIQUE constraint failed: meta.key`。窗口很窄。

`technical-design.md` §6 声称存在"schema_version 迁移机制"——**该声称与代码不符**。

### L3 · `PermissionCache._reload` 的两次事务窗口

`skill_platform/permissions.py:108-111` · **未验证**

先读快照、后读版本，分属两次事务。两次之间落地的权限写入会被记成"当前版本"，要等 TTL 过期才被看见。有 TTL 兜底，因此不比设计更差，但**先读版本号即可消除该窗口**。

### L4 · 文档与代码不符 · **已修复**（2026-09-26）

- 根 `README.md` 曾称 importer 是 "git importer"，并描述一个 web console——两者都**没有对应代码**（`import` 接受的是本地目录，仓库里没有任何 git 相关代码，也没有 console）。**已改写为真实的包结构与状态。**
- `pyproject.toml` 曾宣传 "tiered licensing, progressive payment"，而 [`technical-design.md`](technical-design.md) 明确本阶段**不含计费**。**已改为 "server-side storage, remote progressive loading"。**

### L5 · Dockerfile 的 `/app` 归 root

`.cicd/Dockerfile:7,18` · **未验证**

运行时用户对数据库路径是正确的（`SKILL_PLATFORM_DB` 目录已创建并 `chown` 给 `skill`，WAL 的 `-wal`/`-shm` 落在可写目录）。但 `WORKDIR /app` 仍归 root，因此容器内跑 `materialize --dest out` 这类相对路径会 `EACCES`。按其"只作 DB 用途"的定位没问题；若 materializer 保留则需 `chown /app`。

---

## 没有发现问题的部分

审计同时确认了以下方面**没有问题**，不必再查：

- **SQL 注入**：`store.py` 的 `IN (?,?,…)` 完全由 `"?"` 字符拼成，不含未可信输入；不存在字符串插值式 SQL。
- **CI 里 pin 的 action 版本**：`actions/checkout@v7`、`astral-sh/setup-uv@v10`、`aws-actions/configure-aws-credentials@v6`、`aws-actions/amazon-ecr-login@v2`、`docker/setup-buildx-action@v4`、`docker/build-push-action@v7` **均解析到真实存在的已发布 tag**。
- **Dockerfile 的安装序列可跑通**：在干净副本里 `pip install .` 能产出可用的 `skill-platform` 入口；当前版本 setuptools 仍接受 `license = { text = "MIT" }`，无 PEP 639 破坏。
- **本次为过 lint 所做的机械改动是行为保持的**：`TYPE_CHECKING` 导入（注解得 `from __future__ import annotations` 延迟求值，且运行时的 `PermissionSnapshot` 导入仍在）、`datetime.now(UTC)`、以及测试里 `l` → `link` 的改名，均不改变行为。
- `ruff check`、`ruff format --check`、`pytest`（39 项）全部通过。
