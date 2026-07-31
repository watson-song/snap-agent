---
name: replenishment-strategy-diagnose
description: 丰智云策预补调计划系统补货策略生成问题分析。Use when investigating why a specific SKU (with optional warehouse) did not generate a replenishment strategy entry. Triggers on: 调查/排查/分析/查某sku为什么没有/未生成补货策略, 补货策略缺失, 补货参数表没有数据, replm_inv_param_sku_wh_input未生成. Also triggers when user mentions 补货策略生成/初始化/插入 and a specific skuCode or warehouse. 仅适用于scpdrp-saas项目。
---

# Replenishment Strategy Diagnose

**项目编码**: `scpdrp-saas`

## Workflow

1. **Ask user for environment** (if not already specified):
   - `prod` → `https://scpdrp.sf-express.com` (DB: scpdrp_saas_core)
   - `uat` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas_core)
   - `sit` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas_core)

2. **Collect diagnostic info**: skuCode, warehouseCode (optional), batchId (if available)

3. **Identify the problem category** (see [REFERENCE.md](REFERENCE.md) for details):

   | 症状 | 可能涉及Service | 诊断路径 |
   |------|----------------|---------|
   | 补货策略参数缺失 | SepReplenishmentStrategyServiceImpl.init() | 查 drp_sku_warehouse_network → drp_replenishment_strategy_parameters |
   | 品仓初始化输入表未生成 | SepReplenishmentStrategyServiceImpl.init() | 查 replm_initial_dstr_sku_wh_input |
   | 库存参数输入表未生成 | SepReplenishmentStrategyServiceImpl.stockSugCalculate() | 查 replm_inv_param_sku_wh_input |
   | 建议补货点/目标未更新 | SepWhStockSugCalculateServiceImpl.taskAfterProcess() | 查 dws_replm_sku_wh_inv_param_output |

4. **Execute SQL queries** via BDP IDE or DB connection to check relevant tables

5. **Analyze root cause** based on service logic (see [REFERENCE.md](REFERENCE.md#诊断逻辑))

## Two Core Services

### 1. SepReplenishmentStrategyServiceImpl (`sepReplenishmentStrategyService`)
**职责**: 补货库存策略全生命周期管理（初始化、库存建议计算、结果回写）

**核心流程**:
- `init(batchId)`: 补货网络定义 → 过滤有效品仓 → 增删改策略参数表 → 生成品仓初始化输入表
- `stockSugCalculate(batchId)`: 全量计算 → 分批查策略参数 → 关联品仓配置 → 生成库存参数输入表
- `stockSugUpdate(reqVOList, batchId)`: 部分更新 → 按指定ID查策略 → 生成库存参数输入表
- `runProcess(batchId)`: 触发Dolphin品仓初始化流程 (definitionCode=202096)
- `findStockSugCalculateDto(reqVOList)`: 触发Dolphin库存建议计算 (definitionCode=202016, batchId前缀BH)

**关键表**: `drp_sku_warehouse_network`, `drp_replenishment_strategy_parameters`, `replm_initial_dstr_sku_wh_input`, `replm_inv_param_sku_wh_input`

### 2. SepWhStockSugCalculateServiceImpl (`sepWhStockSugCalculateService`)
**职责**: 补货库存建议计算的Dolphin调度入口和后置处理

**核心流程**:
- `jobTaskProcess()`: Dolphin定时触发 → 调用 `findStockSugCalculateDto(null)` 全量计算
- `manualTaskBeforeProcess()`: 手动触发 → 有reqVOList走stockSugUpdate，无则走stockSugCalculate
- `taskAfterProcess()`: BDP计算完成后 → 从 `dws_replm_sku_wh_inv_param_output` 读取结果 → 更新 `drp_replenishment_strategy_parameters` 的建议补货点/目标/圆整规则/安全库存

**关键表**: `dws_replm_sku_wh_inv_param_output`, `drp_replenishment_strategy_parameters`

## Quick Diagnostic SQL

```sql
-- 1. 检查补货网络定义是否存在
SELECT * FROM drp_sku_warehouse_network WHERE sku_code='${skuCode}' AND dest_warehouse_code='${warehouseCode}';

-- 2. 检查补货策略参数是否生成
SELECT * FROM drp_replenishment_strategy_parameters WHERE sku_code='${skuCode}' AND warehouse_code='${warehouseCode}';

-- 3. 检查品仓初始化输入表是否生成
SELECT * FROM replm_initial_dstr_sku_wh_input WHERE sku_code='${skuCode}' AND warehouse_code='${warehouseCode}' ORDER BY id DESC LIMIT 10;

-- 4. 检查库存参数输入表是否生成
SELECT * FROM replm_inv_param_sku_wh_input WHERE sku_code='${skuCode}' AND warehouse_code='${warehouseCode}' ORDER BY id DESC LIMIT 10;

-- 5. 检查BDP计算结果是否返回
SELECT * FROM dws_replm_sku_wh_inv_param_output WHERE sku_code='${skuCode}' AND warehouse_code='${warehouseCode}' ORDER BY id DESC LIMIT 10;
```

For detailed service logic, data flow diagram, and diagnostic decision tree, see [REFERENCE.md](REFERENCE.md).
