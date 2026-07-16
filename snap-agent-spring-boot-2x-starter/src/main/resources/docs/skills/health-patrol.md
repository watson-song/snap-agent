---
name: health-patrol
description: "Comprehensive health check using observability metrics. Queries CPU, memory, error rate, and latency from Prometheus, checks for anomalies, and generates a summary report. Use when proactively monitoring system health."
tools: [metrics_query]
inputs:
  - name: service
    description: "Service name to check"
    required: true
---

# Health Patrol

## Step 1: Query Key Metrics
Use the `metrics_query` tool to query key health metrics for the `{service}` service:

- CPU usage: `100 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100`
- Memory available: `node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes * 100`
- Error rate: `rate(http_requests_total{service="{service}",status=~"5.."}[5m])`
- P99 latency: `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{service="{service}"}[5m]))`

## Step 2: Check for Anomalies
Evaluate each metric against normal thresholds:
- CPU > 80% for sustained period -> anomaly
- Memory available < 20% -> anomaly
- Error rate > 1% -> anomaly
- P99 latency > 500ms -> anomaly

## Step 3: Generate Summary Report
Summarize the health status:
- Overall: HEALTHY / WARNING / CRITICAL
- List any anomalies detected with metric values
- Recommend actions for any anomalies
