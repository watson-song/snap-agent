---
name: 调拨计划
tables:
  - drp_allocation_plan
  - drp_allocation_detail
services:
  - AllocationPlanService
  - BalanceAlgorithmService
entry_points:
  - ReplenishmentPlanTask.generate()
related_concepts:
  - 补货策略
  - 安全库存
tags: [replenishment, allocation, 航材]
---

# 调拨计划

## 业务描述

航材消耗件在多基地间的库存平衡调拨。当某个基地的库存低于安全库存时，系统自动从库存充足的基地生成调拨计划，实现库存的多基地平衡优化。

**适用范围**：仅航材消耗件走多基地平衡算法，航材非消耗件走单基地补货。

## 数据表

### drp_allocation_plan（调拨计划主表）
| 字段 | 说明 |
|------|------|
| sku_code | 物料编码 |
| from_warehouse | 调出基地 |
| to_warehouse | 调入基地 |
| quantity | 调拨数量 |
| status | 状态（草稿/已审核/已下达） |

### drp_allocation_detail（调拨明细表）
| 字段 | 说明 |
|------|------|
| plan_id | 关联计划ID |
| batch_no | 批次号 |
| actual_quantity | 实际调拨数量 |

## 核心服务

- **AllocationPlanService** — 调拨计划生成核心逻辑
  - `createPlan()` — 根据库存差异计算调拨数量并保存
  - `validatePlan()` — 校验调拨数量不超过可用库存

- **BalanceAlgorithmService** — 多基地平衡算法
  - 计算各基地的供需差异
  - 确定最优调拨路径

## 入口方法

```
DolphinJobRunService.execute()
  └── ReplenishmentPlanTask.generate()
        ├── InventoryCheckService.check()       // 校验库存
        │     └── InventoryApi.query()           // 查询库存
        └── AllocationPlanService.createPlan()  // 生成调拨计划
              └── AllocationPlanRepository.save() // 持久化
```

## 业务规则

1. 只有航材消耗件才走多基地平衡算法
2. 调拨数量 = max(0, 目标基地安全库存 - 目标基地可用库存 + 预留量)
3. 同一天同一SKU不能生成两次调拨
4. 调出基地的可用库存必须 >= 调拨数量 + 安全库存

## 已知陷阱

- **并发问题**：并发场景下 `getAvailableStock()` 可能返回过期数据，需加分布式锁
- **安全库存延迟**：`replm_safety_stock` 表在 Dolphin 任务执行后约15分钟才更新，期间查询可能不准确
- **跨时区**：跨时区基地计算时日期边界处理有bug，UTC+8 和 UTC-5 的基地可能跨天
- **零库存**：当可用库存为0时，`InventoryApi.query()` 返回 null 而非空对象，需判空

## 常见诊断问题

### SKU 没有生成调拨计划
1. 检查 `drp_allocation_plan` 表是否有记录
2. 检查 `replm_safety_stock` 表安全库存是否已更新
3. 检查 `InventoryApi` 返回的可用库存是否 >= 安全库存
4. 检查 Dolphin 任务是否正常执行

### 调拨数量异常
1. 检查安全库存计算是否正确
2. 检查是否有预留量未扣减
3. 检查 BalanceAlgorithmService 的平衡算法参数

## SQL 示例

```sql
-- 查询某SKU的调拨计划
SELECT * FROM drp_allocation_plan
WHERE sku_code = 'A123'
ORDER BY create_time DESC;

-- 查询某基地的待审核调拨
SELECT * FROM drp_allocation_plan
WHERE from_warehouse = 'WH01' AND status = 'DRAFT';
```
