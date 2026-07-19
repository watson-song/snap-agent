---
name: config-diff
description: "环境配置对比：读取不同环境配置，识别差异项，标记风险。适合'sit 和 prod 配置有什么不同'类问题。"
tools: [config_read, metrics_query]
---

# 环境配置对比

## Phase 1: 读取配置
- 调用 `config_read` (source=local) 读取当前环境配置
- 如需对比远程环境，调用 `config_read` (source=nacos) 读取目标环境配置

## Phase 2: 差异识别
- 对比两个环境的配置 key
- 标记: 仅一侧有的 key / 值不同的 key

## Phase 3: 风险评估
- 连接池/线程池大小差异 → 性能风险
- 超时配置差异 → 可靠性风险
- 日志级别差异 → 可观测性风险
- 限流/熔断配置差异 → 稳定性风险

## Phase 4: 输出
- 差异项表格（key | sit值 | prod值 | 风险等级）
- 高风险项重点标注
- 建议对齐的配置项
