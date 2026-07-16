# SnapAgent 业务知识示例

## 系统概述
SnapAgent 是嵌入式 LLM 诊断 Agent，提供只读数据库查询、日志分析、代码理解、运营诊断能力。它以 Spring Boot 2.x starter 的形式嵌入宿主应用，复用宿主的安全上下文和数据源。

## 数据库诊断
数据库诊断基于独立只读数据源连接。Agent 可查询业务数据但不能修改。所有 SQL 仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN，严禁 DDL/DML。

常见问题:
- 连接池打满 → 检查 max-pool-size 配置，确认是否有长事务占用连接
- 慢查询 → 用 EXPLAIN 分析执行计划，检查索引使用情况
- 死锁 → 查 information_schema.innodb_trx 查看未提交事务

## 代码理解
代码理解工具可读取项目源码、查看 git 历史、扫描项目结构。适用于"这段逻辑为什么这样写"和"这个接口在哪实现"类问题。Agent 会自动注入项目结构摘要到 system prompt。

## 运营诊断
运营诊断对接 Prometheus/Loki/Jaeger，可查询指标、搜索日志、分析调用链。适用于"线上接口为什么慢"和"错误率为什么升高"类问题。需要配置各平台 base-url 和鉴权信息。
