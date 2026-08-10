---
name: snap-agent-cost-tracking
description: 成本追踪 — CostTracker SPI、CostStore、CostCalculator、BudgetEnforcer、CostTrackingLlmClient、CostBudgetAdvisor
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 成本追踪系统

## 1. 架构

```
LlmClient 调用 → CostTrackingLlmClient(装饰器)
                    ├── LlmEventSink.onUsage() → CostCalculator → CostRecord → CostStore
                    └── CostBudgetAdvisor(Order=300) → BudgetEnforcer 预算检查
```

## 2. 核心 SPI

### 2.1 CostTracker 接口（4 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostTracker.java -->
```java
public interface CostTracker {
    void record(CostRecord record);
    boolean isWithinBudget(String userId, String skillName);
    CostSummary getSummary(String dimension, String dimensionValue, long from, long to);
    String type();
}
```

### 2.2 CostStore 接口

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostStore.java -->
```java
public interface CostStore {
    void save(CostRecord record);
    List<CostRecord> list(String userId, Instant from, Instant to);
    // sumCostByUser / sumCostBySkill / countByUser / countBySkill / deleteBefore
}
```

### 2.3 CostRecord

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostRecord.java -->
```java
// id, userId, skillName, taskId, model, inputTokens, outputTokens, cacheReadTokens, cost, timestamp
```

## 3. 实现类

| 类 | 模块 | 说明 |
|----|------|------|
| `CostTrackingLlmClient` | boot2x/cost | LlmClient 装饰器，通过 onUsage() 捕获 token |
| `CostCalculator` | core/cost | token→cost 计算（input/output/cacheRead 单价）|
| `DefaultCostTracker` | boot2x/cost | 默认 CostTracker 实现 |
| `FileCostStore` | boot2x/cost | JSON 文件按日期分目录存储 |
| `BudgetEnforcer` | boot2x/cost | per-user/per-skill/global 日限额 |
| `CostSummaryService` | boot2x/cost | 成本汇总查询 |
| `CostBudgetAdvisor` | core/cost | Advisor(Order=300)，before: 预算检查，after: 成本记录 |

<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/CostTrackingLlmClient.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostCalculator.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/DefaultCostTracker.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/FileCostStore.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/BudgetEnforcer.java -->
<!-- source: snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/cost/CostSummaryService.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/cost/CostBudgetAdvisor.java -->

## 4. CostBudgetAdvisor 执行流程

- **beforeNode**: 检查 perUserDaily / perSkillDaily / globalDaily 预算，超限 → InterruptException
- **afterNode**: 读取 state 中的 token 计数（llm.input_tokens 等），计算 cost → CostStore.save()

## 5. 配置

```yaml
snap-agent:
  cost:
    enabled: true
    pricing:
      input: 3.00            # $/1M tokens
      output: 15.00
      cache-read: 0.30
      currency: USD
    budgets:
      per-user-daily: 10.00
      per-skill-daily: 50.00
      global-daily: 100.00
    storage-dir: /tmp/snap-agent-cost
    warn-threshold: 0.8
```
