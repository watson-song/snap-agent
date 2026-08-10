---
name: snap-agent-graph-engine
description: Graph 执行引擎 — StateGraph、CompiledGraph、GraphExecutor、ReAct 节点链、Checkpoint
version: 2.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Graph 执行引擎

## 1. 架构概述

Graph 引擎是 2.x 的执行核心，替代了 1.x 的线性 `AgentExecutor`。

```
StateGraph (定义) → CompiledGraph (编译) → GraphExecutor (执行)
                         ↓
              EntryNode → AgentNode ⇄ ToolsNode → END
```

## 2. 核心 SPI

### 2.1 Node 接口

```java
// core/graph/Node.java
public interface Node {
    String getName();
    GraphState execute(GraphState state, ExecutionContext ctx);
}
```

### 2.2 GraphState（不可变状态容器）

```java
// core/graph/GraphState.java
public class GraphState {
    private final Map<String, Object> data;
    public GraphState with(String key, Object value) { ... }  // 返回新实例
    public <T> T get(String key) { ... }
}
```

`StateKey<T>` / `StateKeys` 提供 type-safe 的 key 定义。

### 2.3 StateGraph + CompiledGraph

```java
// core/graph/StateGraph.java
StateGraph g = new StateGraph();
g.addNode("entry", entryNode)
 .addNode("agent", agentNode)
 .addNode("tools", toolsNode)
 .addNode("END", endNode)
 .addEdge("entry", "agent")
 .addConditionalEdges("agent", shouldContinue, Map.of("continue","tools","end","END"))
 .addEdge("tools", "agent")
 .setEntryPoint("entry");
CompiledGraph compiled = g.compile();  // 拓扑排序 + 环路检测
```

### 2.4 GraphExecutor

```java
// core/graph/execution/GraphExecutor.java
public class GraphExecutor {
    private final CheckpointStore checkpointStore;
    private final int maxTurns;
    public TaskResult execute(CompiledGraph graph, GraphState initialState) { ... }
}
```

支持 interrupt/fail/cancel/timeout 四种异常处理模式。

### 2.5 Edge 类型

| 类 | 说明 |
|---|------|
| `Edge` | 固定边 A→B |
| `ConditionalEdge` | 条件边，根据 state 路由 |
| `EdgeCondition` | 决策函数 |
| `EdgeTarget` | 目标节点 |

## 3. ReAct 节点链

| 节点 | 类 | 职责 |
|------|-----|------|
| Entry | `EntryNode` | 初始化 GraphState，构建 system prompt，注入防御 |
| Agent | `AgentNode` | 调用 LlmClient.stream()，解析 tool_calls |
| Tools | `ToolsNode` | 执行工具调用，截断结果，错误反馈 |
| Route | `ShouldContinue` | 有 tool_calls→"tools"，否则→"end" |
| END | 匿名 Node | 空操作终止 |

所有节点通过 `AdvisorNode` 包装，注入 Advisor 链。

## 4. Checkpoint 机制

```java
// core/graph/checkpoint/CheckpointStore.java
public interface CheckpointStore {
    void save(String taskId, int turn, GraphState state);
    GraphState load(String taskId, int turn);
    void delete(String taskId);
    List<Integer> listTurns(String taskId);
}
```

| 实现 | 说明 |
|------|------|
| `InMemoryCheckpointStore` | 内存，默认 |
| `SqliteCheckpointStore` | SQLite 文件，开发环境 |
| `CheckpointMetadata` | 检查点元数据 |

## 5. HITL（人工审批）

```java
// core/graph/hitl/ToolApproval.java — 注解
@ToolApproval(required = true)  // 标记工具需人工确认

// core/graph/hitl/InterruptException.java — 中断执行
public class InterruptException extends Exception {
    private final String reason;
    private final Map<String, Object> context;
}
```

## 6. 异常类

| 类 | 说明 |
|---|------|
| `IllegalGraphException` | 图编译失败（环路、孤立节点等）|
| `WorkflowCompileException` | 工作流编译异常 |
| `CheckpointNotFoundException` | 检查点不存在 |
