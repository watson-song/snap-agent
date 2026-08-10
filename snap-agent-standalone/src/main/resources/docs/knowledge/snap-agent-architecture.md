---
name: snap-agent-architecture
description: SnapAgent 核心架构 — 模块划分、12 个 AutoConfiguration、REST 端点、配置总览
version: 2.0.0
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
└── snap-agent-demo                     # E2E 演示
```

## 2. 自动配置类（13 个）

| 配置类 | 启用条件 | 核心 Bean |
|--------|---------|----------|
| `SnapAgentAutoConfiguration` | `snap-agent.enabled=true` | LlmClient, TaskStore, SkillRegistry, AgentService |
| `WebAutoConfiguration` | enabled | SnapAgentController, ConversationStore |
| `ToolAutoConfiguration` | enabled | ToolCallbackRegistry, 内置工具 |
| `SecurityAutoConfiguration` | enabled | SecurityGateway, SqlGuard, AuditStore |
| `BridgeAutoConfiguration` | `@ConditionalOnExpression(enabled AND bridge.enabled)` | IssueBridgeService, BridgeHttpExecutor |
| `LlmBridgeAutoConfiguration` | `snap-agent.llm.api-type=bridge` | LlmBridgeService, BridgeLlmClient |
| `FileBridgeAutoConfiguration` | enabled | FileBridgeService, FileBridgeController |
| `CostAutoConfiguration` | `cost.enabled=true` | CostStore, CostTracker, CostCalculator, BudgetEnforcer |
| `KnowledgeAutoConfiguration` | enabled | VectorStore, DocumentRetriever, KnowledgeETLPipeline |
| `DomainKnowledgeAutoConfiguration` | enabled | DomainKnowledgeIndex, DomainKnowledgeLoader |
| `IssueAutoConfiguration` | enabled | IssueStore, IssueTracker, VcsClient, FixExecutionService |
| `PatrolAutoConfiguration` | `patrol.enabled=true` | PatrolScheduler, AlertConverger, AlertPushChannel |
| `WorkflowAutoConfiguration` | `snap-agent.enabled=true`（内部 bean 按 `workflows.enabled`）| WorkflowEngine, WorkflowDefinition |

## 3. Agent 引擎

```
SnapAgentController → AgentService.execute(task, skill)
    → ReActGraphFactory.build(skill, task, advisors)
        → EntryNode → AdvisorNode(AgentNode) ⇄ AdvisorNode(ToolsNode) → END
    → GraphExecutor.execute(compiledGraph, initialState)
```

## 4. REST 端点

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
  enabled: true
  base-path: /snap-agent
  llm:
    api-type: anthropic     # anthropic | openai | bridge
    base-url: ...
    auth-token: ...
    model: claude-sonnet-4-20250514
  agent:
    max-turns: 20
    task-timeout-minutes: 30
  knowledge:
    enabled: true
    sources: [{type: markdown, dir: classpath:/docs/knowledge/}]
  code-graph:
    enabled: false
  cost:
    enabled: false
  patrol:
    enabled: false
  bridge:
    enabled: false
```
