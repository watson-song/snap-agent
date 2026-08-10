---
name: snap-agent-agent-engine
description: Agent 引擎 — AgentService、ReActGraphFactory、SimpleExecutionContext、任务状态
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Agent 引擎

## 1. 架构

```
SnapAgentController
  → AgentService.execute(task, skill)
      → ReActGraphFactory.build(skill, task, advisors) → CompiledGraph
      → GraphExecutor.execute(graph, initialState)
```

## 2. 核心组件 (core/agent/)

| 类 | 职责 |
|----|------|
| `AgentTask` | 任务数据（taskId, skillId, inputs, status, report）|
| `TaskStatus` | 状态机: PENDING → RUNNING → SUCCEEDED / FAILED / CANCELLED |
| `TaskStore` | 任务持久化 SPI |
| `TranscriptEvent` | 流式事件（thought/tool_use/tool_result/response/done/error）|
| `AuditRecord` | 审计记录 |
| `RateLimiter` | 限流 |
| `ReportGenerator` | 报告生成 |

## 3. Starter 组件 (boot2x/agent/)

| 类 | 职责 |
|----|------|
| `AgentService` | 编排执行：验证 LlmClient → 构建 Graph → 执行 → 更新状态 |
| `SimpleExecutionContext` | 默认 ExecutionContext 实现，转发 transcript events |

## 4. ReActGraphFactory

```
输入: SkillMeta + AgentTask + List<Advisor>
输出: CompiledGraph

节点:
  entry  → AdvisorNode(EntryNode)     — 初始化 state, 构建 system prompt
  agent  → AdvisorNode(AgentNode)     — 调 LLM, 解析 tool_calls
  tools  → AdvisorNode(ToolsNode)     — 执行工具, 截断结果
  END    → 匿名空节点

边:
  entry → agent
  agent → (shouldContinue) → tools | END
  tools → agent (循环)
```

## 5. 配置

```yaml
snap-agent:
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 3
```
