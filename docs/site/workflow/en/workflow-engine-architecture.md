# SnapAgent Workflow Engine Architecture

> Version: v1.0 | Updated: 2026-07-17

## 1. Architecture Overview

The SnapAgent workflow engine (v1.0) chains multiple Skills into a multi-step diagnostic pipeline with conditional branching. A single Skill can only perform one diagnostic action (e.g., query metrics, search logs, read code), while a workflow orchestrates them into a complete end-to-end diagnostic flow — the output of each step can be referenced by subsequent steps, enabling automation like "health check → error investigation → code analysis → solution suggestion."

Typical use case: `full-diagnose` workflow

```
Trigger (service=order-service)
    │
    ▼
┌─────────────────────┐
│ Step 1: health-check │  skill: health-check
│ condition: (none)     │  → always executes
│ onFailure: STOP      │  → abort on failure
└─────────┬────────────┘
          │ result stored in stepResults
          ▼
┌─────────────────────────────┐
│ Step 2: find-root-cause      │  skill: error-spike-investigation
│ condition: ${health-check    │  → executes only if prior result
│   .result.contains('error')} │    contains 'error'
│ onFailure: STOP              │
└─────────┬────────────────────┘
          │
          ▼
┌──────────────────────────────┐
│ Step 3: code-analysis         │  skill: code-analysis
│ condition: ${find-root-cause  │  → executes only if prior step
│   .result != null}            │    produced a result
│ onFailure: SKIP               │  → skip on failure, continue
└─────────┬─────────────────────┘
          │
          ▼
┌──────────────────────────────┐
│ Step 4: solution-suggest      │  skill: solution-suggest
│ condition: ${code-analysis   │  → executes only if code analysis
│   .result.size > 0}           │    produced output
│ onFailure: SKIP               │
└─────────┬─────────────────────┘
          │
          ▼
    WorkflowResult
    (success=true, stepResults, durationMs)
```

**Core component responsibilities:**

| Component | Module | Responsibility |
|-----------|--------|----------------|
| `YamlWorkflowLoader` | starter (`boot2x/workflow/`) | Parses `WorkflowDefinition` from `.yml` files on the filesystem |
| `SimpleWorkflowEngine` | starter (`boot2x/workflow/`) | Sequential step execution, condition evaluation, failure handling |
| `WorkflowEngine` (SPI) | core (`core/workflow/`) | Engine interface; hosts can replace the implementation |
| `WorkflowDefinition` / `WorkflowStep` | core | Immutable value objects describing the workflow structure |
| `WorkflowResult` / `StepResult` | core | Immutable value objects carrying execution results |

Workflow definitions are written in YAML format and placed in the directory specified by `snap-agent.workflows.dir`. At startup, `YamlWorkflowLoader` scans all `.yml` files and parses them into a list of `WorkflowDefinition` objects. When triggered via REST API, `SimpleWorkflowEngine` executes each step by calling `AgentExecutor` to run the corresponding Skill, aggregating results into a `WorkflowResult`.

---

## 2. Core SPI

The core interfaces and value objects reside in the `cn.watsontech.snapagent.core.workflow` package of `snap-agent-core`. All are immutable value objects (defensive copy).

### 2.1 WorkflowStep

A single step in a workflow. Each step binds to a Skill, with an optional condition expression, input parameters, and failure policy.

```java
public final class WorkflowStep {

    // Failure policy constants
    public static final String STOP = "STOP";    // Abort the entire workflow
    public static final String SKIP = "SKIP";    // Skip this step, continue to next
    public static final String RETRY = "RETRY";  // Retry this step once

    private final String name;           // Step name (referenced in condition/inputs)
    private final String skill;          // Skill name to execute
    private final String condition;      // Condition expression (null = always execute)
    private final Map<String, String> inputs;   // Input params (may contain ${trigger.xxx} / ${stepName.result})
    private final String onFailure;      // Failure policy (null = STOP)

    public WorkflowStep(String name, String skill, String condition,
                        Map<String, String> inputs, String onFailure);

    public String getName();
    public String getSkill();
    public String getCondition();
    public Map<String, String> getInputs();   // Unmodifiable view, never null
    public String getOnFailure();              // Never null, defaults to STOP
}
```

- `inputs` is defensively copied on both input and output; null is treated as an empty map
- `onFailure` defaults to `STOP` when null

### 2.2 WorkflowDefinition

A workflow definition containing a name, description, and list of steps.

```java
public final class WorkflowDefinition {

    private final String name;
    private final String description;
    private final List<WorkflowStep> steps;

    public WorkflowDefinition(String name, String description,
                              List<WorkflowStep> steps);

    public String getName();
    public String getDescription();           // May be null
    public List<WorkflowStep> getSteps();     // Unmodifiable view, never null
}
```

- `steps` is defensively copied on both input and output; null is treated as an empty list

### 2.3 WorkflowResult

The workflow execution result. Constructed via factory methods; records the final status, failure information, and per-step results.

```java
public final class WorkflowResult {

    private final String workflowName;
    private final WorkflowStatus status;       // COMPLETED / FAILED / ABORTED / RUNNING
    private final String failedStep;           // Failed step name (may be null)
    private final String errorMessage;          // Failure reason (may be null)
    private final Map<String, StepResult> stepResults;  // stepName → StepResult
    private final long durationMs;             // Execution duration (ms)

    // Factory: success (status = COMPLETED)
    public static WorkflowResult success(String name,
                                         Map<String, StepResult> stepResults,
                                         long durationMs);

    // Factory: failure (status = FAILED)
    public static WorkflowResult failure(String name, String failedStep,
                                         String error,
                                         Map<String, StepResult> stepResults,
                                         long durationMs);

    public boolean isSuccess();               // Backward compat: status == COMPLETED
    public WorkflowStatus getStatus();
    public String getFailedStep();
    public String getErrorMessage();
    public Map<String, StepResult> getStepResults();  // Unmodifiable view
    public long getDurationMs();
}
```

**WorkflowStatus enum values:**

| Status | Meaning |
|--------|---------|
| `RUNNING` | Currently executing (not used in final results) |
| `COMPLETED` | All steps completed successfully (or skipped) |
| `ABORTED` | Aborted due to a step failure with STOP policy |
| `FAILED` | Failed due to an error |

### 2.4 StepResult

The execution result of a single step.

```java
public final class StepResult {

    private final String stepName;
    private final String taskId;    // AgentTask ID, or null (when step is skipped)
    private final String status;    // TaskStatus name (SUCCEEDED/FAILED etc.), or null
    private final String report;    // Agent report text, or null

    public StepResult(String stepName, String taskId, String status, String report);

    public String getStepName();
    public String getTaskId();
    public String getStatus();
    public String getReport();
}
```

### 2.5 WorkflowEngine (SPI)

The workflow execution engine interface. Host applications can implement this to provide alternative execution strategies (e.g., parallel execution, DAG-based scheduling, human-in-the-loop gates).

```java
public interface WorkflowEngine {

    /**
     * Execute a workflow.
     *
     * @param workflow       the workflow definition
     * @param triggerInputs   the trigger context (referenced as ${trigger.xxx}
     *                        in step inputs and conditions)
     * @return the workflow execution result (never null)
     */
    WorkflowResult execute(WorkflowDefinition workflow,
                           Map<String, String> triggerInputs);

    /**
     * Returns the engine type identifier (e.g., "simple", "dag").
     */
    String type();
}
```

The starter module provides a default implementation `SimpleWorkflowEngine` (`type() = "simple"`), assembled via `@ConditionalOnMissingBean(WorkflowEngine.class)`. A custom host implementation automatically replaces it.

---

## 3. YAML Format

Workflows are defined in YAML files, with each `.yml` file representing a single workflow. `YamlWorkflowLoader` uses SnakeYAML (bundled with Spring Boot) to parse YAML into a `Map<String, Object>`, then manually constructs `WorkflowDefinition` and `WorkflowStep` objects.

### 3.1 File Structure

```yaml
name: full-diagnose                    # Required, workflow name (used for API references)
description: "Full diagnostic pipeline"  # Optional, workflow description
steps:                                 # Required, list of steps
  - name: health-check                 # Required, step name (referenced in condition/inputs)
    skill: health-check                # Required, Skill name to execute
    condition: "${step.result != null}" # Optional, condition expression (empty = always execute)
    inputs:                            # Optional, input parameters (may contain variable references)
      service: "${trigger.service}"
      time_range: "1h"
    onFailure: STOP                    # Optional, failure policy: STOP / SKIP / RETRY (default STOP)
```

### 3.2 Field Reference

| Field | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `name` | Yes | String | — | Workflow name, used in REST API path references |
| `description` | No | String | null | Workflow description |
| `steps` | Yes | List | — | List of steps |
| `steps[].name` | Yes | String | — | Step name, referenced in conditions and inputs |
| `steps[].skill` | Yes | String | — | Skill name to execute (must be registered in SkillRegistry) |
| `steps[].condition` | No | String | null | Condition expression; empty = always execute |
| `steps[].inputs` | No | Map<String,String> | empty map | Input parameters; values may contain `${trigger.xxx}` and `${stepName.result}` references |
| `steps[].onFailure` | No | String | `STOP` | Failure policy: `STOP` / `SKIP` / `RETRY` |

### 3.3 File Name vs. Name Field

- Each `.yml` file represents one workflow
- The `name` field in the YAML takes precedence over the file name — the `load(name)` method looks up `{name}.yml`
- The file name does not need to match the `name` field, but keeping them consistent is recommended to avoid confusion

### 3.4 Built-in Example

The built-in workflow `full-diagnose.yml` (located at `starter/src/main/resources/docs/workflows/`):

```yaml
name: full-diagnose
description: "Full diagnostic workflow — health check → error investigation → code analysis → solution suggestion"
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

## 4. SimpleWorkflowEngine Execution Logic

`SimpleWorkflowEngine` is the default `WorkflowEngine` implementation. It executes steps sequentially, supporting conditional branching and failure policies.

### 4.1 Execution Flow

```
execute(workflow, triggerInputs)
    │
    ▼
┌──────────────────────────────────────────────────┐
│  Iterate over workflow.steps (sequential)         │
│                                                    │
│  For each step:                                    │
│    1. Evaluate condition                           │
│       ├─ null/empty → always execute             │
│       ├─ evaluates false → skip (record empty     │
│       │                   StepResult)             │
│       └─ evaluates true → proceed                 │
│    2. Resolve ${...} placeholders in inputs       │
│       ├─ ${trigger.xxx} → triggerInputs            │
│       └─ ${stepName.result} → prior step result    │
│    3. Look up Skill (SkillRegistry.get)            │
│       └─ not found → handle per onFailure         │
│    4. Execute Skill (AgentExecutor.execute, sync)  │
│       └─ construct AgentTask → execute →          │
│          StepResult                                │
│    5. Failure handling (onFailure):                │
│       ├─ RETRY → retry once                       │
│       │   └─ still failing → treat as non-STOP    │
│       │       (skip + continue)                   │
│       ├─ STOP → record failure, return failure    │
│       └─ SKIP → record FAILED marker, continue    │
│    6. Success → store in stepResults               │
└──────────────────────────────────────────────────┘
    │
    ▼
All complete → WorkflowResult.success(name, stepResults, durationMs)
```

### 4.2 Core Execution Code

```java
public WorkflowResult execute(WorkflowDefinition workflow,
                               Map<String, String> triggerInputs) {
    long startTime = System.currentTimeMillis();
    Map<String, StepResult> stepResults = new LinkedHashMap<>();

    for (WorkflowStep step : workflow.getSteps()) {
        // 1. Evaluate condition — skip if false
        if (!evaluateCondition(step.getCondition(), stepResults)) {
            stepResults.put(step.getName(),
                    new StepResult(step.getName(), null, null, null));  // empty StepResult
            continue;
        }

        // 2. Resolve input placeholders
        Map<String, String> resolvedInputs = resolveInputs(
                step.getInputs(), triggerInputs, stepResults);

        // 3. Look up Skill
        SkillMeta skill = skillRegistry.get(step.getSkill());
        if (skill == null) {
            if (STOP.equals(step.getOnFailure())) {
                return WorkflowResult.failure(...);  // abort immediately
            }
            continue;  // SKIP — skip
        }

        // 4. Execute step (with optional retry)
        StepResult stepResult = executeStep(workflow.getName(), step, skill, resolvedInputs);

        // 5. Failure handling
        if (stepResult == null || !SUCCEEDED.equals(stepResult.getStatus())) {
            if (RETRY.equals(step.getOnFailure())) {
                stepResult = executeStep(...);  // retry once
            }
            if (still failing) {
                if (STOP.equals(step.getOnFailure())) {
                    return WorkflowResult.failure(...);  // abort
                }
                // SKIP — record FAILED marker, continue
                continue;
            }
        }

        // 6. Store successful result
        stepResults.put(step.getName(), stepResult);
    }

    return WorkflowResult.success(workflow.getName(), stepResults,
            System.currentTimeMillis() - startTime);
}
```

### 4.3 Step Execution (executeStep)

Each step is executed synchronously via `AgentExecutor`:

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
        return null;  // exception → null (triggers onFailure logic)
    }
}
```

- `systemUserId`: workflows execute under the system user identity (from `snap-agent.issue-closure.system-user-id` config)
- On success, `report` contains the Agent report text; on failure, `report` is null
- On exception, returns null, which triggers the onFailure logic in the caller

### 4.4 Failure Policy Details

| Policy | Behavior | Use Case |
|--------|----------|----------|
| `STOP` | Records the failed step's result, immediately returns `WorkflowResult.failure` | Critical step; subsequent steps are meaningless if it fails |
| `SKIP` | Records `StepResult(status=FAILED, report=null)`, continues to the next step | Non-critical step; allows graceful degradation |
| `RETRY` | Re-executes the step once; if still failing, follows `SKIP` logic (records FAILED marker, continues) | Transient failures (e.g., network jitter) |

**RETRY detail**: The retry happens exactly once. If the retry still fails, since `onFailure` is `"RETRY"` (not `"STOP"`), it enters the SKIP branch — a FAILED marker is recorded and the workflow continues. In other words, RETRY = retry once → if still failing, skip and continue.

### 4.5 YamlWorkflowLoader Loading Logic

```java
public List<WorkflowDefinition> loadAll() {
    // Scan all *.yml files in workflowsDir
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(workflowsDir, "*.yml")) {
        for (Path file : stream) {
            WorkflowDefinition def = parseFile(file);  // SnakeYAML parsing
            if (def != null) result.add(def);
        }
    }
    return Collections.unmodifiableList(result);
}

public WorkflowDefinition load(String name) {
    Path file = workflowsDir.resolve(name + ".yml");  // look up by name
    return parseFile(file);  // not found or parse failure → null
}
```

- Returns an empty list when `workflowsDir` is null
- Returns an empty list when the directory does not exist
- A single file parse failure is skipped (WARN log) without affecting other files
- Files missing the `name` field are skipped
- Steps missing `name` or `skill` fields are skipped

---

## 5. Condition Expressions

`SimpleWorkflowEngine` uses a string-based condition expression language (not SpEL) that supports checking the results of prior steps.

### 5.1 Syntax

Condition expressions are wrapped in `${...}`, with the format:

```
${<stepName>.<field>[<operator>]}
```

- `stepName`: the `name` of a prior step (supports word characters and hyphens `[a-zA-Z0-9-]`)
- `field`: `result` (report text), `status` (task status name), or `taskId` (task ID)
- `operator`: optional, see table below

### 5.2 Operators

| Operator | Applicable Field | Meaning | Example |
|----------|-------------------|---------|---------|
| (none) | result/status/taskId | Truthy check: non-null and non-empty | `${health-check.result}` |
| `!= null` | result | Non-null check: report is not null | `${find-root-cause.result != null}` |
| `.contains('text')` | result | String contains: report contains the given text | `${health-check.result.contains('error')}` |
| `.size > 0` | result | Non-empty string: report length > 0 | `${code-analysis.result.size > 0}` |
| `== 'value'` | status | Equality check: status equals the given value | `${health-check.status == 'SUCCEEDED'}` |

### 5.3 Expression Examples

| Expression | Evaluation | Description |
|------------|------------|-------------|
| (empty/null) | `true` | No condition; always executes |
| `${health-check.result}` | report non-empty → true | Truthy check |
| `${health-check.result != null}` | report non-null → true | Checks non-null only (allows empty string) |
| `${health-check.result.contains('error')}` | report contains "error" → true | Substring check |
| `${health-check.result.size > 0}` | report non-empty → true | Equivalent to `!report.isEmpty()` |
| `${health-check.status == 'SUCCEEDED'}` | status is SUCCEEDED → true | Exact match |
| `${health-check.taskId}` | taskId non-empty → true | Truthy check |
| `${unknown-step.result}` | `false` | Step not found → stepResult is null → false |

### 5.4 Evaluation Rules

1. **Empty condition**: condition is null or blank → always returns `true` (step executes)
2. **Unrecognized format**: expression does not match the expected pattern → WARN log, defaults to `true`
3. **Step not found**: referenced stepName is not in stepResults → returns `false` (step does not execute)
4. **Field mapping**:
   - `result` → `StepResult.getReport()` (Agent report text)
   - `status` → `StepResult.getStatus()` (TaskStatus name)
   - `taskId` → `StepResult.getTaskId()`
5. **Truthy semantics**: non-null and (for strings) non-empty string
6. **Single expression**: AND/OR logical combinations are not supported; each condition can only contain one expression

### 5.5 Input Parameter Placeholders

Unlike condition expressions, placeholders in input parameters are used for **value substitution** (not boolean evaluation) and support multiple placeholders within a single value:

| Placeholder | Resolves to | Example |
|-------------|-------------|---------|
| `${trigger.<key>}` | `triggerInputs.get("<key>")` | `${trigger.service}` → "order-service" |
| `${<stepName>.result}` | That step's report text | `${find-root-cause.result}` → "NullPointerException at..." |
| `${<stepName>.status}` | That step's status name | `${health-check.status}` → "SUCCEEDED" |
| `${<stepName>.taskId}` | That step's task ID | `${health-check.taskId}` → "task-abc123" |

- Multiple placeholders can appear in the same value: `"Root cause: ${find-root-cause.result}, Code: ${code-analysis.result}"`
- Unresolvable placeholders are replaced with an empty string

---

## 6. Built-in Workflow

SnapAgent ships with a built-in `full-diagnose` full-chain diagnostic workflow, located at `starter/src/main/resources/docs/workflows/full-diagnose.yml`.

### 6.1 Flow Diagram

```
health-check ──result contains 'error'?──▶ find-root-cause ──result non-null?──▶ code-analysis ──result non-empty?──▶ solution-suggest
     │                                          │                                    │                                 │
     │ STOP                                    │ STOP                               │ SKIP                            │ SKIP
     ▼                                         ▼                                    ▼                                 ▼
  Abort                                     Abort                                Skip, continue                    Skip, continue
```

### 6.2 Step Details

| Step | Skill | Condition | onFailure | Purpose |
|------|-------|-----------|-----------|---------|
| `health-check` | health-check | (none) | STOP | Checks system health via metrics snapshot |
| `find-root-cause` | error-spike-investigation | `${health-check.result.contains('error')}` | STOP | When health check finds errors, investigates error spike root cause |
| `code-analysis` | code-analysis | `${find-root-cause.result != null}` | SKIP | When root cause investigation produced a result, analyzes related code |
| `solution-suggest` | solution-suggest | `${code-analysis.result.size > 0}` | SKIP | When code analysis produced output, suggests fixes |

### 6.3 Condition Chain Logic

1. **Step 1 → Step 2**: `find-root-cause` executes only if `health-check`'s report text contains "error". If the system is healthy (no error), subsequent diagnostic steps are skipped and the workflow completes successfully.
2. **Step 2 → Step 3**: `code-analysis` executes only if `find-root-cause` produced a non-null report. onFailure is SKIP, so even if code analysis fails, the workflow continues.
3. **Step 3 → Step 4**: `solution-suggest` executes only if `code-analysis`'s report is non-empty. It references both `find-root-cause.result` (root cause) and `code-analysis.result` (code analysis) as inputs.

### 6.4 Triggering

Triggered via REST API, requiring a `service` trigger input:

```json
POST /workflows/full-diagnose/run
{
    "service": "order-service"
}
```

`${trigger.service}` is referenced in each step's inputs, passing the service name.

---

## 7. REST API

The workflow engine exposes three REST endpoints (in `SnapAgentController`), all requiring authentication (`requireAuth()`). When workflows are disabled, returns `503 WORKFLOWS_DISABLED`.

### 7.1 GET /workflows

Lists all loaded workflow definitions.

**Response** (200 OK):

```json
[
    {
        "name": "full-diagnose",
        "description": "Full diagnostic workflow — health check → error investigation → code analysis → solution suggestion",
        "stepCount": 4
    }
]
```

### 7.2 GET /workflows/{name}

Retrieves the full definition of a specific workflow.

**Response** (200 OK):

```json
{
    "name": "full-diagnose",
    "description": "Full diagnostic workflow — health check → error investigation → code analysis → solution suggestion",
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

**Error responses:**

| HTTP Status | Error Code | Description |
|-------------|------------|-------------|
| 404 | `WORKFLOW_NOT_FOUND` | Workflow not found |
| 503 | `WORKFLOWS_DISABLED` | Workflow engine not enabled |

### 7.3 POST /workflows/{name}/run

Executes a specific workflow. The request body is a map of trigger inputs (`Map<String, String>`).

**Request**:

```json
POST /workflows/full-diagnose/run
Content-Type: application/json

{
    "service": "order-service"
}
```

**Response** (200 OK):

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
            "report": "System health check complete. Found error: CPU usage 95%..."
        },
        "find-root-cause": {
            "stepName": "find-root-cause",
            "taskId": "task-d4e5f6",
            "status": "SUCCEEDED",
            "report": "Root cause analysis: order-service OOM at 14:00..."
        },
        "code-analysis": {
            "stepName": "code-analysis",
            "taskId": "task-g7h8i9",
            "status": "SUCCEEDED",
            "report": "Code analysis: OrderProcessor.process() has no memory limit..."
        },
        "solution-suggest": {
            "stepName": "solution-suggest",
            "taskId": "task-j0k1l2",
            "status": "SUCCEEDED",
            "report": "Suggestions: 1. Add batch size limit in OrderProcessor..."
        }
    },
    "durationMs": 45230
}
```

**Failure response example** (STOP policy triggers abort):

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

**Skipped step response**: Steps skipped by condition are recorded in `stepResults` with null values:

```json
"stepResults": {
    "health-check": {
        "stepName": "health-check",
        "taskId": "task-a1b2c3",
        "status": "SUCCEEDED",
        "report": "System normal, no anomalous metrics."
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

## 8. Configuration & Extension

### 8.1 Configuration

```yaml
snap-agent:
  workflows:
    enabled: false             # Master switch, default false (no workflow beans when disabled)
    dir: ""                   # Directory for workflow .yml files, empty defaults to {upload-skills-dir}/workflows/
```

| Property | Default | Description |
|----------|---------|-------------|
| `snap-agent.workflows.enabled` | `false` | Master switch for the workflow engine. When false, no workflow beans are assembled and the REST API returns 503 |
| `snap-agent.workflows.dir` | `""` (empty) | Directory for workflow YAML files. When empty, defaults to `{upload-skills-dir}/workflows/`; auto-created at startup |

### 8.2 Auto-Configuration

`SnapAgentAutoConfiguration` assembles two beans when `enabled=true`:

```java
@Bean
@ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public YamlWorkflowLoader yamlWorkflowLoader(SnapAgentProperties props) {
    // dir empty → {upload-skills-dir}/workflows/
    // auto-create directory
    return new YamlWorkflowLoader(workflowsDir);
}

@Bean
@ConditionalOnProperty(prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(WorkflowEngine.class)
public SimpleWorkflowEngine simpleWorkflowEngine(
        AgentExecutor agentExecutor,
        SkillRegistry skillRegistry,
        SnapAgentProperties props) {
    // systemUserId from snap-agent.issue-closure.system-user-id
    return new SimpleWorkflowEngine(agentExecutor, skillRegistry, systemUserId);
}
```

- Both beans are gated by `@ConditionalOnProperty(enabled=true)`
- `YamlWorkflowLoader` uses `@ConditionalOnMissingBean` (matches return type by default); hosts can declare a same-type bean to replace it
- `SimpleWorkflowEngine` uses `@ConditionalOnMissingBean(WorkflowEngine.class)`; hosts implementing the `WorkflowEngine` interface replace it
- Workflows execute under the system user identity; `systemUserId` reuses the `snap-agent.issue-closure.system-user-id` config

### 8.3 Custom Execution Engine

Implement the `WorkflowEngine` interface and register as a Spring Bean to replace the default `SimpleWorkflowEngine`:

```java
@Component
public class DagWorkflowEngine implements WorkflowEngine {

    @Override
    public WorkflowResult execute(WorkflowDefinition workflow,
                                   Map<String, String> triggerInputs) {
        // Custom execution strategy: DAG parallel scheduling,
        // human approval gates, loops, etc.
        // ...
    }

    @Override
    public String type() {
        return "dag";
    }
}
```

Once registered, `@ConditionalOnMissingBean(WorkflowEngine.class)` prevents the default `SimpleWorkflowEngine` from being assembled.

### 8.4 Custom Workflow Loading

`YamlWorkflowLoader` is a concrete class (not an SPI interface). Hosts can extend it in the following ways:

- **Subclassing**: Extend `YamlWorkflowLoader` and override `loadAll()` / `load(name)` to load from a database or external API
- **Replace bean**: Declare a same-type bean; `@ConditionalOnMissingBean` prevents the default from being assembled

```java
@Component
public class DatabaseWorkflowLoader extends YamlWorkflowLoader {

    public DatabaseWorkflowLoader() {
        super(null);  // no filesystem directory
    }

    @Override
    public List<WorkflowDefinition> loadAll() {
        // Load workflow definitions from database
        return jdbcTemplate.query("SELECT name, definition FROM workflows",
            (rs, i) -> parseYaml(rs.getString("definition")));
    }
}
```

### 8.5 Known Limitations & Roadmap

| Limitation | Description | Plan |
|------------|-------------|------|
| Sequential execution | Only linear step sequences; no parallel/DAG support | v1.0.1 parallel scheduling |
| No loops | No step loop/iteration support | v1.0.1 loop nodes |
| No human approval | No pause-and-wait-for-approval gates | v1.0.1 human approval gates |
| No scheduled/event triggers | Only manual REST API triggering | v1.0.1 scheduled/event triggers |
| Single condition expression | No AND/OR logical combinations | Future enhancement |
| No `.size >= N` | Only `.size > 0`; no arbitrary numeric comparison | Future enhancement |
| Synchronous execution | Steps call `AgentExecutor` synchronously; long workflows block HTTP threads | v1.0.1 async execution |
