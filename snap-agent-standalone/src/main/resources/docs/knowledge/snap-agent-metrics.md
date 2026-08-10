---
name: snap-agent-metrics
description: Metrics 监控 — MicrometerObservationAdvisor、MetricsCollector
version: 2.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Metrics 监控

## 1. 架构

```
GraphExecutor 执行节点
  → MicrometerObservationAdvisor (Order=10)
      beforeNode: Observation.start("agent.node")
      afterNode: observation.stop() → 记录耗时
  → MetricsCollector → Actuator / Prometheus
```

## 2. 核心组件

| 类 | 模块 | 职责 |
|----|------|------|
| `MicrometerObservationAdvisor` | core/metrics | Advisor(Order=10)，节点执行计时 |
| `MetricsCollector` | core/metrics | 指标收集 SPI |

## 3. 暴露方式

通过 Spring Boot Actuator `/actuator/metrics` 暴露，可对接 Prometheus/Grafana。
