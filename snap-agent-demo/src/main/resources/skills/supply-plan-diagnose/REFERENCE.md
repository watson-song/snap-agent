# Supply Plan Diagnose Reference

**项目编码**: `scpdrp-saas`

## Environment Configuration

| Environment | Domain | DB (business) | DB (core) |
|------------|--------|---------------|-----------|
| prod | `https://scpdrp.sf-express.com` | scpdrp_saas_core | scpdrp_saas_core |
| uat | `https://scpdrp-saas.sit.sf-express.com` | scpdrp_saas_uat | scpdrp_saas_uat_core |
| sit | `https://scpdrp-saas.sit.sf-express.com` | scpdrp_saas | scpdrp_saas_core |

## Related Tables

| 表名 | 实体类 | 说明 |
|------|--------|------|
| `drp_supply_plan_config` | DrpSupplyPlanConfig | 计划配置(时间粒度/长度/冻结/下发/指标/安全策略) |
| `drp_supply_plan_self_index` | DrpSupplyPlanSelfIndex | **核心表** 推演结果(应用计算)，90个 index_value 时间槽 |
| `ads_supply_plan_index` | - | BDP ADS 层指标(推演输入源) |
| `drp_supply_plan_extend` | DrpSupplyPlanExtend | 用户编辑记录(edit字段标记) |
| `drp_supply_plan_time` | DrpSupplyPlanTime | 时间颗粒度配置(dateKey→showName映射) |
| `drp_supply_plan_time_lock` | DrpSupplyPlanTimeLock | 计划确认/下达锁定记录 |
| `drp_preparation_orders` | DrpPreparationOrders | 备货单(见模块06) |
| `sku_available_resources` | SkuAvailableResources | 产线信息 |
| `work_progress_order` | WorkProgressOrder | 在制库存(按skuCode聚合) |
| `drp_sku_detail` | DrpSkuDetail | 箱规(boxSpec) |
| `drp_supply_strategy_parameters` | DrpSupplyStrategyParameters | 安全库存策略参数(非全国) |
| `drp_country_replenishment_strategy_parameters` | DrpCountryReplenishmentStrategyParameters | 全国安全库存策略 |
| `drp_demand_plan_extend_index` | DemandPlanExtendIndex | 需求计划扩展指标(`demandPlanService.getBySkuAndWhToCh` 查询源) |
| `dim_biz_clnd_nd` | BizCalendar | 业务日历表(`initTimeFormat`→`bizCalendarService` 依赖,今日记录缺失则抛异常) |

## 计划状态枚举 (PreParationOrderStatusEnum)

| 码 | 枚举 | 含义 | 可编辑 |
|----|------|------|--------|
| 1 | TO_BE_CONFIRMED | 待确认 | 是 |
| 2 | TO_BE_DISTRIBUTE | 待下发(已确认) | 否 |
| 3 | DISTRIBUTED | 下发中 | 否 |
| 4 | DISTRIBUTE_SUCCESSFUL | 下发成功 | 否 |
| 5 | DISTRIBUTE_FAILED | 下发失败 | 否 |

## Dolphin Configuration

| 项 | 值 |
|----|-----|
| serviceName | `supplyPlanJobService` |
| flowType | API(回调) |
| 触发参数 | `plan_id` |
| taskAfterProcess 分支 | targetShowList 含 "plan" → `initPlanValueByIndexData()`; 否则 → `initPlanTime()` |

## Data Flow

```
ads_supply_plan_index (BDP ADS指标)
    │
    ▼ deduction(planId) — Redis锁 deduction_{planId} TTL 1分钟
    │
initValueByIndexData():
  1. initTimeFormat() — 删旧建新 drp_supply_plan_time (依赖 dim_biz_clnd_nd)
  2. 确定 targetShowList(展示) + participateTargetShowList(参与计算) + 追加 expiryAvailQty
  3. deleteByPlanId 清空 self_index
  4. 解析安全库存策略 (isNational→国家策略表 / 否则→供应策略表)
  5. 分页100条读 ads_supply_plan_index
  6. 关联需求计划 demandPlanService.getBySkuAndWhToCh() → drp_demand_plan_extend_index
  7. processIndexData() 计算指标 (participateTargetShowList 决定需求侧/供给侧)
  8. saveBatch drp_supply_plan_self_index (status=1 待确认)
  9. 清理 time_lock + extend
    │
    ├── updatePlanValue() → extend记录编辑值 → deductionSuggest()局部重算
    │
    ├── planConfirmation() → 仅校验status!=2,设为2 → time_lock写入
    │
    └── planDistribute() → 仅校验status!=4,设为4 → time_lock写入 → createPreparationOrders()
                                                    │
                                                    ▼
                                              drp_preparation_orders (备货单)
```

## 净需求建议公式 (processSuggest)

```
净需求建议[i] = 累加(需求侧指标[1..i]) + (累加(净需求计划[1..i-1]) - 累加(净需求建议[1..i-1]))
              - 累加(供给侧指标[1..i])

需求侧指标: participateTargetShowList 中值为 supplyPlan、safetyStockRequire 的指标 → planTotal
供给侧指标: participateTargetShowList 中其余指标(如在库、在途、在制等) → inventoryTotal
具体参与计算的指标由配置 participateTargetShowList 决定,非硬编码

若结果 < 0: 净需求建议=0, 期末库存=|净需求建议|
若结果 ≥ 0: 期末库存=0

净需求计划: extend 有编辑值→用编辑值; 否则→用建议值
```

## processIndexData 计算顺序

| 步骤 | 方法 | 产出指标 | 逻辑 |
|------|------|----------|------|
| 1 | processFromIndexData | 基础指标箱转换 | ads值÷箱规, status=1(待确认) |
| 2 | processSupplyPlan | supplyPlan / offlineChannelDemand / demandExpiryDifference | 需求计划各渠道汇总; 线下渠道(ch=2001/2002/2004)累加; 差值=累加效期可用(expiryAvailQty)-累加线下需求 |
| 3 | processSafety | safetyStockRequire | 渠道×策略天数×日均需求计划÷箱规 |
| 4 | processSuggest | netRecommendationSuggest / endInventoryQty / netRecommendationPlan | 配置驱动:participateTargetShowList 决定需求侧/供给侧指标,见上方公式 |

> **targetShowList vs participateTargetShowList**:
> - `targetShowList`: Tab 页**展示**哪些指标
> - `participateTargetShowList`: 哪些指标**参与净需求建议计算**。`supplyPlan`/`safetyStockRequire`→需求侧(planTotal),其余→供给侧(inventoryTotal)
> - `expiryAvailQty`: 效期可用库存(箱),来自 `ads_supply_plan_index`,始终无条件追加到查询索引列表

---

## 诊断逻辑

### 1. 推演数据缺失(self_index无数据) — deduction / initValueByIndexData

**源码位置**: `scpdrp-biz/.../service/supply/impl/DrpSupplyPlanDeductionServiceImpl.java`
**Spring Bean**: `drpSupplyPlanDeductionService`

**触发链路**: Dolphin回调 → `SupplyPlanJobServiceImpl.taskAfterProcess()` → targetShowList含"plan" → `initPlanValueByIndexData()` → `initValueByIndexData()`

**诊断步骤**:

1. **检查计划配置是否存在且启用**:
   ```sql
   SELECT id, plan_name, enable, target_show_list, time_granularity, time_format
   FROM drp_supply_plan_config
   WHERE id = ${planId} AND deleted = 0;
   ```
   - 记录不存在 → 配置未创建或已删除
   - `enable=false` → Tab 不展示，推演也不触发
   - `target_show_list` 不含 "plan" → taskAfterProcess 只执行 `initPlanTime`，不推演

2. **检查 ADS 指标数据是否存在**:
   ```sql
   SELECT COUNT(*) as cnt FROM ads_supply_plan_index
   WHERE plan_id = ${planId};
   ```
   - 0 条 → BDP ETL 未产出，推演无输入源
   - 按SKU检查:
     ```sql
     SELECT COUNT(*) FROM ads_supply_plan_index
     WHERE plan_id = ${planId} AND sku_code = '${skuCode}';
     ```

3. **检查时间轴是否生成**:
   ```sql
   SELECT supply_plan_id, date_key, date_format, sort
   FROM drp_supply_plan_time
   WHERE supply_plan_id = ${planId}
   ORDER BY sort;
   ```
   - 无数据 → `initTimeFormat()` 未执行或 `bizCalendarService` 查询 `dim_biz_clnd_nd` 失败
   - 检查业务日历:
     ```sql
     SELECT biz_dt, biz_week, week_first_day, week_last_day
     FROM dim_biz_clnd_nd
     WHERE biz_dt = CURDATE();
     ```
   - 今日不存在 → 日历数据未初始化，推演会抛异常

4. **检查 self_index 是否有数据**:
   ```sql
   SELECT deduction_status, COUNT(DISTINCT search_key) as search_key_cnt
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId}
   GROUP BY deduction_status;
   ```
   - 无数据 → 推演未执行或执行失败
   - 有数据但 deduction_status != 1 → 已被确认/下达

5. **检查 Dolphin 实例状态**:
   ```sql
   SELECT id, flow_status, retry_status, process_fail_msg, create_time
   FROM sys_dolphin_instance
   WHERE project_code = '2715'
   AND JSON_EXTRACT(state, '$.processInstanceId') IS NOT NULL
   ORDER BY id DESC LIMIT 5;
   ```
   - 参考 dolphin-task-diagnose skill 进一步排查

6. **检查 Redis 分布式锁**:
   - 锁 key: `deduction_{planId}`，TTL 1分钟
   - 如果锁未释放 → 推演被阻塞，等待 TTL 过期后重试

**常见问题**:
- `target_show_list` 不含 "plan" → taskAfterProcess 只初始化时间，不推演数据
- `ads_supply_plan_index` 无数据 → BDP ETL 未产出
- `dim_biz_clnd_nd` 缺今日记录 → bizCalendar 抛异常
- Redis 锁未释放 → 推演被阻塞

---

### 2. 推演指标计算错误 — processIndexData / processSuggest

**源码位置**: `scpdrp-biz/.../service/supply/impl/DrpSupplyPlanDeductionServiceImpl.java`

**诊断步骤**:

1. **核对安全库存策略分支**:
   ```sql
   -- 检查配置的安全策略类型
   SELECT safety_inventory_strategy, supply_node, supply_type
   FROM drp_supply_plan_config
   WHERE id = ${planId};
   ```
   - `isNational()` → 查 `drp_country_replenishment_strategy_parameters`
   - 非全国 → 查 `drp_supply_strategy_parameters`
   - `isPoint()` → 取订货点天数; 否则取目标天数

2. **检查安全库存策略参数**:
   ```sql
   -- 非全国
   SELECT sku_code, warehouse_code, target_days, order_point_days
   FROM drp_supply_strategy_parameters
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}';

   -- 全国
   SELECT sku_code, target_days, order_point_days
   FROM drp_country_replenishment_strategy_parameters
   WHERE sku_code = '${skuCode}';
   ```
   - 无数据 → 安全库存策略未生成（参考 supply-strategy-diagnose skill）
   - 天数为空 → 安全库存计算为0

3. **检查需求计划关联**:
   ```sql
   -- demandPlanService.getBySkuAndWhToCh 查询的需求计划
   SELECT date_key, plan_value, channel_code
   FROM drp_demand_plan_extend_index
   WHERE sku_code = '${skuCode}' AND wh_code = '${warehouseCode}'
   ORDER BY date_key;
   ```
   - 无数据 → 需求计划未生成，供应计划的需求计划/线下渠道需求为0

4. **核对箱规**:
   ```sql
   SELECT sku_code, box_spec FROM drp_sku_detail WHERE sku_code = '${skuCode}';
   ```
   - `box_spec` 为空或0 → 基础指标箱转换除0异常

5. **检查在库/在途/在制数据**:
   ```sql
   -- 在制库存(按skuCode聚合)
   SELECT sku_code, SUM(qty) as total_wip
   FROM work_progress_order
   WHERE sku_code = '${skuCode}'
   GROUP BY sku_code;
   ```
   - 在库/在途来自 `ads_supply_plan_index` 的对应 index_code
   - 在制来自 `work_progress_order`

**常见问题**:
- 安全库存策略分支选错（全国 vs 仓维度）
- 需求计划未关联 → 需求计划指标为0（查 `drp_demand_plan_extend_index`）
- 箱规为空 → 箱转换除0
- 在制库存未按 skuCode 聚合 → 周日备货建议计算错误
- `participateTargetShowList` 配置错误 → 需求侧/供给侧指标归类错误

---

### 3. 计划值无法编辑 — updatePlanValue

**源码位置**: `scpdrp-biz/.../service/supply/impl/DrpSupplyPlanServiceImpl.java`
**Spring Bean**: `drpSupplyPlanService`

**诊断步骤**:

1. **检查 self_index 状态**:
   ```sql
   SELECT DISTINCT deduction_status
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId} AND sku_code = '${skuCode}';
   ```
   - `deduction_status` 为 2/3/4/5 → `updatePlanValue` 直接 return（不可改）

2. **检查冻结长度**:
   ```sql
   SELECT plan_freezing_length, time_granularity
   FROM drp_supply_plan_config
   WHERE id = ${planId};
   ```
   - 冻结长度内的列不可编辑
   - 例: `plan_freezing_length=2` → 前两个周期列只读

3. **检查 extend 记录是否写入**:
   ```sql
   SELECT id, date_key, plan_value, edit, search_key
   FROM drp_supply_plan_extend
   WHERE supply_plan_id = ${planId} AND sku_code = '${skuCode}'
   ORDER BY id DESC LIMIT 10;
   ```
   - 无记录 → 编辑值未保存
   - `edit=false` → 新旧值相同且配置含 plan，extend 被置 false

**常见问题**:
- 状态非"待确认(1)" → 需先重新推演(deduction)重置状态
- 冻结长度内 → 不可编辑，属正常限制
- 配置 targetShowList 不含 "plan" → extend 记录被删除而非置 false

---

### 4. 计划确认失败 — planConfirmation

**源码位置**: `scpdrp-biz/.../service/supply/impl/DrpSupplyPlanServiceImpl.java`

**诊断步骤**:

1. **检查当前状态**:
   ```sql
   SELECT deduction_status, search_key
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId} AND sku_code = '${skuCode}';
   ```
   - 确认: 代码仅校验 status != 2(非"待下发"即放行),不强制要求 status=1
   - 状态为 2(待下发) → 已确认，不能重复确认(抛异常)

2. **检查 time_lock 是否写入**:
   ```sql
   SELECT id, date_key, search_key, status
   FROM drp_supply_plan_time_lock
   WHERE supply_plan_id = ${planId}
   ORDER BY id DESC LIMIT 20;
   ```
   - 无记录 → 确认流程未执行或中途失败

3. **检查状态是否更新**:
   ```sql
   SELECT deduction_status, COUNT(*) as cnt
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId}
   GROUP BY deduction_status;
   ```
   - 确认后应全部为 2(待下发)

**常见问题**:
- 状态已为 2(待下发) → 重复确认抛异常
- time_lock 构建异常 → dateKey/dateFormat 未取到（检查 drp_supply_plan_time）

---

### 5. 计划下达/备货单未生成 — planDistribute / createPreparationOrders

**源码位置**: `scpdrp-biz/.../service/supply/impl/DrpSupplyPlanServiceImpl.java`

**诊断步骤**:

1. **检查当前状态**:
   ```sql
   SELECT DISTINCT deduction_status
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId} AND sku_code = '${skuCode}';
   ```
   - 下达仅校验 `status != 4`(非"下发成功"即放行),不强制要求 status=2
   - 状态为 4(下发成功) → 已下达，不能重复下达(抛异常)

2. **检查下发长度配置**:
   ```sql
   SELECT distribution_length, plan_freezing_length
   FROM drp_supply_plan_config
   WHERE id = ${planId};
   ```
   - `distribution_length=0` 或 NULL → 不生成备货单（条件: distributionLength>0）

3. **检查净需求是否>0**:
   ```sql
   -- 查 distributionLength+1 位置的净需求建议
   SELECT search_key, index_code, index_name,
          CONCAT('index_value', ${distributionLength} + 1) as target_col
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId} AND sku_code = '${skuCode}'
   AND index_code = 'netRecommendationSuggest';
   ```
   - 净需求≤0 → 不生成备货单（正常跳过）

4. **检查备货单是否生成**:
   ```sql
   SELECT id, sku_code, wh_code, plan_demand, plan_suggest, create_time
   FROM drp_preparation_orders
   WHERE sku_code = '${skuCode}'
   ORDER BY id DESC LIMIT 10;
   ```
   - 无记录 → createPreparationOrders 未执行或条件不满足

5. **检查在制库存(周/日)**:
   ```sql
   -- 周日备货建议 = MAX(0, 净需求建议 - 在制库存)
   SELECT sku_code, SUM(qty) as total_wip
   FROM work_progress_order
   WHERE sku_code = '${skuCode}'
   GROUP BY sku_code;
   ```
   - 在制≥净需求建议 → 备货建议=0

**常见问题**:
- 状态已为 4(下发成功) → 重复下达抛异常
- `distribution_length` 为0 → 不满足备货单生成条件
- 净需求≤0 → 正常跳过
- 月度: 备货建议=净需求建议(distributionLength+1位置); 周/日: 备货建议=MAX(0,净需求建议-在制)

---

### 6. 时间轴未生成 — initPlanTime / initTimeFormat

**诊断步骤**:

1. **检查时间轴表**:
   ```sql
   SELECT supply_plan_id, date_key, date_format, sort
   FROM drp_supply_plan_time
   WHERE supply_plan_id = ${planId}
   ORDER BY sort;
   ```
   - 无数据 → initTimeFormat/initPlanTime 未执行

2. **检查时间格式配置**:
   ```sql
   SELECT time_granularity, time_format, plan_length, history_plan_length
   FROM drp_supply_plan_config
   WHERE id = ${planId};
   ```
   - `time_format` 为空 → 默认返回空时间轴

3. **检查业务日历**:
   ```sql
   SELECT biz_dt, biz_week, week_first_day, week_last_day
   FROM dim_biz_clnd_nd
   WHERE biz_dt = CURDATE();
   ```
   - 无记录 → bizCalendarService 抛异常
   - 周颗粒度需查周一日期:
     ```sql
     SELECT biz_dt, biz_week, week_first_day, week_last_day
     FROM dim_biz_clnd_nd
     WHERE biz_dt = DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY);
     ```

---

### 7. 局部重算异常 — deductionSuggest

**诊断步骤**:

1. **检查 extend 编辑值是否保留**:
   ```sql
   SELECT id, date_key, plan_value, edit, search_key
   FROM drp_supply_plan_extend
   WHERE supply_plan_id = ${planId} AND search_key = '${searchKey}'
   ORDER BY date_key;
   ```
   - `edit=true` → 编辑值保留正确
   - 无记录 → 编辑值被删除（可能配置 targetShowList 不含 "plan"）

2. **检查重算后 self_index 指标**:
   ```sql
   SELECT index_code, index_name, index_value1, index_value2, index_value3
   FROM drp_supply_plan_self_index
   WHERE plan_id = ${planId} AND search_key = '${searchKey}'
   AND index_code IN ('netRecommendationSuggest', 'endInventoryQty', 'netRecommendationPlan');
   ```
   - `netRecommendationPlan` 应取 extend 编辑值
   - `netRecommendationSuggest` 和 `endInventoryQty` 应重算

**常见问题**:
- extend 编辑值未保留 → deductionSuggest 先删后写时未保留
- netRecommendationPlan 未用编辑值 → extend 查询条件不匹配

---

## 诊断决策树

```
供应计划问题?
├── 推演数据缺失?
│   ├── 查 drp_supply_plan_config → 配置存在且 enable=true?
│   │   ├── 否 → 创建/启用配置
│   │   └── 是 → target_show_list 含 "plan"?
│   │       ├── 否 → 修改配置加入 "plan"
│   │       └── 是 → 查 ads_supply_plan_index 有数据?
│   │           ├── 否 → BDP ETL 未产出，排查数据上游
│   │           └── 是 → 查 drp_supply_plan_time 有数据?
│   │               ├── 否 → 查 dim_biz_clnd_nd 今日是否存在
│   │               └── 是 → 检查 Redis 锁 + Dolphin 实例状态
│   └── → deduction / initValueByIndexData
│
├── 推演指标错误?
│   ├── 安全库存策略分支 → isNational? → 查对应策略表
│   ├── 需求计划关联 → 查 drp_demand_plan_extend
│   ├── 箱规 → 查 drp_sku_detail.box_spec
│   └── 在库/在途/在制 → 查 ads_supply_plan_index + work_progress_order
│   └── → processIndexData / processSuggest
│
├── 计划值无法编辑?
│   ├── 查 self_index.deduction_status → 2/3/4/5?
│   │   ├── 是 → 需重新推演重置为1
│   │   └── 否 → 查 plan_freezing_length → 冻结期内?
│   │       ├── 是 → 正常限制
│   │       └── 否 → 查 extend 记录是否写入
│   └── → updatePlanValue
│
├── 计划确认/下达失败?
│   ├── 确认: 仅校验 status != 2(非"待下发"即放行) → 状态设为 2
│   ├── 下达: 仅校验 status != 4(非"下发成功"即放行,不强制要求 status=2) → 状态设为 4
│   ├── time_lock 是否写入 → 查 drp_supply_plan_time_lock
│   └── 备货单是否生成 → distribution_length>0? 净需求>0?
│   └── → planConfirmationOrDistribute / createPreparationOrders
│
├── 时间轴未生成?
│   ├── 查 drp_supply_plan_config → time_format 非空?
│   ├── 查 dim_biz_clnd_nd → 今日/本周一存在?
│   └── → initPlanTime / initTimeFormat
│
└── Dolphin 任务未触发?
    ├── 查 sys_dolphin_instance → 实例存在?
    │   ├── 否 → 检查 sys_dolphin_job_cron 和 sys_dolphin_config
    │   └── 是 → flow_status 和 retry_status?
    └── → 参考 dolphin-task-diagnose skill
```

## Service 触发机制

| 入口方法 | 触发方式 | 说明 |
|---------|---------|------|
| `SupplyPlanJobServiceImpl.taskAfterProcess()` | Dolphin API回调 | plan_id → targetShowList含"plan"? → initPlanValueByIndexData / initPlanTime |
| `DrpSupplyPlanDeductionServiceImpl.deduction()` | API `/supplyPlan/deduction` | 全量推演，Redis锁 deduction_{planId} |
| `DrpSupplyPlanServiceImpl.updatePlanValue()` | API `/supplyPlan/updatePlanValue` | 编辑→extend→deductionSuggest局部重算 |
| `DrpSupplyPlanServiceImpl.planConfirmation()` | API `/supplyPlan/confirmation` | 仅校验 status!=2,设为 2，写 time_lock |
| `DrpSupplyPlanServiceImpl.planDistribute()` | API `/supplyPlan/distribute` | 仅校验 status!=4,设为 4，写 time_lock，生成备货单 |
| `DrpSupplyPlanServiceImpl.deductionSuggest()` | API `/supplyPlan/deductionSuggest` | 按 searchKey 局部重算 |
| `DrpSupplyPlanServiceImpl.deductionTest()` | API `/supplyPlan/deductionTest` | 按SKU/仓调试推演(不保存) |

## 关键业务规则

| 规则 | 说明 |
|------|------|
| 推演前清空 self_index | `deleteByPlanId` 后重算 |
| 安全库存策略分支 | isNational→国家策略表; 否则→供应策略表; isPoint→订货点天数 |
| 净需求计划取值 | extend 编辑值优先，否则用建议值 |
| 净需求建议计算 | 配置驱动:`participateTargetShowList` 决定参与指标,`supplyPlan`/`safetyStockRequire`→需求侧(planTotal),其余→供给侧(inventoryTotal) |
| 冻结长度内不可编辑 | planFreezingLength 控制 |
| 状态 2/3/4/5 不可编辑 | updatePlanValue 直接 return |
| 确认状态校验 | 仅校验 status != 2(非"待下发"即放行),设为 2 |
| 下达状态校验 | 仅校验 status != 4(非"下发成功"即放行),不强制要求 status=2,可跳过确认直接下达 |
| 备货单生成条件 | distributionLength>0 且净需求>0 |
| 月度备货建议 | 净需求建议(distributionLength+1位置) |
| 周日备货建议 | MAX(0, 净需求建议-在制库存) |
| 推演并发控制 | Redis 锁 `deduction_{planId}` TTL 1分钟 |
| extend 优先级 | extend 有编辑值→用编辑值; 否则→用建议值 |
| targetShowList 分支 | 含"plan"→推演数据+时间; 不含→仅时间 |
| 时间轴依赖 | `initTimeFormat`→`bizCalendarService`→`dim_biz_clnd_nd`,今日记录缺失则抛异常 |

## 模块依赖链

```
01 供应数据初始化 → 02 供应策略生成 → 03 供应计划生成 → 06 备货计划
                                          ↑ reads
                                   04 供应分仓比 (BDP only)
                                   07 供应计划配置 (metadata)
```

## 关联 Skill

- [supply-strategy-diagnose](../../skills/supply-strategy-diagnose/SKILL.md) — 供应策略参数缺失排查
- [replenishment-strategy-diagnose](../../skills/replenishment-strategy-diagnose/SKILL.md) — 补货策略参数缺失排查
- [dolphin-task-diagnose](../../skills/dolphin-task-diagnose/SKILL.md) — Dolphin 调度任务异常排查
