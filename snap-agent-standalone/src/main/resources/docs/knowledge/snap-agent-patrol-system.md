---
name: snap-agent-patrol-system
description: Patrol 主动巡检系统详解 — ScheduledPatrolScheduler、异常检测、告警推送
version: 1.0.0
modules:
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Patrol 主动巡检系统

## 1. 架构概述

Patrol 系统定期执行健康巡检，检测异常并推送告警。

```
┌─────────────────────────────────────────────────────────┐
│            ScheduledPatrolScheduler                      │
│  - 定时触发巡检任务                                      │
│  - 管理巡检锁（分布式锁）                                │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Patrol Report Store                         │
│  - 存储巡检报告                                          │
│  - InMemoryPatrolReportStore（默认）                     │
└────────────────────────┬────────────────────────────────┘
                         │
                    检测到异常
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Alert Push Channels                         │
│  - EmailAlertPushChannel                                 │
│  - WebhookAlertPushChannel                               │
└─────────────────────────────────────────────────────────┘
```

## 2. 核心组件

### ScheduledPatrolScheduler
定时触发巡检，支持分布式锁防止重复执行。

### TemplateBugfixSuggester
基于巡检结果，自动生成修复建议模板。

### InMemoryAlertConverger
告警收敛，防止告警风暴。

### AlertPushChannel (SPI)
```java
public interface AlertPushChannel {
    void push(Alert alert);
}
```

## 3. 配置

```yaml
snap-agent:
  patrol:
    enabled: true
    scheduler-pool-size: 2
    report-buffer-size: 500
  alert:
    enabled: true
    buffer-size: 1000
    auto-resolve-minutes: 30
```
