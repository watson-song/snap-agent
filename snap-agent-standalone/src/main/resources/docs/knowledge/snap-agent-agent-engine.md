---
name: snap-agent-agent-engine
description: Agent 引擎详解 — AgentService、ReAct Graph 执行流程、任务状态管理
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Agent 引擎

## 1. 架构概述

Agent 引擎是 SnapAgent 的核心执行引擎，负责将 Skill 定义转化为可执行的 ReAct 图并驱动执行。

```
┌─────────────────────────────────────────────────────────┐
│                    用户请求                               │
│                  POST /snap-agent/runs                   │
└────────────────────────┬────────────────────────────────┘
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
┌─────────────────────────────────────────────────────────┐
│                  GraphExecutor                           │
│                                                         │
│  - 管理 GraphState                                       │
│  - 驱动节点执行循环                                       │
│  - 处理 Checkpoint（可恢复）                             │
│  - 最大轮次控制（maxTurns）                              │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心组件

### 2.1 AgentService

```java
public class AgentService {
    private final LlmClient llmClient;
    private final ToolCallbackRegistry tools;
    private final TaskStore taskStore;
    private final int maxTurns;
    private final List<Advisor> advisors;
    private final GraphExecutor graphExecutor;

    public void execute(AgentTask task, SkillMeta skill) {
        // 1. 验证 LlmClient
        if (llmClient == null) {
            task.setStatus(TaskStatus.FAILED);
            task.setReport("LlmClient not configured");
            return;
        }

        // 2. 更新状态
        task.setStatus(TaskStatus.RUNNING);
        taskStore.update(task);

        try {
            // 3. 构建 ReAct Graph
            CompiledGraph graph = new ReActGraphFactory().build(skill, task, advisors);

            // 4. 执行 Graph
            TaskResult result = graphExecutor.execute(graph, initialGraphState(task, skill));

            // 5. 更新结果
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

**职责**：
- 任务生命周期管理
- Graph 构建和执行协调
- 状态持久化（通过 TaskStore）

### 2.2 ReActGraphFactory

```java
public class ReActGraphFactory {
    public CompiledGraph build(SkillMeta skill, AgentTask task, List<Advisor> advisors) {
        // 1. 验证 Skill 可用性
        if (skill.getAvailability() != SkillAvailability.AVAILABLE) {
            throw new SkillUnavailableException(skill.getName(), ...);
        }

        // 2. 创建核心节点
        EntryNode entryNode = new EntryNode(skill, inputs);
        AgentNode agentNode = new AgentNode(skill, task);
        ToolsNode toolsNode = new ToolsNode(4000);  // max tool result chars
        ShouldContinue shouldContinue = new ShouldContinue();

        // 3. 包装 AdvisorNode
        Node wrappedEntry = new AdvisorNode(entryNode, advisors);
        Node wrappedAgent = new AdvisorNode(agentNode, advisors);
        Node wrappedTools = new AdvisorNode(toolsNode, advisors);

        // 4. 构建图结构
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

**节点流程**：
```
entry → agent ⇄ tools → END
  │       │      │
  │       └──────┘ (循环直到 ShouldContinue 返回 "end")
  │
  └→ 初始化 GraphState（skill、inputs、conversationId）
```

### 2.3 GraphExecutor

```java
public class GraphExecutor {
    private final CheckpointStore checkpointStore;
    private final int maxTurns;

    public TaskResult execute(CompiledGraph graph, GraphState initialState) {
        GraphState state = initialState;
        String currentNode = graph.getEntryPoint();
        int turn = 0;

        while (currentNode != null && turn < maxTurns) {
            // 1. 保存 Checkpoint
            checkpointStore.save(taskId, turn, state);

            // 2. 执行当前节点
            Node node = graph.getNode(currentNode);
            state = node.execute(state, executionContext);

            // 3. 确定下一个节点
            if (node instanceof ConditionalNode) {
                currentNode = ((ConditionalNode) node).getNextNode(state);
            } else {
                currentNode = graph.getNextNode(currentNode);
            }

            turn++;
        }

        return new TaskResult(state, turn);
    }
}
```

## 3. 执行流程详解

### 3.1 完整执行序列

```
T=0: entryNode.beforeNode()
     ├─ 验证 skill 可用性
     ├─ 解析 inputs
     └─ 初始化 GraphState

T=1: MessageChatMemoryAdvisor.beforeNode("entry")
     └─ 加载对话历史 → state["memory.messages"]

T=2: entryNode.execute()
     ─ 注入 skill body 到 system prompt

T=3: agentNode.beforeNode()
     └─ Advisors 注入上下文

T=4: agentNode.execute()
     ├─ 组装 LLM 请求（system + history + tools）
     ├─ 调用 LlmClient.stream()
     ├─ 解析 tool_calls
     └─ 更新 GraphState

T=5: MessageChatMemoryAdvisor.afterNode("agent")
     └─ 持久化 assistant 消息

T=6: ShouldContinue.evaluate()
     ├─ 有 tool_calls? → "tools"
     └─ 无 tool_calls? → "end"

T=7: toolsNode.execute()
     ├─ 遍历 tool_calls
     ├─ 调用 ToolCallbackRegistry.find(name)
     ├─ 执行 tool.execute(input)
     └─ 收集 tool_results

T=8: MessageChatMemoryAdvisor.afterNode("tools")
     └─ 持久化 tool_result 消息

T=9: 返回 T=3 继续循环...

T=N: ShouldContinue → "end"
     └─ agentNode 生成最终回复
     └─ 退出循环
```

### 3.2 ShouldContinue 决策逻辑

```java
public class ShouldContinue {
    public String getNextNode(GraphState state) {
        List<ToolCall> toolCalls = state.get("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            return "tools";  // 需要调用工具
        }
        return "end";  // 直接结束
    }
}
```

## 4. 任务状态管理

### 4.1 TaskStatus 状态机

```
PENDING → RUNNING → SUCCEEDED
                  → FAILED
                  → TIMEOUT
                  → CANCELLED
RUNNING → PAUSED → RUNNING (恢复)
```

### 4.2 TaskStore

```java
public class TaskStore {
    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<>();

    public void save(AgentTask task) { ... }
    public AgentTask get(String taskId) { ... }
    public List<AgentTask> query(userId, skillId, status, limit, offset) { ... }
}
```

**特性**：
- 内存存储（ConcurrentHashMap）
- 线程安全
- 支持分页查询

## 5. 配置属性

```yaml
snap-agent:
  agent:
    max-turns: 20              # 最大 ReAct 轮次
    task-timeout-minutes: 30   # 任务超时
    max-concurrent-runs-per-user: 3  # 用户并发限制
    max-runs-per-hour: 20      # 用户每小时限制
```

## 6. 性能指标

| 指标 | 数值 | 说明 |
|------|------|------|
| Graph 构建时间 | <10ms | 单次 build |
| 节点执行延迟 | 100-500ms | 取决于 LLM 响应 |
| 平均任务耗时 | 5-30s | 包含多轮工具调用 |
| 最大并发任务 | 50+ | 线程池配置决定 |

## 7. 故障处理

### 7.1 LlmClient 不可用
```
症状：任务立即 FAILED
原因：未配置 api-type 或 api-key
解决：检查 application.yml 配置
```

### 7.2 工具调用失败
```
症状：tools 节点抛出异常
原因：工具未注册或执行错误
解决：检查 ToolCallbackRegistry 注册
```

### 7.3 超过最大轮次
```
症状：GraphExecutor 强制退出
原因：ReAct 循环超过 max-turns
解决：调整 max-turns 配置或优化 Skill
```
