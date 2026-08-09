---
name: snap-agent-agent-engine
description: Agent 引擎详解 — AgentService、ReAct Graph 执行流程、任务状态管理
version: 1.0.1
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Agent 引擎

## 1. 架构概述

```
┌─────────────────────────────────────────────────────────┐
│                    用户请求                               │
│                  POST /snap-agent/runs                   │
└────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────
│                  SnapAgentController                    │
│  - 验证 skillId、inputs                                 │
│  - 创建 AgentTask                                       │
│  - 异步提交到线程池                                      │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                    AgentService                          │
│                                                         │
│  execute(task, skill) {                                 │
│    1. 验证 LlmClient 可用性                              │
│    2. 更新任务状态 → RUNNING                            │
│    3. 构建 ReAct Graph                                  │
│    4. 执行 Graph 循环                                    │
│    5. 更新任务状态 → SUCCEEDED/FAILED                   │
│  }                                                       │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│                ReActGraphFactory.build()                 │
│                                                         │
│  输入：SkillMeta + AgentTask + List<Advisor>            │
│  输出：CompiledGraph                                    │
│                                                         │
│  节点：entry → agent ⇄ tools → END                     │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
─────────────────────────────────────────────────────────┐
│                  GraphExecutor                           │
│                                                         │
│  - 管理 GraphState                                       │
│  - 驱动节点执行循环                                       │
│  - 处理 Checkpoint（可恢复）                             │
│  - 最大轮次控制（maxTurns）                              │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心代码

### 2.1 AgentService

```java
package cn.watsontech.snapagent.boot2x.agent;

public class AgentService {
    private final LlmClient llmClient;
    private final ToolCallbackRegistry tools;
    private final TaskStore taskStore;
    private final int maxTurns;
    private final List<Advisor> advisors;
    private final GraphExecutor graphExecutor;

    public AgentService(LlmClient llmClient, ToolCallbackRegistry tools,
                        TaskStore taskStore, int maxTurns, List<Advisor> advisors) {
        this.llmClient = llmClient;
        this.tools = tools;
        this.taskStore = taskStore;
        this.maxTurns = maxTurns;
        this.advisors = advisors != null ? advisors : Collections.emptyList();
        this.graphExecutor = new GraphExecutor(new InMemoryCheckpointStore(), maxTurns);
    }

    public void execute(AgentTask task, SkillMeta skill) {
        log.info("AgentService executing task {} with skill '{}'", 
                 task.getTaskId(), skill.getName());

        if (llmClient == null) {
            log.error("LlmClient not available");
            task.setStatus(TaskStatus.FAILED);
            task.setReport("LlmClient not configured");
            taskStore.update(task);
            return;
        }

        task.setStatus(TaskStatus.RUNNING);
        taskStore.update(task);

        try {
            CompiledGraph graph = new ReActGraphFactory().build(skill, task, advisors);
            TaskResult result = graphExecutor.execute(graph, 
                initialGraphState(task, skill));
            
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport(result.getFinalReport());
        } catch (Exception e) {
            task.setStatus(TaskStatus.FAILED);
            task.setReport("Execution error: " + e.getMessage());
        } finally {
            taskStore.update(task);
        }
    }
}
```

### 2.2 ReActGraphFactory

```java
package cn.watsontech.snapagent.core.graph.react;

public class ReActGraphFactory {
    public CompiledGraph build(SkillMeta skill, AgentTask task, List<Advisor> advisors) {
        if (skill.getAvailability() != SkillAvailability.AVAILABLE) {
            throw new SkillUnavailableException(skill.getName(), 
                skill.getAvailability(), skill.getUnavailableReason());
        }

        Map<String, Object> inputs = (Map<String, Object>) (Map<?, ?>) task.getInputs();

        Node entryNode = new EntryNode(skill, inputs);
        Node agentNode = new AgentNode(skill, task);
        Node toolsNode = new ToolsNode(4000);
        ShouldContinue shouldContinue = new ShouldContinue();

        Node wrappedEntry = new AdvisorNode(entryNode, advisors);
        Node wrappedAgent = new AdvisorNode(agentNode, advisors);
        Node wrappedTools = new AdvisorNode(toolsNode, advisors);

        Node endNode = new Node() {
            @Override public String getName() { return "END"; }
            @Override public GraphState execute(GraphState s, ExecutionContext ctx) { return s; }
        };

        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");

        StateGraph g = new StateGraph();
        g.addNode("entry", wrappedEntry)
         .addNode("agent", wrappedAgent)
         .addNode("tools", wrappedTools)
         .addNode("END", endNode)
         .addEdge("entry", "agent")
         .addConditionalEdges("agent", shouldContinue, routing)
         .addEdge("tools", "agent")
         .setEntryPoint("entry");

        return g.compile();
    }
}
```

## 3. 节点流程

```
entry → agent ⇄ tools → END
  │       │      │
  │       ──────┘ (循环直到 ShouldContinue 返回 "end")
  │
  └→ 初始化 GraphState（skill、inputs、conversationId）
```

## 4. 配置

```yaml
snap-agent:
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 3
    max-runs-per-hour: 20
```
