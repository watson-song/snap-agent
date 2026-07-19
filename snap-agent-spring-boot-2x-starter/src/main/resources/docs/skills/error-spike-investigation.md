---
name: error-spike-investigation
description: "错误率突增排查：定位时间窗口 → 查日志 → 关联部署 → 定位根因。适合'错误率突然升高'或'为什么突然报错'类问题。"
tools: [metrics_query, log_search, trace_search, code_read, git_log]
---

# 错误率突增排查

## Phase 1: 定位时间窗口
调用 `metrics_query` 查错误率趋势：
- query: `sum(rate(http_requests_total{status=~"5.."}[5m])) by (status)`
- range: 过去 2 小时, step=1m
- 识别错误率突增的起始时间

## Phase 2: 提取错误日志
调用 `log_search` 查对应时段的错误日志：
- query: `{app="{service}"} |= "ERROR" | json`
- start: 突增时间, end: now
- 分析错误类型频率

## Phase 3: 关联变更
- 调用 `git_log` 查突增时间前 1 小时的提交记录
- 识别是否有相关服务代码变更

## Phase 4: 调用链分析
- 调用 `trace_search` 查突增时段的异常 trace
- 定位慢/失败 span

## Phase 5: 代码定位
- 调用 `code_read` 读取报错对应的源码行
- 结合 `git_log` blame 分析最近变更

## Phase 6: 输出根因
- 错误突增时间 + 持续时长
- 错误类型 Top 3
- 根因分析（代码变更/依赖故障/资源耗尽）
- 修复建议
