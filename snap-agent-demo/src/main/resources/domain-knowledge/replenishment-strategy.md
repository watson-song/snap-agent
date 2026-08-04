---
name: 补货策略
tables:
  - replm_inv_param_sku_wh_input
  - replm_safety_stock
services:
  - ReplenishmentStrategyService
  - SafetyStockService
entry_points:
  - ReplenishmentStrategyTask.execute()
related_concepts:
  - 调拨计划
  - 安全库存
tags: [replenishment, strategy, 补货参数]
---

# 补货策略

## 业务描述

根据历史销量、安全库存、供应商交期等参数，计算每个 SKU 在每个仓库的补货建议。补货策略是调拨计划的前置环节——先确定需要补什么、补多少，再通过调拨计划执行跨基地的库存平衡。

## 数据表

### replm_inv_param_sku_wh_input（补货参数输入表）
| 字段 | 说明 |
|------|------|
| sku_code | 物料编码 |
| warehouse_code | 仓库编码 |
| safety_stock | 安全库存 |
| reorder_point | 补货点 |
| lead_time_days | 供应商交期（天） |

### replm_safety_stock（安全库存表）
| 字段 | 说明 |
|------|------|
| sku_code | 物料编码 |
| warehouse_code | 仓库编码 |
| safety_stock_qty | 安全库存数量 |
| update_time | 最后更新时间 |

## 业务规则

1. 补货建议 = 安全库存 + 在途量 - 可用库存
2. 可用库存 = 物理库存 - 预留量 - 不合格品
3. 安全库存每天由 Dolphin 任务重新计算
4. 补货策略参数按品仓维度（SKU + 仓库）独立维护

## 已知陷阱

- **安全库存更新延迟**：Dolphin 任务执行后约15分钟才写入 `replm_safety_stock` 表
- **品仓未初始化**：如果品仓数据未初始化（`replm_initial_dstr_sku_wh_input` 无记录），补货策略不会生成
- **零参数**：当 `reorder_point` 为0时，系统认为该品仓不需要补货，跳过处理

## 常见诊断问题

### 补货策略未生成
1. 检查 `replm_inv_param_sku_wh_input` 是否有该品仓的记录
2. 检查品仓初始化是否完成
3. 检查 Dolphin 任务是否正常执行
4. 检查安全库存参数是否为空
