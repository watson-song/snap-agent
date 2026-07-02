# 02 — Skill 加载

## 1. 标准 skill 格式定义

一份 skill = 一个 `*.md` 文件，结构如下：

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
- `SnapAgentAutoConfiguration` 装配 `SkillRegistry` 时，构造阶段同步扫描 `skills-dir`（`PathMatchingResourcePatternResolver`，支持 `classpath:` 与 `file:` 前缀）。
- 扫描 `*.md`，逐个解析，存入 `Map<String, SkillMeta>`（key=name）。
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

## 7. skill 来源 = 服务端目录（决策 #13）

- 仅从 `skills-dir` 加载（admin 控制的文件系统/classpath 目录）。
- **无运行时上传**接口。「恶意 skill」= 文件被篡改，属运维问题，不在本库威胁模型内。
- 部署建议：`skills-dir: file:/opt/skills/`，目录权限 750，仅应用账号可读，admin 通过部署流水线写入。

## 8. 验证

- 拿 `sep-wh-replenish-diagnose` 的迁移后版本，模拟：解析 frontmatter → `tools=[mysql_query]` 与已装配 `{mysql_query}` 交集非空 → `AVAILABLE` → `inputs` 5 项 → UI 渲染表单。
- 构造一个 `tools: [mysql_query, redis_get]` 但 redis 关闭的 skill → 标 `UNAVAILABLE`，reason 指明缺 `redis_get`。
- 构造一个缺 `tools` 字段的 skill → 标 `INVALID`，reason 指明缺 `tools`。
