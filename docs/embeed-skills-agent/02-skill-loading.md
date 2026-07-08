# 02 — Skill 加载

## 1. 标准 skill 格式定义

一份 skill 可以是两种形式之一：

- **独立 `.md` 文件**（如 `my-skill.md`）：整个文件即一个 skill。
- **目录 skill**（如 `my-skill/SKILL.md` + `my-skill/REFERENCE.md`）：目录下必须包含 `SKILL.md` 作为入口；目录内其他文件为辅助资源（如参考文档、SQL 片段），**不被解析**，仅供 skill 正文引用。当目录存在 `SKILL.md` 时，整个目录视为一个 skill。

标准 skill 文件结构如下：

```markdown
---
name: sep-wh-replenish-diagnose
description: 分仓补货计划生成问题分析。Use when investigating why a SKU did not generate...
tools: [mysql_query, redis_get]      # 声明所需工具名（必填，本库契约）
inputs:                              # 声明入参契约（选填；无则不渲染表单）
  - key: skuCode
    label: 件号编码
    required: true
    type: string
  - key: warehouseCode
    label: 目的仓编码
    required: false
    type: string
  - key: env
    label: 环境
    required: true
    type: enum
    options: [sit, uat, prod]
  - key: tenantId
    label: 租户ID
    required: false
    type: string
  - key: generateDate
    label: 期望生成日期
    required: false
    type: date
---

# 分仓补货计划生成问题分析

## Phase 1: 收集信息
（正文，含 {skuCode} / {warehouseCode} 占位 SQL 与结果判断）
...
```

### frontmatter 字段契约

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `name` | ✅ | string | skill 唯一标识，与文件名一致；重复 → 加载报错 |
| `description` | ✅ | string | LLM 用于判断何时选用本 skill 的触发描述（与 Claude Code skill 同语义） |
| `tools` | ✅ | string[] | 所需工具名清单；缺任一 → skill 标 `UNAVAILABLE` |
| `inputs` | ❌ | InputSpec[] | 入参契约；缺省 → UI 只显示 Run 按钮，不渲染表单 |

### InputSpec 字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `key` | ✅ | 参数键，对应正文占位符 `{key}` |
| `label` | ✅ | 表单显示名 |
| `required` | ✅ | 是否必填；提交时校验 |
| `type` | ✅ | `string` / `enum` / `date` / `number` / `boolean` |
| `options` | type=enum 时必填 | 可选项 |
| `default` | ❌ | 默认值 |

## 2. 与现有 3 个 skill 的迁移

现有 skill（`allocation-plan-diagnose`、`sep-wh-replenish-diagnose`、`replenishment-strategy-diagnose`）frontmatter 仅 `name` + `description`，正文用 `mcp__mysql-sit__query` 工具引用与 `{skuCode}` 占位。迁移到本库标准格式需补两字段：

**迁移前（现状，节选自 `docs/skills/sep-wh-replenish-diagnose/SKILL.md`）：**
```yaml
---
name: sep-wh-replenish-diagnose
description: your business system分仓补货计划生成问题分析。Use when investigating...
---
```

**迁移后（标准格式）：**
```yaml
---
name: sep-wh-replenish-diagnose
description: your business system分仓补货计划生成问题分析。Use when investigating...
tools: [mysql_query]
inputs:
  - { key: skuCode,      label: 件号编码,   required: true,  type: string }
  - { key: warehouseCode,label: 目的仓编码, required: false, type: string }
  - { key: env,          label: 环境,       required: true,  type: enum, options: [sit, uat, prod] }
  - { key: tenantId,     label: 租户ID,     required: false, type: string }
  - { key: generateDate, label: 期望生成日期,required: false, type: date }
---
```

正文侧的改造（由 skill 作者完成，本库不强约束）：
- `mcp__mysql-sit__query` → 统一工具名 `mysql_query`（本库 `JdbcQueryToolProvider` 注册名）。环境选择不再由 skill 正文判断（`env=sit` 选哪个 MCP），而由 yml 配的只读 DSN 决定（一个 DSN 对应一个环境）。skill 正文只写 SQL 与结果判断。
- `{skuCode}` 等占位由 `AgentExecutor` 在构造 system prompt 前用 `inputs` 值替换。

> 迁移非本库代码职责；本库只要求 skill 文件 frontmatter 合规即可加载。

## 3. frontmatter 解析（snakeyaml，决策隐含）

解析流程：
1. 读文件首行必须是 `---`，否则视为无 frontmatter → skill 标 `INVALID`（缺 `name`/`description`/`tools`）。
2. 从 `---` 到下一个 `---` 之间为 YAML，用 snakeyaml `Yaml().load()` 解析为 `Map<String,Object>`。
3. 校验必填字段：`name`(string) / `description`(string) / `tools`(list)。缺任一 → `INVALID`。
4. `inputs` 若存在，逐项校验 `key`/`label`/`required`/`type`；不合规 → `INVALID`。
5. 第三个 `---` 之后为正文 body（原样保留，含占位符）。
6. 构造 `SkillMeta { name, description, tools, inputs, body, availability }`。

### snakeyaml 安全
- 用 `new Yaml(new SafeConstructor())`（SafeConstructor 拒绝任意对象构造），防止 YAML 反序列化攻击。
- 版本交由宿主 Spring Boot BOM 管控（决策 #17）。

## 4. 启动缓存 + 手动刷新（决策 #9）

### 启动扫描
- `SnapAgentAutoConfiguration` 装配 `SkillRegistry` 时，构造阶段**两层扫描**：
  - **内置 skill**：经 `ClasspathSkillScanner` 用 Spring 的 `ResourcePatternResolver` 扫描 `classpath:/docs/skills/**/*.md`，启动时解析一次，只读。
  - **上传 skill**：经 `FilesystemSkillScanner` 用 `Files.walkFileTree` 扫描文件系统 `upload-skills-dir`，刷新时重新扫描，读写。
- **目录 skill 扫描规则**：`preVisitDirectory` 检查目录下是否存在 `SKILL.md`；若存在，仅解析 `SKILL.md` 并跳过子树（`SKIP_SUBTREE`），整个目录视为一个 skill；若不存在，视为组织性目录，递归进入子目录继续扫描。
- **内置 Skill 保护机制（两遍扫描）**：`ClasspathSkillScanner.scan()` 采用两遍扫描策略——
  1. **第一遍**：扫描 URL 含 `snap-agent-spring-boot` 或 `snap-agent-core` 的资源（即 starter JAR 内置 skill），解析并记录 name 到 `seenNames` 集合。
  2. **第二遍**：扫描其余资源（宿主 classpath），若 name 已在 `seenNames` 中 → 跳过并记录 WARN 日志。
  - 效果：**内置 skill 始终优先于宿主 classpath 同名 skill**。如需覆盖内置 skill，通过上传目录（`upload-skills-dir`）方式，custom 优先级高于 builtin。
  - 场景：宿主项目 `docs/skills/` 下放了 `health-check.md`，但 starter JAR 也有 `health-check.md` → 宿主版本被跳过，使用内置版本。
- 解析后存入 `Map<String, SkillMeta>`（key=name）。
- 扫描失败（目录不存在 / 无文件）→ 日志 WARN，registry 为空，不崩；`GET /skills` 返回空列表。
- **不做文件监听**（无 WatchService 线程，无竞态）。

### 手动刷新
- `POST /skills/refresh` → `SkillRegistry.refresh()` 重新扫描全量 `*.md`，替换内存 map（原子替换引用，读不加锁）。
- 鉴权：需要 `snap-agent.security.required-permission` 配的权限（或管理员角色，由宿主权限体系决定）；默认空 = 已登录即可调用（运维页面用，建议宿主配一个 admin 权限码）。
- 返回 `{ total, available, unavailable, invalid }` 计数。

### 何时需要刷新
- skill 文件被 admin 编辑后（无热重载，Phase 3 才考虑）。
- 新增 / 删除 skill 文件后。

## 5. tools 契约校验与 unavailable 标记（决策 #11）

加载每个 skill 时，`SkillRegistry` 交叉校验 `frontmatter.tools` 与**已装配的 ToolProvider 名单**：

```
SkillMeta.tools = [mysql_query, redis_get]
已装配 ToolProvider 名单 = { mysql_query }   # redis 未配/缺 RedisTemplate
→ redis_get 缺失 → availability = UNAVAILABLE
→ unavailableReason = "tool 'redis_get' not available (redis disabled or no RedisTemplate)"
```

### availability 三态
| 状态 | 含义 | UI 表现 |
|------|------|---------|
| `AVAILABLE` | 所有 tools 已装配，frontmatter 合规 | 正常显示，可 Run |
| `UNAVAILABLE` | frontmatter 合规但缺 tool | 灰显 + tooltip 说明缺哪个工具 |
| `INVALID` | frontmatter 不合规（缺 name/description/tools，或 inputs 不合规） | 灰显 + tooltip 说明校验错误 |

### `GET /skills` 响应示例
```json
{
  "skills": [
    {
      "name": "sep-wh-replenish-diagnose",
      "description": "分仓补货计划生成问题分析...",
      "availability": "AVAILABLE",
      "inputs": [ {"key":"skuCode","label":"件号编码","required":true,"type":"string"}, ... ],
      "tools": ["mysql_query"]
    },
    {
      "name": "foo-diagnose",
      "description": "...",
      "availability": "UNAVAILABLE",
      "unavailableReason": "tool 'redis_get' not available",
      "inputs": [...],
      "tools": ["mysql_query","redis_get"]
    }
  ]
}
```

## 6. 占位符替换（决策 #12）

- `inputs` frontmatter 是**唯一**的参数契约。**不再**从正则启发式提取 `{xxx}` 占位符。
- `AgentExecutor` 在构造 system prompt 前，用用户提交的 `inputs` 值替换正文 `{key}`：
  - 简单字符串替换：`body.replace("{" + key + "}", value)`。
  - 缺失的 required input → POST /runs 在入口即 400，不会进 agent 循环。
  - 未提供的 optional input → 替换为空字符串 `""`（与现有 skill 的 `IF('{warehouseCode}' = '', ...)` SQL 惯用法兼容）。
- 替换后正文注入 system prompt（见 [03-agent-engine.md](03-agent-engine.md) §system prompt 构造）。

## 7. skill 来源 = 两层模型（决策 #13）

skill 来源分为两层：

- **内置（builtin）**：classpath 扫描，只读，打包在 JAR 中，**不可删除**。
- **可上传（custom）**：文件系统扫描，读写，重启后持久化。

### 运行时上传 / 删除接口
- `POST /skills/upload`：上传单个 `.md` 文件或 `.zip`（解压到 `upload-skills-dir`）。
- `POST /skills/upload-folder`：以 `multipart/form-data` 上传整个目录。
- `DELETE /skills/{name}`：删除自定义 skill；**内置 skill 不可删除**。

### 覆盖与恢复
- 自定义 skill 可按 name 覆盖同名内置 skill（custom 优先级高于 builtin）。
- 删除覆盖用的自定义 skill 后，同名内置 skill **自动恢复**。

### 元数据字段
- 每个 skill 的 `SkillMeta` 带 `source` 字段：`"builtin"` 或 `"custom"`。
- 当 custom 覆盖 builtin 时，`overridesBuiltin` 标记为 `true`。

### 部署建议
- `upload-skills-dir: file:/opt/snap-agent-skills/`，目录权限 750，仅应用账号可读写。
- 内置 skill 由 JAR 版本管理，随发版更新。

## 8. 验证

- 拿 `sep-wh-replenish-diagnose` 的迁移后版本，模拟：解析 frontmatter → `tools=[mysql_query]` 与已装配 `{mysql_query}` 交集非空 → `AVAILABLE` → `inputs` 5 项 → UI 渲染表单。
- 构造一个 `tools: [mysql_query, redis_get]` 但 redis 关闭的 skill → 标 `UNAVAILABLE`，reason 指明缺 `redis_get`。
- 构造一个缺 `tools` 字段的 skill → 标 `INVALID`，reason 指明缺 `tools`。
