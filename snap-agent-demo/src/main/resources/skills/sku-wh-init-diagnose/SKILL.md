---
name: sku-wh-init-diagnose
description: 丰智云策预补调计划系统品仓数据初始化问题分析。Use when investigating why the SKU-warehouse data initialization process failed or did not produce expected data. Triggers on: 调查/排查/分析/品仓初始化失败/品仓初始化未生成/初始化数据缺失/replm_initial_dstr_sku_wh_input未生成/supply_initial_dstr_sku_wh_input未生成/InitReplenishmentDataServiceImpl异常. Also triggers when user mentions 品仓初始化/数据初始化/初始化失败 and mentions batchId or Dolphin 202096. 仅适用于scpdrp-saas项目。
---

# SKU-Warehouse Init Diagnose

**项目编码**: `scpdrp-saas`

## Workflow

1. **Ask user for environment** (if not already specified):
   - `prod` → `https://scpdrp.sf-express.com` (DB: scpdrp_saas_core)
   - `uat` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas_core)
   - `sit` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas_core)

2. **Collect diagnostic info**: batchId, skuCode (optional), warehouseCode (optional)

3. **Identify the problem category** (see [REFERENCE.md](REFERENCE.md) for details):

   | 症状 | 可能涉及方法 | 诊断路径 |
   |------|-------------|---------|
   | 补货品仓初始化未执行 | manualTaskBeforeProcess() | 查 Dolphin 202096 实例状态 |
   | 供货品仓初始化未执行 | manualTaskBeforeProcess() | 查 Dolphin 18861343825824 实例状态 |
   | 初始化输入表未生成 | init() (补货/供货) | 查 replm_initial_dstr_sku_wh_input / supply_initial_dstr_sku_wh_input |
   | runProcess未触发 | jobTaskProcess() | 查 Dolphin 202096 实例 flow_status |

4. **Execute SQL queries** via BDP IDE or DB connection to check relevant tables

5. **Analyze root cause** based on service logic (see [REFERENCE.md](REFERENCE.md#诊断逻辑))

## Core Service

### InitReplenishmentDataServiceImpl (`initReplenishmentDataService`)
**职责**: 品仓数据初始化调度入口，编排补货和供货两条初始化链路

**核心流程**:
- `manualTaskBeforeProcess()`: Dolphin前置处理 → 同时执行补货init和供货init
  - `sepReplenishmentStrategyService.init(batchId)` — 补货品仓初始化
  - `centerSupplyStrategyService.init(batchId)` — 供货品仓初始化
- `jobTaskProcess()`: Dolphin执行 → `sepReplenishmentStrategyService.runProcess(batchId)` → 触发补货品仓初始化Dolphin流程
- `taskAfterProcess()`: 空实现（无后置处理）

**关键表**: `drp_sku_warehouse_network`, `drp_supply_network`, `drp_replenishment_strategy_parameters`, `drp_supply_strategy_parameters`, `replm_initial_dstr_sku_wh_input`, `supply_initial_dstr_sku_wh_input`

## Quick Diagnostic SQL

```sql
-- 1. 检查Dolphin品仓初始化实例状态（补货）
SELECT id, flow_status, retry_status, process_fail_msg, param, create_time, update_time
FROM sys_dolphin_instance WHERE project_code='2715' AND definition_code='202096' ORDER BY id DESC LIMIT 10;

-- 2. 检查Dolphin品仓初始化实例状态（供货）
SELECT id, flow_status, retry_status, process_fail_msg, param, create_time, update_time
FROM sys_dolphin_instance WHERE project_code='2715' AND definition_code='18861343825824' ORDER BY id DESC LIMIT 10;

-- 3. 按batchId检查补货品仓初始化输入表
SELECT COUNT(*) FROM replm_initial_dstr_sku_wh_input WHERE batch_id='${batchId}';

-- 4. 按batchId检查供货品仓初始化输入表
SELECT COUNT(*) FROM supply_initial_dstr_sku_wh_input WHERE batch_id='${batchId}';

-- 5. 检查补货和供货策略参数表总量
SELECT 'replenishment' AS type, COUNT(*) AS cnt FROM drp_replenishment_strategy_parameters
UNION ALL
SELECT 'supply' AS type, COUNT(*) AS cnt FROM drp_supply_strategy_parameters;
```

For detailed service logic, dual-track flow, and diagnostic decision tree, see [REFERENCE.md](REFERENCE.md).
