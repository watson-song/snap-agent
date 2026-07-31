# 需求规格说明书 - 调拨计划模块 (Allocation Plan)

> 版本: 1.3  
> 模块: 13-allocation-plan  
> 对应Controller: AllocationPlanController (新建)  
> 对应Service: IAllocationPlanService / AllocationPlanServiceImpl (新建)  
> 对应Entity: AllocationPlan (新建)

---

## 1. 需求元信息

```yaml
需求ID: REQ-ALLOC-001
需求名称: 调拨计划页面与平衡调拨建议计算
优先级: P0
迭代: Sprint 6
负责人: DRP团队
状态: 评审中
```

### 1.1 背景与目标
- **业务背景**: 多基地之间存在库存不平衡，需要通过系统化的平衡算法计算调拨建议，实现基地间库存的优化调配。当前缺乏调拨建议的统一管理页面和自动化计算能力
- **用户价值**: 提供可视化的调拨建议页面，支持手工/定时运行多基地库存平衡算法，自动生成调拨建议和采购建议，减少人工经验判断的误差
- **成功指标**: 平衡算法计算准确率>95%，全量计算完成时间<10分钟，调拨建议下发成功率>99%

### 1.2 范围边界
- **包含**:
    - 调拨建议单列表页面（分页查询、筛选、导出）
    - 手工触发"运行平衡算法"（全量/勾选部分件号）
    - 定时任务"基地平衡推荐计算"（每天全量执行一次）
    - 算法日志记录与查询
    - 调拨建议单状态管理（待确认→下发中→已下发/下发失败）
    - 计划调拨数量手工调整及调整原因填写
    - 要求到货日期编辑
    - 来源仓/目的仓编码编辑（含去重校验）
    - 未来7天MR需求悬浮窗明细展示
    - 算法输出的采购建议写入"补货建议计算"，来源标记为"基地平衡"
    - 替代件号查询与切换（只更新件号字段，systemSkuCode不变）
    - 人工创建调拨建议单（手工录入+Excel批量导入）
    - 库存网络策略备注展示
- **不包含**:
    - 算法核心实现（由算法团队提供独立算法服务）
    - 实际调拨单执行与物流跟踪（由WMS负责）
    - 采购建议的后续执行流程

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解措施 | 负责人 |
|--------|------|------|------|----------|--------|
| R1 | 算法服务响应超时或不可用 | 中 | 高 | 算法异步调用+超时熔断+状态记录，前端轮询监控 | 算法团队 |
| R2 | 全量计算数据量大导致耗时长 | 高 | 中 | 分批计算+异步执行+Redis进度记录，每分钟监控运行结果 | 开发团队 |
| R3 | 替代件库存计算逻辑复杂 | 中 | 中 | 复用现有替代件库存计算逻辑（SepWhReplenish已实现） | 开发团队 |
| R4 | 库存数据实时性不足 | 中 | 高 | 计算时取最新库存快照，记录计算时间点 | 数据团队 |
| R5 | allocationType字段值为非数字文本 | 低 | 低 | getDescByTypeSafe方法处理NumberFormatException，保留原始文本 | 开发团队 |

**关键假设**:
- 算法团队按《多基地库存平衡与调拨算法设计文档_v1.0》提供算法API接口
- 库存网络配置、调拨网络定义、需求预测、库存策略等基础数据已存在
- 在途库存数据可从现有系统获取（调拨在途、采购在途）
- 替代关系数据已维护在product_substitute表中
- allocation_type字段值从算法输出(dws_alg_allocation_output)直接映射，值为"0"(正常分配)或"1"(缺货分配)，也可能为null或非数字文本

---

## 2. 用户故事 (User Stories)

### US-1: 调拨建议单列表查询
```gherkin
作为 调拨计划员
我希望 查看调拨建议单列表
以便 了解各件号的调拨建议情况并进行管理
```

**验收标准 (AC):**
```gherkin
AC1: 分页查询调拨建议单
  Given 系统存在调拨建议单数据
  When 调用 POST /allocationPlan/page
  Then 返回调拨建议单分页列表（每页最多3000行）
  And 支持按来源仓、目的仓、件号、建议单状态、替代件标识等条件筛选
  And 按更新时间倒序排列

AC2: 查询字段完整
  Given 调拨建议单存在
  When 查询调拨建议单详情
  Then 返回所有字段（详见4.4.1.2字段说明）
  And 包含来源仓编码/名称、目的仓编码/名称、件号信息、库存信息、在途信息等

AC3: 未来7天MR需求悬浮窗
  Given 调拨建议单的未来7天MR数量>0
  When 点击未来7天MR数量字段
  Then 悬浮窗展示MR需求明细列表
  And 明细包含：维修需求单号、需求日期、需求数量、需求站点
```

### US-2: 手工运行平衡算法
```gherkin
作为 调拨计划员
我希望 手工触发平衡算法计算
以便 获取最新的调拨建议结果
```

**验收标准 (AC):**
```gherkin
AC1: 全量运行平衡算法
  Given 用户未勾选任何数据
  When 点击"运行平衡算法"按钮
  Then 弹出确认框："是否全件号运行平衡算法？"
  And 用户确认后，调用算法服务异步执行
  And 创建算法任务记录，状态为"运行中"
  And 每分钟监控运行结果
  And 完成后保存调拨建议结果
  And 算法同时输出的采购建议写入"补货建议计算"，来源标记为"基地平衡"

AC2: 按条件运行平衡算法
  Given 用户选择了物料组、章节号、件号、物料类型或清单号等过滤条件（均为非必填）
  When 点击"运行平衡算法"按钮
  Then 多个条件取交集筛选参与运算的件号范围
  And 如果选择了清单号，则筛选该清单下的所有件号
  And 如果所有条件都未选择，则与全量运行范围一致
  And 用户确认后，仅用筛选后的件号参与平衡算法计算
  And 调用算法服务异步执行
  And 创建算法任务记录
  And 完成后仅更新筛选件号的调拨建议结果

AC3: 算法运行中防重复触发
  Given 存在运行中的算法任务（状态=运行中）
  When 用户再次点击"运行平衡算法"
  Then 提示"算法正在运行中，请稍后再试"
  And 不触发新的算法任务

AC4: 算法运行失败处理
  Given 算法执行失败
  When 监控到失败状态
  Then 更新任务状态为"失败"
  And 记录失败原因到算法日志
  And 通知用户（站内消息或前端提示）
```

### US-3: 定时任务自动计算
```gherkin
作为 系统管理员
我希望 系统每天自动执行基地平衡推荐计算
以便 持续获得最新的调拨建议
```

**验收标准 (AC):**
```gherkin
AC1: 定时任务触发
  Given 定时任务"基地平衡推荐计算"已配置
  When 到达定时任务执行时间
  Then 自动执行全件号平衡算法计算
  And 使用Redis分布式锁防止并发执行
  And 全量运算并保存调拨建议结果
  And 算法输出的采购建议写入"补货建议计算"

AC2: 定时任务失败重试
  Given 定时任务执行失败
  When 到达下次执行时间
  Then 正常执行，不受上次失败影响
  And 记录失败日志供排查
```

### US-4: 调拨建议单操作
```gherkin
作为 调拨计划员
我希望 对调拨建议单进行调整和下发
以便 确认并执行调拨计划
```

**验收标准 (AC):**
```gherkin
AC1: 编辑计划调拨数量
  Given 调拨建议单状态为"待确认"
  When 修改计划调拨数量
  Then 必须填写调整原因（必填）
  And 保存 planAllocationQty 和 adjustReason
  And 重新计算平衡后可用库存数量和平衡后可用天数

AC2: 编辑要求到货日期
  Given 调拨建议单状态为"待确认"
  When 修改要求到货日期
  Then 保存新的 requireArrivalDate
  And 默认值为次日+7天，可修改

AC3: 下发调拨建议单
  Given 调拨建议单状态为"待确认"或"待下发"
  When 调用"下发"操作
  Then 状态变更为"下发中"
  And 调用中台API下发调拨单
  And 根据响应：成功→"已下发"，失败→"下发失败"

AC4: 重新下发
  Given 调拨建议单状态为"下发失败"
  When 调用"下发"操作
  Then 状态变更为"下发中"
  And 重新调用中台API

AC5: 导出调拨建议单
  Given 查询条件已设置
  When 调用"导出"操作
  Then 按查询条件导出调拨建议单Excel
  And 包含所有展示字段

AC6: 确认调拨建议单
  Given 调拨建议单状态为"待确认"
  When 调用"确认"操作
  Then 状态变更为"待下发"
  And 待下发状态下不可编辑数量和日期

AC7: 撤销确认调拨建议单
  Given 调拨建议单状态为"待下发"
  When 调用"撤销确认"操作
  Then 状态变更为"待确认"
  And 待确认状态下可重新编辑数量和日期
```

### US-5: 算法日志查询
```gherkin
作为 系统管理员
我希望 查看平衡算法的运行日志
以便 监控算法执行情况和排查问题
```

**验收标准 (AC):**
```gherkin
AC1: 分页查询算法日志
  Given 存在算法运行日志
  When 调用 POST /allocationPlan/algorithmLog/page
  Then 返回算法日志分页列表
  And 包含：任务ID、运行时间、运行类型（全量/部分）、件号数量、开始时间、结束时间、耗时、状态、失败原因

AC2: 查看日志详情
  Given 算法日志存在
  When 查询日志详情
  Then 返回完整的算法运行日志内容
  And 包含输入参数摘要、输出结果统计
```

### US-6: 替代件号切换
```gherkin
作为 调拨计划员
我希望 将调拨建议单中的件号切换为替代件号
以便 根据实际库存可用性选择替代件执行调拨
```

**验收标准 (AC):**
```gherkin
AC1: 切换替代件号
  Given 调拨建议单存在且用户选择了目标替代件号
  When 调用 POST /allocationPlan/substitute/change
  Then 更新 skuCode/skuName/skuNameEn 为目标替代件信息
  And systemSkuCode 保持不变
  And 评估分析过程（库存、在途等）不变

AC2: 查询替代件列表
  Given 件号存在替代关系且目的仓有库存
  When 调用 GET /allocationPlan/substitute/list
  Then 返回该件号在目的仓的替代件列表及可用库存
```

### US-7: 人工创单
```gherkin
作为 调拨计划员
我希望 手工创建调拨建议单
以便 灵使用不在算法建议范围内的定制调拨需求
```

**验收标准 (AC):**
```gherkin
AC1: 单条手工创建
  Given 用户填写始发仓、目的仓、件号、计划数量等必填信息
  When 调用 POST /allocationPlan/manualCreate
  Then 创建一条 type=人工(1)、status=待确认(1) 的调拨建议单
  And planNo 使用 Redis 序列号生成（格式：AP-yyyyMMdd-xxxx）
  And systemSkuCode 与 skuCode 初始值相同

AC2: Excel模板下载
  Given 用户需要批量导入
  When 调用 POST /allocationPlan/manualCreate/downloadTemplate
  Then 返回包含必填项标记（*字段）的 Excel 模板文件

AC3: 批量导入
  Given 用户按模板填写了多条数据
  When 调用 POST /allocationPlan/manualCreate/upload 上传 Excel 文件
  Then 校验始发仓编码、目的仓编码、件号（在 drp_sku_detail 中存在）、计划数量（非负整数）
  And 校验通过的记录批量保存（saveBatch）
  And 返回成功/失败计数及失败明细
```

---

## 2.5 用户故事地图

| 用户阶段 | 用户故事 | 价值目标 | 衡量指标 | 依赖关系 |
|----------|----------|----------|----------|----------|
| 查看 | US-1 列表查询 | 了解调拨建议全貌 | 查询响应时间<1s | 基础数据 |
| 计算 | US-2 手工计算 | 按需获取最新建议 | 计算完成率>95% | US-1 |
| 自动化 | US-3 定时计算 | 持续更新建议 | 定时任务成功率>99% | US-2 |
| 执行 | US-4 调整与下发 | 确认并执行调拨 | 下发成功率>99% | US-1 |
| 监控 | US-5 日志查询 | 监控算法运行 | 日志可追溯 | US-2 |
| 扩展 | US-6 替代件切换 | 灵活切换件号 | 切换成功率>99% | US-1 |
| 扩展 | US-7 人工创单 | 手工定制调拨需求 | 导入校验通过率>95% | US-1 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 用例名称 | 优先级 | 对应AC | 测试类型 |
|--------|----------|--------|--------|----------|
| UC-AP-01 | 调拨建议单分页查询 | P0 | US-1 AC1/AC2 | 集成测试 |
| UC-AP-02 | 未来7天MR需求查询 | P1 | US-1 AC3 | 单元测试 |
| UC-AP-03 | 手工全量运行算法 | P0 | US-2 AC1 | 集成测试 |
| UC-AP-04 | 手工部分件号运行算法 | P0 | US-2 AC2 | 集成测试 |
| UC-AP-05 | 算法防重复触发 | P0 | US-2 AC3 | 单元测试 |
| UC-AP-06 | 算法失败处理 | P1 | US-2 AC4 | 集成测试 |
| UC-AP-07 | 定时任务自动计算 | P0 | US-3 AC1/AC2 | 集成测试 |
| UC-AP-08 | 编辑计划调拨数量 | P0 | US-4 AC1 | 单元测试 |
| UC-AP-09 | 编辑要求到货日期 | P1 | US-4 AC2 | 单元测试 |
| UC-AP-10 | 下发调拨建议单 | P0 | US-4 AC3/AC4 | 集成测试 |
| UC-AP-11 | 导出调拨建议单 | P1 | US-4 AC5 | 集成测试 |
| UC-AP-12 | 算法日志查询 | P1 | US-5 AC1/AC2 | 集成测试 |
| UC-AP-13 | 调拨单生成Job主执行 | P0 | 3.5.1 jobTaskProcess | 单元+集成 |
| UC-AP-14 | 调拨单生成Job前置处理 | P0 | 3.5.1 manualTaskBeforeProcess | 单元测试 |
| UC-AP-15 | 调拨单生成Job后置处理 | P0 | 3.5.1 taskAfterProcess | 单元+集成 |
| UC-AP-16 | 替代关系聚合计算 | P0 | 3.5.3 | 单元测试 |
| UC-AP-17 | 字段映射与转换 | P1 | 3.5.5 | 单元测试 |
| UC-AP-18 | 确认调拨建议单 | P0 | US-4 AC6 | 单元测试 |
| UC-AP-19 | 撤销确认调拨建议单 | P0 | US-4 AC7 | 单元测试 |
| UC-AP-20 | 查询替代件列表 | P1 | US-6 AC2 | 单元测试 |
| UC-AP-21 | 切换替代件号 | P0 | US-6 AC1 | 单元测试 |
| UC-AP-22 | 人工创建调拨建议单 | P0 | US-7 AC1 | 单元测试 |
| UC-AP-23 | 人工创单模板下载 | P1 | US-7 AC2 | 单元测试 |
| UC-AP-24 | 人工创单批量导入 | P0 | US-7 AC3 | 单元测试 |

### 3.2 调拨建议单状态机

```
待确认(1) --确认--> 待下发(2)
待下发(2) --撤销确认--> 待确认(1)
待确认(1) --下发--> 下发中(4)
待下发(2) --下发--> 下发中(4)
下发中(4) --下发成功--> 已下发(3)
下发中(4) --下发失败--> 下发失败(5)
下发失败(5) --重新下发--> 下发中(4)
下发失败(5) --回退--> 待确认(1)
```

### 3.3 平衡算法调用流程

```
用户点击"运行平衡算法"
    |
    +-- 未勾选数据 -> 提示"是否全件号运行平衡算法？" -> 确认 -> 全量计算
    |
    +-- 勾选N个件号 -> 提示"勾选N个件号运行平衡算法" -> 确认 -> 部分计算
         |
         v
    检查是否有运行中任务 (Redis锁: ALLOCATION_BALANCE_CALCULATE)
         |
         +-- 有 -> 提示"算法正在运行中"
         |
         +-- 无 -> 创建算法任务记录
              |
              v
         异步调用算法API
              |
              +-- 输入: 库存网络配置 + 调拨网络 + 需求预测 + 库存策略 + 在库库存 + 在途信息 + 替代关系 + 参数配置
              |
              +-- 输出: 调拨建议(件号/来源站点/目的站点/建议数量) + 采购建议(件号/站点/补货数量)
                   |
                   v
              保存调拨建议结果 -> AllocationPlan表
                   |
                   v
              采购建议写入补货建议 -> SepWhReplenish/CloudWhReplenish (来源=基地平衡)
                   |
                   v
              更新任务状态为"完成"
```

### 3.4 字段计算规则

#### 3.4.1 可用库存数量计算
```
调出仓可用库存数量 = SUM(件号"在仓可用库存") + SUM("替代件库存")
调入仓可用库存数量 = SUM(件号"在仓可用库存") + SUM("替代件库存")
```

#### 3.4.2 在途库存计算
```
调入在途库存数量 = SUM(件号+目的地仓的"调拨数量")
替代件调入在途库存数量 = SUM(替代件件号+调入仓的"调拨数量")
```

#### 3.4.3 平衡后可用库存数量计算
```
平衡后可用库存数量 = 调入仓在仓可用库存(dest_avail_stock_qty) + 调入在途库存数量(in_transit_in_qty)
                  + 替代件调入在途库存数量(substitute_in_transit_in_qty)
                  - 调出在途（未发运）(in_transit_out_unshipped_qty)
                  - 替代件调出在途（未发运）(substitute_in_transit_out_unshipped_qty)
                  + 计划调拨数量(plan_allocation_qty)

注: 页面级重算使用AllocationPlan的plan_allocation_qty（用户可调整）
    Job级生成使用DwsAlgAllocationOutput的transfer_qty
    两者公式结构相同，仅"调拨数量"字段来源不同
```

#### 3.4.4 平衡后可用天数（月）计算
```
平衡后可用天数（月） = 平衡后可用库存数量 / 未来月均需求量（替代组求和）
```

#### 3.4.5 调拨单生成Job的平衡后可用库存数量计算
```
平衡后可用库存数量 = 调入仓在仓可用库存(dest_avail_stock_qty) + 调入在途库存数量(in_transit_in_qty)
                  + 替代件调入在途库存数量(substitute_in_transit_in_qty)
                  - 调出在途（未发运）(in_transit_out_unshipped_qty)
                  - 替代件调出在途（未发运）(substitute_in_transit_out_unshipped_qty)
                  + 计划调拨数量(transfer_qty)

注: 从dws_alg_allocation_output表直接读取各库存字段，替代件调出在途（未发运）为新增字段substitute_in_transit_out_unshipped_qty
    公式与3.4.3结构一致，仅最后项使用transfer_qty（Job级）而非plan_allocation_qty（页面级）
```

#### 3.4.6 调拨单生成Job的可用天数计算
```
平衡后可用天数（月） = 平衡后可用库存数量 / 未来月均需求量

未来月均需求量 = monthly_avg_consumption_qty + monthly_avg_allocation_out_qty

注: 参考dws_alg_allocation_output表的monthly_avg_consumption_qty和monthly_avg_allocation_out_qty字段，
替代组需将所有替代件号的需求量求和
```

### 3.5 调拨单生成Job逻辑

#### 3.5.1 Job定义

调拨单生成Job遵循项目JobProcessService标准模式，实现三阶段生命周期：

```java
/**
 * 调拨计划生成Job
 * Bean Name: allocationPlanGenerateService
 * 文件: handle/replm/AllocationPlanGenerateServiceImpl.java
 */
@Service("allocationPlanGenerateService")
public class AllocationPlanGenerateServiceImpl implements JobProcessService {

    /**
     * 执行阶段 — API调用BDP的Job运行
     */
    @Override
    public void jobTaskProcess(JobConfig jobConfig, JSONObject param, LocalDateTime now) {
       // 根据sys_dolphin_config里定义的job配置 调用Dolphin或BDP运行调度并传入batch_id参数，具体参考补货计划Job调起BDP任务 
    }

    /**
     * 前置处理阶段 — 手工触发Dolphin/BDP任务前
     * 校验参数、准备数据
     */
    @Override
    public void manualTaskBeforeProcess(JobConfig jobConfig, Map<String, String> param) {
        // 校验batch_id参数是否存在
        // 校验当前是否有运行中的算法任务(Redis锁: ALLOCATION_BALANCE_CALCULATE)
        // 生成算法输入dws_alg_allocation_input表，手工触发则记录用户勾选的sku+仓，日常调度记录全部补货参数work_flow=3的scope范围sku+仓
    }

    /**
     * 后置处理阶段 — Dolphin/BDP流程完成后回调
     * 从dws_alg_allocation_output读取算法输出，结合主数据和替代关系生成调拨计划
     * 算法平台执行完成后，读取算法输出并生成调拨计划
     */
    @Override
    public void taskAfterProcess(JobConfig jobConfig, JSONObject param) {
        // 1. 从param解析batch_id
        // 2. 查询dws_alg_allocation_output WHERE batch_id = ? AND tenant_id = ?
        // 3. 关联drp_sku_detail获取件号主数据（sku_name_cn, sku_name_en, chapter_no, material_group, unit等）
        // 4. 关联product_substitute获取替代关系，计算替代件库存和在途
        // 5. 按件号+调出仓+调入仓组合，计算平衡后可用库存数量(参考3.4.5)和可用天数(参考3.4.6)
        // 6. 批量生成AllocationPlan记录，写入drp_allocation_plan表
        // 7. 算法输出的采购建议写入补货建议表(SepWhReplenish)，来源标记"基地平衡"
    }
}
```

JobConfig配置注册（sys_dolphin_config表）：
```yaml
signCode: ALLOCATION_PLAN_GENERATE
serviceName: allocationPlanGenerateService
flowType: API
platform: bdp
asyncRun: true
onlyRun: true
```

#### 3.5.2 数据读取与关联流程

```
Job触发 (传入batch_id)
    |
    v
查询 dws_alg_allocation_output WHERE batch_id = ? AND tenant_id = ?
    |
    v
遍历算法输出记录，每条记录包含:
  - out_warehouse_code (调出仓编码)
  - in_warehouse_code (调入仓编码)
  - sku_code (件号)
  - transfer_qty (建议调拨数量 → sug_allocation_qty)
  - sug_fill_point_qty (建议补货点数量)
  - sug_fill_target_qty (建议补货目标数量)
  - monthly_avg_consumption_qty (月均消耗数量)
  - monthly_avg_allocation_out_qty (月均调拨出库数量)
  - latest_allocation_in_time (最近调拨入库时间)
  - future_7d_mr_qty (未来7天MR数量)
  - src_avail_stock_qty (调出仓可用库存)
  - src_stock_avail_days (调出仓可用天数)
  - dest_avail_stock_qty (调入仓可用库存)
  - dest_stock_avail_days (调入仓可用天数)
  - pending_receive_qty (待收货数量)
  - substitute_pending_receive_qty (替代件待收货数量)
  - in_transit_in_qty (调入在途)
  - substitute_in_transit_in_qty (替代件调入在途)
  - in_transit_out_unshipped_qty (调出在途未发运)
  - in_transit_out_shipped_qty (调出在途已发运)
  - allocation_type (缺货分配标识: 0=正常分配, 1=缺货分配)
  - algorithm_task_id (关联算法任务ID)
    |
    v
关联 drp_safety_stock ON o.sku_code = ss.sku_code AND o.in_warehouse_code = ss.warehouse_code
  → 补充: current_bucket_sales_qty (当月销量，用于qty转天计算)
    |
    v
关联 drp_sku_detail WHERE sku_code = ? AND tenant_id = ?
  → 补充: sku_name_cn, sku_name_en, chapter_no, material_group, unit, pma
    |
    v
关联 product_substitute WHERE (replaced_sku_code = ? OR substitute_sku_code = ?) AND status = 有效 AND tenant_id = ?
  → 补充: has_substitute标识, 替代件库存和在途聚合计算
    |
    v
计算平衡后可用库存数量 (参考3.4.5，calculateBalancedAvailStockQty)
计算平衡后可用天数 (调用commonSuggestService.calculateQtyToDay，qty转天公共方法)
    |
    v
生成AllocationPlan记录:
  - plan_no: 按编码规则自动生成 (AP-{yyyyMMdd}-{seq})
  - type: 0 (系统)
  - status: 1 (待确认)
  - plan_allocation_qty: 默认等于sug_allocation_qty
  - require_arrival_date: 默认次日+7天
  - balanced_avail_stock_qty: 计算值
  - balanced_avail_days: 计算值
  - algorithm_task_id: 来自dws_alg_allocation_output
  - batch_id: 来自dws_alg_allocation_output
  - 其他字段从算法输出和主数据映射
  - allocation_type: 从dws_alg_allocation_output直接映射("0"=正常分配,"1"=缺货分配,null保留为null)
    |
    v
批量写入 drp_allocation_plan 表
    |
    v
采购建议数据 → 写入补货建议表 (来源 = "基地平衡")
```

#### 3.5.3 替代关系处理逻辑

替代关系通过 `SubstituteRelationsDto` 批量查询，不再逐条查 `product_substitute` 表：

```java
// 批量查询所有SKU的替代关系（包括单双向替代）
List<SubstituteRelationsDto> substituteRelationsDtoList = substituteMapper.countSubstituteRelations(skuCodes);
Map<String, SubstituteRelationsDto> substituteRelationsMap = substituteRelationsDtoList.stream()
    .collect(Collectors.toMap(SubstituteRelationsDto::getSkuCode, obj -> obj, (k1, k2) -> k1));

// 设置hasSubstitute标识
plan.setHasSubstitute(substituteRelationsMap
    .getOrDefault(skuCode, new SubstituteRelationsDto())
    .hasSubstitutes());
// hasSubstitutes() 返回值:
//   - "bothSubstitution": 同时存在单双向替代
//   - "oneWaySubstitution": 仅单向替代
//   - "twoWaySubstitution": 仅双向替代
//   - null: 无替代件
```

#### 3.5.4 平衡后可用天数计算（qty转天）

平衡后可用天数不再使用独立公式计算，改为调用公共 `qty转天` 服务：

```java
// 通过 CalculatePlanReplenishFactory 构建 VO，调用 commonSuggestService 批量计算
CalculatePlanReplenishRepVO vo = CalculatePlanReplenishFactory.buildQtyToDay(
    skuCode, warehouseCode, BigDecimal.ZERO, balancedAvailStockQty, currentBucketSalesQty);
commonSuggestService.calculateQtyToDay(planReplenishRepVOList, StrategyTypeEnum.REPLENISHMENT);
// balancedAvailDays = vo.getReplenishTargetDay()
```

#### 3.5.5 输入数据表结构


**算法输入表 dws_alg_allocation_input:**

```yaml
表名: dws_alg_allocation_input
描述: 平衡算法输入结果（应用写入，BDP读取）

字段:
  - id: BIGINT, PK, 自增
  - warehouse_code: VARCHAR(50), 调出仓编码
  - sku_code: VARCHAR(50), 件号
  - min_transfer_qty: DECIMAL(20,4), 最小调拨批量
  - stockout_rule: VARCHAR(32), 缺货分配规则
  - warehouse_category: VARCHAR(48), 站点分类
  - tenant_id: bigint, 租户ID
  - batch_id: VARCHAR(64), 批次ID（Job查询的关键参数）
  - create_time: DATETIME, 创建时间
  - create_by: VARCHAR(50), 创建人
```

**算法输出表 dws_alg_allocation_output:**

```yaml
表名: dws_alg_allocation_output
描述: 平衡算法输出结果（算法平台写入，Job读取）

字段:
  - id: BIGINT, PK, 自增
  - out_warehouse_code: VARCHAR(50), 调出仓编码
  - in_warehouse_code: VARCHAR(50), 调入仓编码
  - sku_code: VARCHAR(50), 件号
  - transfer_qty: DECIMAL(20,4), 建议调拨数量
  - sug_fill_point_qty: DECIMAL(20,4), 建议补货点数量
  - sug_fill_target_qty: DECIMAL(20,4), 建议补货目标数量
  - monthly_avg_consumption_qty: DECIMAL(20,4), 月均消耗数量
  - monthly_avg_allocation_out_qty: DECIMAL(20,4), 月均调拨出库数量
  - latest_allocation_in_time: DATETIME, 最近调拨入库时间
  - future_7d_mr_qty: DECIMAL(20,4), 未来7天MR数量
  - src_avail_stock_qty: DECIMAL(20,4), 调出仓可用库存数量
  - src_stock_avail_days: DECIMAL(20,4), 调出仓库存可用天数(月)
  - dest_avail_stock_qty: DECIMAL(20,4), 调入仓可用库存数量
  - dest_stock_avail_days: DECIMAL(20,4), 调入仓库存可用天数(月)
  - pending_receive_qty: DECIMAL(20,4), 待收货数量
  - substitute_pending_receive_qty: DECIMAL(20,4), 替代件待收货数量
  - in_transit_in_qty: DECIMAL(20,4), 调入在途库存数量
  - substitute_in_transit_in_qty: DECIMAL(20,4), 替代件调入在途库存数量
  - in_transit_out_unshipped_qty: DECIMAL(20,4), 调出在途（未发运）
  - in_transit_out_shipped_qty: DECIMAL(20,4), 调出在途（已发运）
  - allocation_type: VARCHAR(20), 缺货预警(缺货分配/常规调拨/null)
  - demand_sku_codes: VARCHAR(500), 需求件号列表(逗号分隔)
  - algorithm_task_id: VARCHAR(64), 算法任务ID
  - tenant_id: VARCHAR(50), 租户ID
  - batch_id: VARCHAR(64), 批次ID（Job查询的关键参数）
  - create_time: DATETIME, 创建时间
```

**主数据表 drp_sku_detail:**

Job关联查询字段: `sku_code`, `sku_name_cn`, `sku_name_en`, `chapter_no`, `material_group`, `unit`, `pma`, `tenant_id`

**替代关系表 product_substitute:**

```yaml
表名: product_substitute
描述: 件号替代关系

字段:
  - id: BIGINT, PK
  - replaced_sku_code: VARCHAR(50), 被替代件号编码（原件号）
  - replaced_sku_name: VARCHAR(200), 被替代件号名称
  - replaced_pma: VARCHAR(50), 被替代件号PMA
  - substitute_sku_code: VARCHAR(50), 替代件号编码
  - substitute_sku_name: VARCHAR(200), 替代件号名称
  - substitute_pma: VARCHAR(50), 替代件号PMA
  - substitution_type: VARCHAR(20), 替代类型
  - substitute_group_no: VARCHAR(50), 替代组编号
  - grouped_substitute_no: VARCHAR(50), 组内替代编号
  - replenish_rule: VARCHAR(50), 补货规则
  - tenant_id: VARCHAR(50), 租户ID
  - create_time: DATETIME
  - create_by: VARCHAR(50)
  - update_time: DATETIME
  - update_by: VARCHAR(50)
  - status: VARCHAR(20), 状态（有效/无效，Job仅查询有效记录）
  - remark: VARCHAR(200)
  - system_sku_code: VARCHAR(50), 系统件号编码
  - adjust_sku_code: VARCHAR(50), 调整件号编码
  - post_sku_code: VARCHAR(50), 岗位件号编码
  - substitute_warning: VARCHAR(50), 替代预警
  - service_primary_key: VARCHAR(100), 服务主键
```

#### 3.5.5 字段映射表 (dws_alg_allocation_output → AllocationPlan)

| 算法输出字段                         | AllocationPlan字段               | 转换规则 |
|--------------------------------|--------------------------------|---------|
| out_warehouse_code             | src_warehouse_code             | 直接映射，关联查询仓库名称 |
| in_warehouse_code              | dest_warehouse_code            | 直接映射，关联查询仓库名称 |
| sku_code                       | sku_code                       | 直接映射 |
| -                              | sku_name_cn/sku_name_en        | 从drp_sku_detail查询 |
| -                              | chapter_no/material_group/unit | 从drp_sku_detail查询 |
| transfer_qty                   | sug_allocation_qty             | 直接映射 |
| transfer_qty                   | plan_allocation_qty            | 默认=transfer_qty，可手工调整 |
| sug_fill_point_qty             | reorder_point_qty              | 直接映射 |
| sug_fill_target_qty            | replenish_target_qty           | 直接映射 |
| monthly_avg_consumption_qty    | monthly_avg_consumption_qty    | 直接映射 |
| monthly_avg_allocation_out_qty | monthly_avg_allocation_out_qty | 直接映射 |
| future_7d_mr_qty               | future_7d_mr_qty               | 直接映射 |
| src_avail_stock_qty            | src_avail_stock_qty            | 直接映射 |
| src_stock_avail_days           | src_stock_avail_days           | 直接映射 |
| dest_avail_stock_qty           | dest_avail_stock_qty           | 直接映射 |
| dest_stock_avail_days          | dest_stock_avail_days          | 直接映射 |
| pending_receive_qty            | pending_receive_qty            | 直接映射 |
| substitute_pending_receive_qty | substitute_pending_receive_qty | 直接映射 |
| in_transit_in_qty              | in_transit_in_qty              | 直接映射 |
| substitute_in_transit_in_qty   | substitute_in_transit_in_qty   | 直接映射 |
| in_transit_out_unshipped_qty   | in_transit_out_unshipped_qty   | 直接映射 |
| substitute_in_transit_out_unshipped_qty | substitute_in_transit_out_unshipped_qty | 直接映射 |
| in_transit_out_shipped_qty     | in_transit_out_shipped_qty     | 直接映射 |
| allocation_type                | allocation_type                | 直接映射，类型为VARCHAR(20)，"缺货分配"→是,"常规调拨"→否,null→否 |
| algorithm_task_id              | algorithm_task_id              | 直接映射 |
| batch_id                       | batch_id                       | 直接映射 |
| -                              | balanced_avail_stock_qty       | 计算值(参考3.4.5) |
| -                              | balanced_avail_days            | 计算值(参考3.4.6) |
| -                              | has_substitute                 | 从product_substitute查询判断 |
| -                              | plan_no                        | 系统生成: AP-{yyyyMMdd}-{seq} |
| -                              | type                           | 固定值: 0(系统) |
| -                              | status                         | 待确认(1)、待下发(2)或下发失败(5) |
| -                              | require_arrival_date           | 默认: 次日+7天 |

---

## 4. 接口规格 (API Specs)

### 4.1 内部接口

#### IAllocationPlanService

```java
/**
 * 调拨计划服务
 * 文件: service/replm/IAllocationPlanService.java
 */
public interface IAllocationPlanService {
    /**
     * 分页查询调拨建议单
     */
    PageResult<AllocationPlanPageRespDto> page(AllocationPlanPageReqVO vo);

    /**
     * 手工运行平衡算法（全量）
     */
    String runBalanceAlgorithmAll();

    /**
     * 按条件运行平衡算法
     * 支持物料组、章节号、件号、物料类型、清单号等多条件过滤，取交集
     * 若所有条件均为空，则与全量运行一致
     * @param reqVO 算法运行条件
     */
    String runBalanceAlgorithmBySkus(AllocationPlanRunAlgorithmReqVO reqVO);

    /**
     * 更新计划调拨数量
     */
    Boolean updatePlanQty(AllocationPlanUpdateQtyReqVO reqVO);

    /**
     * 更新要求到货日期
     */
    Boolean updateRequireDate(AllocationPlanUpdateDateReqVO reqVO);

    /**
     * 综合更新调拨建议单（数量+日期+状态）
     * @param reqVO 包含id、planAllocationQty（可选）、requireArrivalDate（可选）、status（可选）、adjustReason（条件必填）
     */
    Boolean update(AllocationPlanUpdateReqVO reqVO);

    /**
     * 下发调拨建议单
     */
    Boolean issue(IdListReqVO reqVO);

    /**
     * 导出调拨建议单
     */
    List<AllocationPlanExportExcelVO> export(AllocationPlanPageReqVO reqVO);

    /**
     * 查询未来7天MR需求明细
     */
    List<MrDemandDetailRespDto> queryMrDemandDetails(String skuCode, String warehouseCode);

    /**
     * 查询算法任务状态
     */
    AlgorithmTaskStatusRespDto queryAlgorithmTaskStatus(String taskId);
}
```

#### IAllocationPlanGenerateService (调拨单生成Job)

```java
/**
 * 调拨计划生成Job服务
 * 文件: service/replm/IAllocationPlanGenerateService.java
 * Bean Name: allocationPlanGenerateService
 * 实现: handle/replm/AllocationPlanGenerateServiceImpl.java
 */
public interface IAllocationPlanGenerateService {

    /**
     * 根据batch_id从算法输出表生成调拨计划
     * @param batchId 算法批次ID
     * @param tenantId 租户ID
     */
    void generateAllocationPlans(String batchId, String tenantId);

    /**
     * 从算法输出表查询指定batch_id的数据
     * @param batchId 算法批次ID
     * @param tenantId 租户ID
     */
    List<AllocationOutputDto> queryAllocationOutput(String batchId, String tenantId);

    /**
     * 计算平衡后可用库存数量
     * 参考3.4.5计算逻辑
     */
    BigDecimal calculateBalancedAvailStockQty(AllocationOutputDto output, List<ProductSubstitute> substitutes);

    /**
     * 计算平衡后可用天数（月）
     * 参考3.4.6计算逻辑
     */
    BigDecimal calculateBalancedAvailDays(BigDecimal balancedAvailStockQty, BigDecimal monthlyAvgConsumption,
                                           BigDecimal monthlyAvgAllocationOut, List<ProductSubstitute> substitutes);

    /**
     * 将算法输出的采购建议写入补货建议表
     * 来源标记为"基地平衡"
     */
    void writePurchaseSuggestionsToReplenish(String batchId, String tenantId);
}
```

#### IAllocationPlanAlgorithmLogService

```java
/**
 * 算法日志服务
 * 文件: service/replm/IAllocationPlanAlgorithmLogService.java
 */
public interface IAllocationPlanAlgorithmLogService {
    /**
     * 分页查询算法日志
     */
    PageResult<AlgorithmLogRespDto> page(AlgorithmLogPageReqVO vo);

    /**
     * 创建算法任务记录
     */
    String createTask(String taskType, List<String> skuCodes);

    /**
     * 更新任务状态
     */
    void updateTaskStatus(String taskId, String status, String errorMsg);

    /**
     * 保存算法日志
     */
    void saveLog(String taskId, String logContent);
}
```

### 4.2 REST API

```yaml
接口: POST /allocationPlan/page
名称: 分页查询调拨建议单
版本: v1

Request:
  Content-Type: application/json
  Headers:
    Authorization: Bearer {token}
  Body:
    type: object
    properties:
      srcWarehouseCode:
        type: string
        example: "WH001"
      destWarehouseCode:
        type: string
        example: "WH002"
      skuCode:
        type: string
        example: "SKU001"
      hasSubstitute:
        type: integer
        description: 替代件标识(0=不存在,1=存在)
      status:
        type: integer
        description: 建议单状态
      pageNum:
        type: integer
      pageSize:
        type: integer
        maximum: 3000

Response:
  200:
    description: 成功
    body:
      code: "SUCCESS"
      data:
        total: 100
        list:
          type: array
          items:
            $ref: AllocationPlanPageRespDto
```

```yaml
接口: POST /allocationPlan/runAlgorithm/all
名称: 全量运行平衡算法

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        taskId: "TASK-20260422-001"

  409:
    description: 算法正在运行中
    body:
      code: "ALGORITHM_RUNNING"
      message: "算法正在运行中，请稍后再试"
```

```yaml
接口: POST /allocationPlan/runAlgorithm/bySkus
名称: 按条件运行平衡算法

Request:
  Body:
    required: []  // 所有条件均为非必填
    properties:
      skuCodeList:
        type: array<string>
        description: 件号编码列表
      lv1ClssfNameList:
        type: array<string>
        description: 物料组列表
      chapterNoList:
        type: array<string>
        description: 章节号列表
      skuTypeMasterList:
        type: array<integer>
        description: 物料类型列表(0=周转件,1=消耗件,2=控修件)
      bomNoList:
        type: array<string>
        description: 清单号列表

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        taskId: "TASK-20260422-002"

  409:
    body:
      code: "ALGORITHM_RUNNING"
```

```yaml
接口: POST /allocationPlan/update/planQty
名称: 更新计划调拨数量

Request:
  Body:
    required: [id, planAllocationQty, adjustReason]
    properties:
      id:
        type: long
      planAllocationQty:
        type: decimal
      adjustReason:
        type: string
        maxLength: 200
        description: 调整原因（必填）

Response:
  200:
    body:
      code: "SUCCESS"
      data: true

  400:
    body:
      code: "ADJUST_REASON_REQUIRED"
      message: "调整原因不能为空"

  403:
    body:
      code: "STATUS_NOT_ALLOW"
      message: "当前状态不支持修改计划数量"
```

```yaml
接口: POST /allocationPlan/update/requireDate
名称: 更新要求到货日期

Request:
  Body:
    required: [id, requireArrivalDate]
    properties:
      id: long
      requireArrivalDate:
        type: string
        format: date

Response:
  200:
    body:
      code: "SUCCESS"
```

```yaml
接口: POST /allocationPlan/update
名称: 综合更新调拨建议单

描述: 同时更新调拨数量、需求到货日期和调拨单状态。支持单项或多项组合更新。

Request:
  Body:
    required: [id]
    properties:
      id: long — 拨建议单ID
      planAllocationQty:
        type: decimal
        description: 调拨数量（可选）
      requireArrivalDate:
        type: string
        format: date
        description: 需求到货日期（可选）
      status:
        type: integer
        enum: [1, 3, 4, 5]
        description: 调拨单状态（可选）。仅允许从下发失败(5)回退到待确认(1)；待下发(2)→待确认(1)通过revoke接口完成
      adjustReason:
        type: string
        maxLength: 500
        description: 调整原因。修改planAllocationQty或将status从5回退到1时必填

Response:
  200:
    body:
      code: "SUCCESS"

  400:
    body:
      code: "ADJUST_REASON_REQUIRED"
      message: "修改调拨数量或回退状态时必须填写调整原因"

  403:
    body:
      code: "STATUS_NOT_ALLOW"
      message: "当前状态不允许修改"

  403:
    body:
      code: "STATUS_TRANSITION_INVALID"
      message: "仅允许从下发失败(5)回退到待确认(1)"
```

```yaml
接口: POST /allocationPlan/issue
名称: 下发调拨建议单

Request:
  Body:
    required: [ids]
    properties:
      ids: array<long>

Response:
  200:
    body:
      code: "SUCCESS"

  403:
    body:
      code: "STATUS_NOT_ALLOW"
```

```yaml
接口: POST /allocationPlan/export
名称: 导出调拨建议单

Request: 同分页查询参数
Response:
  200:
    Content-Type: application/octet-stream
```

```yaml
接口: GET /allocationPlan/mrDemandDetails
名称: 查询未来7天计划性维修需求明细（兼容旧接口，内部调用pmTaskList）

Request:
  Query Params:
    skuCode: string, required
    warehouseCode: string, required

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        type: array
        items:
          $ref: PmTaskRespDto
```

```yaml
接口: GET /allocationPlan/pmTask/list
名称: 未来7天计划性需求数量

Request:
  Query Params:
    skuCode: string, required, 件号
    warehouseCode: string, required, 仓库编码

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        type: array
        items:
          type: object
          properties:
            id: long
            skuCode: string, 件号
            skuName: string, 件号名称
            warehouseCode: string, 仓库编码
            warehouseName: string, 仓库名称
            demandDate: string, 需求日期
            demandQty: decimal, 需求数量
            demandUnit: string, 需求单位
            cardType: string, 工卡类型
            workType: string, 工作类型
            taskType: string, 任务类型
            workCheck: integer, 工作检查
            amountCheck: integer, 数量检查
            workOrderNo: string, 工单号
            mrCode: string, MR编号
            mrLineCode: string, MR行编号
            mrpCode: string, MRP编号
            status: string, 状态
```

```yaml
接口: GET /allocationPlan/inStock/list
名称: 在仓可用库存数量

Request:
  Query Params:
    skuCode: string, required, 件号
    warehouseCode: string, required, 仓库编码

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        type: array
        items:
          type: object
          properties:
            id: long
            skuCode: string, 件号
            skuName: string, 件号名称
            uom: string, 单位
            airportTcode: string, 机场三字码
            airportCname: string, 机场中文名
            warehouseType: string, 仓库类型(机场/非机场)
            warehouseCategory: string, 仓库分类
            warehouseCode: string, 仓库编码
            warehouseName: string, 仓库名称
            inStockQty: decimal, 在库数量
            inTransQty: decimal, 在途数量
            inTransPoQty: decimal, 在途PO数量
            inTransPrQty: decimal, 在途PR数量
            inTransTransQty: decimal, 在途调拨数量
            availQty: decimal, 可用数量
```

```yaml
接口: POST /allocationPlan/algorithmLog/page
名称: 分页查询算法日志

Response:
  200:
    body:
      code: "SUCCESS"
      data:
        total: long
        list:
          type: array
          items:
            type: object
            properties:
              taskId: string
              taskType: string
              skuCount: integer
              startTime: string
              endTime: string
              duration: integer
              status: string
              errorMsg: string
              createdBy: string
```

---

## 5. 数据规格 (Data Specs)

### 5.1 数据模型

```yaml
实体: AllocationPlan
表名: drp_allocation_plan
描述: 调拨建议单

字段:
  - name: id
    type: BIGINT
    nullable: false
    primaryKey: true
    autoIncrement: true

  - name: plan_no
    type: VARCHAR(64)
    nullable: false
    description: 调拨建议单号，系统按编码规则自动生成
    testData: "AP-20260422-001"

  - name: type
    type: INT
    nullable: false
    default: 0
    description: 类型(0=系统,1=人工)
    testData: 0

  - name: status
    type: INT
    nullable: false
    default: 1
    description: 状态(1=待确认,2=待下发,3=已下发,4=下发中,5=下发失败)
    testData: 1

  - name: src_warehouse_code
    type: VARCHAR(50)
    nullable: false
    description: 来源仓编码

  - name: src_warehouse_name
    type: VARCHAR(100)
    description: 来源仓名称

  - name: dest_warehouse_code
    type: VARCHAR(50)
    nullable: false
    description: 目的仓编码

  - name: dest_warehouse_name
    type: VARCHAR(100)
    description: 目的仓名称

  - name: sku_code
    type: VARCHAR(50)
    nullable: false
    description: 件号

  - name: sku_name_cn
    type: VARCHAR(200)
    description: 件号名称（中文）

  - name: sku_name_en
    type: VARCHAR(200)
    description: 件号名称（英文）

  - name: chapter_no
    type: VARCHAR(50)
    description: 章节号

  - name: material_group
    type: VARCHAR(50)
    description: 物料组

  - name: require_arrival_date
    type: DATE
    description: 要求到货日期，默认次日+7天，可修改

  - name: has_substitute
    type: VARCHAR(20)
    description: 替代件标识(存在替代件/空值)

  - name: unit
    type: VARCHAR(20)
    description: 件号的库存单位

  - name: sug_allocation_qty
    type: DECIMAL(20,4)
    nullable: false
    description: 建议调拨数量（算法计算值）

  - name: plan_allocation_qty
    type: DECIMAL(20,4)
    description: 计划调拨数量（业务确认结果，可手工编辑）

  - name: adjust_reason
    type: VARCHAR(200)
    description: 调整原因（修改计划补货数量时填写）

  - name: reorder_point_qty
    type: DECIMAL(20,4)
    description: 重新订货点数量

  - name: replenish_target_qty
    type: DECIMAL(20,4)
    description: 补货目标数量

  - name: monthly_avg_consumption_qty
    type: DECIMAL(20,4)
    description: 月均消耗数量（当前件号、调出仓，预测配置时间段内的月均发料数量）

  - name: monthly_avg_allocation_out_qty
    type: DECIMAL(20,4)
    description: 月均调拨出库数量（当前件号、调出仓，预测配置时间段内的月均调拨出库）

  - name: latest_allocation_in_time
    type: DATETIME
    description: 最近调拨入库时间（来源dws_alg_allocation_output，同一调入仓+件号最近一次调拨入库的时间）

  - name: future_7d_mr_qty
    type: DECIMAL(20,4)
    description: 未来7天MR数量（件号、站点的需求日期在7天内的计划性维修需求）

  - name: src_avail_stock_qty
    type: DECIMAL(20,4)
    description: 调出仓可用库存数量

  - name: src_stock_avail_days
    type: DECIMAL(20,4)
    description: 调出仓库存可用天数（月）

  - name: dest_avail_stock_qty
    type: DECIMAL(20,4)
    description: 调入仓可用库存数量

  - name: dest_stock_avail_days
    type: DECIMAL(20,4)
    description: 调入仓库存可用天数（月）

  - name: pending_receive_qty
    type: DECIMAL(20,4)
    description: 待收货数量（采购在途且已送达待接收状态，暂不处理）

  - name: substitute_pending_receive_qty
    type: DECIMAL(20,4)
    description: 替代件待收货数量（暂不处理）

  - name: in_transit_in_qty
    type: DECIMAL(20,4)
    description: 调入在途库存数量

  - name: substitute_in_transit_in_qty
    type: DECIMAL(20,4)
    description: 替代件调入在途库存数量

  - name: in_transit_out_unshipped_qty
    type: DECIMAL(20,4)
    description: 调出在途（未发运）

  - name: in_transit_out_shipped_qty
    type: DECIMAL(20,4)
    description: 调出在途（已发运）

  - name: allocation_type
    type: VARCHAR(20)
    description: 缺货预警(缺货分配/常规调拨/null)，从算法输出直接映射
    testData: "1"

  - name: balanced_avail_stock_qty
    type: DECIMAL(20,4)
    description: 平衡后可用库存数量

  - name: balanced_avail_days
    type: DECIMAL(20,4)
    description: 平衡后可用天数（月）

  - name: algorithm_task_id
    type: VARCHAR(64)
    description: 关联的算法任务ID

  - name: update_time
    type: DATETIME
    nullable: false
    description: 更新时间（MAX(调拨建议单成功生成时间，更新调拨成功时间)）

  - name: tenant_id
    type: VARCHAR(50)
    description: 租户ID

索引:
  - name: idx_plan_no
    fields: [plan_no]
    type: unique
  - name: idx_src_dest_sku
    fields: [src_warehouse_code, dest_warehouse_code, sku_code]
    type: normal
  - name: idx_status
    fields: [status]
    type: normal
  - name: idx_algorithm_task
    fields: [algorithm_task_id]
    type: normal

约束:
  - "UNIQUE(plan_no)"
  - "CHECK(sug_allocation_qty >= 0)"
  - "CHECK(plan_allocation_qty >= 0)"
```

```yaml
实体: AllocationPlanAlgorithmTask
表名: dp_allocation_plan_algorithm_task
描述: 平衡算法任务记录

字段:
  - name: id
    type: BIGINT
    primaryKey: true
    autoIncrement: true

  - name: task_id
    type: VARCHAR(64)
    nullable: false
    description: 任务ID

  - name: task_type
    type: VARCHAR(20)
    nullable: false
    description: 任务类型(FULL=全量,PARTIAL=部分)

  - name: sku_codes
    type: TEXT
    description: 参与计算的件号列表(JSON数组)

  - name: sku_count
    type: INT
    description: 件号数量
    
  - name: task_instance_info
    type: TEXT
    description: 任务节点信息，包括BDP任务和领慧任务信息

  - name: status
    type: VARCHAR(20)
    nullable: false
    default: RUNNING
    description: 状态(RUNNING=运行中,COMPLETED=完成,FAILED=失败)

  - name: start_time
    type: DATETIME
    nullable: false
    description: 开始时间

  - name: end_time
    type: DATETIME
    description: 结束时间

  - name: duration_seconds
    type: INT
    description: 耗时(秒)

  - name: error_msg
    type: TEXT
    description: 失败原因

  - name: log_content
    type: TEXT
    description: 算法日志内容

  - name: created_by
    type: VARCHAR(50)
    description: 创建人(SYSTEM=定时任务,用户名=手工触发)

索引:
  - name: idx_task_id
    fields: [task_id]
    type: unique
  - name: idx_status_time
    fields: [status, start_time]
    type: normal
```

### 5.2 分层DTO设计

```java
// Request DTO - 分页查询
public class AllocationPlanPageReqVO extends PageParam {
    private String srcWarehouseCode;
    private String destWarehouseCode;
    private String skuCode;
    private Integer hasSubstitute;
    private Integer status;
}

// Request DTO - 更新计划数量
public class AllocationPlanUpdateQtyReqVO {
    @NotNull(message = "ID不能为空")
    private Long id;
    @NotNull(message = "计划调拨数量不能为空")
    private BigDecimal planAllocationQty;
    @NotBlank(message = "调整原因不能为空")
    @Size(max = 200, message = "调整原因不能超过200字符")
    private String adjustReason;
}

// Request DTO - 综合更新（数量+日期+状态）
public class AllocationPlanUpdateReqVO {
    @NotNull(message = "ID不能为空")
    private Long id;
    private BigDecimal planAllocationQty;
    private LocalDate requireArrivalDate;
    private Integer status;
    @Size(max = 500, message = "调整原因长度不能超过500")
    private String adjustReason;
}

// Request DTO - 按条件运行算法
public class AllocationPlanRunAlgorithmReqVO {
    @ApiModelProperty("件号编码列表")
    private List<String> skuCodeList;

    @ApiModelProperty("物料组列表")
    private List<String> lv1ClssfNameList;

    @ApiModelProperty("章节号列表")
    private List<String> chapterNoList;

    @ApiModelProperty("物料类型列表")
    private List<Integer> skuTypeMasterList;

    @ApiModelProperty("清单号列表")
    private List<String> bomNoList;
}

// Response DTO - 分页结果
public class AllocationPlanPageRespDto {
    private Long id;
    private String planNo;
    private Integer type;
    private Integer status;
    private String srcWarehouseCode;
    private String srcWarehouseName;
    private String destWarehouseCode;
    private String destWarehouseName;
    private String skuCode;
    private String skuName;
    private String skuNameEn;
    private String chapterNo;
    private String materialGroup;
    private Date requireArrivalDate;
    private String hasSubstitute;
    private String unit;
    private BigDecimal sugAllocationQty;
    private BigDecimal planAllocationQty;
    private String adjustReason;
    private BigDecimal reorderPointQty;
    private BigDecimal replenishTargetQty;
    private BigDecimal monthlyAvgConsumptionQty;
    private BigDecimal monthlyAvgAllocationOutQty;
    private LocalDateTime latestAllocationInTime;
    private BigDecimal future7dMrQty;
    private BigDecimal srcAvailStockQty;
    private BigDecimal srcStockAvailDays;
    private BigDecimal destAvailStockQty;
    private BigDecimal destStockAvailDays;
    private BigDecimal pendingReceiveQty;
    private BigDecimal substitutePendingReceiveQty;
    private BigDecimal inTransitInQty;
    private BigDecimal substituteInTransitInQty;
    private BigDecimal inTransitOutUnshippedQty;
    private BigDecimal inTransitOutShippedQty;
    private String allocationType;

    private String allocationTypeDesc;  // "是" when allocation_type="缺货分配", "否" otherwise
    private BigDecimal balancedAvailStockQty;
    private BigDecimal balancedAvailDays;
    private Date updateTime;
}

// Response DTO - MR需求明细
public class MrDemandDetailRespDto {
    private String mrNo;
    private Date requireDate;
    private BigDecimal requireQty;
    private String siteCode;
    private String siteName;
}

// Response DTO - 算法任务状态
public class AlgorithmTaskStatusRespDto {
    private String taskId;
    private String taskType;
    private Integer skuCount;
    private String status;
    private Date startTime;
    private Date endTime;
    private Integer durationSeconds;
    private String errorMsg;
}

// Response DTO - 算法日志
public class AlgorithmLogRespDto {
    private String taskId;
    private String taskType;
    private Integer skuCount;
    private Date startTime;
    private Date endTime;
    private Integer durationSeconds;
    private String status;
    private String errorMsg;
    private String createdBy;
}

// Export Excel VO
public class AllocationPlanExportExcelVO {
    @ExcelProperty("调拨建议单号")
    private String planNo;
    @ExcelProperty("类型")
    private String type;
    @ExcelProperty("建议单状态")
    private String status;
    @ExcelProperty("来源仓编码")
    private String srcWarehouseCode;
    @ExcelProperty("来源仓名称")
    private String srcWarehouseName;
    @ExcelProperty("目的仓编码")
    private String destWarehouseCode;
    @ExcelProperty("目的仓名称")
    private String destWarehouseName;
    @ExcelProperty("件号")
    private String skuCode;
    @ExcelProperty("件号名称（中文）")
    private String skuName;
    @ExcelProperty("要求到货日期")
    private String requireArrivalDate;
    @ExcelProperty("建议调拨数量")
    private BigDecimal sugAllocationQty;
    @ExcelProperty("计划调拨数量")
    private BigDecimal planAllocationQty;
    @ExcelProperty("调整原因")
    private String adjustReason;
    @ExcelProperty("月均消耗数量")
    private BigDecimal monthlyAvgConsumptionQty;
    @ExcelProperty("月均调拨出库数量")
    private BigDecimal monthlyAvgAllocationOutQty;
    @ExcelProperty("最近调拨入库时间")
    private String latestAllocationInTime;
    @ExcelProperty("未来7天MR数量")
    private BigDecimal future7dMrQty;
    @ExcelProperty("调出仓可用库存数量")
    private BigDecimal srcAvailStockQty;
    @ExcelProperty("调入仓可用库存数量")
    private BigDecimal destAvailStockQty;
    @ExcelProperty("缺货预警")
    private String allocationType;
    @ExcelProperty("平衡后可用库存数量")
    private BigDecimal balancedAvailStockQty;
    @ExcelProperty("平衡后可用天数（月）")
    private BigDecimal balancedAvailDays;
    @ExcelProperty("更新时间")
    private String updateTime;
}
```

### 5.3 测试数据

```yaml
数据集: DS-ALLOC-001
描述: 调拨建议单标准测试数据

records:
  - id: 1
    plan_no: "AP-20260422-001"
    type: 0
    status: 1
    src_warehouse_code: "WH-SH"
    src_warehouse_name: "上海基地仓"
    dest_warehouse_code: "WH-BJ"
    dest_warehouse_name: "北京基地仓"
    sku_code: "SKU-A001"
    sku_name_cn: "液压泵组件"
    sug_allocation_qty: 50.0000
    plan_allocation_qty: 50.0000
    src_avail_stock_qty: 200.0000
    dest_avail_stock_qty: 30.0000
    monthly_avg_consumption_qty: 25.0000
    future_7d_mr_qty: 10.0000
    balanced_avail_stock_qty: 80.0000
    balanced_avail_days: 3.2000
    allocation_type: "1"

  - id: 2
    plan_no: "AP-20260422-002"
    type: 0
    status: 1
    src_warehouse_code: "WH-SH"
    dest_warehouse_code: "WH-GZ"
    sku_code: "SKU-B002"
    has_substitute: "存在替代件"
    sug_allocation_qty: 100.0000
    plan_allocation_qty: 80.0000
    adjust_reason: "目的仓库容限制"
    monthly_avg_consumption_qty: 40.0000
    future_7d_mr_qty: 15.0000

边界数据:
  - sug_allocation_qty: 0 (最小值)
  - plan_allocation_qty: 999999 (大数值)
  - balanced_avail_days: 负数 (库存不足场景)
```

---

## 6. 错误处理规格 (Error Handling)

### 6.1 错误码定义

| 错误码 | 级别 | 描述 | 用户提示 | 日志内容 | 告警策略 |
|--------|------|------|----------|----------|----------|
| AP_001 | WARN | 参数验证失败 | 输入参数有误 | 参数字段和实际值 | 不告警 |
| AP_002 | ERROR | 算法服务调用失败 | 平衡算法调用失败，请稍后重试 | 异常堆栈+算法服务URL | 连续2次触发 |
| AP_003 | WARN | 算法正在运行中 | 算法正在运行中，请稍后再试 | 当前运行中的任务ID | 不告警 |
| AP_004 | ERROR | 算法执行超时 | 平衡算法执行超时 | 任务ID+耗时+超时阈值 | 连续3次触发 |
| AP_005 | ERROR | 算法执行失败 | 平衡算法执行失败 | 异常堆栈+输入参数摘要 | 立即触发 |
| AP_006 | WARN | 调拨单状态不允许操作 | 当前状态不支持此操作 | 单据号+当前状态+目标操作 | 不告警 |
| AP_007 | WARN | 调整原因未填写 | 修改计划数量时必须填写调整原因 | 单据ID+操作人 | 不告警 |
| AP_008 | ERROR | 下发中台失败 | 调拨建议单下发失败 | 单据号+中台响应内容 | 连续3次触发 |

### 6.2 错误场景

```gherkin
场景: 算法服务连接超时
  When 调用算法服务超过30秒无响应
  Then 返回错误码 "ALGORITHM_TIMEOUT"
  And 用户提示 "平衡算法执行超时，请稍后重试"
  And 记录 ERROR 日志
  And 更新任务状态为FAILED

场景: Redis分布式锁冲突
  Given 已存在运行中的算法任务
  When 用户再次点击"运行平衡算法"
  Then 返回错误码 "ALGORITHM_RUNNING"
  And 用户提示 "算法正在运行中，请稍后再试"

场景: 调整原因未填写
  Given 用户修改计划调拨数量但未填写调整原因
  When 提交修改请求
  Then 返回错误码 "ADJUST_REASON_REQUIRED"

场景: 下发中台网络异常
  Given 中台服务不可达
  When 调拨建议单下发
  Then 状态更新为"下发失败"
  And 返回错误码 "ISSUE_FAILED"
  And 支持重新下发
```

---

## 7. 非功能需求 (NFR)

### 7.1 性能要求

```yaml
指标:
  - name: 分页查询响应时间
    target: "P95 < 1s"
  - name: 全量算法计算完成时间
    target: "< 10分钟"
  - name: 部分件号计算响应
    target: "按件号数量线性增长"
  - name: 数据库查询
    target: "单表查询 < 50ms"

监控:
  - 算法任务每分钟轮询状态
  - 记录任务开始/结束时间和耗时
  - 超时阈值: 30分钟
```

### 7.2 安全要求

- [x] 输入参数校验（防SQL注入、XSS）
- [x] API鉴权验证（JWT Token）
- [x] 敏感操作审计日志
- [x] 数据权限隔离（租户ID过滤）
- [x] 导出操作权限控制（allocationPlan:export）

---

## 8. 测试策略 (Test Strategy)

### 8.1 测试清单

| 测试ID | 类型 | 描述 | 自动化 | 优先级 |
|--------|------|------|--------|--------|
| UT-AP-001 | 单元 | 调拨计划Service分页查询 | 是 | P0 |
| UT-AP-002 | 单元 | 算法任务防重复触发 | 是 | P0 |
| UT-AP-003 | 单元 | 计划数量更新校验 | 是 | P0 |
| UT-AP-004 | 单元 | 状态机转换验证 | 是 | P0 |
| UT-AP-005 | 单元 | 平衡后可用库存计算 | 是 | P0 |
| UT-AP-006 | 单元 | MR需求明细查询 | 是 | P1 |
| UT-AP-007 | 单元 | 调拨单生成Job替代关系聚合计算 | 是 | P0 |
| UT-AP-008 | 单元 | 调拨单生成Job平衡后可用库存计算(参考3.4.5) | 是 | P0 |
| UT-AP-009 | 单元 | 调拨单生成Job可用天数计算(参考3.4.6) | 是 | P0 |
| UT-AP-010 | 单元 | 调拨单生成Job字段映射与转换 | 是 | P1 |
| UT-AP-010-1 | 单元 | AllocationPlanAllocationTypeEnum枚举值验证 | 是 | P0 |
| UT-AP-010-2 | 单元 | allocationType字段从算法输出到调拨计划映射 | 是 | P0 |
| UT-AP-010-3 | 单元 | allocationTypeDesc字典转换(getDescByTypeSafe) | 是 | P0 |
| UT-AP-010-4 | 单元 | allocationType Excel导出映射(allocationTypeDesc→allocationType) | 是 | P1 |
| UT-AP-011 | 单元 | 调拨单生成Job前置校验(batch_id+Redis锁) | 是 | P0 |
| UT-AP-012 | 单元 | 调拨单生成Job采购建议写入补货建议表 | 是 | P0 |
| IT-AP-001 | 集成 | 全量算法API调用流程 | 是 | P0 |
| IT-AP-002 | 集成 | 部分件号算法调用 | 是 | P0 |
| IT-AP-003 | 集成 | 定时任务自动计算 | 是 | P0 |
| IT-AP-004 | 集成 | 下发中台成功流程 | 是 | P0 |
| IT-AP-005 | 集成 | 下发中台失败流程 | 是 | P1 |
| IT-AP-006 | 集成 | 导出功能 | 是 | P1 |
| IT-AP-007 | 集成 | 算法日志查询 | 是 | P1 |
| IT-AP-008 | 集成 | 调拨单生成Job主执行(jobTaskProcess)全流程 | 是 | P0 |
| IT-AP-009 | 集成 | 调拨单生成Job后置处理(taskAfterProcess)全流程 | 是 | P0 |
| E2E-AP-001 | E2E | 完整调拨建议生命周期 | 是 | P1 |

### 8.2 Mock策略

```yaml
需要Mock的外部依赖:
  - 算法API服务: 使用WireMock模拟算法调用响应
  - 中台API: 使用WireMock模拟下发响应
  - 数据库: 使用H2内存数据库
  - 缓存: 使用Embedded Redis

算法服务Mock响应:
  - 正常响应: 返回调拨建议列表和采购建议列表
  - 超时响应: 延迟35秒后返回
  - 失败响应: 返回HTTP 500
  - 空结果: 返回空列表
```

---

## 9. 依赖与前置条件

### 9.1 外部依赖

| 依赖 | 状态 | 降级策略 | 联系人 |
|------|------|----------|--------|
| 多基地库存平衡与调拨算法服务 | 开发中 | 记录失败日志，不阻塞页面展示 | 算法团队 |
| 中台调拨单下发API | 已完成 | 下发失败，支持重试 | 中台团队 |
| 库存数据服务 | 已完成 | 使用最近一次库存快照 | 数据团队 |
| 在途库存服务 | 已完成 | 显示为0，日志记录 | 数据团队 |
| 替代件服务 | 已完成 | 复用现有ProductSubstituteService | DRP团队 |

### 9.2 内部依赖
- [x] 补货执行模块（算法输出的采购建议写入补货建议表）
- [x] 网络与调拨模块（TransferWarehouseNetwork调拨网络定义）
- [x] 补货建议模块（ReplenishmentStrategyParameters库存策略）
- [x] 主数据模块（SKU详情、仓库信息）
- [x] 库存与仓库模块（在库库存数据）
- [x] 任务调度模块（定时任务执行）

### 9.3 数据依赖
- [x] 库存网络配置（件号站点关系、缺货分配规则、站点优先级、最小调拨数量）
- [x] 调拨网络定义（运输时间）
- [x] by站点by件号需求预测
- [x] by站点by件号库存策略（安全库存、ROP、补货目标）
- [x] 在库库存数据
- [x] 调拨在途信息
- [x] 替代关系数据
- [x] by WSFA(公司) by件号库存策略（ROP）
- [ ] 参数配置：调拨缓冲（X/Y）、严重缺货阈值、基地水位差异阈值

---

## 10. 可观测性设计 (Observability)

### 10.1 日志规范

```yaml
算法任务日志:
  - 任务开始: 记录任务类型、件号数量、触发人
  - 算法调用: 记录算法URL、请求参数摘要、超时时间
  - 算法响应: 记录状态码、响应时间、返回建议数量
  - 任务完成: 记录总耗时、成功/失败件号数量
  - 任务失败: 记录异常堆栈、已处理件号数量

结构化日志:
  - 格式: JSON
  - 必填字段: timestamp, level, traceId, service, message
  - 业务字段: taskId, taskType, skuCount, duration
```

### 10.2 指标监控

```yaml
业务指标:
  - allocation_plan_total_count: 调拨建议单总数
  - allocation_plan_pending_count: 待确认状态数量
  - allocation_plan_issue_success_rate: 下发成功率
  - algorithm_task_duration_seconds: 算法任务耗时
  - algorithm_task_success_rate: 算法任务成功率
```

---

## 11. 原型与交互参考

### 11.1 关键交互

| 操作 | 触发条件 | 交互行为 |
|------|----------|----------|
| 运行平衡算法(全量) | 未勾选数据 | 弹出确认："是否全件号运行平衡算法？" |
| 运行平衡算法(部分) | 勾选N个件号 | 弹出确认："勾选N个件号运行平衡算法" |
| 未来7天MR数量 | 点击字段 | 悬浮窗展示明细列表 |
| 修改计划数量 | 双击单元格 | 弹出编辑框，必填调整原因 |
| 下发 | 勾选记录+点击 | 确认后调用中台API |
| 导出 | 点击按钮 | 按当前筛选条件导出Excel |

### 11.2 界面状态定义

| 状态 | 视觉表现 | 文案 | 交互 |
|------|----------|------|------|
| 待确认 | 橙色标签 | 待确认 | 可编辑、可下发 |
| 下发中 | 蓝色标签+加载动画 | 下发中 | 不可编辑 |
| 已下发 | 绿色标签 | 已下发 | 只读 |
| 下发失败 | 红色标签 | 下发失败 | 可重新下发 |
| 算法运行中 | 顶部横幅 | "平衡算法正在运行中..." | 不可再次触发 |
| 空状态 | 插图 | "暂无调拨建议数据" | 引导运行算法 |

---

## 12. 附录

### 12.1 变更历史

| 版本 | 日期 | 作者 | 变更内容 |
|------|------|------|----------|
| 1.0 | 2026-04-22 | DRP团队 | 初始版本，基于4.4调拨计划需求 |
| 1.1 | 2026-04-27 | DRP团队 | 补充调拨单生成Job逻辑(3.5)，算法输出表/主数据表/替代关系表定义，字段映射表，新增UC-AP-13~17和UT/IT测试项 |
| 1.2 | 2026-05-15 | DRP团队 | 新增allocation_type(缺货分配)字段，算法输出→调拨计划→页面展示→Excel导出全链路映射，新增AllocationPlanAllocationTypeEnum枚举，页面配置(sys_column/cell_setting_template)和字典配置(sys_dict/sys_dict_detail) |
| 1.3 | 2026-06-15 | DRP团队 | 完善allocation_type相关测试项(UT-AP-010-1~4)，补充字段映射规则和边界条件说明 |

版本更新说明:
- v1.1新增3.5节"调拨单生成Job逻辑"，包含Job定义(参考JobProcessService三阶段模式)、数据读取关联流程、替代关系处理逻辑、输入数据表结构、字段映射表
- v1.1新增3.4.5和3.4.6计算规则，明确从dws_alg_allocation_output直接读取各库存字段
- v1.1新增IAllocationPlanGenerateService接口定义
- v1.1新增UC-AP-13~17用例、UT-AP-007~012和IT-AP-008~009测试项

### 12.2 参考文档
- 《多基地库存平衡与调拨算法设计文档_v1.0》
- 《03-replenishment/TDD_SPEC.md》- 补货执行模块规格
- 《11-network-transfer/TDD_SPEC.md》- 网络与调拨模块规格
- JobProcessService框架 — 三阶段Job生命周期(jobTaskProcess/manualTaskBeforeProcess/taskAfterProcess)，参考scpdrp-sys/handle/JobProcessService

### 12.3 术语表

| 术语 | 定义 |
|------|------|
| 平衡算法 | 多基地库存平衡与调拨算法，计算最优调拨方案 |
| 调拨建议单 | 系统计算或人工创建的库存调拨建议记录 |
| 替代件 | 可替代原件号的备选商品，存在替代关系 |
| 调入仓 | 接收调拨货物的目的仓库 |
| 调出仓 | 发出调拨货物的来源仓库 |
| 在途库存 | 已调拨但尚未到达目的仓库的货物 |
| 月均消耗 | 预测配置时间段内平均每月发料数量 |
| 月均调拨出库 | 预测配置时间段内平均每月调拨出库数量 |
| MR需求 | 计划性维修需求（Maintenance Request） |
| 重新订货点 | 触发补货的库存水位线（ROP） |
| 补货目标 | 补货后期望达到的库存水平 |
| 基地平衡 | 多基地间的库存平衡调拨策略 |
| 缺货预警 | allocation_type字段标识，"缺货分配"→是(库存不足时分配)，"常规调拨"/null→否 |

---

## AI开发提示词模板

```markdown
基于以下需求规格，请：

1. **生成单元测试代码**
   每个public方法至少3-5个测试方法
   - 正例、反例、边界测试
   - 使用JUnit4 + Mockito（本项目使用JUnit4）
   - 测试命名规范：`should{期望结果}When{条件}`

2. **生成实现代码**
   - 先写骨架（Entity/DTO/Service接口/Controller定义）
   - Entity使用MyBatis-Plus注解(@TableName, @TableId)
   - Controller使用R<T>包装响应
   - DTO-Entity转换使用MapStruct
   - 导出使用EasyExcel(@ExcelProperty)

3. **生成集成测试**
   - 使用@SpringBootTest
   - 验证数据持久化
   - 使用@Transactional保证测试隔离

4. **代码规范**
   - 包结构: com.sf.scpdrp.biz.{entity,dto,vo,mapper,service,controller,converter,enums}

5. **整理migration脚本**     
   - 整理更新脚本ddl和dml，包括页面配置（sys_cell_setting_template,sys_column_setting_template,sys_filter_setting_template），用户权限配置(sys_permission)，表结构变更，字典值配置（sys_dict,sys_dict_detail）以及历史数据处理
   
    
需求规格：
[Paste Section 3-5 here]
```
