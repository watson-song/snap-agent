---
name: snap-agent-patrol-system
description: 主动巡检 — ScheduledPatrolScheduler、告警收敛、告警推送、修复建议
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 主动巡检系统

## 1. 架构

```
ScheduledPatrolScheduler → PatrolTask → AgentService(health-patrol skill)
    → PatrolReport → PatrolReportStore
    → AnomalyEvent → AnomalyEventListener → AlertConverger → AlertPushChannel
```

## 2. 核心 SPI (core/patrol/)

| 接口/类 | 职责 |
|---------|------|
| `AnomalyEvent` | 不可变异常事件（type/service/message/stackTrace/metadata）|
| `AnomalyEventListener` | onEvent 回调 |
| `AlertConverger` | 告警去重 + 自动解决 |
| `AlertConvergence` | 收敛后的告警记录 |
| `AlertPushChannel` | 告警推送 SPI |
| `PatrolScheduler` | 调度巡检 SPI |
| `PatrolTask` | 不可变巡检任务（skill/cron/inputs）|
| `PatrolReport` | 巡检报告 |
| `PatrolReportStore` | 内存环形缓冲存储 |
| `PatrolLockProvider` | 分布式锁 SPI |
| `BugfixSuggester` | 修复建议 SPI |
| `BugfixSuggestion` | 修复建议数据 |

## 3. 实现类 (boot2x/patrol/)

| 类 | 说明 |
|----|------|
| `ScheduledPatrolScheduler` | Spring TaskScheduler, pool-size=2, cron 调度 |
| `InMemoryAlertConverger` | 内存去重, buffer=1000, auto-resolve=30min |
| `InMemoryPatrolReportStore` | 内存环形缓冲 |
| `DefaultAnomalyEventListener` | anomaly → converger → 跑 skill → 存报告 |
| `TemplateBugfixSuggester` | 模板化修复建议 |
| `NoopPatrolLockProvider` | 无锁实现 |
| `EmailAlertPushChannel` | 邮件推送 |
| `WebhookAlertPushChannel` | Webhook 推送 |

## 4. 配置

```yaml
snap-agent:
  patrol:
    enabled: false
    scheduler-pool-size: 2
    report-buffer-size: 500
  alert:
    enabled: false
    buffer-size: 1000
    auto-resolve-minutes: 30
```
