# SKU-Warehouse Init Diagnose Reference

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
| `drp_sku_warehouse_network` | DrpSkuWarehouseNetwork | 补货网络定义 |
| `drp_supply_network` | DrpSupplyNetwork | 供货网络定义 |
| `drp_replenishment_strategy_parameters` | DrpReplenishmentStrategyParameters | 补货策略参数 |
| `drp_supply_strategy_parameters` | DrpSupplyStrategyParameters | 供货策略参数 |
| `replm_initial_dstr_sku_wh_input` | ReplmInitialDstrSkuWhInput | 补货品仓初始化输入表 |
| `supply_initial_dstr_sku_wh_input` | SupplyInitialDstrSkuWhInput | 供货品仓初始化输入表 |
| `product_warehouse_config` | ProductWarehouseConfig | 品仓配置 |
| `sys_dolphin_instance` | DataTaskInstance | Dolphin实例记录 |
| `sys_dolphin_config` | JobConfig | Dolphin任务配置 |

## Dolphin Definition Codes

| 配置项 | DefinitionCode | 说明 |
|--------|---------------|------|
| `dolphin.replmInitialDstrSkuWhInput` | 202096 | 补货品仓数据初始化 |
| `dolphin.supplyInitialDstrSkuWhInput` | 18861343825824 | 供货品仓数据初始化 |

## Dual-Track Flow

品仓初始化是一个**双轨编排**过程，`InitReplenishmentDataServiceImpl` 同时驱动补货和供货两条链路：

```
Dolphin (202096) 触发
    │
    ▼ manualTaskBeforeProcess() — 前置处理
    │
    ├─── 补货轨道 ────────────────────────────────────┐
    │    SepReplenishmentStrategyService.init(batchId) │
    │    1. 查 drp_sku_warehouse_network                │
    │    2. 过滤有效品仓（商品有效+参与补货+仓库有效）  │
    │    3. 增删改 drp_replenishment_strategy_parameters│
    │    4. 关联 product_warehouse_config               │
    │    5. 生成 replm_initial_dstr_sku_wh_input        │
    │                                                    │
    ├─── 供货轨道 ────────────────────────────────────┐│
    │    CenterSupplyStrategyService.init(batchId)     ││
    │    1. 查 drp_supply_network                       ││
    │    2. 过滤有效品仓（商品有效+参与补货+仓库有效）  ││
    │    3. 增删改 drp_supply_strategy_parameters       ││
    │    4. 关联 product_warehouse_config               ││
    │    5. 生成 supply_initial_dstr_sku_wh_input       ││
    │                                                    ││
    ▼ jobTaskProcess() — 执行阶段                      ││
    │    sepReplenishmentStrategyService.runProcess()   ││
    │    → 触发 Dolphin (202096) BDP计算                ││
    │                                                    ││
    ▼ taskAfterProcess() — 后置处理（空实现）            ││
    └────────────────────────────────────────────────────┘┘
```

---

## 诊断逻辑

### 1. 品仓初始化未执行 — InitReplenishmentDataServiceImpl

**源码位置**: `scpdrp-biz/.../handle/basic/InitReplenishmentDataServiceImpl.java`
**Spring Bean**: `initReplenishmentDataService`
**实现接口**: `JobProcessService`

**诊断步骤**:

1. **检查Dolphin实例状态（补货 202096）**:
   ```sql
   SELECT id, project_code, definition_code, instance_id, tenant_id,
          flow_status, retry_status, process_fail_msg, param,
          create_time, update_time
   FROM sys_dolphin_instance
   WHERE project_code = '2715' AND definition_code = '202096'
   ORDER BY id DESC LIMIT 10;
   ```
   - `flow_status=2` → 实例执行中，可能卡住
   - `flow_status=0` → 实例失败
   - `flow_status=1` → 实例成功，检查 `retry_status`
   - `retry_status=0` → 后置处理未执行（manualTaskBeforeProcess未触发）
   - `retry_status<0` → 后置处理失败

2. **检查Dolphin实例状态（供货 18861343825824）**:
   ```sql
   SELECT id, project_code, definition_code, instance_id, tenant_id,
          flow_status, retry_status, process_fail_msg, param,
          create_time, update_time
   FROM sys_dolphin_instance
   WHERE project_code = '2715' AND definition_code = '18861343825824'
   ORDER BY id DESC LIMIT 10;
   ```

3. **检查config配置是否存在**:
   ```sql
   SELECT project_code, definition_code, service_name, flow_type, platform
   FROM sys_dolphin_config
   WHERE project_code = '2715' AND definition_code IN ('202096', '18861343825824');
   ```
   - `service_name` 应为 `initReplenishmentDataService`
   - config不存在 → DolphinTaskCheckServiceImpl会报"未找到config"

4. **检查param中的batchId**:
   - 从实例的 `param` JSON中提取 `batch_id`
   - `batch_id` 为空 → `jobTaskProcess()`中 `param.get("batch_id")+""` 会变成 "null" 字符串

**常见问题**:
- Dolphin实例卡在执行中 → 参考 dolphin-task-diagnose skill
- `retry_status=0` → 后置处理未触发，检查 DolphinTaskCheckServiceImpl
- `service_name` 配置错误 → 后置处理调用错误的Bean
- `batch_id` 为null → param中缺少 `batch_id`，需检查Dolphin任务参数配置

---

### 2. 补货品仓初始化数据未生成 — SepReplenishmentStrategyServiceImpl.init()

**详细诊断请参考**: [replenishment-strategy-diagnose REFERENCE.md](../replenishment-strategy-diagnose/REFERENCE.md#1-补货策略参数缺失--sepreplenishmentstrategyserviceimplinit)

**快速检查**:
```sql
-- 按batchId检查补货品仓初始化输入表
SELECT COUNT(*) FROM replm_initial_dstr_sku_wh_input WHERE batch_id = '${batchId}';

-- 按skuCode检查补货策略参数
SELECT COUNT(*) FROM drp_replenishment_strategy_parameters WHERE sku_code = '${skuCode}';
```

---

### 3. 供货品仓初始化数据未生成 — CenterSupplyStrategyServiceImpl.init()

**详细诊断请参考**: [supply-strategy-diagnose REFERENCE.md](../supply-strategy-diagnose/REFERENCE.md#1-供应策略参数缺失--centersupplystrategyserviceimplinit)

**快速检查**:
```sql
-- 按batchId检查供货品仓初始化输入表
SELECT COUNT(*) FROM supply_initial_dstr_sku_wh_input WHERE batch_id = '${batchId}';

-- 按skuCode检查供货策略参数
SELECT COUNT(*) FROM drp_supply_strategy_parameters WHERE sku_code = '${skuCode}';
```

---

### 4. runProcess未触发 — InitReplenishmentDataServiceImpl.jobTaskProcess()

**触发链路**: Dolphin(202096) `jobTaskProcess()` → `sepReplenishmentStrategyService.runProcess(batchId)` → `startStockSigDolphin(projectCode, 202096, batchId)`

**诊断步骤**:

1. **检查jobTaskProcess是否执行**:
   - `jobTaskProcess` 在Dolphon实例 `flow_status=SUCCESS` 后由 `DolphinTaskCheckServiceImpl` 调用
   - 检查 `retry_status`: 0=未处理, 1=已处理, 负数=失败

2. **检查runProcess触发的子Dolphin实例**:
   ```sql
   SELECT id, flow_status, retry_status, param, create_time
   FROM sys_dolphin_instance
   WHERE project_code = '2715' AND definition_code = '202096'
     AND param LIKE '%${batchId}%'
   ORDER BY id DESC LIMIT 5;
   ```

3. **检查batchId是否正确传递**:
   - `jobTaskProcess` 中 `batchId = param.get("batch_id")+""`
   - 如果 `batch_id` 不在 param 中，batchId 变成 `"null"` 字符串

**常见问题**:
- `batch_id` 为 "null" 字符串 → param中缺少 `batch_id` 参数
- runProcess触发的新Dolphin实例失败 → 检查BDP侧任务状态
- 嵌套Dolphin调用超时 → 检查Dolphin平台超时配置

---

## 诊断决策树

```
品仓初始化问题?
├── 初始化完全未执行?
│   ├── 查 sys_dolphin_instance (202096) → 实例存在?
│   │   ├── 否 → 检查 sys_dolphin_job_cron 和 sys_dolphin_config
│   │   └── 是 → flow_status?
│   │       ├── 2(执行中) → 可能卡住，参考 dolphin-task-diagnose
│   │       ├── 0(失败) → 检查 flow_fail_msg
│   │       └── 1(成功) → retry_status?
│   │           ├── 0 → 后置处理未执行
│   │           └── 负数 → 后置处理失败，检查 process_fail_msg
│   └── → InitReplenishmentDataServiceImpl + dolphin-task-diagnose
│
├── 补货品仓数据未生成?
│   ├── 查 drp_sku_warehouse_network → 网络定义存在?
│   ├── 查 drp_replenishment_strategy_parameters → 策略参数存在?
│   ├── 查 replm_initial_dstr_sku_wh_input → 初始化输入表有数据?
│   └── → 参考 replenishment-strategy-diagnose skill
│
├── 供货品仓数据未生成?
│   ├── 查 drp_supply_network → 网络定义存在?
│   ├── 查 drp_supply_strategy_parameters → 策略参数存在?
│   ├── 查 supply_initial_dstr_sku_wh_input → 初始化输入表有数据?
│   └── → 参考 supply-strategy-diagnose skill
│
└── runProcess未触发?
    ├── 查 sys_dolphin_instance.retry_status → 后置处理状态?
    ├── 检查 param 中的 batch_id → 是否为 "null"?
    └── 检查 runProcess 触发的子 Dolphin 实例状态
```

## Service 触发机制

`InitReplenishmentDataServiceImpl` 实现了 `JobProcessService` 接口，通过 `sys_dolphin_config.service_name = initReplenishmentDataService` 注册：

| 方法 | 触发时机 | 说明 |
|------|---------|------|
| `manualTaskBeforeProcess()` | Dolphin实例创建后、执行前 | 执行补货+供货init()，生成策略参数和初始化输入表 |
| `jobTaskProcess()` | Dolphin实例执行中 | 调用runProcess()，触发补货BDP计算 |
| `taskAfterProcess()` | Dolphin实例完成后 | 空实现（无后置处理） |

**与其他Service的关系**:
| 被调用Service | 调用方法 | 用途 |
|--------------|---------|------|
| `sepReplenishmentStrategyService` | `init(batchId)` | 补货品仓初始化 |
| `sepReplenishmentStrategyService` | `runProcess(batchId)` | 触发补货Dolphin(202096) |
| `centerSupplyStrategyService` | `init(batchId)` | 供货品仓初始化 |

> 注意：`centerSupplyStrategyService.runProcess()` 不在 `InitReplenishmentDataServiceImpl` 中调用，而是由 `CenterSupplyStrategyServiceImpl.init()` 末尾注释标记（已移至 runProcess 方法）。供货品仓初始化的Dolphin触发由其他入口调用 `centerSupplyStrategyService.runProcess(batchId)`。
