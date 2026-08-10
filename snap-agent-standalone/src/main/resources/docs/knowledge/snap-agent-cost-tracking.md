---
name: snap-agent-cost-tracking
description: 成本追踪 — CostTracker、CostStore、CostCalculator、BudgetEnforcer、CostTrackingLlmClient、CostBudgetAdvisor
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 成本追踪系统

## 1. 架构

```
LlmClient 调用 → CostTrackingLlmClient(装饰器)
                    ├── LlmEventSink.onUsage() → CostCalculator → CostStore
                    └── CostBudgetAdvisor(Order=300) → BudgetEnforcer 预算检查
```

## 2. 核心 SPI

```java
// core/cost/CostTracker.java
public interface CostTracker {
    void record(CostRecord record);
    boolean isWithinBudget(String userId, String skillId);
    CostSummary getSummary(String dimension, String value);
}

// core/cost/CostStore.java
public interface CostStore {
    void save(CostRecord record);
    List<CostRecord> list(String userId, Instant from, Instant to);
    // ... sumCost/countBy/deleteBefore 等
}

// core/cost/CostRecord.java
// id, userId, skillName, taskId, model, inputTokens, outputTokens, cacheReadTokens, cost, timestamp
```

## 3. 实现类

| 类 | 模块 | 说明 |
|----|------|------|
| `CostTrackingLlmClient` | boot2x/cost | LlmClient 装饰器，通过 onUsage() 捕获 token |
| `CostCalculator` | boot2x/cost | token→cost 计算（按模型定价）|
| `DefaultCostTracker` | boot2x/cost | 默认 CostTracker 实现 |
| `FileCostStore` | boot2x/cost | JSON 文件按日期分目录存储 |
| `BudgetEnforcer` | boot2x/cost | per-user/per-skill/global 日限额 |
| `CostSummaryService` | boot2x/cost | 成本汇总查询 |
| `CostBudgetAdvisor` | core/cost | Advisor(Order=300)，执行前预算检查 |

## 4. 配置

```yaml
snap-agent:
  cost:
    enabled: true
    pricing:
      claude-sonnet-4-20250514:
        input: 3.00        # $/1M tokens
        output: 15.00
        cache-read: 0.30
    budgets:
      per-user-daily: 10.00
      per-skill-daily: 50.00
      global-daily: 100.00
    storage-dir: /tmp/snap-agent-cost
```
