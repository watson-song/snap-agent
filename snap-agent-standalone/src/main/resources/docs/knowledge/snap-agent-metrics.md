---
name: snap-agent-metrics
description: Metrics 监控详解 — MicrometerObservationAdvisor、指标收集
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Metrics 监控系统

## 1. 架构

```
GraphExecutor 执行节点
  ── MicrometerObservationAdvisor (Order=10)
       ── beforeNode: 开始 Observation
       ── afterNode: 停止 Observation，记录耗时
  ── MetricsCollector
       ── 收集 Agent 执行指标
       ── 暴露到 Actuator / Prometheus
```

## 2. MicrometerObservationAdvisor

```java
public class MicrometerObservationAdvisor implements Advisor {
    @Override
    public int getOrder() { return 10; }  // 最早执行

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        Observation observation = Observation.start("agent.node", registry);
        return state.with("_observation", observation);
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
        Observation obs = state.get("_observation");
        if (obs != null) obs.stop();
        return state;
    }
}
```

## 3. 暴露指标

通过 Spring Boot Actuator 暴露到 `/actuator/metrics`，可对接 Prometheus/Grafana。
