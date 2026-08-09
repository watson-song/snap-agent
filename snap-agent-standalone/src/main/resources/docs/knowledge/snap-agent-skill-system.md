---
name: snap-agent-skill-system
description: Skill 系统详解 — 加载、解析、校验、热重载、两层模型
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# Skill 系统详解

## 1. Skill 定义格式

Skill 使用 Markdown + YAML frontmatter 格式：

```markdown
---
name: database-query
description: Queries the application database with read-only SQL
version: 1.0.0
tools:
  - mysql_query
inputs:
  - key: message
    label: 用户问题
    required: true
    type: text
triggers:
  - 查询数据库
  - 查数据
  - SQL 查询
author: SnapAgent
---

# Database Query

## Step 1: Understand the Question
Read the user's message carefully...

## Step 2: Discover the Schema
Use `mysql_query` to list tables...
```

## 2. Skill 加载流程

```
ClasspathSkillScanner (启动时扫描一次)
  └── scan("classpath:/docs/skills/")
        └── SkillLoader.parse(file)
              └── SnakeYAML SafeConstructor → SkillMeta

SkillHotReloader (可选，监听文件系统)
  └── WatchService HIGH sensitivity (2s poll)
        ── refresh() → 原子替换 volatile holder
```

## 3. 两层合并模型

```
builtin skills (classpath)    custom skills (upload dir)
        │                              │
        └──────────┬───────────────────┘
                   ▼
            SkillRegistry.all()
                   │
                   ▼
        custom 覆盖同名 builtin
```

**覆盖规则**：
- 同名 skill：custom 优先
- 删除 custom：自动恢复 builtin
- `overridesBuiltin: true` 标记覆盖关系

## 4. 工具契约校验

```java
// 加载时校验
for (String toolName : skill.getTools()) {
    if (!toolRegistry.hasTool(toolName)) {
        skill.setAvailability(UNAVAILABLE);
        skill.setUnavailableReason("tool not available: " + toolName);
    }
}
```

**校验时机**：
- Skill 加载时
- ToolRegistry 变更时（热更新）

## 5. 内置 Skills 列表

| Skill | 工具依赖 | 说明 |
|-------|----------|------|
| `health-check` | mysql_query | 数据库健康检查 |
| `database-query` | mysql_query | 通用数据库查询 |
| `redis-query` | redis_get | Redis 数据查询 |
| `log-analysis` | log_read | 日志分析 |
| `slow-query-analysis` | log_read, mysql_query | 慢查询分析 |
| `error-spike-investigation` | metrics_query, log_search | 错误尖峰调查 |
| `code-analysis` | project_structure, code_read | 代码分析 |
| `domain-knowledge-discovery` | project_structure, read_code | 领域知识发现 |
| `create-issue` | 无 | Issue 创建（纯文本） |
| `solution-suggest` | 无 | 解决方案建议 |
| `verify-fix` | 无 | 修复验证 |
| `trend-prediction` | metrics_query | 趋势预测 |
| `health-patrol` | metrics_query | 健康巡检 |
| `ops-health-check` | metrics_query, log_search | 运维健康检查 |
| `config-diff` | config_read, metrics_query | 配置对比 |

## 6. Skill 上传 API

```
POST /snap-agent/skills
Content-Type: multipart/form-data

file: skill.md
```

**上传目录**：`snap-agent.upload-skills-dir` 配置（默认 `./skills`）

**热重载**：上传后自动触发 `SkillHotReloader.refresh()`
