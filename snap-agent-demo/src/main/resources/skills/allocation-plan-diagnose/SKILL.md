---
name: allocation-plan-diagnose
description: 云策航材备件计划系统调拨计划生成问题分析。Use when investigating why a specific SKU (with optional warehouse) did not generate an allocation plan entry. Triggers on: 调查/排查/分析/查某sku为什么没有/未生成调拨计划/调拨建议, 调拨计划缺失, 调拨建议单没有数据, drp_allocation_plan未生成. Also triggers when user mentions 调拨计划生成/调拨建议/基地平衡算法 and a specific skuCode or warehouse.
---

# 云策航材备件计划系统 — 调拨计划生成问题分析

调查某个 SKU(+仓可选) 未生成调拨建议单条目的结构化排查流程。

## 适用场景

- 某个 skuCode 未出现在 `drp_allocation_plan` 表（运行平衡算法后）
- 某个 skuCode+仓库组合 未生成调拨建议
- 全量/部分运行平衡算法后数据缺失
- 调拨计划生成Job后置处理(taskAfterProcess)数据遗漏

## 不适用场景

- 调拨建议单的计算结果错误（平衡后可用天数不对等）→ 应走 `/diagnose` 技能
- 调拨下发/调拨单的业务逻辑问题 → 应走 `/diagnose` 技能
- 补货策略参数表(work_flow=3)未生成 → 应走 `/replenishment-strategy-diagnose` 技能

---

## Phase 1: 收集信息

向用户确认以下信息（缺失项必须主动询问，不可假设）：

| 信息项 | 必需? | 说明 |
|--------|-------|------|
| `skuCode` | **必需** | 要排查的件号编码 |
| `warehouseCode` | 可选 | 如果只看某个仓(调出仓或调入仓)，提供仓库编码；不提供则排查该SKU所有仓 |
| `环境` | **必需** | prod / sit / uat — 决定查询方式 |
| `tenantId` | 可选 | 多租户场景需要；不提供则默认当前租户 |
| `运行时间范围` | 可选 | 最近一次运行算法的时间范围，帮助定位batchId |

> **环境确认特别提示**: 如果用户未明确指定环境，**必须主动询问**。
> - 用户说"生产"/"线上"/"prod" → `env=prod`
> - 用户说"sit"/"uat"/"测试环境" → `env=sit` 或 `env=uat`
> - 用户说"本地"/"开发" → 建议走代码分析而非数据查询

---

## Phase 2: 确定查询方式

根据环境选择查询方式：

### 环境为 prod（生产）

**仅提供 SQL 语句，不直接查询数据库**。每个 SQL 前附带提示：

```
⚠️ 生产环境 SQL — 请在数据库管理工具(Navicat/DBeaver)中执行，建议：
1. 所有查询加 LIMIT 限制，避免大表全表扫描
2. SELECT 语句只读，绝不执行 DELETE/UPDATE
3. 查询前确认租户ID，避免跨租户数据泄露
```

SQL 中的 `{skuCode}` / `{warehouseCode}` / `{tenantId}` 替换为用户提供的值。

### 环境为 sit 或 uat

**查询优先级**: MySQL MCP > mysql CLI > 手动SQL

按以下顺序尝试，找到可用方式即停止：

#### Step 1: 检查 MySQL MCP 是否可用

检查项目中是否有 `.mcp.json` 配置文件，以及 MCP 工具是否可用：

1. 检查项目根目录是否存在 `.mcp.json`
2. 搜索可用工具中是否有 `mcp__mysql-sit__` 或 `mcp__mysql-uat__` 开头的工具

**如果 MCP 可用** → 直接使用 MCP 工具查询，跳到 Phase 3

| 环境 | MCP 服务名 | 工具名格式 |
|--------|-----------|-----------|
| SIT | `mysql-sit` | `mcp__mysql-sit__query` |
| UAT | `mysql-uat` | `mcp__mysql-uat__query` |

> ⚠️ MCP 查询时注意：只执行 SELECT 查询，绝不执行写操作。

**如果 MCP 不可用** → 进入 Step 2

#### Step 2: 检查 mysql CLI 是否可用

```bash
which mysql 2>/dev/null && mysql --version 2>/dev/null
```

**如果 mysql CLI 可用** → 通过 Bash 工具执行查询：

| 环境 | Host | Port | Database | Username | Password |
|------|------|------|----------|----------|----------|
| SIT | `mengsit-m.db-inn.sfcloud.local` | `3306` | `scpdrp` | `scpdrp` | `Scpdrp#1219` |
| UAT | `mengsit-m.db-inn.sfcloud.local` | `3306` | `scpdrp` | `scpdrp` | `Scpdrp#1219` |

> 配置来源: `scpdrp-starter/src/main/resources/application-sit.yml` / `application-uat.yml`

UAT 查询示例：
```bash
mysql -h mengsit-m.db-inn.sfcloud.local -P 3306 -u scpdrp -p'Scpdrp#1219' scpdrp -e "SELECT ..." 2>/dev/null
```

**如果 mysql CLI 也不可用** → 进入 Step 3

#### Step 3: 安装 MySQL MCP（自动安装）

当 MCP 和 CLI 均不可用时，**主动帮用户安装 MySQL MCP Server**，步骤如下：

**3.1 安装 npm 包**

```bash
npm install -g mysql-mcp-server
```

> `mysql-mcp-server` 只允许 SELECT/SHOW/DESCRIBE/EXPLAIN 查询，安全可靠。

**3.2 创建项目级 `.mcp.json` 配置**

在项目根目录创建 `.mcp.json`，配置 SIT 和 UAT 两个 MySQL MCP 服务：

```json
{
  "mcpServers": {
    "mysql-sit": {
      "command": "npx",
      "args": ["-y", "mysql-mcp-server"],
      "env": {
        "MYSQL_HOST": "mengsit-m.db-inn.sfcloud.local",
        "MYSQL_PORT": "3306",
        "MYSQL_USER": "scpdrp",
        "MYSQL_PASSWORD": "Scpdrp#1219",
        "MYSQL_DATABASE": "scpdrp"
      }
    },
    "mysql-uat": {
      "command": "npx",
      "args": ["-y", "mysql-mcp-server"],
      "env": {
        "MYSQL_HOST": "mengsit-m.db-inn.sfcloud.local",
        "MYSQL_PORT": "3306",
        "MYSQL_USER": "scpdrp",
        "MYSQL_PASSWORD": "Scpdrp#1219",
        "MYSQL_DATABASE": "scpdrp"
      }
    }
  }
}
```

**3.3 重启 Claude Code 会话**

MCP 配置需要重启会话才能生效。提示用户：
> "MySQL MCP 已配置完成，需要重启 Claude Code 会话才能加载 MCP 工具。请退出当前会话后重新启动，然后继续排查。"

**3.4 不可安装时的回退方案**

如果 npm 安装失败（网络/权限等问题），回退到提供 SQL + 提示用户在 Navicat/DBeaver 中手动执行。

> ⚠️ SIT/UAT 环境也需要注意：只执行 SELECT 查询，绝不执行写操作。

---

## Phase 3: 逐层排查

调拨计划生成的数据链路为：

```
补货策略参数(work_flow=3) → 算法输入(dws_alg_allocation_input) → BDP算法平台 → 算法输出(dws_alg_allocation_output) → Job后置处理 → 调拨建议单(drp_allocation_plan)
```

按照四层排查链，**逐层推进，每层只查到根因即停止**，不盲目全查。

### Layer 0: 确认最近的算法运行状态

**先确认最近一次算法运行是否成功**，避免在算法根本没跑成功的情况下逐层排查。

```sql
-- 查询最近算法运行记录(sys_batch_log)
SELECT bl.id, bl.batch_id, bl.start_params, bl.status, bl.create_time, bl.update_time
FROM sys_batch_log bl
WHERE bl.start_params LIKE '%ALLOCATION%'
   OR bl.batch_id LIKE 'AP-%'
ORDER BY bl.create_time DESC
LIMIT 10;
```

**结果判断**:
- ✅ 最近有成功记录 → 继续排查 Layer 1
- ❌ 最近无记录或全部失败 → **根因找到**: 算法没有成功运行，需要先确保算法能正常运行
- ❌ 状态为 RUNNING → 算法正在运行中，需等待完成后再排查

如果找到了最近的 batchId，后续所有查询都使用该 batchId。

---

### Layer 1: 调拨建议单是否存在该品仓？

```sql
-- 查询 drp_allocation_plan 表
SELECT id, plan_no, type, status, src_warehouse_code, dest_warehouse_code,
       sku_code, sug_allocation_qty, plan_allocation_qty,
       allocation_type, batch_id, algorithm_task_id, generate_date, tenant_id
FROM drp_allocation_plan
WHERE sku_code = '{skuCode}'
  AND (src_warehouse_code = IF('{warehouseCode}' = '', src_warehouse_code, '{warehouseCode}')
       OR dest_warehouse_code = IF('{warehouseCode}' = '', dest_warehouse_code, '{warehouseCode}'))
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
ORDER BY generate_date DESC
LIMIT 20;
```

**结果判断**:
- ✅ **存在记录** → 调拨建议单已生成，问题可能是：
  - 数量不正确 → 超出本技能范围
  - 状态不对 → 超出本技能范围
  - 某个特定仓组合缺失 → 继续排查 Layer 2
- ❌ **不存在** → 继续排查 Layer 2

---

### Layer 2: 算法输出表是否存在该品仓？

```sql
-- 查询 dws_alg_allocation_output 表
SELECT id, out_warehouse_code, in_warehouse_code, sku_code,
       transfer_qty, allocation_type, algorithm_task_id, batch_id, tenant_id, create_time
FROM dws_alg_allocation_output
WHERE sku_code = '{skuCode}'
  AND (out_warehouse_code = IF('{warehouseCode}' = '', out_warehouse_code, '{warehouseCode}')
       OR in_warehouse_code = IF('{warehouseCode}' = '', in_warehouse_code, '{warehouseCode}'))
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
ORDER BY create_time DESC
LIMIT 20;
```

**结果判断**:
- ✅ **存在记录** → 算法输出了该SKU，但Job后置处理没有生成调拨建议单。
  → **排查方向**: 检查Job后置处理是否报错、是否因为clearOldAllocationPlans删除了记录、或是否因为SKU主数据缺失导致构建失败
  → 检查 sys_batch_log 的错误信息、检查 drp_sku_detail 是否有该 SKU

```sql
-- 检查SKU主数据是否存在
SELECT sku_code, sku_name, sku_name_en, chapter_no, lv1_clssf_code, inventory_uom
FROM drp_sku_detail
WHERE sku_code = '{skuCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
LIMIT 5;
```

**主数据结果判断**:
- ❌ drp_sku_detail 中不存在 → **根因找到**: SKU主数据缺失，Job后置处理无法关联件号名称等信息，可能导致构建失败
- ✅ drp_sku_detail 中存在 → Job处理可能正常但数据被删除了，检查是否有旧数据清理逻辑

- ❌ **算法输出不存在** → 继续排查 Layer 3

---

### Layer 3: 算法输入表是否存在该SKU？

```sql
-- 查询 dws_alg_allocation_input 表
SELECT id, warehouse_code, sku_code, batch_id, tenant_id, create_time
FROM dws_alg_allocation_input
WHERE sku_code = '{skuCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
ORDER BY create_time DESC
LIMIT 20;
```

**结果判断**:
- ✅ **存在记录** → 算法输入了该SKU，但算法平台没有输出结果。
  → **根因方向**:
    1. 算法平台内部处理逻辑未为该SKU生成调拨建议（例如：该SKU在所有仓库存充足，不需要调拨）
    2. 算法平台运行异常，数据丢失
    3. 需联系算法团队确认该SKU的算法计算情况

  → 验证"算法认为不需要调拨"的可能性：
```sql
-- 检查该SKU在算法输出中是否有transfer_qty=0的记录（算法可能判定无需调拨但仍输出了记录）
SELECT id, out_warehouse_code, in_warehouse_code, sku_code,
       transfer_qty, allocation_type, batch_id
FROM dws_alg_allocation_output
WHERE sku_code = '{skuCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
ORDER BY create_time DESC
LIMIT 20;
```

  如果 transfer_qty=0 的记录存在 → 算法判定该SKU无需调拨（**根因**: 件号在各仓库存充足，无调拨需求）

- ❌ **算法输入不存在** → 继续排查 Layer 4

---

### Layer 4: 为什么该SKU未被写入算法输入表？

根据运行类型分别排查：

#### 4A: 全量运行(task_type=FULL)排查

全量运行时，算法输入来自补货策略参数(work_flow=3)的scope范围。

**检查 1: 补货策略参数(work_flow=3)是否存在？**

```sql
SELECT id, sku_code, warehouse_code, work_flow, inventory_strategy, service_level,
       replenishment_calendar, planner, service_primary_key, tenant_id
FROM drp_replenishment_strategy_parameters
WHERE sku_code = '{skuCode}'
  AND work_flow = 3
  AND warehouse_code = IF('{warehouseCode}' = '', warehouse_code, '{warehouseCode}')
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
LIMIT 20;
```

**结果判断**:
- ❌ **不存在** → **根因找到**: 补货策略参数表中没有该SKU的调拨类型(work_flow=3)条目。
  → 需要先排查为什么该SKU未生成 work_flow=3 的策略条目，使用 `/replenishment-strategy-diagnose` 技能
- ✅ **存在** → 继续检查 2

**检查 2: 调拨网络定义是否满足？**

```sql
-- 检查调拨网络定义(vw_drp_sku_stock_network_scope)
SELECT sku_code, whs_code, tenant_id
FROM vw_drp_sku_stock_network_scope
WHERE sku_code = '{skuCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
LIMIT 20;
```

如果视图不存在，退回使用源表：

```sql
-- 检查调拨网络定义(drp_sku_stock_network)
SELECT sku_code, whs_code, tenant_id
FROM drp_sku_stock_network
WHERE sku_code = '{skuCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
LIMIT 20;
```

**结果判断**:
- ❌ **不存在** → **根因找到**: 调拨网络定义(`drp_sku_stock_network`)中未包含该SKU。需在调拨网络定义中新增。
- ✅ **存在但 whs_code 中不包含目标仓库** → **根因找到**: 调拨网络的 whs_code 字段不包含该仓库编码，该SKU与目标仓之间没有调拨路径
- ✅ **存在且包含目标仓库** → 继续检查 3

**检查 3: 商品是否参与调拨 (transfer_flag)?**

```sql
SELECT a.sku_code, a.replenishment_flag, a.transfer_flag,
       b.replenishment_flag as extend_replenishment_flag, b.transfer_flag as extend_transfer_flag,
       IF(b.id IS NOT NULL, b.replenishment_flag, a.replenishment_flag) as effective_replenishment_flag,
       IF(b.id IS NOT NULL, b.transfer_flag, a.transfer_flag) as effective_transfer_flag
FROM drp_sku_detail a
LEFT JOIN drp_sku_detail_extend b ON a.sku_code = b.sku_code AND a.tenant_id = b.tenant_id
WHERE a.sku_code IN ('{skuCode}', '{substitute_skuCode_if_any}')
  AND a.tenant_id = IF('{tenantId}' = '', a.tenant_id, '{tenantId}')
LIMIT 10;
```

**结果判断**:
- `effective_transfer_flag = 1` → ✅ 调拨标记满足
- `effective_transfer_flag != 1` → **根因找到**: 商品未标记参与调拨。需在商品详情或扩展表中设置 `transfer_flag=1`

**检查 4: 仓库是否有效？**

```sql
SELECT warehouse_code, warehouse_name, warehouse_status, warehouse_type, warehouse_category, tenant_id
FROM drp_warehouse
WHERE warehouse_code = '{warehouseCode}'
  AND tenant_id = IF('{tenantId}' = '', tenant_id, '{tenantId}')
LIMIT 10;
```

**结果判断**:
- `warehouse_status = 1` → ✅ 仓库有效
- `warehouse_status != 1` → **根因找到**: 仓库状态非有效(`warehouse_status={actual_value}`)。需在仓库管理中启用该仓库。

#### 4B: 部分运行(task_type=PARTIAL)排查

部分运行时，SKU范围来自用户选择的筛选条件，通过 `findAllocCalcSkuCodeList` 查询。

**检查 1: 用户的筛选条件是否包含该SKU？**

需要根据用户提供的筛选条件模拟 `findAllocCalcSkuCodeList` 查询：

```sql
-- 模拟部分运行的SKU筛选（根据用户提供的筛选条件组合查询）
SELECT DISTINCT drsp.sku_code
FROM drp_replenishment_strategy_parameters drsp
LEFT JOIN drp_sku_detail sku ON drsp.sku_code = sku.sku_code AND drsp.tenant_id = sku.tenant_id
WHERE drsp.work_flow = 3
  AND drsp.tenant_id = IF('{tenantId}' = '', drsp.tenant_id, '{tenantId}')
  -- 以下为筛选条件，根据用户实际提供的条件启用
  AND (drsp.sku_code IN ('{skuCode}'))  -- 如果提供了skuCodeList
  AND (sku.lv1_clssf_code IN ('{物料组}'))  -- 如果提供了lv1ClssfCode
  AND (sku.chapter_no IN ('{章节号}'))  -- 如果提供了chapterNo
  AND (sku.sku_type_master IN ({物料类型}))  -- 如果提供了skuTypeMaster
  AND (sku.is_spare_list IN ('{清单号}'))  -- 如果提供了spareListNo
LIMIT 50;
```

**结果判断**:
- ❌ **筛选结果中不包含该SKU** → **根因找到**: 用户的筛选条件未覆盖该SKU。需调整筛选条件或使用全量运行。
- ✅ **包含该SKU** → 继续排查该SKU是否因其他条件被排除

#### 4C: 模拟全量输入生成逻辑（当上述单项检查都通过但输入表仍未写入时）

```sql
WITH transfer_skus AS (
    SELECT DISTINCT a.tenant_id, a.sku_code,
        IFNULL(b.replenishment_flag, a.replenishment_flag) as replenishment_flag,
        IFNULL(b.transfer_flag, a.transfer_flag) as transfer_flag
    FROM drp_sku_detail a
    LEFT JOIN drp_sku_detail_extend b ON a.tenant_id = b.tenant_id AND a.sku_code = b.sku_code
    WHERE IFNULL(b.transfer_flag, a.transfer_flag) = 1
),
allocation_parameters AS (
    SELECT DISTINCT 3 as work_flow, drsp.sku_code, drsp.warehouse_code, drsp.tenant_id
    FROM drp_replenishment_strategy_parameters drsp
    INNER JOIN transfer_skus ts ON drsp.sku_code = ts.sku_code AND drsp.tenant_id = ts.tenant_id
    WHERE drsp.work_flow = 3
),
allocation_network_scope AS (
    SELECT o.sku_code, o.warehouse_code, o.tenant_id
    FROM vw_drp_sku_stock_network_scope o
    INNER JOIN transfer_skus ts ON o.sku_code = ts.sku_code AND ts.tenant_id = o.tenant_id
)
SELECT ap.sku_code, ap.warehouse_code, ap.tenant_id, ns.sku_code as network_sku
FROM allocation_parameters ap
LEFT JOIN allocation_network_scope ns ON ap.sku_code = ns.sku_code AND ap.tenant_id = ns.tenant_id
WHERE ap.sku_code = '{skuCode}'
  AND ap.warehouse_code = IF('{warehouseCode}' = '', ap.warehouse_code, '{warehouseCode}')
LIMIT 20;
```

**结果判断**:
- ✅ CTE 中能找到该品仓 → 说明该SKU应被写入算法输入表，检查是否被漏写或在手工触发流程中丢失
- ❌ `network_sku IS NULL` → **根因找到**: 该SKU虽有策略参数但没有调拨网络定义，无法进入算法输入范围
- ❌ 整行不存在 → 回到 4A 各项检查定位具体缺失条件

---

## Phase 4: 生成诊断报告

排查完成后，输出结构化报告：

```markdown
## 调拨计划生成问题诊断报告

**排查时间**: {datetime}
**SKU**: {skuCode}
**仓库**: {warehouseCode or "全部"}
**环境**: {env}
**最近batchId**: {最近一次算法运行的batchId}

### 数据链路追踪

| 阶段 | 表 | 是否存在 | 说明 |
|------|----|----------|------|
| 算法运行 | sys_batch_log | ✅/❌ | |
| 调拨建议单 | drp_allocation_plan | ✅/❌ | |
| 算法输出 | dws_alg_allocation_output | ✅/❌ | |
| 算法输入 | dws_alg_allocation_input | ✅/❌ | |
| 策略参数(wf=3) | drp_replenishment_strategy_parameters | ✅/❌ | |
| 调拨网络 | drp_sku_stock_network | ✅/❌ | |
| 商品调拨标记 | drp_sku_detail.transfer_flag | ✅/❌ | |

### 诊断结果

**根因**: {根因描述}

**缺失层级**: {Layer 0 / Layer 1 / Layer 2 / Layer 3 / Layer 4}

**具体条件**: {哪个条件不满足}

### 修复建议

{具体的修复步骤}

### 验证方式

修复后重新运行平衡算法，然后用以下 SQL 验证：
{验证SQL}
```

---

## 根因速查表

| 现象 | 根因层级 | 可能原因 |
|------|---------|---------|
| 算法从未运行成功 | L0 | BDP调度任务未配置或运行失败 |
| 算法运行中(Redis锁占用) | L0 | 算法正在执行，需等待完成 |
| 调拨建议单无该SKU任何记录 | L2/L3/L4 | 算法输出或输入表中不存在该SKU |
| 算法输出有该SKU但调拨建议单无 | L2 | Job后置处理报错或SKU主数据缺失 |
| 算法输入有但输出无 | L3 | 算法判定无需调拨(库存充足) 或 算法平台内部异常 |
| 策略参数表无work_flow=3记录 | L4 | 该SKU未生成调拨类型策略条目，走 `/replenishment-strategy-diagnose` |
| 调拨网络未定义该SKU | L4 | drp_sku_stock_network未包含该SKU-仓组合 |
| 商品 transfer_flag != 1 | L4 | 商品未标记参与调拨 |
| 仓库 warehouse_status != 1 | L4 | 目的仓/来源仓状态非有效 |
| 部分运行筛选条件未覆盖 | L4(B) | 用户筛选条件未包含该SKU |

---

## 参考文档

- 需求规格: `docs/specs/13-allocation-plan/tdd_spec.md`
- 补货策略排查: `docs/skills/replenishment-strategy-diagnose/SKILL.md`
- 核心源码(Job后置处理): `scpdrp-biz/src/main/java/com/sf/scpdrp/biz/service/replm/impl/AllocationPlanGenerateServiceImpl.java`
- Job Handler: `scpdrp-biz/src/main/java/com/sf/scpdrp/biz/handle/replm/GenerateAllocationPlanServiceImpl.java`
- 页面触发服务: `scpdrp-biz/src/main/java/com/sf/scpdrp/biz/service/replm/impl/AllocationPlanServiceImpl.java`
- 算法输出Mapper: `scpdrp-biz/src/main/resources/mapper/DwsAlgAllocationOutputMapper.xml`
- 算法输入Mapper: `scpdrp-biz/src/main/resources/mapper/DwsAlgAllocationInputMapper.xml`
- 策略参数Mapper: `scpdrp-biz/src/main/resources/mapper/DrpReplenishmentStrategyParametersMapper.xml` (findAllocCalcSkuCodeList)
- 补货策略分析: `docs/specs/08-strategy-parameter/replenishment-strategy-parameters-insert-analysis.md`
- SIT配置: `scpdrp-starter/src/main/resources/application-sit.yml`
- UAT配置: `scpdrp-starter/src/main/resources/application-uat.yml`
