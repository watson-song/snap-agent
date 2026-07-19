# SnapAgent 工作流引擎架构

> 版本：v1.0 | 更新日期：2026-07-17

## 1. 架构概览

SnapAgent 工作流引擎（v1.0）将多个 Skill 串联为一条带条件分支的多步诊断流水线。单个 Skill 只能完成一步诊断动作（如查指标、搜日志、读代码），而工作流可以将它们编排为完整的端到端诊断流程——前一步的输出结果可以被后续步骤引用，从而实现"健康检查 → 错误排查 → 代码分析 → 解决方案建议"的全链路自动化。

典型用例：`full-diagnose` 工作流

```
Trigger (service=order-service)
    │
    ▼
┌─────────────────────┐
│ Step 1: health-check │  skill: health-check
│ condition: (无)       │  → 总是执行
│ onFailure: STOP      │  → 失败则终止
└─────────┬────────────┘
          │ result 存入 stepResults
          ▼
┌─────────────────────────────┐
│ Step 2: find-root-cause      │  skill: error-spike-investigation
│ condition: ${health-check    │  → 前一步结果含 'error' 才执行
│   .result.contains('error')} │
│ onFailure: STOP              │
└─────────┬────────────────────┘
          │
          ▼
┌──────────────────────────────┐
│ Step 3: code-analysis         │  skill: code-analysis
│ condition: ${find-root-cause  │  → 前一步有结果才执行
│   .result != null}            │
│ onFailure: SKIP               │  → 失败则跳过, 继续
└─────────┬─────────────────────┘
          │
          ▼
┌──────────────────────────────┐
│ Step 4: solution-suggest      │  skill: solution-suggest
│ condition: ${code-analysis   │  → 代码分析有输出才执行
│   .result.size > 0}           │
│ onFailure: SKIP               │
└─────────┬─────────────────────┘
          │
          ▼
    WorkflowResult
    (success=true, stepResults, durationMs)
```

**核心组件职责：**

| 组件 | 模块 | 职责 |
|------|------|------|
| `YamlWorkflowLoader` | starter (`boot2x/workflow/`) | 从文件系统 `.yml` 文件解析 `WorkflowDefinition` |
| `SimpleWorkflowEngine` | starter (`boot2x/workflow/`) | 顺序执行步骤，条件求值，失败处理 |
| `WorkflowEngine` (SPI) | core (`core/workflow/`) | 执行引擎接口，宿主可替换实现 |
| `WorkflowDefinition` / `WorkflowStep` | core | 不可变值对象，描述工作流结构 |
| `WorkflowResult` / `StepResult` | core | 不可变值对象，承载执行结果 |

工作流定义以 YAML 格式编写，放在 `snap-agent.workflows.dir` 指定的目录中。启动时 `YamlWorkflowLoader` 扫描所有 `.yml` 文件并解析为 `WorkflowDefinition` 列表。REST API 触发执行后，`SimpleWorkflowEngine` 逐步调用 `AgentExecutor` 运行每个 Skill，汇总结果为 `WorkflowResult` 返回。

---

## 2. 核心 SPI

工作流引擎的核心接口和值对象位于 `snap-agent-core` 的 `cn.watsontech.snapagent.core.workflow` 包下，全部为不可变值对象（defensive copy）。

### 2.1 WorkflowStep

工作流中的一个步骤。每个步骤绑定一个 Skill，可附带条件表达式、输入参数和失败策略。

```java
public final class WorkflowStep {

    // 失败策略常量
    public static final String STOP = "STOP";    // 终止整个工作流
    public static final String SKIP = "SKIP";    // 跳过当前步骤, 继续下一步
    public static final String RETRY = "RETRY";  // 重试当前步骤一次

    private final String name;           // 步骤名 (用于在 condition/inputs 中引用)
    private final String skill;          // 要执行的 Skill 名
    private final String condition;      // 条件表达式 (可空, 空则总是执行)
    private final Map<String, String> inputs;   // 输入参数 (可含 ${trigger.xxx} / ${stepName.result} 引用)
    private final String onFailure;      // 失败策略 (可空, 空=STOP)

    public WorkflowStep(String name, String skill, String condition,
                        Map<String, String> inputs, String onFailure);

    public String getName();
    public String getSkill();
    public String getCondition();
    public Map<String, String> getInputs();   // 不可变视图, 永不为 null
    public String getOnFailure();              // 永不为 null, 默认 STOP
}
```

- `inputs` 在构造时和返回时均做防御性拷贝，null 视为空 map
- `onFailure` 为 null 时默认为 `STOP`

### 2.2 WorkflowDefinition

工作流定义，包含名称、描述和步骤列表。

```java
public final class WorkflowDefinition {

    private final String name;
    private final String description;
    private final List<WorkflowStep> steps;

    public WorkflowDefinition(String name, String description,
                              List<WorkflowStep> steps);

    public String getName();
    public String getDescription();           // 可空
    public List<WorkflowStep> getSteps();     // 不可变视图, 永不为 null
}
```

- `steps` 在构造时和返回时均做防御性拷贝，null 视为空列表

### 2.3 WorkflowResult

工作流执行结果。通过工厂方法构造，记录最终状态、失败信息和各步骤结果。

```java
public final class WorkflowResult {

    private final String workflowName;
    private final WorkflowStatus status;       // COMPLETED / FAILED / ABORTED / RUNNING
    private final String failedStep;           // 失败步骤名 (可空)
    private final String errorMessage;          // 失败原因 (可空)
    private final Map<String, StepResult> stepResults;  // stepName → StepResult
    private final long durationMs;             // 执行耗时 (毫秒)

    // 工厂方法: 成功 (status = COMPLETED)
    public static WorkflowResult success(String name,
                                         Map<String, StepResult> stepResults,
                                         long durationMs);

    // 工厂方法: 失败 (status = FAILED)
    public static WorkflowResult failure(String name, String failedStep,
                                         String error,
                                         Map<String, StepResult> stepResults,
                                         long durationMs);

    public boolean isSuccess();               // 向后兼容: status == COMPLETED
    public WorkflowStatus getStatus();
    public String getFailedStep();
    public String getErrorMessage();
    public Map<String, StepResult> getStepResults();  // 不可变视图
    public long getDurationMs();
}
```

**WorkflowStatus 枚举值：**

| 状态 | 含义 |
|------|------|
| `RUNNING` | 执行中（不用于最终结果） |
| `COMPLETED` | 所有步骤成功完成（或被跳过） |
| `ABORTED` | 因 STOP 策略中止 |
| `FAILED` | 因错误失败 |

### 2.4 StepResult

单个步骤的执行结果。

```java
public final class StepResult {

    private final String stepName;
    private final String taskId;    // AgentTask ID, 或 null (步骤被跳过时)
    private final String status;    // TaskStatus 名称 (SUCCEEDED/FAILED 等), 或 null
    private final String report;    // Agent 报告文本, 或 null

    public StepResult(String stepName, String taskId, String status, String report);

    public String getStepName();
    public String getTaskId();
    public String getStatus();
    public String getReport();
}
```

### 2.5 WorkflowEngine (SPI)

工作流执行引擎接口。宿主应用可实现此接口提供替代执行策略（如并行执行、DAG 调度、人工审批门）。

```java
public interface WorkflowEngine {

    /**
     * 执行工作流。
     *
     * @param workflow       工作流定义
     * @param triggerInputs   触发上下文 (在步骤输入和条件中引用为 ${trigger.xxx})
     * @return 工作流执行结果 (永不为 null)
     */
    WorkflowResult execute(WorkflowDefinition workflow,
                           Map<String, String> triggerInputs);

    /**
     * 返回引擎类型标识 (如 "simple", "dag")。
     */
    String type();
}
```

starter 模块提供默认实现 `SimpleWorkflowEngine`（`type() = "simple"`），通过 `@ConditionalOnMissingBean(WorkflowEngine.class)` 装配，宿主自定义实现会自动替换。

---

## 3. YAML 格式

工作流以 YAML 文件定义，每个 `.yml` 文件代表一个工作流。`YamlWorkflowLoader` 使用 SnakeYAML（Spring Boot 内置）将 YAML 解析为 `Map<String, Object>`，再手动构造 `WorkflowDefinition` 和 `WorkflowStep` 对象。

### 3.1 文件结构

```yaml
name: full-diagnose                    # 必填, 工作流名 (用于 API 引用)
description: "完整诊断流程"             # 可选, 工作流描述
steps:                                 # 必填, 步骤列表
  - name: health-check                 # 必填, 步骤名 (用于 condition/inputs 中引用)
    skill: health-check                # 必填, 要执行的 Skill 名
    condition: "${step.result != null}" # 可选, 条件表达式 (空则总是执行)
    inputs:                            # 可选, 输入参数 (可含变量引用)
      service: "${trigger.service}"
      time_range: "1h"
    onFailure: STOP                    # 可选, 失败策略: STOP / SKIP / RETRY (默认 STOP)
```

### 3.2 字段说明

| 字段 | 必填 | 类型 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | 是 | String | — | 工作流名，用于 REST API 路径引用 |
| `description` | 否 | String | null | 工作流描述 |
| `steps` | 是 | List | — | 步骤列表 |
| `steps[].name` | 是 | String | — | 步骤名，在 condition 和 inputs 中被引用 |
| `steps[].skill` | 是 | String | — | 要执行的 Skill 名（需在 SkillRegistry 中已注册） |
| `steps[].condition` | 否 | String | null | 条件表达式，空则总是执行 |
| `steps[].inputs` | 否 | Map<String,String> | 空 map | 输入参数，值可含 `${trigger.xxx}` 和 `${stepName.result}` 引用 |
| `steps[].onFailure` | 否 | String | `STOP` | 失败策略：`STOP` / `SKIP` / `RETRY` |

### 3.3 文件名与 name 字段

- 每个 `.yml` 文件代表一个工作流
- YAML 中的 `name` 字段优先于文件名——`load(name)` 方法查找 `{name}.yml` 文件
- 文件名不必与 `name` 字段一致，但建议保持一致以避免混淆

### 3.4 内置示例

内置工作流 `full-diagnose.yml`（位于 `starter/src/main/resources/docs/workflows/`）：

```yaml
name: full-diagnose
description: "全链路诊断工作流 — 健康检查 → 错误排查 → 代码分析 → 解决方案建议"
steps:
  - name: health-check
    skill: health-check
    inputs:
      service: "${trigger.service}"
    onFailure: STOP
  - name: find-root-cause
    skill: error-spike-investigation
    condition: "${health-check.result.contains('error')}"
    inputs:
      timeWindow: "1h"
      service: "${trigger.service}"
    onFailure: STOP
  - name: code-analysis
    skill: code-analysis
    condition: "${find-root-cause.result != null}"
    inputs:
      rootCause: "${find-root-cause.result}"
    onFailure: SKIP
  - name: solution-suggest
    skill: solution-suggest
    condition: "${code-analysis.result.size > 0}"
    inputs:
      rootCause: "${find-root-cause.result}"
      codeAnalysis: "${code-analysis.result}"
    onFailure: SKIP
```

---

## 4. SimpleWorkflowEngine 执行逻辑

`SimpleWorkflowEngine` 是 `WorkflowEngine` 的默认实现，按顺序执行步骤，支持条件分支和失败策略。

### 4.1 执行流程

```
execute(workflow, triggerInputs)
    │
    ▼
┌──────────────────────────────────────────────────┐
│  遍历 workflow.steps (顺序)                       │
│                                                    │
│  对每个 step:                                      │
│    1. 求值 condition                                │
│       ├─ null/空 → 总是执行                        │
│       ├─ 求值为 false → 跳过 (记录空 StepResult)  │
│       └─ 求值为 true → 继续                        │
│    2. 解析 inputs 中的 ${...} 占位符               │
│       ├─ ${trigger.xxx} → triggerInputs            │
│       └─ ${stepName.result} → 前序步骤结果         │
│    3. 查找 Skill (SkillRegistry.get)               │
│       └─ 未找到 → 按 onFailure 处理                │
│    4. 执行 Skill (AgentExecutor.execute, 同步)     │
│       └─ 构造 AgentTask → 执行 → StepResult        │
│    5. 失败处理 (onFailure):                        │
│       ├─ RETRY → 重试一次                          │
│       │   └─ 仍失败 → 按非 STOP 处理 (跳过+继续)   │
│       ├─ STOP → 记录失败, 返回 failure             │
│       └─ SKIP → 记录 FAILED 标记, 继续下一步       │
│    6. 成功 → 存入 stepResults                       │
└──────────────────────────────────────────────────┘
    │
    ▼
全部完成 → WorkflowResult.success(name, stepResults, durationMs)
```

### 4.2 核心执行代码

```java
public WorkflowResult execute(WorkflowDefinition workflow,
                               Map<String, String> triggerInputs) {
    long startTime = System.currentTimeMillis();
    Map<String, StepResult> stepResults = new LinkedHashMap<>();

    for (WorkflowStep step : workflow.getSteps()) {
        // 1. 求值条件 — false 则跳过
        if (!evaluateCondition(step.getCondition(), stepResults)) {
            stepResults.put(step.getName(),
                    new StepResult(step.getName(), null, null, null));  // 空 StepResult
            continue;
        }

        // 2. 解析输入占位符
        Map<String, String> resolvedInputs = resolveInputs(
                step.getInputs(), triggerInputs, stepResults);

        // 3. 查找 Skill
        SkillMeta skill = skillRegistry.get(step.getSkill());
        if (skill == null) {
            if (STOP.equals(step.getOnFailure())) {
                return WorkflowResult.failure(...);  // 立即终止
            }
            continue;  // SKIP — 跳过
        }

        // 4. 执行步骤 (可重试)
        StepResult stepResult = executeStep(workflow.getName(), step, skill, resolvedInputs);

        // 5. 失败处理
        if (stepResult == null || !SUCCEEDED.equals(stepResult.getStatus())) {
            if (RETRY.equals(step.getOnFailure())) {
                stepResult = executeStep(...);  // 重试一次
            }
            if (仍失败) {
                if (STOP.equals(step.getOnFailure())) {
                    return WorkflowResult.failure(...);  // 终止
                }
                // SKIP — 记录 FAILED 标记, 继续
                continue;
            }
        }

        // 6. 存入成功结果
        stepResults.put(step.getName(), stepResult);
    }

    return WorkflowResult.success(workflow.getName(), stepResults,
            System.currentTimeMillis() - startTime);
}
```

### 4.3 步骤执行 (executeStep)

每个步骤通过 `AgentExecutor` 同步执行：

```java
private StepResult executeStep(String workflowName, WorkflowStep step,
                               SkillMeta skill, Map<String, String> inputs) {
    try {
        AgentTask task = AgentTask.create(systemUserId, step.getSkill(), inputs, null);
        agentExecutor.execute(task, skill);

        String statusName = task.getStatus() != null ? task.getStatus().name() : null;
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            return new StepResult(step.getName(), task.getTaskId(),
                    statusName, task.getReport());
        }
        return new StepResult(step.getName(), task.getTaskId(), statusName, null);
    } catch (RuntimeException e) {
        return null;  // 异常 → null (触发 onFailure 逻辑)
    }
}
```

- `systemUserId`：工作流以系统用户身份执行（来自 `snap-agent.issue-closure.system-user-id` 配置）
- 成功时 `report` 为 Agent 报告文本，失败时 `report` 为 null
- 异常时返回 null，由上层 onFailure 逻辑处理

### 4.4 失败策略详解

| 策略 | 行为 | 适用场景 |
|------|------|----------|
| `STOP` | 记录失败步骤结果，立即返回 `WorkflowResult.failure` | 关键步骤，失败后后续步骤无意义 |
| `SKIP` | 记录 `StepResult(status=FAILED, report=null)`，继续执行下一步 | 非关键步骤，允许降级 |
| `RETRY` | 重新执行该步骤一次；若仍失败，按 `SKIP` 逻辑处理（记录 FAILED 标记，继续） | 偶发失败（如网络抖动） |

**RETRY 细节**：重试仅一次。若重试后仍然失败，因为 `onFailure` 值为 `"RETRY"`（不等于 `"STOP"`），进入 SKIP 分支——记录 FAILED 标记并继续后续步骤。即 RETRY = 重试一次 → 仍失败则跳过继续。

### 4.5 YamlWorkflowLoader 加载逻辑

```java
public List<WorkflowDefinition> loadAll() {
    // 扫描 workflowsDir 下所有 *.yml 文件
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(workflowsDir, "*.yml")) {
        for (Path file : stream) {
            WorkflowDefinition def = parseFile(file);  // SnakeYAML 解析
            if (def != null) result.add(def);
        }
    }
    return Collections.unmodifiableList(result);
}

public WorkflowDefinition load(String name) {
    Path file = workflowsDir.resolve(name + ".yml");  // 按名查找
    return parseFile(file);  // 不存在或解析失败 → null
}
```

- `workflowsDir` 为 null 时返回空列表
- 目录不存在时返回空列表
- 单个文件解析失败时跳过该文件（WARN 日志），不影响其他文件
- `name` 字段缺失的文件被跳过
- 步骤缺少 `name` 或 `skill` 字段时，该步骤被跳过

---

## 5. 条件表达式

`SimpleWorkflowEngine` 使用基于字符串的简单条件表达式语言（非 SpEL），支持对前序步骤结果进行判断。

### 5.1 语法

条件表达式以 `${...}` 包裹，格式为：

```
${<stepName>.<field>[<operator>]}
```

- `stepName`：前序步骤的 `name`（支持单词字符和连字符 `[a-zA-Z0-9-]`）
- `field`：`result`（报告文本）、`status`（任务状态名）、`taskId`（任务 ID）
- `operator`：可选，见下表

### 5.2 运算符

| 运算符 | 适用字段 | 含义 | 示例 |
|--------|----------|------|------|
| (无) | result/status/taskId | 真值检查：非 null 且非空 | `${health-check.result}` |
| `!= null` | result | 非空检查：report 不为 null | `${find-root-cause.result != null}` |
| `.contains('text')` | result | 字符串包含：report 包含指定文本 | `${health-check.result.contains('error')}` |
| `.size > 0` | result | 非空字符串：report 长度 > 0 | `${code-analysis.result.size > 0}` |
| `== 'value'` | status | 等值检查：status 等于指定值 | `${health-check.status == 'SUCCEEDED'}` |

### 5.3 表达式示例

| 表达式 | 求值结果 | 说明 |
|--------|----------|------|
| (空/null) | `true` | 无条件，总是执行 |
| `${health-check.result}` | report 非空 → true | 真值检查 |
| `${health-check.result != null}` | report 非 null → true | 仅检查非 null（允许空字符串） |
| `${health-check.result.contains('error')}` | report 含 "error" → true | 子串检查 |
| `${health-check.result.size > 0}` | report 非空 → true | 等价于 `!report.isEmpty()` |
| `${health-check.status == 'SUCCEEDED'}` | status 为 SUCCEEDED → true | 精确匹配 |
| `${health-check.taskId}` | taskId 非空 → true | 真值检查 |
| `${unknown-step.result}` | `false` | 步骤不存在 → stepResult 为 null → false |

### 5.4 求值规则

1. **空条件**：condition 为 null 或空白 → 总是返回 `true`（执行该步骤）
2. **格式不匹配**：无法识别的表达式格式 → WARN 日志，默认返回 `true`
3. **步骤不存在**：引用的 stepName 不在 stepResults 中 → 返回 `false`（不执行）
4. **字段映射**：
   - `result` → `StepResult.getReport()`（Agent 报告文本）
   - `status` → `StepResult.getStatus()`（TaskStatus 名称）
   - `taskId` → `StepResult.getTaskId()`
5. **真值语义**：非 null 且（对字符串）非空字符串
6. **单表达式**：不支持 AND/OR 逻辑组合，每个 condition 只能包含一个表达式

### 5.5 输入参数占位符

与条件表达式不同，输入参数中的占位符用于**值替换**（而非布尔求值），支持在单个值中嵌入多个占位符：

| 占位符 | 替换为 | 示例 |
|--------|--------|------|
| `${trigger.<key>}` | `triggerInputs.get("<key>")` | `${trigger.service}` → "order-service" |
| `${<stepName>.result}` | 该步骤的 report 文本 | `${find-root-cause.result}` → "NullPointerException at..." |
| `${<stepName>.status}` | 该步骤的 status 名称 | `${health-check.status}` → "SUCCEEDED" |
| `${<stepName>.taskId}` | 该步骤的 task ID | `${health-check.taskId}` → "task-abc123" |

- 多个占位符可出现在同一个值中：`"根因: ${find-root-cause.result}, 代码: ${code-analysis.result}"`
- 占位符无法解析时替换为空字符串

---

## 6. 内置工作流

SnapAgent 内置一个 `full-diagnose` 全链路诊断工作流，位于 `starter/src/main/resources/docs/workflows/full-diagnose.yml`。

### 6.1 流程说明

```
health-check ──result含'error'?──▶ find-root-cause ──result非null?──▶ code-analysis ──result非空?──▶ solution-suggest
     │                                    │                                  │                               │
     │ STOP                               │ STOP                             │ SKIP                          │ SKIP
     ▼                                    ▼                                  ▼                               ▼
  终止                                 终止                              跳过, 继续                      跳过, 继续
```

### 6.2 步骤详解

| 步骤 | Skill | 条件 | onFailure | 作用 |
|------|-------|------|-----------|------|
| `health-check` | health-check | (无) | STOP | 通过 metrics 指标快照检查系统健康状态 |
| `find-root-cause` | error-spike-investigation | `${health-check.result.contains('error')}` | STOP | 健康检查发现 error 时，排查错误激增的根因 |
| `code-analysis` | code-analysis | `${find-root-cause.result != null}` | SKIP | 根因排查有结果时，分析相关代码 |
| `solution-suggest` | solution-suggest | `${code-analysis.result.size > 0}` | SKIP | 代码分析有输出时，建议修复方案 |

### 6.3 条件链逻辑

1. **Step 1 → Step 2**：`health-check` 的报告文本中包含 "error" 才执行错误排查。如果系统健康（无 error），跳过后续诊断步骤，工作流直接成功结束。
2. **Step 2 → Step 3**：`find-root-cause` 产生了非 null 的报告才执行代码分析。onFailure 为 SKIP，即使代码分析失败也继续。
3. **Step 3 → Step 4**：`code-analysis` 的报告非空才执行解决方案建议。`solution-suggest` 同时引用 `find-root-cause.result`（根因）和 `code-analysis.result`（代码分析）作为输入。

### 6.4 触发方式

通过 REST API 触发，需要提供 `service` 触发输入：

```json
POST /workflows/full-diagnose/run
{
    "service": "order-service"
}
```

`${trigger.service}` 在各步骤的 inputs 中被引用，传入对应的 service 名称。

---

## 7. REST API

工作流引擎提供三个 REST 端点（位于 `SnapAgentController`），均需要认证（`requireAuth()`）。工作流未启用时返回 `503 WORKFLOWS_DISABLED`。

### 7.1 GET /workflows

列出所有已加载的工作流定义。

**响应**（200 OK）：

```json
[
    {
        "name": "full-diagnose",
        "description": "全链路诊断工作流 — 健康检查 → 错误排查 → 代码分析 → 解决方案建议",
        "stepCount": 4
    }
]
```

### 7.2 GET /workflows/{name}

获取指定工作流的完整定义。

**响应**（200 OK）：

```json
{
    "name": "full-diagnose",
    "description": "全链路诊断工作流 — 健康检查 → 错误排查 → 代码分析 → 解决方案建议",
    "steps": [
        {
            "name": "health-check",
            "skill": "health-check",
            "condition": null,
            "inputs": {
                "service": "${trigger.service}"
            },
            "onFailure": "STOP"
        },
        {
            "name": "find-root-cause",
            "skill": "error-spike-investigation",
            "condition": "${health-check.result.contains('error')}",
            "inputs": {
                "timeWindow": "1h",
                "service": "${trigger.service}"
            },
            "onFailure": "STOP"
        }
    ]
}
```

**错误响应**：

| HTTP 状态码 | 错误码 | 说明 |
|-------------|--------|------|
| 404 | `WORKFLOW_NOT_FOUND` | 工作流不存在 |
| 503 | `WORKFLOWS_DISABLED` | 工作流引擎未启用 |

### 7.3 POST /workflows/{name}/run

执行指定工作流，请求体为触发输入参数（`Map<String, String>`）。

**请求**：

```json
POST /workflows/full-diagnose/run
Content-Type: application/json

{
    "service": "order-service"
}
```

**响应**（200 OK）：

```json
{
    "workflowName": "full-diagnose",
    "success": true,
    "status": "COMPLETED",
    "failedStep": null,
    "errorMessage": null,
    "stepResults": {
        "health-check": {
            "stepName": "health-check",
            "taskId": "task-a1b2c3",
            "status": "SUCCEEDED",
            "report": "系统健康检查完成。发现 error: CPU 使用率 95%..."
        },
        "find-root-cause": {
            "stepName": "find-root-cause",
            "taskId": "task-d4e5f6",
            "status": "SUCCEEDED",
            "report": "根因分析: order-service 在 14:00 出现 OOM..."
        },
        "code-analysis": {
            "stepName": "code-analysis",
            "taskId": "task-g7h8i9",
            "status": "SUCCEEDED",
            "report": "代码分析: OrderProcessor.process() 未做内存限制..."
        },
        "solution-suggest": {
            "stepName": "solution-suggest",
            "taskId": "task-j0k1l2",
            "status": "SUCCEEDED",
            "report": "建议: 1. 在 OrderProcessor 中增加批量大小限制..."
        }
    },
    "durationMs": 45230
}
```

**失败响应示例**（STOP 策略触发终止）：

```json
{
    "workflowName": "full-diagnose",
    "success": false,
    "status": "FAILED",
    "failedStep": "health-check",
    "errorMessage": "step execution failed",
    "stepResults": {
        "health-check": {
            "stepName": "health-check",
            "taskId": "task-a1b2c3",
            "status": "FAILED",
            "report": null
        }
    },
    "durationMs": 5230
}
```

**跳过步骤的响应**：被条件跳过的步骤在 `stepResults` 中以空值记录：

```json
"stepResults": {
    "health-check": {
        "stepName": "health-check",
        "taskId": "task-a1b2c3",
        "status": "SUCCEEDED",
        "report": "系统正常, 无异常指标。"
    },
    "find-root-cause": {
        "stepName": "find-root-cause",
        "taskId": null,
        "status": null,
        "report": null
    }
}
```

---

## 8. 配置与扩展

### 8.1 配置

```yaml
snap-agent:
  workflows:
    enabled: false             # 总开关, 默认 false (未启用时零工作流 Bean)
    dir: ""                   # 工作流 .yml 文件目录, 空则默认 {upload-skills-dir}/workflows/
```

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `snap-agent.workflows.enabled` | `false` | 工作流引擎总开关。false 时不装配任何工作流 Bean，REST API 返回 503 |
| `snap-agent.workflows.dir` | `""` (空) | 工作流 YAML 文件目录。空时默认为 `{upload-skills-dir}/workflows/`，启动时自动创建 |

### 8.2 自动装配

`SnapAgentAutoConfiguration` 在 `enabled=true` 时装配两个 Bean：

```java
@Bean
@ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public YamlWorkflowLoader yamlWorkflowLoader(SnapAgentProperties props) {
    // dir 为空 → {upload-skills-dir}/workflows/
    // 自动创建目录
    return new YamlWorkflowLoader(workflowsDir);
}

@Bean
@ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(WorkflowEngine.class)
public SimpleWorkflowEngine simpleWorkflowEngine(
        AgentExecutor agentExecutor,
        SkillRegistry skillRegistry,
        SnapAgentProperties props) {
    // systemUserId 来自 snap-agent.issue-closure.system-user-id
    return new SimpleWorkflowEngine(agentExecutor, skillRegistry, systemUserId);
}
```

- 两个 Bean 均受 `@ConditionalOnProperty(enabled=true)` 控制
- `YamlWorkflowLoader` 用 `@ConditionalOnMissingBean`（默认匹配返回类型），宿主可声明同类型 Bean 替换
- `SimpleWorkflowEngine` 用 `@ConditionalOnMissingBean(WorkflowEngine.class)`，宿主实现 `WorkflowEngine` 接口即可替换
- 工作流以系统用户身份执行，`systemUserId` 复用 `snap-agent.issue-closure.system-user-id` 配置

### 8.3 自定义执行引擎

实现 `WorkflowEngine` 接口并注册为 Spring Bean，即可替换默认的 `SimpleWorkflowEngine`：

```java
@Component
public class DagWorkflowEngine implements WorkflowEngine {

    @Override
    public WorkflowResult execute(WorkflowDefinition workflow,
                                   Map<String, String> triggerInputs) {
        // 自定义执行策略: DAG 并行调度、人工审批门、循环等
        // ...
    }

    @Override
    public String type() {
        return "dag";
    }
}
```

注册后，`@ConditionalOnMissingBean(WorkflowEngine.class)` 使默认 `SimpleWorkflowEngine` 不装配。

### 8.4 自定义工作流加载

`YamlWorkflowLoader` 为具体类（非 SPI 接口），宿主可通过以下方式扩展：

- **子类化**：继承 `YamlWorkflowLoader`，覆盖 `loadAll()` / `load(name)` 从数据库或外部 API 加载
- **替换 Bean**：声明同类型 Bean，`@ConditionalOnMissingBean` 使默认实现不装配

```java
@Component
public class DatabaseWorkflowLoader extends YamlWorkflowLoader {

    public DatabaseWorkflowLoader() {
        super(null);  // 不使用文件系统目录
    }

    @Override
    public List<WorkflowDefinition> loadAll() {
        // 从数据库加载工作流定义
        return jdbcTemplate.query("SELECT name, definition FROM workflows",
            (rs, i) -> parseYaml(rs.getString("definition")));
    }
}
```

### 8.5 已知限制与规划

| 限制 | 说明 | 计划 |
|------|------|------|
| 顺序执行 | 仅支持线性步骤序列，不支持并行/DAG | v1.0.1 并行调度 |
| 无循环 | 不支持 step 循环/迭代 | v1.0.1 循环节点 |
| 无人工审批 | 不支持暂停等待人工确认后继续 | v1.0.1 人工审批门 |
| 无定时/事件触发 | 仅支持 REST API 手动触发 | v1.0.1 定时/事件触发 |
| 单条件表达式 | 不支持 AND/OR 逻辑组合 | 未来增强 |
| 无 `.size >= N` | 仅支持 `.size > 0`，不支持任意数值比较 | 未来增强 |
| 同步执行 | 步骤同步调用 `AgentExecutor`，长时间工作流阻塞 HTTP 线程 | v1.0.1 异步执行 |
