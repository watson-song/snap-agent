---
name: snap-agent-advisor-system
description: Advisor 系统 — SPI 接口、Order 排序、AdvisorNode 装饰器、全部内置 Advisor 实现
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Advisor 系统

## 1. 架构

Advisor 是 Graph 节点的横切关注点装饰器，类似 Spring AOP。

```
AdvisorNode(delegate=agentNode, advisors=[...])
  ── beforeNode: 按 Order 升序执行所有 Advisor.beforeNode()
  ── delegate.execute()
  ── afterNode: 按 Order 升序执行所有 Advisor.afterNode()
```

## 2. Advisor SPI

```java
// core/graph/advisor/Advisor.java
public interface Advisor {
    int getOrder();       // 数值越小越先执行
    String getName();
    GraphState beforeNode(String nodeName, GraphState state, Object ctx)
        throws InterruptException;
    GraphState afterNode(String nodeName, GraphState state, Object ctx)
        throws InterruptException;
}
```

## 3. 内置 Advisor 清单（按 Order 排序）

| Order | 类 | 模块 | 职责 |
|-------|-----|------|------|
| 10 | `MicrometerObservationAdvisor` | core/metrics | Observation 计时 |
| 10 | `ProjectContextAdvisor` | boot2x/context | 注入项目结构信息 |
| 50 | `SafeGuardAdvisor` | core/security | 危险工具拦截 |
| 100 | `MessageChatMemoryAdvisor` | core/memory | 对话记忆加载/持久化 |
| 150 | `LongTermMemoryAdvisor` | core/memory | 长期记忆检索 |
| 200 | `RetrievalAugmentationAdvisor` | core/rag | RAG 向量检索注入 |
| 300 | `CostBudgetAdvisor` | core/cost | 成本预算检查 |
| 400 | `AuditAdvisor` | core/security | 审计日志记录 |

## 4. AdvisorNode 装饰器

```java
// core/graph/advisor/AdvisorNode.java
public class AdvisorNode implements Node {
    private final Node delegate;
    private final List<Advisor> advisors;  // 已按 Order 排序

    public GraphState execute(GraphState state, ExecutionContext ctx) {
        for (Advisor a : advisors) state = a.beforeNode(name, state, ctx);
        state = delegate.execute(state, ctx);
        for (Advisor a : advisors) state = a.afterNode(name, state, ctx);
        return state;
    }
}
```

## 5. 执行流程示例

```
AgentNode 执行:
  Order 10:  MicrometerObservationAdvisor.beforeNode() → 开始计时
  Order 10:  ProjectContextAdvisor.beforeNode()         → 注入项目信息
  Order 50:  SafeGuardAdvisor.beforeNode()              → 安全检查
  Order 100: MessageChatMemoryAdvisor.beforeNode()      → 加载对话历史
  Order 150: LongTermMemoryAdvisor.beforeNode()         → 加载长期记忆
  Order 200: RetrievalAugmentationAdvisor.beforeNode()  → RAG 检索
  Order 300: CostBudgetAdvisor.beforeNode()             → 预算检查
  Order 400: AuditAdvisor.beforeNode()                  → 审计开始
  ──────────────────────────────────────────
  AgentNode.execute()  ← 实际 LLM 调用
  ──────────────────────────────────────────
  Order 10:  MicrometerObservationAdvisor.afterNode()   → 停止计时
  Order 100: MessageChatMemoryAdvisor.afterNode()       → 持久化消息
  Order 400: AuditAdvisor.afterNode()                   → 审计记录
```

## 6. 自动装配

Advisors 通过 `ObjectProvider<List<Advisor>>` 收集所有 Advisor bean，按 `getOrder()` 排序后注入 `ReActGraphFactory`。宿主可声明自定义 Advisor bean，自动加入链中。
