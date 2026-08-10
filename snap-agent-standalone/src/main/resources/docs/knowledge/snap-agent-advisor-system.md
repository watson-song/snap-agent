---
name: snap-agent-advisor-system
description: Advisor 系统 — Advisor SPI、AdvisorNode 装饰器、内置 Advisor 清单、自动装配
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Advisor 系统

## 1. 架构概述

Advisor 是图节点的前置/后置装饰器，用于注入横切关注点（记忆、安全、成本、审计等）。
按 `getOrder()` 升序排列，`beforeNode` 正向执行，`afterNode` 反向执行。

```
AdvisorNode(delegate, advisors[])
  ├── beforeNode: advisor[0] → advisor[1] → ... → advisor[N]
  ├── delegate.execute()
  └── afterNode:  advisor[N] → ... → advisor[1] → advisor[0]
```

## 2. 核心 SPI

### 2.1 Advisor 接口（4 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/advisor/Advisor.java -->
```java
public interface Advisor {
    int getOrder();
    String getName();
    GraphState beforeNode(String nodeName, GraphState state, Object ctx) throws InterruptException;
    GraphState afterNode(String nodeName, GraphState state, Object ctx) throws InterruptException;
}
```

### 2.2 AdvisorNode 装饰器

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/graph/advisor/AdvisorNode.java -->
```java
public class AdvisorNode implements Node {
    public AdvisorNode(Node delegate, List<Advisor> advisors)
    // advisors 按 getOrder() 升序排列
    // beforeNode: 正向遍历, afterNode: 反向遍历
    // 任一 advisor 异常仅 WARN 不中断
    public Node getDelegate()
    public List<Advisor> getAdvisors()
}
```

## 3. 内置 Advisor 清单

| Order | 类 | 模块 | 说明 |
|-------|-----|------|------|
| 10 | `MicrometerObservationAdvisor` | core/metrics | Micrometer 指标采集 |
| 10 | `ProjectContextAdvisor` | boot2x/context | 项目上下文注入 |
| 50 | `SafeGuardAdvisor` | core/security | 敏感词过滤 + 输出消毒 |
| 100 | `MessageChatMemoryAdvisor` | core/memory | 会话历史加载/持久化 |
| 150 | `LongTermMemoryAdvisor` | core/memory | 用户画像 + 项目事实注入 |
| 200 | `RetrievalAugmentationAdvisor` | core/rag | RAG 知识检索增强 |
| 300 | `CostBudgetAdvisor` | core/cost | 预算检查 + 成本记录 |
| 400 | `AuditAdvisor` | core/security | 审计日志记录 |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/metrics/MicrometerObservationAdvisor.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/context/ProjectContextAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/SafeGuardAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/MessageChatMemoryAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/memory/LongTermMemoryAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/rag/RetrievalAugmentationAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostBudgetAdvisor.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/security/AuditAdvisor.java -->

## 4. 执行流程

```
AgentNode 执行:
  Order 10:  MicrometerObservationAdvisor.beforeNode() → 开始计时
  Order 10:  ProjectContextAdvisor.beforeNode()         → 注入项目信息
  Order 50:  SafeGuardAdvisor.beforeNode()              → 过滤敏感词
  Order 100: MessageChatMemoryAdvisor.beforeNode()      → 加载对话历史
  Order 150: LongTermMemoryAdvisor.beforeNode()         → 注入用户画像/项目事实
  Order 200: RetrievalAugmentationAdvisor.beforeNode()  → RAG 检索
  Order 300: CostBudgetAdvisor.beforeNode()             → 预算检查
  Order 400: AuditAdvisor.beforeNode()                  → 审计开始
  ──────────────────────────────────────────
  delegate.execute()  ← 实际 LLM 调用
  ──────────────────────────────────────────
  Order 400: AuditAdvisor.afterNode()                   → 审计记录
  Order 300: CostBudgetAdvisor.afterNode()              → 成本记录
  Order 100: MessageChatMemoryAdvisor.afterNode()       → 持久化消息
  Order 50:  SafeGuardAdvisor.afterNode()               → 消毒 LLM 输出
```

## 5. 自动装配

`SnapAgentAutoConfiguration.agentService()` 通过 `ObjectProvider<Advisor>` 收集所有 Advisor bean，
按 `getOrder()` 排序后注入到 `AgentService`。宿主可声明自定义 Advisor bean 扩展链式行为。

<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java -->
```java
List<Advisor> advisors = advisorProvider.orderedStream().collect(Collectors.toList());
return new AgentService(llmClient, toolCallbackRegistry, taskStore, maxTurns, advisors);
```
