# TDD需求规格说明书 — 工作流引擎 (Workflow Engine)

> 版本: 2.0 | 模块: `snap-agent-core/workflow` + `snap-agent-spring-boot-2x-starter/workflow`

---

## 1. 需求元信息

```yaml
需求ID: REQ-WF-07
需求名称: SimpleWorkflowEngine / YAML工作流 / 条件DSL / 失败策略
优先级: P1 | 迭代: v1.0-ecosystem | 状态: 开发中
```

**背景**: v1.0引入工作流引擎，将多个Skill编排为顺序执行的工作流，支持条件分支、失败策略和步骤间结果传递。**目标**: 减少80%人工多步操作，将健康检查→根因分析→方案建议串联为单次触发。**指标**: 执行成功率>95%；条件跳转准确率100%；YAML加载容错率100%。

**范围**: WorkflowEngine SPI、SimpleWorkflowEngine（顺序+条件）、YamlWorkflowLoader、条件DSL（5种模式）、失败策略（STOP/SKIP/RETRY）、输入占位符解析、WorkflowResult聚合。**不含**: 并行/DAG（v1.0.1）、循环/审批（v1.0.1）。

| 风险 | 描述 | 缓解 |
|------|------|------|
| R1 | 条件DSL不支持SpEL，语法有限 | 文档明确支持模式，无法识别默认true |
| R2 | RETRY仅一次，无退避 | v1.0.1扩展 |
| R3 | SnakeYAML坏文件 | loadAll跳过+WARN日志 |

---

## 2. 用户故事 (User Stories)

### US-1: 加载YAML工作流定义
```gherkin
作为 运维管理员
我希望 从文件系统目录加载.yml工作流定义文件
以便 无需编码即可编排多步诊断流程
```
**AC:**
```gherkin
AC1: Given workflows目录含valid.yml / When loadAll() / Then 返回非空WorkflowDefinition列表
AC2: Given 目录含格式错误的bad.yml / When loadAll() / Then 坏文件跳过，其他正常加载，记录WARN
```

### US-2: 顺序执行步骤与条件跳过
```gherkin
作为 平台开发者
我希望 引擎按顺序执行步骤，支持条件表达式跳过
以便 实现基于前序结果的动态分支
```
**AC:**
```gherkin
AC1: Given 步骤B condition="${stepA.result != null}"且stepA report=null / When 执行 / Then 步骤B跳过，StepResult全null，工作流COMPLETED
```

### US-3: 失败策略处理
```gherkin
作为 平台开发者
我希望 步骤失败时按STOP/SKIP/RETRY策略处理
以便 灵活控制故障传播范围
```
**AC:**
```gherkin
AC1: Given 步骤onFailure=STOP且失败 / When 执行 / Then 工作流终止，status=FAILED，failedStep=该步骤名
AC2: Given 步骤onFailure=SKIP且失败 / When 执行 / Then 该步骤记录FAILED，工作流继续
AC3: Given 步骤onFailure=RETRY且首次失败 / When 执行 / Then 重试一次；仍失败按非STOP语义继续
```

### US-4: 步骤间结果传递
```gherkin
作为 平台开发者
我希望 后续步骤可引用前序步骤的result/status/taskId及触发器输入
以便 实现数据流自动传递
```
**AC:**
```gherkin
AC1: Given input="${trigger.service}" / When trigger含service=order-svc / Then resolved="order-svc"
AC2: Given input="${stepA.result}" / When stepA report="error found" / Then resolved="error found"
```

### US-5: 工作流与Agent集成
```gherkin
作为 平台开发者
我希望 工作流引擎通过AgentExecutor执行Skill并聚合结果
以便 复用已有的Agent执行循环和Skill注册体系
```
**AC:**
```gherkin
AC1: Given 步骤skill="health-check" / When 执行 / Then SkillRegistry.get调用+AgentExecutor.execute调用+StepResult从AgentTask提取
AC2: Given skill="nonexistent" onFailure=STOP / When 执行 / Then status=FAILED errorMessage含"skill not found"
```

### US-6: 工作流结果聚合
```gherkin
作为 平台开发者
我希望 工作流返回含所有步骤结果和耗时的WorkflowResult
以便 调用方了解整体执行情况
```
**AC:**
```gherkin
AC1: Given 所有步骤成功 / When 执行 / Then status=COMPLETED stepResults含全部步骤 durationMs>0
AC2: Given steps为空 / When 执行 / Then status=COMPLETED stepResults为空Map
```

### US-7: 条件 DSL 多模式匹配
```gherkin
作为 工作流开发者
我希望 步骤条件支持 !=null、contains、==、bare truthy 多种模式
  以便 灵活控制步骤执行流程
```
**AC:**
```gherkin
AC1: Given step.condition = "result != null"
  When evaluateCondition 执行且 result 非空
  Then 返回 true (执行该步骤)
  Given step.condition = "result != null"
  When result 为 null
  Then 返回 false (跳过该步骤)
AC2: Given step.condition = "result.contains('error')"
  When result = "connection error occurred"
  Then 返回 true
  Given step.condition = "result.contains('error')"
  When result = "success"
  Then 返回 false
AC3: Given step.condition = "status == 'PENDING'"
  When result.status = "PENDING"
  Then 返回 true
  Given step.condition = "status == 'PENDING'"
  When result.status = "DONE"
  Then 返回 false
```

### US-8: 工作流结果 HTML 提取
```gherkin
作为 系统开发者
我希望 从 workflow 执行结果中提取 HTML 内容
  以便 inject 模式可复用 workflow 生成的结构化输出
```
**AC:**
```gherkin
AC1: Given stepResults 含 step "render" 的 result 为 "<div>报告</div>"
  When extractHtmlFromWorkflowResult(stepResults)
  Then 返回 "<div>报告</div>"
AC2: Given stepResults 为空或 null
  When extractHtmlFromWorkflowResult(stepResults)
  Then 返回空串 ""
AC3: Given stepResults 所有 step 均无 HTML 内容
  When extractHtmlFromWorkflowResult(stepResults)
  Then 返回空串 ""
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 定义 | US-1 YAML加载 | 无代码编排 | 加载成功率100% | - |
| 执行 | US-2 顺序+条件 | 动态分支 | 跳转准确率100% | US-1 |
| 容错 | US-3 失败策略 | 故障隔离 | 策略正确率100% | US-2 |
| 传参 | US-4 结果传递 | 数据流转 | 解析率100% | US-2 |
| 集成 | US-5 Agent集成 | 复用执行 | Skill查找>95% | US-2 |
| 聚合 | US-6 结果聚合 | 全局可观测 | 完整率100% | US-5 |
| 条件 | US-7 | 灵活控制 | 5种模式覆盖 | US-2 |
| 提取 | US-8 | 结果复用 | HTML 提取 100% | US-6 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | YAML加载有效文件 | P0 | US-1 AC1 | 单元 |
| UC-02 | YAML坏文件容错 | P1 | US-1 AC2 | 单元 |
| UC-03 | 条件!=null评估 | P0 | US-2 AC1 | 单元 |
| UC-04 | 条件.contains()评估 | P0 | US-2 AC1 | 单元 |
| UC-05 | 条件==值评估 | P0 | US-2 AC1 | 单元 |
| UC-06 | STOP策略终止 | P0 | US-3 AC1 | 单元 |
| UC-07 | SKIP策略继续 | P0 | US-3 AC2 | 单元 |
| UC-08 | RETRY策略重试 | P0 | US-3 AC3 | 单元 |
| UC-09 | trigger占位符解析 | P0 | US-4 AC1 | 单元 |
| UC-10 | stepName.result占位符 | P0 | US-4 AC2 | 单元 |
| UC-11 | Skill未找到+STOP | P1 | US-5 AC2 | 单元 |
| UC-12 | 空工作流成功 | P1 | US-6 AC2 | 单元 |

### 3.2 详细用例 (Gherkin)

#### UC-03/04/05: 条件DSL评估 (!=null / .contains / ==)
```gherkin
@priority:high @type:unit
功能: 条件DSL 5种模式评估
  场景: result != null — report有值时为真
    Given "stepA" report="error detected"
    When 评估"${stepA.result != null}"
    Then 返回true
  场景: result != null — report为null/步骤不存在时为假
    Given "stepA" report=null 或 stepResults不含"stepA"
    When 评估"${stepA.result != null}"
    Then 返回false，步骤跳过
  场景: .contains('text') — 包含时为真
    Given "health-check" report="CRITICAL: CPU > 90%"
    When 评估"${health-check.result.contains('CRITICAL')}"
    Then 返回true
  场景: .contains('text') — 不包含或report=null时为假
    Given "health-check" report="All normal" 或 report=null
    When 评估"${health-check.result.contains('error')}"
    Then 返回false
  场景: == 'value' — status匹配/不匹配
    Given "check" status="SUCCEEDED"
    When 评估"${check.status == 'SUCCEEDED'}"
    Then 返回true（status="FAILED"时返回false）
  场景: 裸truthy — result/status/taskId非null非空时为真
    Given "stepA" report="ok" status="SUCCEEDED" taskId="t-1"
    When 评估"${stepA.result}" 或 "${stepA.status}" 或 "${stepA.taskId}"
    Then 均返回true
```

#### UC-06: STOP策略终止
```gherkin
@priority:high @type:unit
功能: 步骤失败时onFailure=STOP终止工作流
  场景: 失败步骤终止整个工作流
    Given 工作流含3步骤，步骤2 onFailure=STOP
    And AgentExecutor对步骤2抛RuntimeException
    When 执行工作流
    Then status=FAILED，failedStep=步骤2名
    And stepResults仅含步骤1和2，步骤3未执行
  场景: Skill未找到时STOP
    Given skill="nonexistent" onFailure=STOP
    When 执行
    Then status=FAILED errorMessage="skill not found: nonexistent"
```

#### UC-07: SKIP策略继续
```gherkin
@priority:high @type:unit
功能: 步骤失败时onFailure=SKIP继续
  场景: 失败步骤跳过后继续
    Given 工作流含3步骤，步骤2 onFailure=SKIP
    And AgentExecutor对步骤2返回FAILED
    When 执行
    Then status=COMPLETED，stepResults含步骤2(status=FAILED)
    And 步骤3正常执行
```

#### UC-08: RETRY策略重试一次
```gherkin
@priority:high @type:unit
功能: 步骤失败时onFailure=RETRY重试一次
  场景: 首次失败重试成功
    Given onFailure=RETRY，首次FAILED第二次SUCCEEDED
    When 执行
    Then StepResult.status=SUCCEEDED，工作流继续
  场景: 重试仍失败时按SKIP处理
    Given onFailure=RETRY，两次均FAILED
    When 执行
    Then stepResults中该步骤status=FAILED，工作流继续
```

#### UC-09/10: 输入占位符解析 (trigger + stepName)
```gherkin
@priority:high @type:unit
功能: 解析${trigger.xxx}和${stepName.result|status|taskId}占位符
  场景: trigger占位符替换
    Given input service="${trigger.service}"，trigger含service=order-svc
    When 解析
    Then resolved service="order-svc"
  场景: 占位符在字符串中间
    Given input "Checking ${trigger.env} env"，trigger含env=prod
    Then resolved="Checking prod env"
  场景: trigger不存在key时替换为空字符串
    Given input x="${trigger.missing}"
    Then resolved x=""
  场景: 引用前序步骤report
    Given "diagnose" report="Root cause: OOM"，input cause="${diagnose.result}"
    Then resolved cause="Root cause: OOM"
  场景: 引用status和taskId
    Given "check" status="SUCCEEDED" taskId="task-123"
    Then "${check.status}"="SUCCEEDED"，"${check.taskId}"="task-123"
```

#### UC-11: Skill未找到
```gherkin
@priority:medium @type:unit
功能: Skill在Registry中未找到时处理
  场景: STOP时终止
    Given skill="nonexistent" onFailure=STOP
    When 执行
    Then status=FAILED，errorMessage="skill not found: nonexistent"，AgentExecutor未调用
  场景: SKIP时继续
    Given skill="nonexistent" onFailure=SKIP
    When 执行
    Then 该步骤StepResult全null，工作流继续
```

#### UC-12: 空工作流
```gherkin
@priority:medium @type:unit
功能: 空步骤列表工作流
  场景: 无步骤
    Given steps为空列表
    When 执行
    Then status=COMPLETED，stepResults为空Map，durationMs>=0
```

---

## 4. 接口规格 (API Specs)

### 4.1 WorkflowEngine SPI
```java
WorkflowResult execute(WorkflowDefinition workflow, Map<String,String> triggerInputs);
String type(); // "simple"
// 测试: 正常执行 / null triggerInputs容错 / 空steps返回成功
```

### 4.2 SimpleWorkflowEngine内部方法
```java
boolean evaluateCondition(String condition, Map<String,StepResult> stepResults);
// 支持: !=null / .contains('') / .size>0 / =='' / 裸truthy
// 测试: null条件→true / 无法识别→true / 各模式true/false

Map<String,String> resolveInputs(Map<String,String> inputs, Map<String,String> triggerInputs, Map<String,StepResult> stepResults);
// 支持: ${trigger.xxx} / ${stepName.result|status|taskId}
// 测试: 单占位符 / 多占位符 / 未找到→空字符串
```

### 4.3 YamlWorkflowLoader
```java
List<WorkflowDefinition> loadAll(); // null目录→空 / 不存在→空 / 坏文件跳过
WorkflowDefinition load(String name); // 按文件名加载
```

---

## 5. 数据规格 (Data Specs)

```yaml
WorkflowDefinition: 不可变，steps防御性拷贝+不可变视图，null→空列表
WorkflowStep: 不可变，inputs防御性拷贝，onFailure默认STOP
  常量: STOP="STOP" SKIP="SKIP" RETRY="RETRY"
StepResult: 不可变，跳过步骤taskId/status/report均null
WorkflowResult: 不可变，stepResults防御性拷贝，工厂success()/failure()
WorkflowStatus: 枚举 RUNNING/COMPLETED/ABORTED/FAILED
条件DSL: ${stepName.field [op]}
  field: result|status|taskId
  op: !=null / .contains('text') / .size>0 / =='value' / 裸truthy
```

---

## 6. 错误处理规格

| 场景 | 行为 | 日志 |
|------|------|------|
| Skill未找到+STOP | 终止，FAILED | ERROR |
| Skill未找到+SKIP | 跳过继续 | ERROR |
| 步骤抛异常 | StepResult=null按onFailure | ERROR |
| 条件无法识别 | 默认true | WARN |
| YAML缺name | 跳过文件 | WARN |
| YAML解析异常 | 跳过返回null | WARN |
| trigger引用不存在 | 替换空字符串 | DEBUG |

---

## 7. 非功能需求 (NFR)

- [x] 核心模型单元覆盖率>80%
- [x] 不可变性验证（防御性拷贝+不可变视图）
- [x] 条件DSL边界测试（null/空/无法识别）
- [x] 失败策略全分支覆盖
- [x] YAML加载容错

---

## 8. 测试策略 (Test Strategy)

### 8.1 已有测试覆盖

| 测试类 | 模块 | 覆盖内容 | 数量 |
|--------|------|----------|------|
| WorkflowStepTest | core | getter/防御拷贝/不可变/null默认/STOP-SKIP-RETRY/toString | 8 |
| StepResultTest | core | getter/null字段/空字符串/toString chars | 6 |
| WorkflowResultTest | core | success/failure工厂/防御拷贝/不可变/null空/isSuccess兼容/toString | 7 |
| WorkflowStatusTest | core | 枚举完整/valueOf/唯一性 | 3 |

### 8.2 测试缺口

| 缺口 | 描述 | 优先级 | 涉及类 |
|------|------|--------|--------|
| GAP-1 | **SimpleWorkflowEngine无单元测试** — execute/evaluateCondition/resolveInputs全未测 | P0 | SimpleWorkflowEngine |
| GAP-2 | **YamlWorkflowLoader无单元测试** — loadAll/load/parseFile全未测 | P0 | YamlWorkflowLoader |
| GAP-3 | 条件DSL 5种模式无独立测试 | P0 | evaluateCondition |
| GAP-4 | STOP/SKIP/RETRY端到端测试缺失 | P0 | execute |
| GAP-5 | 占位符解析(trigger+stepName)测试缺失 | P0 | resolveInputs/resolveValue |
| GAP-6 | Skill未找到场景测试缺失 | P1 | execute |
| GAP-7 | RETRY重试后仍失败行为缺失 | P1 | execute |
| GAP-8 | YAML坏文件/空文件容错缺失 | P1 | parseFile |
| GAP-9 | 多占位符混合解析缺失 | P2 | resolveValue |

### 8.3 Mock策略
```yaml
AgentExecutor: Mockito mock，设置task status/report
SkillRegistry: Mockito mock，返回SkillMeta或null
文件系统: 临时目录创建.yml测试文件
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| AgentExecutor | 已完成(v0.1) | 同步调用 |
| SkillRegistry | 已完成(v0.1) | 未找到按onFailure |
| SnakeYAML | Spring Boot自带 | 解析异常跳过 |

---

## 10. 可观测性设计
```yaml
日志: 跳过/完成=INFO, 未找到/异常=ERROR, 重试/失败跳过=INFO
```
---

## 11. 原型与交互参考
不适用（纯后端SPI模块）。

---

## 12. 附录
### 变更历史
| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0 | 2026-07-23 | 基于v1.0-ecosystem创建 |
### 参考文档
`docs/superpowers/specs/2026-07-16-v1.0-ecosystem-design.md` / `docs/tdd/TEMPLATE.md`
### 术语表
| 术语 | 定义 |
|------|------|
| WorkflowDefinition | 工作流定义(name/description/steps) |
| WorkflowStep | 步骤(skill/condition/inputs/onFailure) |
| Condition DSL / onFailure | 5种条件模式 / STOP终止·SKIP跳过·RETRY重试 |
| StepResult | 单步结果(taskId/status/report) |
