# Replenishment Strategy Diagnose Reference

**项目编码**: `scpdrp-saas`

## Environment Configuration

| Environment | Domain | CAS Server | DB |
|------------|--------|------------|-----|
| prod | `https://scpdrp.sf-express.com` | `https://cas.sf-express.com` | scpdrp_saas_core |
| uat | `https://scpdrp-saas.sit.sf-express.com` | `https://cas.sit.sf-express.com` | scpdrp_saas_core |
| sit | `https://scpdrp-saas.sit.sf-express.com` | `https://cas.sit.sf-express.com` | scpdrp_saas_core |

## Related Tables

| 表名 | 实体类 | 说明 |
|------|--------|------|
| `drp_sku_warehouse_network` | DrpSkuWarehouseNetwork | 补货网络定义（skuCode, destWarehouseCode, sourceWarehouseCode） |
| `drp_replenishment_strategy_parameters` | DrpReplenishmentStrategyParameters | 补货库存策略参数（suggestedReplenishmentPoint, suggestedReplenishmentTarget, planner等） |
| `replm_initial_dstr_sku_wh_input` | ReplmInitialDstrSkuWhInput | 补货品仓初始化输入表（batchId, skuCode, warehouseCode） |
| `replm_inv_param_sku_wh_input` | ReplmInvParamSkuWhInput | 补货库存参数输入表（batchId, skuCode, warehouseCode） |
| `dws_replm_sku_wh_inv_param_output` | DwsReplmInvParamSkuWhOutput | BDP计算结果输出表（sugFillPointDay, sugFillPointQty, sugFillTargetDay, sugFillTargetQty） |
| `product_warehouse_config` | ProductWarehouseConfig | 品仓配置（planId, 库存策略, 补货日历等） |

## Dolphin Definition Codes

| 配置项 | DefinitionCode | 说明 |
|--------|---------------|------|
| `dolphin.replmInitialDstrSkuWhInput` | 202096 | 补货品仓数据初始化 |
| `dolphin.replmInvParamSkuWhInput` | 202016 | 补货库存建议计算 |

## Data Flow

```
drp_sku_warehouse_network (补货网络定义)
    │
    ▼ init() — 过滤有效品仓（商品有效+参与补货+目的仓有效）
    │
drp_replenishment_strategy_parameters (补货策略参数表)
    │   ├── 新增: 网络有但策略表无的品仓
    │   ├── 删除: 策略表有但网络无的品仓
    │   └── 更新: 交集品仓的计划员
    │
    ▼ init() — 关联 product_warehouse_config 获取配置
    │
replm_initial_dstr_sku_wh_input (品仓初始化输入表)
    │
    ▼ runProcess() — 触发 Dolphin (202096) BDP计算
    │
    ▼ stockSugCalculate() — 关联 product_warehouse_config
    │
replm_inv_param_sku_wh_input (库存参数输入表)
    │
    ▼ findStockSugCalculateDto() — 触发 Dolphin (202016) BDP计算
    │
dws_replm_sku_wh_inv_param_output (BDP计算结果)
    │
    ▼ taskAfterProcess() — 按batchId分批回写
    │
drp_replenishment_strategy_parameters (更新建议补货点/目标/圆整规则/安全库存)
```

---

## 诊断逻辑

### 1. 补货策略参数缺失 — SepReplenishmentStrategyServiceImpl.init()

**源码位置**: `scpdrp-biz/.../service/suggest/impl/SepReplenishmentStrategyServiceImpl.java`
**Spring Bean**: `sepReplenishmentStrategyService`

**触发链路**: Dolphin(202096) → `InitReplenishmentDataServiceImpl.manualTaskBeforeProcess()` → `sepReplenishmentStrategyService.init(batchId)`

**诊断步骤**:

1. **检查补货网络定义是否存在**:
   ```sql
   SELECT id, sku_code, dest_warehouse_code, source_warehouse_code, tenant_id, deleted
   FROM drp_sku_warehouse_network
   WHERE sku_code = '${skuCode}' AND dest_warehouse_code = '${warehouseCode}';
   ```
   - 记录不存在 → 补货网络未定义，需先配置网络
   - `deleted=true` → 网络已删除

2. **检查商品是否有效且参与补货**:
   ```sql
   SELECT sku_code, replenish_flag, status
   FROM drp_sku_detail
   WHERE sku_code = '${skuCode}';
   ```
   - `findReplenishListByCode` 只返回有效且参与补货的商品
   - 商品不存在或 `replenish_flag` 为否 → 品仓被过滤掉

3. **检查目的仓是否有效**:
   ```sql
   SELECT warehouse_code, status
   FROM drp_warehouse
   WHERE warehouse_code = '${warehouseCode}';
   ```
   - 仓库不存在或状态无效 → 品仓被过滤掉

4. **检查品仓配置是否命中**:
   ```sql
   SELECT id, sku_code, warehouse_code, plan_id
   FROM product_warehouse_config
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}';
   ```
   - `findReplenishListBySkuCodeAndWhCode` 查不到配置 → 品仓初始化输入表不会生成
   - `plan_id` 为空 → 品仓初始化输入表不会生成

5. **检查策略参数表是否有数据**:
   ```sql
   SELECT id, sku_code, warehouse_code, planner, suggested_replenishment_point,
          suggested_replenishment_target, target_value_last_update_time
   FROM drp_replenishment_strategy_parameters
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}';
   ```
   - 记录不存在 → init()的updateBatchValidData()未执行或该品仓在新增列表中缺失

**常见问题**:
- 补货网络未定义 → 需在 `drp_sku_warehouse_network` 中配置
- 商品未标记参与补货 → `drp_sku_detail.replenish_flag` 需为是
- 仓库状态无效 → `drp_warehouse.status` 需为有效
- 品仓配置未命中 → `product_warehouse_config` 缺少对应记录或 `plan_id` 为空
- 数据源上下文丢失 → 代码中有 `DataSourceContextHolder.peek() == null` 的补偿逻辑

---

### 2. 库存参数输入表未生成 — SepReplenishmentStrategyServiceImpl.stockSugCalculate()

**源码位置**: `scpdrp-biz/.../service/suggest/impl/SepReplenishmentStrategyServiceImpl.java`
**Spring Bean**: `sepReplenishmentStrategyService`

**触发链路**: Dolphin(202016) → `SepWhStockSugCalculateServiceImpl.manualTaskBeforeProcess()` → `stockSugCalculate(batchId)` 或 `stockSugUpdate(reqVOList, batchId)`

**诊断步骤**:

1. **检查策略参数表是否有数据**（stockSugCalculate的前置条件）:
   ```sql
   SELECT COUNT(*) FROM drp_replenishment_strategy_parameters
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}';
   ```
   - 无数据 → 先排查 init() 是否执行成功

2. **检查库存参数输入表是否生成**:
   ```sql
   SELECT id, batch_id, sku_code, warehouse_code, create_time
   FROM replm_inv_param_sku_wh_input
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}'
   ORDER BY id DESC LIMIT 10;
   ```
   - 无数据 → 品仓配置未命中（`findReplenishListBySkuCodeAndWhCode` 返回空）或 `product_warehouse_config` 不存在

3. **按batchId检查**:
   ```sql
   SELECT COUNT(*) FROM replm_inv_param_sku_wh_input WHERE batch_id = '${batchId}';
   ```
   - 0条 → 计算流程未执行或全部品仓配置未命中

**常见问题**:
- 策略参数表无数据 → 先执行 init()
- 品仓配置未命中 → `product_warehouse_config` 缺少记录
- `plan_id` 为空 → `HitConfigDTO.planId` 为null，跳过生成

---

### 3. 建议补货点/目标未更新 — SepWhStockSugCalculateServiceImpl.taskAfterProcess()

**源码位置**: `scpdrp-biz/.../handle/suggest/SepWhStockSugCalculateServiceImpl.java`
**Spring Bean**: `sepWhStockSugCalculateService`

**触发链路**: Dolphin(202016)回调 → `DolphinTaskCheckServiceImpl` → `taskAfterProcess()`

**诊断步骤**:

1. **检查BDP计算结果是否返回**:
   ```sql
   SELECT id, batch_id, sku_code, warehouse_code, replm_id,
          sug_fill_point_day, sug_fill_point_qty,
          sug_fill_target_day, sug_fill_target_qty,
          round_rule, safety_stock_qty, create_time
   FROM dws_replm_sku_wh_inv_param_output
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}'
   ORDER BY id DESC LIMIT 10;
   ```
   - 无数据 → BDP计算未完成或未输出结果
   - 有数据但 `replm_id` 为空 → BDP侧未关联到策略参数ID

2. **按batchId检查BDP结果**:
   ```sql
   SELECT COUNT(*) FROM dws_replm_sku_wh_inv_param_output WHERE batch_id = '${batchId}';
   ```
   - 0条 → BDP计算未完成

3. **检查策略参数是否已更新**:
   ```sql
   SELECT id, suggested_replenishment_point, suggested_replenishment_target,
          coordination_replenishment_point_day, coordination_replenishment_target_day,
          round_rule, safety_stock_qty, target_value_last_update_time
   FROM drp_replenishment_strategy_parameters
   WHERE sku_code = '${skuCode}' AND warehouse_code = '${warehouseCode}';
   ```
   - `target_value_last_update_time` 为空或过旧 → taskAfterProcess()未执行
   - `suggested_replenishment_point` 为0 → BDP结果中 `sug_fill_point_qty` 为null（代码用 `orElse(BigDecimal.ZERO)` 兜底）

4. **检查Dolphin实例状态**（taskAfterProcess的前置条件）:
   ```sql
   SELECT id, flow_status, retry_status, process_fail_msg
   FROM sys_dolphin_instance
   WHERE project_code = '2715' AND definition_code = '202016'
   ORDER BY id DESC LIMIT 5;
   ```
   - `flow_status≠1(SUCCESS)` → Dolphin任务未成功完成
   - `retry_status=0` → 后置处理未执行
   - `retry_status<0` → 后置处理失败，检查 `process_fail_msg`

**常见问题**:
- BDP计算未完成 → 检查Dolphin实例状态
- `replm_id` 不匹配 → BDP输出的 `replm_id` 与 `drp_replenishment_strategy_parameters.id` 无法对应
- taskAfterProcess未执行 → DolphinTaskCheckServiceImpl未运行或serviceName配置错误
- batchId为空 → param中缺少 `batch_id`，taskAfterProcess直接return

---

## 诊断决策树

```
补货策略问题?
├── 策略参数缺失?
│   ├── 查 drp_sku_warehouse_network → 网络定义存在?
│   │   ├── 否 → 配置补货网络定义
│   │   └── 是 → 商品有效且参与补货? 仓库有效?
│   │       ├── 否 → 修正商品/仓库状态
│   │       └── 是 → 查 product_warehouse_config → 配置命中且plan_id非空?
│   │           ├── 否 → 配置品仓配置
│   │           └── 是 → 检查init()是否执行（Dolphin 202096实例状态）
│   └── → SepReplenishmentStrategyServiceImpl.init()
│
├── 库存参数输入表未生成?
│   ├── 查 drp_replenishment_strategy_parameters → 策略参数存在?
│   │   ├── 否 → 先排查init()
│   │   └── 是 → 查 product_warehouse_config → 品仓配置命中?
│   │       ├── 否 → 配置品仓配置
│   │       └── 是 → 检查stockSugCalculate()是否执行（Dolphin 202016实例状态）
│   └── → SepReplenishmentStrategyServiceImpl.stockSugCalculate()
│
├── 建议补货点/目标未更新?
│   ├── 查 dws_replm_sku_wh_inv_param_output → BDP结果存在?
│   │   ├── 否 → BDP计算未完成，检查Dolphin实例
│   │   └── 是 → replm_id能对应策略参数?
│   │       ├── 否 → BDP侧replm_id映射问题
│   │       └── 是 → 检查taskAfterProcess()是否执行（retry_status）
│   └── → SepWhStockSugCalculateServiceImpl.taskAfterProcess()
│
└── Dolphin任务未触发?
    ├── 查 sys_dolphin_instance → 实例存在?
    │   ├── 否 → 检查 sys_dolphin_job_cron 和 sys_dolphin_config
    │   └── 是 → flow_status和retry_status?
    └── → 参考 dolphin-task-diagnose skill
```

## Service 触发机制

| 入口方法 | 触发方式 | Dolphin Code | 说明 |
|---------|---------|-------------|------|
| `InitReplenishmentDataServiceImpl.manualTaskBeforeProcess()` | Dolphin(202096)前置 | 202096 | 补货+供货品仓初始化 |
| `InitReplenishmentDataServiceImpl.jobTaskProcess()` | Dolphin(202096)执行 | 202096 | 触发补货runProcess |
| `SepWhStockSugCalculateServiceImpl.jobTaskProcess()` | Dolphin(202016)定时 | 202016 | 全量库存建议计算 |
| `SepWhStockSugCalculateServiceImpl.manualTaskBeforeProcess()` | Dolphin(202016)手动 | 202016 | 部分/全量库存建议计算 |
| `SepWhStockSugCalculateServiceImpl.taskAfterProcess()` | Dolphin(202016)回调 | 202016 | BDP结果回写策略参数 |

**batchId 前缀**:
- 补货库存建议计算: `BatchNoUtils.build("BH")` → batchId以BH开头
- 补货品仓初始化: 由外部传入（通常来自Dolphin param中的 `batch_id`）
