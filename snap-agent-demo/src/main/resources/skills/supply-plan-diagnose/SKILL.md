---
name: supply-plan-diagnose
description: 丰智云策预补调计划系统供应计划推演与下达问题分析。Use when investigating why supply plan deduction data is missing, net recommendation values are incorrect, plan editing is blocked, or preparation orders are not generated. Triggers on: 调查/排查/分析/查供应计划推演/推演数据缺失/self_index没有数据/净需求建议计算错误/计划值无法编辑/计划确认失败/计划下达失败/备货单未生成/drp_supply_plan_self_index未生成. Also triggers when user mentions 供应计划推演/deduction/计划确认/planConfirmation/计划下达/planDistribute/备货单生成 and a specific planId or skuCode or warehouse. 仅适用于scpdrp-saas项目。
---

# Supply Plan Diagnose

**项目编码**: `scpdrp-saas`

## Workflow

1. **Ask user for environment** (if not already specified):
   - `prod` → `https://scpdrp.sf-express.com` (DB: scpdrp_saas_core)
   - `uat` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas_uat / scpdrp_saas_uat_core)
   - `sit` → `https://scpdrp-saas.sit.sf-express.com` (DB: scpdrp_saas / scpdrp_saas_core)

2. **Collect diagnostic info**: planId (or tabId), skuCode, warehouseCode (optional), batchId (if available)

3. **Identify the problem category** (see [REFERENCE.md](REFERENCE.md) for details):

   | 症状 | 可能涉及方法 | 诊断路径 |
   |------|-------------|---------|
   | 推演数据缺失(self_index无数据) | deduction → initValueByIndexData | 查 drp_supply_plan_config → ads_supply_plan_index → drp_supply_plan_self_index |
   | 推演指标计算错误 | processIndexData → processSuggest | 核对净需求建议公式 + 安全库存策略分支 |
   | 计划值无法编辑 | updatePlanValue | 查 self_index.deduction_status(2/3/4/5不可改) + planFreezingLength |
   | 计划确认失败 | planConfirmationOrDistribute | 查 self_index.deduction_status(确认仅校验status!=2) + time_lock |
   | 计划下达/备货单未生成 | planDistribute → createPreparationOrders | 查 self_index.deduction_status(下达仅校验status!=4) + distributionLength + 净需求>0 + drp_preparation_orders |
   | 时间轴未生成 | initPlanTime / initTimeFormat | 查 drp_supply_plan_time + dim_biz_clnd_nd |
   | 局部重算异常 | deductionSuggest | 查 drp_supply_plan_extend 编辑值是否保留 |

4. **Execute SQL queries** via DB connection to check relevant tables

5. **Analyze root cause** based on service logic (see [REFERENCE.md](REFERENCE.md#诊断逻辑))

## Core Services

### 1. DrpSupplyPlanDeductionServiceImpl (`drpSupplyPlanDeductionService`)
**职责**: 供应计划推演核心（全量推演、局部重算、指标计算）

**核心流程**:
- `deduction(now, planId)`: Redis分布式锁 `deduction_{planId}`(TTL 1分钟) → 锁内调 `initValueByIndexData`
- `initValueByIndexData(now, planId)`: 初始化时间 → 清空 self_index → 解析安全库存策略 → 分页读 ads_supply_plan_index → 关联需求计划 → `processIndexData` 计算 → saveBatch self_index
- `processIndexData()`: ①基础指标箱转换 ②需求计划/线下渠道/效期差值(expiryAvailQty) ③安全库存 ④净需求建议/期末库存/净需求计划(participateTargetShowList 驱动:supplyPlan/safetyStockRequire→需求侧,其余→供给侧)
- `deductionSuggest(planId, searchKeyList)`: 局部重算建议/期末/计划 → 先删后写 → 保留 extend 编辑值

**关键表**: `drp_supply_plan_self_index`(核心), `ads_supply_plan_index`(输入), `drp_supply_plan_time`, `drp_supply_plan_extend`, `drp_supply_strategy_parameters`, `drp_country_replenishment_strategy_parameters`, `drp_demand_plan_extend_index`(需求计划), `dim_biz_clnd_nd`(业务日历,时间轴依赖)

### 2. DrpSupplyPlanServiceImpl (`drpSupplyPlanService`)
**职责**: Tab配置/数据查询、计划值编辑、确认/下达、备货单生成、时间轴管理

**核心流程**:
- `getTabConfig()`: 查 config(enable=true) → 权限过滤 → 构建 Tab(仅 netRecommendationPlan 可编辑)
- `getTabData(vo)`: 分页查 self_index → 查 time → 组装(左 skuInfo + 右 columnInfo) → 按 status 判断可编辑性
- `updatePlanValue(vo)`: 状态 2/3/4/5 → return → extend 记录编辑值 → 调 `deductionSuggest()` 局部重算
- `planConfirmationOrDistribute(vo)`: 确认(仅校验status!=2→设为2) / 下达(仅校验status!=4→设为4,不强制要求status=2) → 构建 time_lock → 下达调 `createPreparationOrders()`
- `createPreparationOrders(vo)`: 筛选净需求>0 → 查产线/在制/箱规 → 月度/周日分支计算 → saveBatch `drp_preparation_orders`
- `initPlanTime(now, config)`: bizCalendar(依赖 `dim_biz_clnd_nd`) 生成 dateKey→showName → 删旧建新 `drp_supply_plan_time`

**关键表**: `drp_supply_plan_config`, `drp_supply_plan_self_index`, `drp_supply_plan_extend`, `drp_supply_plan_time`, `drp_supply_plan_time_lock`, `drp_preparation_orders`

### 3. SupplyPlanJobServiceImpl (`supplyPlanJobService`)
**职责**: Dolphin 调度入口与后置处理

**核心流程**:
- `taskAfterProcess(dolphinConfig, param)`: 取 `plan_id` → 查 config → **targetShowList 含 "plan"** → `initPlanValueByIndexData()`; 不含 → `initPlanTime()`(仅时间)

## Quick Diagnostic SQL

```sql
-- 1. 检查计划配置是否存在且启用
SELECT id, plan_name, time_granularity, time_format, plan_length, history_plan_length,
       plan_freezing_length, distribution_length, target_show_list, enable
FROM drp_supply_plan_config
WHERE id = ${planId} AND deleted = 0;

-- 2. 检查时间轴是否生成
SELECT supply_plan_id, date_key, date_format, sort
FROM drp_supply_plan_time
WHERE supply_plan_id = ${planId}
ORDER BY sort;

-- 3. 检查推演结果(self_index)是否生成
SELECT deduction_status, COUNT(DISTINCT search_key) as search_key_cnt, COUNT(*) as total_rows
FROM drp_supply_plan_self_index
WHERE plan_id = ${planId}
GROUP BY deduction_status;

-- 4. 检查指定SKU+仓的推演数据
SELECT search_key, deduction_status, index_code, index_name,
       index_value1, index_value2, index_value3
FROM drp_supply_plan_self_index
WHERE plan_id = ${planId} AND sku_code = '${skuCode}'
ORDER BY index_code;

-- 5. 检查ADS指标数据(推演输入源)是否存在
SELECT COUNT(*) as cnt FROM ads_supply_plan_index
WHERE plan_id = ${planId} AND sku_code = '${skuCode}';

-- 6. 检查用户编辑记录(extend)是否存在
SELECT id, date_key, plan_value, edit, search_key
FROM drp_supply_plan_extend
WHERE supply_plan_id = ${planId} AND sku_code = '${skuCode}'
ORDER BY id DESC LIMIT 10;

-- 7. 检查计划确认/下达锁定记录
SELECT id, date_key, date_format, search_key, wh_code, sku_code, status
FROM drp_supply_plan_time_lock
WHERE supply_plan_id = ${planId}
ORDER BY id DESC LIMIT 20;

-- 8. 检查备货单是否生成
SELECT id, sku_code, wh_code, plan_demand, plan_suggest, create_time
FROM drp_preparation_orders
WHERE sku_code = '${skuCode}'
ORDER BY id DESC LIMIT 10;

-- 9. 检查业务日历今日记录(时间轴依赖)
SELECT biz_dt, biz_week, week_first_day, week_last_day
FROM dim_biz_clnd_nd
WHERE biz_dt = CURDATE();

-- 10. 检查需求计划扩展指标(供应计划需求侧输入)
SELECT date_key, plan_value, channel_code
FROM drp_demand_plan_extend_index
WHERE sku_code = '${skuCode}' AND wh_code = '${warehouseCode}'
ORDER BY date_key;
```

For detailed service logic, net recommendation formula, data flow diagram, and diagnostic decision tree, see [REFERENCE.md](REFERENCE.md).
