---
name: slow-query-analysis
description: "慢查询排查：查慢日志 → 分析执行计划 → 索引建议。适合'数据库为什么慢'或'有慢查询吗'类问题。"
tools: [mysql_query, log_search, metrics_query]
---

# 慢查询分析

## Phase 1: 发现慢查询
- 调用 `mysql_query` 查 slow_log / pt-slow-log 表（若有）
- 或调用 `log_search` 在应用日志中搜索 "slow query" 关键词
- 或调用 `metrics_query` 查 `mysql_slow_queries` 指标

## Phase 2: 分析执行计划
对每条慢 SQL：
- 调用 `mysql_query` 执行 `EXPLAIN` + SQL
- 分析 type/key/rows/Extra 字段

## Phase 3: 索引建议
- 识别全表扫描（type=ALL）
- 识别未用索引（key=NULL）
- 建议创建索引（基于 WHERE/JOIN 字段）

## Phase 4: 输出
- 慢 SQL 列表 + 耗时 + 调用频率
- 执行计划分析
- 索引优化建议
