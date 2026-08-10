---
name: snap-agent-graph-engine
description: Graph 执行引擎 — Node、StateGraph、CompiledGraph、GraphExecutor、ReAct 节点链、Checkpoint、HITL
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
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

### 2.1 Node 接口（2 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/Node.java -->
```java
public interface Node {
    default String getName() {
        return getClass().getSimpleName();
    }
    GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException;
}
```

### 2.2 GraphState（不可变状态容器，14 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/GraphState.java -->
```java
public class GraphState {
    public static GraphState empty(String threadId)
    public <T> T get(String key)
    public <T> T get(String key, T defaultValue)
    public <T> T get(StateKey<T> key)
    public <T> T get(StateKey<T> key, T defaultValue)
    public GraphState with(String key, Object value)
    public <T> GraphState with(StateKey<T> key, T value)
    public GraphState nextTurn()
    public int getTurn()
    public String getThreadId()
    public String getCheckpointId()
    public GraphState withCheckpointId(String checkpointId)
    public byte[] serialize()
    public static GraphState deserialize(byte[] data)
}
```

不可变类，`with()` / `nextTurn()` / `withCheckpointId()` 均返回新实例。支持 JSON 序列化。

### 2.3 StateGraph（Builder，6 public 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/StateGraph.java -->
```java
public class StateGraph {
    public StateGraph addNode(String name, Node node)
    public StateGraph addEdge(String from, String to)
    public StateGraph addConditionalEdges(String from, EdgeCondition condition, Map<String, String> routing)
    public StateGraph setEntryPoint(String entry)
    public CompiledGraph compile()        // 允许环路（ReAct）
    public CompiledGraph compileDag()     // 拒绝环路（Workflow）
}
```

### 2.4 CompiledGraph（3 public 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/CompiledGraph.java -->
```java
public class CompiledGraph {
    public String getEntryPoint()
    public Map<String, Node> getNodes()
    public List<EdgeTarget> getEdgesFrom(String node)
}
```

### 2.5 GraphExecutor（2 public 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/execution/GraphExecutor.java -->
```java
public class GraphExecutor {
    public GraphExecutor(CheckpointStore checkpointStore, int maxTurns)
    public TaskResult execute(CompiledGraph graph, GraphState state, ExecutionContext ctx)
    public TaskResult resume(CompiledGraph graph, String checkpointId, ExecutionContext ctx)
}
```

执行循环：检查 cancel → 检查 maxTurns → 执行 node → checkpoint → 路由下一节点。
InterruptException → 保存 checkpoint + 返回 PAUSED。

## 3. Edge 类型

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/Edge.java -->
```java
public class Edge {
    public Edge(String from, String to)
    public String getFrom()
    public String getTo()
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/ConditionalEdge.java -->
```java
public class ConditionalEdge {
    public ConditionalEdge(String from, EdgeCondition condition, Map<String, String> routing)
    public String getFrom()
    public EdgeCondition getCondition()
    public Map<String, String> getRouting()
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/EdgeCondition.java -->
```java
@FunctionalInterface
public interface EdgeCondition {
    String route(GraphState state);
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/EdgeTarget.java -->
```java
public class EdgeTarget {
    public EdgeTarget(String nodeName)
    public EdgeTarget(String nodeName, String label)
    public EdgeTarget(String nodeName, String label, EdgeCondition condition)
    public String getNodeName()
    public String getLabel()
    public EdgeCondition getCondition()
}
```

## 4. ReAct 节点链

| 节点 | 类 | 职责 |
|------|-----|------|
| Entry | `EntryNode` | 构建 system prompt + user message |
| Agent | `AgentNode` | LLM 流式调用，解析 tool_use blocks |
| Tools | `ToolsNode` | 执行工具调用，截断结果，错误反馈 |
| Route | `ShouldContinue` | end_turn→end, tool_use→tools, max_tokens→agent |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/EntryNode.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/AgentNode.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/ToolsNode.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/react/ShouldContinue.java -->

所有节点通过 `AdvisorNode` 装饰器包装，注入 Advisor 链。

## 5. Checkpoint 机制

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/checkpoint/CheckpointStore.java -->
```java
public interface CheckpointStore {
    String save(String threadId, GraphState state);
    GraphState load(String checkpointId);
    List<CheckpointMetadata> list(String threadId);
    void delete(String checkpointId);
    void deleteByThread(String threadId);
}
```

| 实现 | 模块 | 说明 |
|------|------|------|
| `InMemoryCheckpointStore` | core | ConcurrentHashMap 内存存储 |
| `SqliteCheckpointStore` | boot2x | SQLite 嵌入式文件存储 |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/checkpoint/InMemoryCheckpointStore.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/graph/checkpoint/SqliteCheckpointStore.java -->

## 6. HITL（人工审批）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/hitl/InterruptException.java -->
```java
public class InterruptException extends Exception {
    public InterruptException(Map<String, Object> checkpointPayload)
    public InterruptException(String message, Map<String, Object> checkpointPayload)
    public Map<String, Object> getCheckpointPayload()
}
```

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/hitl/ToolApproval.java -->
```java
@Retention(RUNTIME) @Target(METHOD)
public @interface ToolApproval {
    boolean required() default false;
    String prompt() default "";
}
```

## 7. 异常类

| 类 | 说明 |
|---|------|
| `IllegalGraphException` | 图编译失败（环路、孤立节点等）|
| `WorkflowCompileException` | 工作流编译异常 |
| `CheckpointNotFoundException` | 检查点不存在 |
