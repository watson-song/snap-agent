---
name: snap-agent-skill-system
description: Skill 系统 — 加载、解析、校验、热重载、两层模型、ClasspathSkillScanner
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# Skill 系统

## 1. Skill 定义格式

Markdown + YAML frontmatter:

```yaml
---
name: database-query
description: Queries the application database
version: 1.0.0
tools: [mysql_query]
inputs:
  - key: message
    label: 用户问题
    required: true
    type: text
triggers: [查询数据库, SQL 查询]
author: SnapAgent
---
```

## 2. 核心 SPI (core/skill/)

| 类 | 职责 |
|----|------|
| `SkillMeta` | 不可变元数据（name/description/tools/inputs/body/availability/source）|
| `SkillLoader` | 解析 Markdown frontmatter → SkillMeta |
| `SkillRegistry` | 两层合并：builtin + custom，custom 覆盖同名 builtin |
| `SkillAvailability` | AVAILABLE / UNAVAILABLE |
| `SkillMode` | Skill 模式 |
| `InputSpec` | 输入参数规格 |
| `Shortcut` | 快捷方式 |
| `SkillUnavailableException` | Skill 不可用异常 |

## 3. 两层模型

```
builtin skills (classpath:/docs/skills/)   +   custom skills (upload-skills-dir/)
    └── ClasspathSkillScanner                    └── 文件系统扫描
                    ↓                                        ↓
                    └────────── SkillRegistry.all() ──────────┘
                              custom 覆盖同名 builtin
```

## 4. 加载与热重载

| 类 | 模块 | 说明 |
|----|------|------|
| `ClasspathSkillScanner` | boot2x/skill | 启动扫描 classpath，JAR 内同名 skill 优先 |
| `SkillHotReloader` | boot2x/skill | WatchService 监听 upload 目录，2s poll，原子替换 |

## 5. 工具契约校验

加载时校验 `skill.tools` 声明的工具是否在 `ToolCallbackRegistry` 中注册。
不满足 → `SkillAvailability.UNAVAILABLE` + 记录原因。

## 6. 内置 Skills 列表

| Skill | 工具依赖 | 说明 |
|-------|----------|------|
| `health-check` | mysql_query | 数据库健康检查 |
| `database-query` | mysql_query | 通用数据库查询 |
| `redis-query` | redis_get | Redis 数据查询 |
| `log-analysis` | log_read | 日志分析 |
| `slow-query-analysis` | log_read, mysql_query | 慢查询分析 |
| `error-spike-investigation` | metrics_query, log_search | 错误尖峰调查 |
| `code-analysis` | project_structure, code_read | 代码分析 |
| `create-issue` | 无 | Issue 创建 |
| `solution-suggest` | 无 | 解决方案建议 |
| `verify-fix` | 无 | 修复验证 |
| `trend-prediction` | metrics_query | 趋势预测 |
| `health-patrol` | metrics_query | 健康巡检 |
| `ops-health-check` | metrics_query, log_search | 运维健康检查 |
| `config-diff` | config_read, metrics_query | 配置对比 |

> `domain-knowledge-discovery` 和 `technical-architecture-discovery` 不是内置 skill，而是集成阶段工具，位于 `docs/skills/`。
