---
name: snap-agent-graph-framework
description: Graph 框架详解 — StateGraph、CompiledGraph、GraphExecutor、Checkpoint 机制
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Graph 框架

## 1. 架构概述

Graph 框架是 SnapAgent 的执行引擎基础，提供有向图定义、状态管理和节点执行能力。

```
┌─────────────────────────────────────────────────────────┐
│                   Graph 层次结构                          │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │              StateGraph (定义层)                   │   │
│  │  - 定义节点（Node）                                │   │
│  │  - 定义边（Edge）                                  │   │
│  │  - 定义入口（Entry Point）                         │   │
│  └─────────────────────────────────────────────────┘   │
│                         │                                │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐   │
│  │            CompiledGraph (编译层)                  │   │
│  │  - 拓扑排序                                      │   │
│  │  - 环路检测                                      │   │
│  │  - 优化执行路径                                  │   │
│  └─────────────────────────────────────────────────   │
│                         │                                │
│                         ▼                                │
│  ┌─────────────────────────────────────────────────┐   │
│  │            GraphExecutor (执行层)                  │   │
│  │  - 驱动节点执行                                  │   │
│  │  - 管理 GraphState                               │   │
│  │  - 处理 Checkpoint                               │   │
│  └─────────────────────────────────────────────────   │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心概念

### 2.1 Node（节点）

```java
public interface Node {
    /** 节点名称，用于路由和日志 */
    String getName();

    /**
     * 执行节点逻辑
     * @param state 当前图状态
     * @param ctx 执行上下文
     * @return 更新后的状态
     */
    GraphState execute(GraphState state, ExecutionContext ctx);
}
```

**内置节点**：
- `EntryNode` — 初始化 GraphState
- `AgentNode` — 调用 LLM 并解析响应
- `ToolsNode` — 执行工具调用
- `AdvisorNode` — 包装节点，注入 Advisor 逻辑
- `END` — 终止节点（空操作）

### 2.2 Edge（边）

```java
public enum EdgeType {
    /** 固定边：A → B */
    FIXED,
    
    /** 条件边：A → (B|C)，根据 state 决定 */
    CONDITIONAL,
    
    /** 动态边：运行时计算目标 */
    DYNAMIC
}
```

### 2.3 GraphState（状态）

```java
public class GraphState {
    private final Map<String, Object> data;
    private final boolean immutable;

    // 不可变设计：每次修改返回新实例
    public GraphState with(String key, Object value) {
        Map<String, Object> newData = new HashMap<>(this.data);
        newData.put(key, value);
        return new GraphState(newData, true);
    }

    public <T> T get(String key) {
        @SuppressWarnings("unchecked")
        T value = (T) this.data.get(key);
        return value;
    }
}
```

**特性**：
- 不可变对象（Immutable）
- 线程安全
- 支持嵌套状态

## 3. StateGraph 定义

### 3.1 构建 API

```java
StateGraph graph = new StateGraph();

// 添加节点
graph.addNode("entry", entryNode)
     .addNode("agent", agentNode)
     .addNode("tools", toolsNode)
     .addNode("END", endNode);

// 添加边
graph.addEdge("entry", "agent")           // 固定边
     .addConditionalEdges("agent",        // 条件边
         shouldContinue,                  // 决策函数
         Map.of("continue", "tools",      // 路由表
                "end", "END"))
     .addEdge("tools", "agent");          // 循环边

// 设置入口
graph.setEntryPoint("entry");

// 编译
CompiledGraph compiled = graph.compile();
```

### 3.2 示例：ReAct Graph

```
┌─────────────────────────────────────────────────────────┐
│                    ReAct Graph                           │
│                                                         │
│  ┌────────┐    ┌────────    ┌────────┐               │
│  │ entry  │ →  │ agent  │ →  │ tools  │               │
│  └────────┘    └────────┘    └────────┘               │
│                      ↑               │                  │
│                      └───────────────┘                  │
│                         (循环)                           │
│                                                         │
│  出口：agent → END (当 ShouldContinue 返回 "end")       │
└─────────────────────────────────────────────────────────┘
```

## 4. CompiledGraph

### 4.1 编译过程

```java
public class CompiledGraph {
    private final Map<String, Node> nodes;
    private final Map<String, Edge> edges;
    private final String entryPoint;

    public CompiledGraph compile(StateGraph definition) {
        // 1. 拓扑排序
        List<String> sorted = topologicalSort(definition);
        
        // 2. 环路检测
        if (hasCycle(definition)) {
            throw new GraphCompilationException("Cycle detected");
        }
        
        // 3. 优化执行路径
        Map<String, List<String>> optimized = optimizePaths(sorted);
        
        return new CompiledGraph(nodes, edges, entryPoint);
    }
}
```

### 4.2 运行时查询

```java
public class CompiledGraph {
    public Node getNode(String name) { ... }
    public String getNextNode(String current) { ... }
    public String getEntryPoint() { ... }
    
    // 条件边决策
    public String getNextNode(String current, GraphState state) {
        Edge edge = edges.get(current);
        if (edge.getType() == CONDITIONAL) {
            return ((ConditionalEdge) edge).getNextNode(state);
        }
        return edge.getTarget();
    }
}
```

## 5. GraphExecutor

### 5.1 执行循环

```java
public class GraphExecutor {
    private final CheckpointStore checkpointStore;
    private final int maxTurns;

    public TaskResult execute(CompiledGraph graph, GraphState initialState) {
        GraphState state = initialState;
        String currentNode = graph.getEntryPoint();
        int turn = 0;

        while (currentNode != null && !currentNode.equals("END")) {
            // 1. 保存 Checkpoint
            if (checkpointStore != null) {
                checkpointStore.save(taskId, turn, state);
            }

            // 2. 获取并执行节点
            Node node = graph.getNode(currentNode);
            try {
                state = node.execute(state, executionContext);
            } catch (Exception e) {
                log.error("Node {} failed: {}", currentNode, e.getMessage());
                state = state.with("error", e.getMessage());
                break;
            }

            // 3. 确定下一个节点
            currentNode = graph.getNextNode(currentNode, state);
            turn++;

            // 4. 检查最大轮次
            if (turn >= maxTurns) {
                log.warn("Max turns ({}) reached", maxTurns);
                break;
            }
        }

        return new TaskResult(state, turn);
    }
}
```

### 5.2 执行上下文

```java
public class ExecutionContext {
    private final String taskId;
    private final SkillMeta skill;
    private final AgentTask task;
    private final Map<String, Object> extras;

    // 传递额外信息给节点
    public void put(String key, Object value) {
        extras.put(key, value);
    }

    public <T> T get(String key) {
        @SuppressWarnings("unchecked")
        T value = (T) extras.get(key);
        return value;
    }
}
```

## 6. Checkpoint 机制

### 6.1 CheckpointStore

```java
public interface CheckpointStore {
    /** 保存检查点 */
    void save(String taskId, int turn, GraphState state);

    /** 加载检查点 */
    GraphState load(String taskId, int turn);

    /** 删除检查点 */
    void delete(String taskId);

    /** 列出所有检查点 */
    List<Integer> listTurns(String taskId);
}
```

### 6.2 InMemoryCheckpointStore

```java
public class InMemoryCheckpointStore implements CheckpointStore {
    private final Map<String, Map<Integer, GraphState>> store = 
        new ConcurrentHashMap<>();

    @Override
    public void save(String taskId, int turn, GraphState state) {
        store.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>())
             .put(turn, state);
    }

    @Override
    public GraphState load(String taskId, int turn) {
        return store.getOrDefault(taskId, Collections.emptyMap())
                    .get(turn);
    }
}
```

### 6.3 恢复执行

```java
public TaskResult resume(String taskId, int fromTurn) {
    GraphState state = checkpointStore.load(taskId, fromTurn);
    if (state == null) {
        throw new IllegalArgumentException("Checkpoint not found");
    }
    return execute(graph, state);
}
```

## 7. Interrupt 机制

### 7.1 InterruptException

```java
public class InterruptException extends Exception {
    private final String reason;
    private final Map<String, Object> context;

    public InterruptException(String reason) {
        this.reason = reason;
        this.context = Collections.emptyMap();
    }
}
```

### 7.2 使用场景

```java
public class SafeGuardAdvisor implements Advisor {
    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        // 检查危险操作
        if (isDangerous(state)) {
            throw new InterruptException("Dangerous operation blocked");
        }
        return state;
    }
}
```

### 7.3 处理 Interrupt

```java
try {
    state = node.execute(state, ctx);
} catch (InterruptException e) {
    log.warn("Interrupted at node {}: {}", nodeName, e.getReason());
    state = state.with("interrupted", true)
                 .with("interrupt_reason", e.getReason());
    // 可以选择继续或终止
    break;
}
```

## 8. 自定义 Graph

### 8.1 定义新 Graph

```java
public class MyCustomGraphFactory {
    public CompiledGraph build() {
        StateGraph g = new StateGraph();
        
        g.addNode("start", startNode)
         .addNode("process", processNode)
         .addNode("validate", validateNode)
         .addNode("END", endNode)
         
         .addEdge("start", "process")
         .addConditionalEdges("process", 
             shouldRetry,
             Map.of("retry", "process",
                    "valid", "validate",
                    "invalid", "END"))
         .addEdge("validate", "END")
         
         .setEntryPoint("start");
        
        return g.compile();
    }
}
```

### 8.2 自定义节点

```java
public class MyCustomNode implements Node {
    @Override
    public String getName() { return "my_node"; }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) {
        // 读取输入
        String input = state.get("input");
        
        // 执行逻辑
        String result = process(input);
        
        // 更新状态
        return state.with("result", result);
    }
}
```

## 9. 性能优化

### 9.1 节点并行执行

```java
public class ParallelNode implements Node {
    private final List<Node> parallelNodes;
    private final ExecutorService executor;

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) {
        // 并行执行所有子节点
        List<Future<GraphState>> futures = parallelNodes.stream()
            .map(node -> executor.submit(() -> node.execute(state, ctx)))
            .collect(Collectors.toList());

        // 合并结果
        for (Future<GraphState> future : futures) {
            state = future.get();
        }
        return state;
    }
}
```

### 9.2 状态压缩

```java
public class StateCompressor {
    public GraphState compress(GraphState state) {
        // 移除临时数据
        Map<String, Object> compressed = new HashMap<>();
        for (Map.Entry<String, Object> entry : state.getData().entrySet()) {
            if (!entry.getKey().startsWith("_")) {  // 跳过临时键
                compressed.put(entry.getKey(), entry.getValue());
            }
        }
        return new GraphState(compressed);
    }
}
```

## 10. 常见问题

### Q1: Graph 编译失败？
```
错误：Cycle detected
原因：图中存在环路
解决：检查边定义，确保无环（除非故意循环）
```

### Q2: 节点执行超时？
```
错误：Node execution timeout
原因：节点逻辑阻塞或死循环
解决：检查节点逻辑，添加超时控制
```

### Q3: 状态丢失？
```
现象：下游节点拿不到上游数据
原因：GraphState 是不可变对象，必须用 with() 返回新实例
解决：确保节点返回 state.with(key, value)
```
