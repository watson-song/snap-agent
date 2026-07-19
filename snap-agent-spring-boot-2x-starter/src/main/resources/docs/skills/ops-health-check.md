---
name: ops-health-check
description: "全面运营健康检查：查询关键指标（CPU/内存/延迟/错误率），识别异常，分析根因。适合'系统健康吗'或'有没有异常'类问题。"
tools: [metrics_query, log_search, mysql_query]
---

# 运营健康检查

## Phase 1: 核心指标快照
调用 `metrics_query` 查询以下指标（过去 5 分钟）：
- QPS: `rate(http_requests_total[5m])`
- 错误率: `sum(rate(http_requests_total{status=~"5.."}[5m])) / sum(rate(http_requests_total[5m]))`
- P99 延迟: `histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))`
- CPU: `rate(process_cpu_seconds_total[5m])`
- 内存: `process_resident_memory_bytes`

## Phase 2: 异常识别
对比指标是否超出阈值：
- 错误率 > 1% → 异常
- P99 > 1s → 延迟异常
- CPU > 80% → 资源告警

## Phase 3: 根因下钻
对每个异常指标：
- 调用 `log_search` 查对应服务的 ERROR 日志（过去 5 分钟）
- 汇总错误模式（频率最高的异常类型）

## Phase 4: 输出报告
- 整体状态: 健康/告警/异常
- 异常项列表（指标值 + 阈值 + 根因摘要）
- 建议操作
