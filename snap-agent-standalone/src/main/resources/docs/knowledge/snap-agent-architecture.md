---
name: snap-agent-architecture
description: SnapAgent 核心架构 — 模块划分、13 个 AutoConfiguration、Agent 引擎流程、REST 端点、配置总览
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 核心架构

## 1. 模块划分

```
snap-agent-parent
├── snap-agent-core                     # 核心 SPI + 领域模型（无 Spring 依赖）
├── snap-agent-spring-boot-2x-starter   # Spring Boot 2.x 自动装配 + Web 端点
├── snap-agent-standalone               # 独立部署 JAR（含 Settings Page + Bridge UI）
├── snap-agent-demo                     # E2E 演示
├── snap-agent-client                   # REST API SDK（无 Spring 依赖）
├── snap-agent-demo-plugin              # 插件开发示例
└── snap-agent-plugin-archetype         # 插件 Maven archetype
```

## 2. 自动配置类（13 个）

| # | 配置类 | 顶层条件 | @Bean 数 |
|---|--------|---------|---------|
| 1 | `SnapAgentAutoConfiguration` | `snap-agent.enabled=true` | 11 |
| 2 | `WebAutoConfiguration` | enabled | 5 |
| 3 | `ToolAutoConfiguration` | enabled | 18 |
| 4 | `SecurityAutoConfiguration` | enabled | 6 |
| 5 | `BridgeAutoConfiguration` | `@ConditionalOnExpression(enabled AND bridge.enabled)` | 5 |
| 6 | `LlmBridgeAutoConfiguration` | `snap-agent.llm.api-type=bridge` | 3 |
| 7 | `FileBridgeAutoConfiguration` | `snap-agent.bridge.enabled=true` | 2 |
| 8 | `CostAutoConfiguration` | `snap-agent.cost.enabled=true` | 5 |
| 9 | `KnowledgeAutoConfiguration` | enabled | 8 |
| 10 | `DomainKnowledgeAutoConfiguration` | enabled | 3 |
| 11 | `IssueAutoConfiguration` | enabled | 17 |
| 12 | `PatrolAutoConfiguration` | enabled | 9 |
| 13 | `WorkflowAutoConfiguration` | enabled | 3 |

<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/WebAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/ToolAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SecurityAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/BridgeAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/LlmBridgeAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/FileBridgeAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/CostAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/KnowledgeAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/DomainKnowledgeAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/IssueAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/PatrolAutoConfiguration.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/WorkflowAutoConfiguration.java -->

## 3. Agent 引擎流程

```
SnapAgentController → AgentService.execute(task, skill)
    → ReActGraphFactory.build(skill, task, advisors)
        → EntryNode → AdvisorNode(AgentNode) ⇄ AdvisorNode(ToolsNode) → END
    → GraphExecutor.execute(compiledGraph, initialState, ctx)
        → checkpoint each node → InterruptException → PAUSED
```

## 4. REST 端点表

| 端点 | 说明 |
|------|------|
| `POST /runs` | 创建 Agent 任务 |
| `GET /runs/{id}` | 查询任务状态 |
| `GET /runs/{id}/stream` | SSE 流式输出 |
| `GET /runs/{id}/transcript` | 完整对话记录 |
| `GET /skills` | Skill 列表 |
| `POST /skills` | 上传 Skill |
| `DELETE /skills/{name}` | 删除 Skill |
| `GET /tools` | 工具列表 |
| `GET /models` | 模型列表 |
| `GET /conversations` | 会话列表 |
| `GET /cost/summary` | 成本汇总 |
| `GET /workflows` | 工作流列表 |
| `POST /workflows/{name}/run` | 执行工作流 |
| `GET /info` | 服务信息 |
| `GET /bridge/*` | Bridge 端点 |

## 5. 配置总览

```yaml
snap-agent:
  enabled: true                    # 总开关
  base-path: /snap-agent
  llm:
    api-type: anthropic            # anthropic | openai | bridge
    base-url: https://api.anthropic.com
    auth-token: ${TOKEN}
    model: claude-sonnet-4-20250514
  agent:
    max-turns: 20
    task-timeout-minutes: 30
  memory:
    repository-type: in-memory     # in-memory | file
    max-messages: 50
  cost:
    enabled: false
  patrol:
    enabled: false
  bridge:
    enabled: false
  workflows:
    enabled: false
```
