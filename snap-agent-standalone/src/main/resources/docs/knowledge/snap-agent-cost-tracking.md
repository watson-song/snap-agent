---
name: snap-agent-cost-tracking
description: 成本追踪系统详解 — CostTracker、BudgetEnforcer、CostCalculator、Token 统计
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 成本追踪系统

## 1. 架构概述

成本追踪系统用于监控 LLM 调用的 token 消耗和费用：

```
┌─────────────────────────────────────────────────────────
│                    LlmClient 调用                        │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│           CostTrackingLlmClient (装饰器)                 │
│  - 拦截 LLM 请求和响应                                    │
│  - 提取 token 使用量                                      │
│  - 计算费用                                              │
└────────────────────────┬────────────────────────────────┘
                         │
           ┌─────────────┼─────────────┐
           ▼             ▼             ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│ CostTracker │ │ CostCalculator│ │BudgetEnforcer│
│ (记录器)    │ │ (计算器)    │ │ (预算控制)   │
└─────────────┘ └─────────────┘ └─────────────┘
     │
     ▼
┌─────────────
│ CostStore   │
│ (持久化)    │
└─────────────┘
```

## 2. CostTracker

### 2.1 接口定义

```java
public interface CostTracker {
    /** 记录单次 LLM 调用成本 */
    void record(String taskId, String skillId, CostRecord record);
    
    /** 查询任务成本 */
    List<CostRecord> queryByTask(String taskId);
    
    /** 查询技能成本 */
    List<CostRecord> queryBySkill(String skillId, Instant from, Instant to);
    
    /** 查询用户成本 */
    List<CostRecord> queryByUser(String userId, Instant from, Instant to);
}
```

### 2.2 文件存储实现

```java
public class FileCostStore implements CostTracker {
    private final Path storageDir;
    private final ObjectMapper mapper;

    @Override
    public void record(String taskId, String skillId, CostRecord record) {
        Path file = storageDir.resolve(taskId + ".json");
        List<CostRecord> records = loadRecords(file);
        records.add(record);
        saveRecords(file, records);
    }
}
```

## 3. CostCalculator

### 3.1 定价模型

```java
public class CostCalculator {
    private final Map<String, Pricing> modelPricing;

    public BigDecimal calculate(String model, int inputTokens, int outputTokens) {
        Pricing pricing = modelPricing.get(model);
        if (pricing == null) {
            pricing = Pricing.DEFAULT;
        }
        
        BigDecimal inputCost = pricing.getInputPrice()
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(BigDecimal.valueOf(1_000_000));
        
        BigDecimal outputCost = pricing.getOutputPrice()
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(BigDecimal.valueOf(1_000_000));
        
        return inputCost.add(outputCost);
    }
}
```

### 3.2 定价配置

```yaml
snap-agent:
  cost:
    pricing:
      claude-sonnet-4-20250514:
        input: 3.00      # $3.00 / 1M tokens
        output: 15.00    # $15.00 / 1M tokens
        cache-read: 0.30 # $0.30 / 1M tokens
      claude-opus-4-20250514:
        input: 15.00
        output: 75.00
        cache-read: 1.50
```

## 4. BudgetEnforcer

### 4.1 预算限制

```java
public class BudgetEnforcer {
    private final BigDecimal perUserDailyLimit;
    private final BigDecimal perSkillDailyLimit;
    private final BigDecimal globalDailyLimit;

    public void check(String userId, String skillId, CostRecord record) {
        // 检查用户日限额
        if (perUserDailyLimit != null) {
            BigDecimal userToday = queryUserToday(userId);
            if (userToday.add(record.getAmount()).compareTo(perUserDailyLimit) > 0) {
                throw new BudgetExceededException("User daily limit exceeded");
            }
        }
        
        // 检查技能日限额
        // 检查全局日限额
    }
}
```

### 4.2 配置示例

```yaml
snap-agent:
  cost:
    budget:
      per-user-daily: 10.00      # 用户每日 $10
      per-skill-daily: 50.00     # 技能每日 $50
      global-daily: 100.00       # 全局每日 $100
```

## 5. CostTrackingLlmClient

### 5.1 装饰器模式

```java
public class CostTrackingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final CostTracker costTracker;
    private final CostCalculator costCalculator;

    @Override
    public void stream(LlmRequest req, LlmEventSink events, String taskId) {
        // 创建事件包装器
        LlmEventSink trackingSink = new LlmEventSink() {
            @Override
            public void onUsage(long inputTokens, long outputTokens, 
                               long cacheReadTokens) {
                // 计算成本
                BigDecimal cost = costCalculator.calculate(
                    req.getModel(), inputTokens, outputTokens);
                
                // 记录成本
                CostRecord record = new CostRecord(
                    taskId, req.getModel(), inputTokens, 
                    outputTokens, cacheReadTokens, cost);
                costTracker.record(taskId, req.getSkillId(), record);
                
                // 转发给原始 sink
                events.onUsage(inputTokens, outputTokens, cacheReadTokens);
            }
        };
        
        // 委托给实际 LlmClient
        delegate.stream(req, trackingSink, taskId);
    }
}
```

## 6. 成本报告

### 6.1 查询 API

```
GET /snap-agent/cost/summary?from=2026-08-01&to=2026-08-31

Response:
{
  "totalCost": 125.50,
  "totalInputTokens": 2_500_000,
  "totalOutputTokens": 5_000_000,
  "byModel": {
    "claude-sonnet-4-20250514": {
      "cost": 100.00,
      "inputTokens": 2_000_000,
      "outputTokens": 4_000_000
    }
  },
  "bySkill": {
    "database-query": {
      "cost": 50.00,
      "invocations": 150
    }
  }
}
```

## 7. 常见问题

### Q1: 成本记录丢失？
```
原因：FileCostStore 写入失败
解决：检查存储目录权限
```

### Q2: 预算超限？
```
错误：BudgetExceededException
原因：超过日限额配置
解决：调整 budget 配置或等待次日重置
```

### Q3: 定价不准确？
```
原因：模型定价未配置
解决：在 pricing 配置中添加模型定价
```
