---
name: snap-agent-advisor-system
description: Advisor 系统详解 — SPI 接口、Order 排序、AdvisorNode 包装、内置 Advisors
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Advisor 系统

## 1. 架构概述

Advisor 是 SnapAgent 的横切关注点机制，类似 Spring 的 AOP，在 Graph 节点执行前后注入额外逻辑。

```
┌─────────────────────────────────────────────────────────
│                    Graph 执行流程                        │
│                                                         │
│  ┌──────────┐    ┌──────────┐    ──────────┐         │
│  │  entry   │ →  │  agent   │ →  │  tools   │ → END   │
│  └──────────┘    └──────────┘    └──────────┘         │
│       │               │               │                │
│       ▼               ▼               ▼                │
│  ┌──────────────────────────────────────────────┐     │
│  │              AdvisorNode 包装                  │     │
│  │                                              │     │
│  │  for (Advisor advisor : advisors) {          │     │
│  │      state = advisor.beforeNode(...);        │     │
│  │  }                                           │     │
│  │                                              │     │
│  │  state = node.execute(state);                │     │
│  │                                              │     │
│  │  for (Advisor advisor : advisors) {          │     │
│  │      state = advisor.afterNode(...);         │     │
│  │  }                                           │     │
│  └──────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
```

## 2. Advisor SPI

### 2.1 接口定义

```java
public interface Advisor {
    /**
     * 执行顺序，数值越小越先执行
     * Order 50:   SafeGuardAdvisor
     * Order 100:  MessageChatMemoryAdvisor
     * Order 200:  RAGAdvisor
     * Order 300:  LongTermMemoryAdvisor
     * Order 400:  AnchorOrchestrator
     */
    int getOrder();

    /** Advisor 名称，用于日志和调试 */
    String getName();

    /**
     * 节点执行前调用
     * @param nodeName 节点名称（entry/agent/tools）
     * @param state 当前图状态
     * @param ctx 执行上下文
     * @return 更新后的状态
     */
    GraphState beforeNode(String nodeName, GraphState state, Object ctx) 
        throws InterruptException;

    /**
     * 节点执行后调用
     */
    GraphState afterNode(String nodeName, GraphState state, Object ctx) 
        throws InterruptException;
}
```

### 2.2 AdvisorNode 包装器

```java
public class AdvisorNode implements Node {
    private final Node delegate;  // 被包装的原始节点
    private final List<Advisor> advisors;  // 按 Order 排序

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) {
        String nodeName = delegate.getName();

        // 1. 执行所有 beforeNode（按 Order 升序）
        for (Advisor advisor : advisors) {
            state = advisor.beforeNode(nodeName, state, ctx);
        }

        // 2. 执行原始节点
        state = delegate.execute(state, ctx);

        // 3. 执行所有 afterNode（按 Order 升序）
        for (Advisor advisor : advisors) {
            state = advisor.afterNode(nodeName, state, ctx);
        }

        return state;
    }
}
```

## 3. 内置 Advisors

### 3.1 SafeGuardAdvisor (Order=50)

**职责**：安全检查，防止危险操作

```java
public class SafeGuardAdvisor implements Advisor {
    @Override
    public int getOrder() { return 50; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        // 检查 tool_calls 是否包含危险操作
        List<ToolCall> toolCalls = state.get("tool_calls");
        if (toolCalls != null) {
            for (ToolCall call : toolCalls) {
                if (isDangerousTool(call.getName())) {
                    throw new InterruptException("Dangerous tool blocked: " + call.getName());
                }
            }
        }
        return state;
    }
}
```

**检查项**：
- 禁止执行系统命令（rm、delete、drop）
- 禁止访问敏感路径（/etc、/proc）
- 禁止大规模数据删除

### 3.2 MessageChatMemoryAdvisor (Order=100)

**职责**：对话记忆管理

```java
public class MessageChatMemoryAdvisor implements Advisor {
    private final ChatMemory chatMemory;
    private final int retrieveLastN = 50;

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        String conversationId = state.get("conversation.id");
        if (conversationId == null) return state;

        // 加载最近 N 条消息
        List<Message> history = chatMemory.get(conversationId, retrieveLastN);
        return state.with("memory.messages", history);
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
        String conversationId = state.get("conversation.id");
        if (conversationId == null) return state;

        switch (nodeName) {
            case "agent":
                // 持久化助手回复
                Message assistantMsg = buildAssistantMessage(state);
                chatMemory.add(conversationId, assistantMsg);
                break;
            case "tools":
                // 持久化工具结果
                for (ToolResult result : state.get("tool_results")) {
                    chatMemory.add(conversationId, buildToolResultMessage(result));
                }
                break;
        }
        return state;
    }
}
```

**执行流程**：
```
beforeNode("agent"):
  ─ 加载历史 → state["memory.messages"]
  
agent.execute():
  └─ 使用 memory.messages 构建 LLM 请求

afterNode("agent"):
  └─ 持久化 assistant 消息

afterNode("tools"):
  ─ 持久化 tool_result 消息
```

### 3.3 RAGAdvisor (Order=200)

**职责**：检索增强生成

```java
public class RAGAdvisor implements Advisor {
    private final VectorStore vectorStore;

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        if (!"agent".equals(nodeName)) return state;

        // 提取查询
        String query = extractQuery(state);
        
        // 检索相关文档
        List<Document> docs = vectorStore.search(query, topK=5);
        
        // 注入到状态
        return state.with("rag.documents", docs);
    }
}
```

**数据来源**：
- 知识库文档（docs/knowledge/）
- 代码图谱（code-graph）
- 用户上传文档

### 3.4 LongTermMemoryAdvisor (Order=300)

**职责**：长期记忆检索

```java
public class LongTermMemoryAdvisor implements Advisor {
    private final LongTermMemoryStore store;

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        if (!"agent".equals(nodeName)) return state;

        String query = extractQuery(state);
        List<String> notes = store.retrieve(query, topK=3);
        return state.with("long_term.notes", notes);
    }
}
```

**特点**：
- 跨对话持久化
- 用户偏好记忆
- 历史经验总结

### 3.5 AnchorOrchestrator (Order=400)

**职责**：锚点上下文注入

```java
public class AnchorOrchestrator implements Advisor {
    private final AnchorContextSummarizer summarizer;
    private final AnchorSkillClassifier classifier;

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        if (!"agent".equals(nodeName)) return state;

        // 判断是否需要 anchor
        AnchorContext anchor = classifyAnchor(state);
        if (anchor == null) return state;

        // 生成摘要并注入
        String summary = summarizer.summarize(anchor);
        return state.with("anchor.summary", summary);
    }
}
```

## 4. Advisor 组装流程

### 4.1 自动配置

```java
@Bean
public List<Advisor> advisors(ObjectProvider<List<Advisor>> advisorsProvider) {
    List<Advisor> advisors = advisorsProvider.getIfAvailable();
    if (advisors == null) return Collections.emptyList();
    
    // 按 Order 排序
    advisors.sort(Comparator.comparing(Advisor::getOrder));
    return advisors;
}
```

### 4.2 注入到 Graph

```java
CompiledGraph graph = new ReActGraphFactory().build(skill, task, advisors);

// ReActGraphFactory 内部
Node wrappedAgent = new AdvisorNode(agentNode, advisors);
```

## 5. 执行顺序示例

```
Agent Node 执行:

Order 50:  SafeGuardAdvisor.beforeNode()
           └─ 安全检查通过

Order 100: MessageChatMemoryAdvisor.beforeNode()
           └─ 加载 50 条历史消息

Order 200: RAGAdvisor.beforeNode()
           └─ 检索 5 篇相关文档

Order 300: LongTermMemoryAdvisor.beforeNode()
           └─ 检索 3 条长期记忆

Order 400: AnchorOrchestrator.beforeNode()
           └─ 注入锚点摘要

─────────────────────────────────────
AgentNode.execute()  ← 实际执行
─────────────────────────────────────

Order 50:  SafeGuardAdvisor.afterNode()
           └─ 无操作

Order 100: MessageChatMemoryAdvisor.afterNode()
           ─ 持久化 assistant 消息

Order 200: RAGAdvisor.afterNode()
           ─ 无操作

Order 300: LongTermMemoryAdvisor.afterNode()
           └─ 无操作

Order 400: AnchorOrchestrator.afterNode()
           ─ 无操作
```

## 6. 自定义 Advisor

### 6.1 实现接口

```java
@Component
public class CostTrackingAdvisor implements Advisor {
    @Override
    public int getOrder() { return 150; }

    @Override
    public String getName() { return "cost-tracking"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        state.put("cost.start_time", System.currentTimeMillis());
        return state;
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
        long duration = System.currentTimeMillis() - state.get("cost.start_time");
        costTracker.record(nodeName, duration);
        return state;
    }
}
```

### 6.2 注册为 Bean

```java
@Bean
public Advisor costTrackingAdvisor(CostTracker costTracker) {
    return new CostTrackingAdvisor(costTracker);
}
```

## 7. 配置属性

```yaml
snap-agent:
  advisor:
    enabled: true
    orders:
      - 50   # SafeGuard
      - 100  # ChatMemory
      - 200  # RAG
      - 300  # LongTermMemory
      - 400  # Anchor
```

## 8. 常见问题

### Q1: Advisor 执行顺序不对？
- 检查 `getOrder()` 返回值
- 确认 advisors 列表已排序

### Q2: beforeNode 修改的状态丢失？
- 确保返回新的 GraphState（不可变对象）
- 使用 `state.with(key, value)` 而非直接修改

### Q3: 如何禁用某个 Advisor？
- 从 Spring 容器中移除对应的 Bean
- 或通过配置属性控制启用/禁用
