# SnapAgent Issue Closure Architecture

> Version: v0.9 | Updated: 2026-07-17

## 1. Architecture Overview

SnapAgent v0.9 issue closure system connects diagnosis, solution proposal, external issue creation, fix verification, and knowledge sedimentation into a complete loop. After diagnosis completes, root cause and solutions are recorded; once the user selects a solution, an external issue (Jira/GitHub) is created; after the fix is applied, a verification skill re-checks; when verification passes, the experience is extracted as a knowledge fragment and sedimented back into the KnowledgeBase for future diagnoses to reuse.

```
┌──────────────────────────────────────────────────────────────────────┐
│                         IssueClosureService                          │
│        (orchestrates diagnose → solution → verify → sediment)        │
└──────┬───────────────┬─────────────────┬──────────────┬──────────────┘
       │               │                 │              │
       ▼               ▼                 ▼              ▼
┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
│ Solution    │ │ IssueTracker │ │ Verification │ │ KnowledgeSedimen │
│ Suggester   │ │ (external    │ │ Runner       │ │ tationExtractor  │
│ (proposals) │ │  issue SPI)  │ │ (fix verify) │ │ (knowledge       │
└─────────────┘ └──────────────┘ └──────────────┘ │  sedimentation)  │
       │               │                 │          └────────┬─────────┘
       ▼               ▼                 ▼                   │
┌─────────────┐ ┌──────────────┐ ┌──────────────┐           │
│ solution-   │ │ Jira/GitHub  │ │ verify-fix   │           │
│ suggest     │ │ /NoopIssue   │ │ skill /      │           │
│ skill /     │ │ Tracker      │ │ SimpleVerifi │           │
│ Template    │ │              │ │ cationRunner │           │
└─────────────┘ └──────────────┘ └──────────────┘           │
       │                                                       │
       │              ┌──────────────────┐                    │
       └──────────────│  IssueStore      │────────────────────┘
                      │  (closure record  │
                      │   persistence)    │
                      │  {issueId}.json   │
                      └──────────────────┘
```

### Main Closure Flow

```
        Diagnosis complete (root cause)
              │
              ▼
      ┌───────────────┐
      │ 1. Propose    │  Solution generation: SolutionSuggester or
      │   Solution    │  solution-suggest skill
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 2. Create      │  External issue: IssueTracker.createIssue()
      │   External    │  (NoopIssueTracker returns null)
      │   Issue       │
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │   Fix in       │  Developer applies the fix in the
      │   progress     │  external system
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 3. Verify     │  Verification: VerificationRunner or
      │   Fix         │  verify-fix skill re-check
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │ 4. Close +    │  Close: extract knowledge fragment →
      │   Sediment    │  sediment back to KnowledgeBase
      └───────┬───────┘
              │
              ▼
      ┌───────────────┐
      │  KnowledgeBase│  Future diagnoses can retrieve this
      │  (feedback)   │  ← loop complete, learning cycle formed
      └───────────────┘
```

---

## 2. Core SPI

The core SPI lives in the `cn.watsontech.snapagent.core.issue` package of `snap-agent-core`, defining the issue closure state machine, value objects, and storage/integration interfaces.

### 2.1 IssueStatus (State Enum)

```java
public enum IssueStatus {
    DIAGNOSED,           // Diagnosed, awaiting solution
    SOLUTION_PROPOSED,   // Solutions generated, awaiting Issue creation
    ISSUE_CREATED,       // External Issue created, awaiting fix start
    FIX_IN_PROGRESS,    // Fix in progress
    VERIFIED,            // Fix verified as effective
    CLOSED,              // Closed, knowledge sedimented
    FAILED               // Failure terminal state (fix impossible or verification failed)
}
```

> **Note**: `ISSUE_CREATED` and `FAILED` are defined in the enum, but the current `IssueClosureService` orchestration logic does not drive these two states — `createExternalIssue()` jumps directly to `FIX_IN_PROGRESS`, and the verification path always marks as `VERIFIED` regardless of pass/fail. These states are reserved for custom extensions (e.g., custom IssueTracker or VerificationRunner implementations may use them).

### 2.2 IssueClosure (Immutable Value Object)

`IssueClosure` is the core value object spanning the full lifecycle. All fields are fixed at construction; mutations are expressed via `with*` methods that return new instances:

```java
public final class IssueClosure {
    private final String issueId;              // Internal closure ID (UUID)
    private final String externalIssueId;     // External Issue ID (Jira/ticket, nullable)
    private final String taskId;              // Associated diagnostic task ID
    private final String conversationId;      // Associated conversation ID (nullable)
    private final String userQuery;           // User's original query
    private final String rootCause;           // Root cause summary
    private final SolutionSuggestion solution;     // Suggested solutions (nullable)
    private final String selectedSolution;    // User-selected solution ID (nullable)
    private final IssueStatus status;         // Current status
    private final String fixCommitId;         // Fix commit (nullable)
    private final VerificationResult verificationResult; // Verification result (nullable)
    private final String knowledgeEntryId;    // Knowledge base entry ID (nullable)
    private final long createdAt;             // Creation time (epoch millis)
    private final long updatedAt;             // Update time (epoch millis)
}
```

**`with*` mutation methods** (each returns a new `IssueClosure` instance):

| Method | Fields Updated |
|--------|----------------|
| `withStatus(IssueStatus, long updatedAt)` | status, updatedAt |
| `withSolution(SolutionSuggestion, long updatedAt)` | solution, updatedAt |
| `withExternalIssue(String externalIssueId, String selectedSolution, IssueStatus status, long updatedAt)` | externalIssueId, selectedSolution, status, updatedAt |
| `withVerification(VerificationResult, long updatedAt)` | verificationResult, updatedAt |
| `withKnowledgeEntry(String knowledgeEntryId, long updatedAt)` | knowledgeEntryId, updatedAt |

### 2.3 SolutionSuggestion / SolutionOption (Solution Value Objects)

```java
public final class SolutionOption {
    private final String id;           // Solution ID (e.g. "opt-1")
    private final String title;        // Solution title
    private final String description;  // Solution description
    private final String effort;      // Implementation cost: "low" / "medium" / "high"
    private final boolean temporary;   // Whether this is a temporary fix
}

public final class SolutionSuggestion {
    private final List<SolutionOption> options;  // Defensively copied
    private final String recommendedOptionId;    // Recommended option ID
    private final String rationale;              // Recommendation rationale
    private final String relatedCode;           // Related code location (nullable)
}
```

### 2.4 VerificationResult (Verification Value Object)

```java
public final class VerificationResult {
    private final boolean passed;        // Whether verification passed
    private final String summary;        // Verification summary
    private final String beforeStatus;   // Task status before fix
    private final String afterStatus;    // Task status after fix
    private final long verifiedAt;       // Verification time (epoch millis)
}
```

### 2.5 IssueStore (Storage SPI)

```java
public interface IssueStore {
    void save(IssueClosure issue);                          // Create or update (upsert)
    IssueClosure load(String issueId);                       // Load by ID
    IssueClosure findByTaskId(String taskId);                // Find by diagnostic task ID
    List<IssueClosure> list();                               // All (sorted by updatedAt desc)
    List<IssueClosure> listByStatus(IssueStatus status);     // Filter by status
    void delete(String issueId);                            // Delete
}
```

### 2.6 IssueTracker (External Issue SPI)

```java
public interface IssueTracker {
    String createIssue(String title, String description, String assignee);
    // Returns external Issue ID; NoopIssueTracker returns null

    void updateStatus(String externalIssueId, String status);
    String getIssueUrl(String externalIssueId);
    String type();  // "noop" / "jira" / "github"
}
```

### 2.7 SolutionSuggester / VerificationRunner (Extension SPIs)

v0.9 provides two replaceable orchestration SPIs, allowing hosts to bypass the built-in skills and inject solution-generation and verification logic directly:

```java
public interface SolutionSuggester {
    // Generate a solution suggestion from a diagnosed IssueClosure
    SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary);
}

public interface VerificationRunner {
    // Verify whether the fix resolved the issue
    VerificationResult verify(IssueClosure issue);
}
```

`IssueClosureService` prefers the injected SPI implementation; if not injected, it falls back to running the built-in skill. See Section 4 for details.

---

## 3. State Machine

### 3.1 State Transitions

```
                          proposeSolution()
              ┌──────────────────────────────────────┐
              │                                      │
              ▼                                      │
      ┌───────────────┐                              │
      │   DIAGNOSED   │  Diagnosed, awaiting solution│
      │ (issue created)│                              │
      └───────┬───────┘                              │
              │ proposeSolution()                     │
              │ (withStatus after solution generated)│
              ▼                                      │
      ┌───────────────────┐                           │
      │ SOLUTION_PROPOSED │  Solutions ready, create  │
      └───────┬───────────┘  Issue pending           │
              │ createExternalIssue()                 │
              │ (calls IssueTracker.createIssue)      │
              ▼                                      │
      ┌──────────────────┐                            │
      │ FIX_IN_PROGRESS  │  Fix in progress            │
      │ (external Issue  │                            │
      │  created)        │                            │
      └───────┬──────────┘                            │
              │ verify()                              │
              │ (verify fix effective)                 │
              ▼                                      │
      ┌───────────────┐                              │
      │   VERIFIED    │  Fix verified as effective    │
      └───────┬───────┘                              │
              │ close()                               │
              │ (sediment knowledge + close)          │
              ▼                                      │
      ┌───────────────┐                              │
      │    CLOSED     │  Closed, knowledge sedimented │
      └───────────────┘                              │
                                                       │
              ┌──────────────────────────────┐         │
              │ ISSUE_CREATED (enum defined, │         │
              │ Service does not drive)      │─────────┘
              │ FAILED (enum defined, Service│
              │ does not drive; reserved for │
              │ custom extensions)           │
              └──────────────────────────────┘
```

### 3.2 State Descriptions

| State | Meaning | Triggering Action |
|-------|---------|-------------------|
| `DIAGNOSED` | Diagnosis complete, awaiting solution | Initial state when `proposeSolution(taskId)` creates the IssueClosure |
| `SOLUTION_PROPOSED` | Solutions generated, awaiting external Issue | `proposeSolution()` calls `withStatus()` after solution generated |
| `ISSUE_CREATED` | External Issue created (enum-defined, Service does not use) | Reserved for custom IssueTracker |
| `FIX_IN_PROGRESS` | Fix in progress | `createExternalIssue()` after `IssueTracker.createIssue()` |
| `VERIFIED` | Fix verified as effective | `verify()` after running verification |
| `CLOSED` | Closed, knowledge sedimented to KnowledgeBase | `close()` after extracting knowledge |
| `FAILED` | Failure terminal state (enum-defined, Service does not use) | Reserved for custom VerificationRunner |

### 3.3 Transition Characteristics

- **Forward-only**: In the current `IssueClosureService` implementation, states can only advance forward; rollback is not supported. Each `with*` method produces a new instance written to `IssueStore`, overwriting the old state.
- **No explicit FAILED path**: The current implementation always marks `verify()` as `VERIFIED` regardless of whether verification passes (the `passed` field is recorded in `VerificationResult`), without triggering `FAILED` state.
- **`ISSUE_CREATED` is a reserved state**: `createExternalIssue()` jumps directly from `SOLUTION_PROPOSED` to `FIX_IN_PROGRESS`, skipping `ISSUE_CREATED`. A custom `IssueTracker` can manually mark this state after `createIssue`.

---

## 4. Starter Implementations

The starter module (`snap-agent-spring-boot-2x-starter`) provides default implementations in the `cn.watsontech.snapagent.boot2x.issue` package.

### 4.1 FileIssueStore (Default Storage)

JSON file storage, same pattern as `FileConversationStore`:

- **File path**: `{storageDir}/{issueId}.json`
- **Default directory**: Falls back to `{upload-skills-dir}/issues/` when `snap-agent.issue-closure.storage-dir` is empty
- **Serialization**: Jackson `ObjectMapper`; each file contains a single `IssueClosure` JSON object
- **Sorting**: `list()` and `listByStatus()` sort by `updatedAt` descending (newest first)
- **Backward compatibility**: `fromMap()` supports legacy formats — older versions stored `solutions` as `List<String>` and `verificationResult` as a plain string; these are auto-converted on load:

```java
// Legacy: solutions as List<String> → converted to SolutionOption (id="opt-N", effort="medium")
private SolutionSuggestion solutionFromLegacyList(Object raw) {
    List<SolutionOption> options = new ArrayList<>();
    int index = 1;
    for (Object item : (List<Object>) raw) {
        options.add(new SolutionOption("opt-" + index, item.toString(), item.toString(),
                "medium", false));
        index++;
    }
    String recommended = options.isEmpty() ? null : "opt-1";
    return new SolutionSuggestion(options, recommended, null, null);
}

// Legacy: verificationResult as plain string → passed inferred from keywords
private VerificationResult verificationFromLegacyString(Object raw) {
    String text = raw.toString();
    boolean passed = text.contains("通过") || text.toLowerCase().contains("pass");
    return new VerificationResult(passed, text, null, null, 0L);
}
```

### 4.2 NoopIssueTracker (Default Issue Tracker)

Default no-op implementation, assembled when `snap-agent.issue-closure.tracker-type=noop`:

```java
public class NoopIssueTracker implements IssueTracker {
    @Override public String createIssue(String title, String description, String assignee) {
        return null;  // Does not create an external Issue, returns null
    }
    @Override public void updateStatus(String externalIssueId, String status) { /* no-op */ }
    @Override public String getIssueUrl(String externalIssueId) { return null; }
    @Override public String type() { return "noop"; }
}
```

Host applications can declare a custom `IssueTracker` bean (Jira/GitHub) to replace it; `@ConditionalOnMissingBean` takes effect.

### 4.3 TemplateSolutionSuggester (Default Solution Suggester)

Uses keyword-template matching on the root cause summary, producing 2-3 candidate `SolutionOption`s. **The first matching template wins**; if no template matches, a fallback is returned:

| Match condition (root cause contains keywords) | Generated solutions |
|------|---------|
| `参数` + (缺失/缺少/未生成) | Manually fill params (low, temp) / Fix filter logic (medium) |
| `连接` + (超时/失败/拒绝) | Adjust pool config (low) / Increase timeout (low, temp) / Investigate slow SQL (high) |
| `数据` + (为空/缺失/不存在) | Check upstream task (medium) / Backfill data (low, temp) |
| `权限` / `鉴权` / `认证` | Self-check permissions (low) / Contact admin (low) |
| No match (fallback) | Code graph location (medium) / Contact owner (low) |

`recommendedOptionId` is always `"opt-1"` (the first option). When root cause is blank, it falls back to `transcriptSummary`; when both are blank, the fallback template is triggered.

### 4.4 SimpleVerificationRunner (Default Verification Runner)

Re-runs the original diagnostic skill and checks whether the new task reaches `TaskStatus.SUCCEEDED`:

```java
public VerificationResult verify(IssueClosure issue) {
    // 1. Load the original diagnostic task from issue.taskId
    AgentTask originalTask = taskStore.get(issue.getTaskId());
    // 2. Create a new task with the same skill and inputs (executed by systemUserId)
    AgentTask verifyTask = AgentTask.create(systemUserId, skillName, originalTask.getInputs(), null);
    agentExecutor.execute(verifyTask, skill);
    // 3. passed = new task status is SUCCEEDED
    boolean passed = TaskStatus.SUCCEEDED.equals(verifyTask.getStatus());
    // 4. beforeStatus=original task status, afterStatus=new task status
    return new VerificationResult(passed, verifyTask.getReport(),
            originalTask.getStatus().name(), verifyTask.getStatus().name(), now);
}
```

### 4.5 IssueClosureService (Orchestration Service)

The core orchestrator, connecting `AgentExecutor`, `IssueStore`, `IssueTracker`, and `KnowledgeBase`:

#### `proposeSolution(taskId)` — Propose Solutions

```
1. taskStore.get(taskId) loads the diagnostic task
   ├─ Not found → return null
   └─ Found → rootCause = task.getReport()
              userQuery = concatenation of all non-empty task.inputs values

2. Create IssueClosure (status=DIAGNOSED, solution=null)

3. Generate solutions (dual path):
   ├─ SolutionSuggester injected → suggester.suggest(issue, rootCause)
   │   └─ Default: TemplateSolutionSuggester
   └─ SolutionSuggester null → fallback: run solution-suggest skill
       ├─ inputs: root_cause, original_query, task_id
       ├─ agentExecutor.execute(solutionTask, skill)
       ├─ Parse report lines into SolutionOption list (id="opt-N", effort="medium")
       └─ Skill not found → return null (preserves legacy behavior)

4. issue.withSolution(suggestion, now).withStatus(SOLUTION_PROPOSED, now)
5. issueStore.save(issue)
6. Return issue
```

#### `createExternalIssue(taskId, selectedSolution)` — Create External Issue

```
1. issueStore.findByTaskId(taskId)
   ├─ Not found → return null
   └─ Found → continue

2. title = truncate(rootCause, 80)  // Root cause truncated to title
   description = selectedSolution
   externalIssueId = issueTracker.createIssue(title, description, null)
   // NoopIssueTracker returns null, but status still advances

3. issue.withExternalIssue(externalIssueId, selectedSolution, FIX_IN_PROGRESS, now)
4. issueStore.save(updated)
5. Return updated
```

#### `verify(issueId)` — Verify Fix

```
1. issueStore.load(issueId)
   ├─ Not found → return null
   └─ Found → continue

2. Verify (dual path):
   ├─ VerificationRunner injected → runner.verify(issue)
   │   └─ Default: SimpleVerificationRunner (re-runs diagnostic skill)
   └─ VerificationRunner null → fallback: run verify-fix skill
       ├─ inputs: root_cause, original_query, issue_id
       ├─ agentExecutor.execute(verifyTask, skill)
       ├─ passed = report contains "通过" or "pass"
       └─ Skill not found → return null

3. issue.withVerification(result, now).withStatus(VERIFIED, now)
4. issueStore.save(updated)
5. Return updated
```

#### `close(issueId)` — Close and Sediment

```
1. issueStore.load(issueId)
   ├─ Not found → return null
   └─ Found → continue

2. KnowledgeFragment fragment = sedimentationExtractor.extract(issue)
   // Extract knowledge fragment (see Section 5)

3. knowledgeBase != null → knowledgeBase.reload()
   // Reload knowledge base so new fragment becomes searchable

4. issue.withKnowledgeEntry("sedimentation:" + issueId, now)
      .withStatus(CLOSED, now)
5. issueStore.save(updated)
6. Return updated
```

> **Note**: `knowledgeBase` may be `null` (when `snap-agent.knowledge.enabled=false`). In this case `close()` still records `knowledgeEntryId` but does not reload the knowledge base — the knowledge fragment is not immediately searchable, but the full record is retained in `IssueStore`.

---

## 5. Knowledge Sedimentation

### 5.1 Sedimentation Mechanism

When an issue is closed, `KnowledgeSedimentationExtractor.extract()` extracts a `KnowledgeFragment` from the `IssueClosure`. The experience is sedimented back into the v0.7 `KnowledgeBase` as structured Markdown:

```
IssueClosure (CLOSED)
    │
    ▼
KnowledgeSedimentationExtractor.extract(issue)
    │
    ├─ title = "问题: " + truncate(userQuery, 60)
    ├─ content = structured Markdown (see below)
    ├─ source = "sedimentation:" + issueId
    └─ metadata = {category: "经验沉淀"}
    │
    ▼
KnowledgeBase.reload()
    │
    ▼
Future diagnoses: KnowledgeInjector retrieves this fragment
    → injects into system prompt → LLM can reference historical experience
```

### 5.2 Extracted Knowledge Fragment Format

```markdown
## 问题
{userQuery}

## 根因
{rootCause}

## 解决方案
{selectedSolution}
// Or when no selectedSolution, lists all SolutionOptions:
- [low] Manually fill missing params: Locate the source of missing params...
- [medium] Fix filter logic: Investigate the param generation pipeline...

## 验证结果
passed: true
{verificationResult.summary}
```

### 5.3 Complete Example

Assume a closed issue:

- `userQuery`: "SKU-001 为什么没生成补货策略？"
- `rootCause`: "replm_inv_param_sku_wh_input table: SKU-001's init_replenishment_param field is empty; parameter initialization task not executed"
- `selectedSolution`: "opt-1: 手动补齐缺失参数"
- `verificationResult.passed`: true
- `verificationResult.summary`: "Replenishment strategy generated, verification passed"

Extracted `KnowledgeFragment`:

```
title:   "问题: SKU-001 为什么没生成补货策略？"
source:  "sedimentation:issue_1721234567890_a1b2c3d4"
metadata: {category: "经验沉淀"}

content:
## 问题
SKU-001 为什么没生成补货策略？

## 根因
replm_inv_param_sku_wh_input table: SKU-001's init_replenishment_param field is empty; parameter initialization task not executed

## 解决方案
opt-1: 手动补齐缺失参数

## 验证结果
passed: true
Replenishment strategy generated, verification passed
```

When a user asks a similar question in the future, `KnowledgeInjector` retrieves this fragment, injects it into the system prompt, and the LLM can directly reference the historical experience of "parameter missing → fill → verify", forming a learning loop.

---

## 6. REST API

All endpoints are mounted under the SnapAgent basePath (default `/snap-agent`) and require `requireAuth()` permission checks. When `IssueClosureService` is not assembled (issue-closure disabled), returns **503** + `ISSUE_CLOSURE_DISABLED`.

### Endpoint Overview

| Method | Path | Description | State Transition |
|--------|------|-------------|------------------|
| POST | `/runs/{taskId}/solution` | Propose solutions for a diagnosed task | DIAGNOSED → SOLUTION_PROPOSED |
| POST | `/runs/{taskId}/issue` | Create external Issue | SOLUTION_PROPOSED → FIX_IN_PROGRESS |
| GET | `/issues/{issueId}` | Get Issue details | (no state change) |
| POST | `/issues/{issueId}/verify` | Verify the fix | FIX_IN_PROGRESS → VERIFIED |
| POST | `/issues/{issueId}/close` | Close and sediment knowledge | VERIFIED → CLOSED |

### POST /runs/{taskId}/solution

Proposes solutions for a completed diagnostic task. No request body required.

**Response** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "externalIssueId": null,
    "taskId": "task_1721234500000_x7y8z9",
    "conversationId": null,
    "userQuery": "SKU-001 为什么没生成补货策略？",
    "rootCause": "replm_inv_param_sku_wh_input table: SKU-001's init_replenishment_param field is empty...",
    "solution": {
        "options": [
            {
                "id": "opt-1",
                "title": "手动补齐缺失参数",
                "description": "Locate the source of missing params, fill via config or API to quickly restore service.",
                "effort": "low",
                "temporary": true
            },
            {
                "id": "opt-2",
                "title": "修复参数过滤逻辑",
                "description": "Investigate the param generation pipeline, fix the filter/validation logic causing the miss.",
                "effort": "medium",
                "temporary": false
            }
        ],
        "recommendedOptionId": "opt-1",
        "rationale": "Root cause points to parameter missing; prioritize manual fill, then fix generation logic.",
        "relatedCode": null
    },
    "selectedSolution": null,
    "status": "SOLUTION_PROPOSED",
    "fixCommitId": null,
    "verificationResult": null,
    "knowledgeEntryId": null,
    "createdAt": 1721234567890,
    "updatedAt": 1721234567890
}
```

**Errors**:

| HTTP | code | Trigger |
|------|------|---------|
| 401 | `UNAUTHORIZED` | Not authenticated |
| 403 | `FORBIDDEN` | No permission |
| 404 | `TASK_NOT_FOUND` | Task or skill not found |
| 503 | `ISSUE_CLOSURE_DISABLED` | issue-closure not enabled |

### POST /runs/{taskId}/issue

Creates an external Issue after the user selects a solution.

**Request body**:

```json
{
    "selected_solution": "opt-1: 手动补齐缺失参数"
}
```

**Response** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "externalIssueId": "JIRA-12345",
    "taskId": "task_1721234500000_x7y8z9",
    "userQuery": "SKU-001 为什么没生成补货策略？",
    "rootCause": "replm_inv_param_sku_wh_input table: ...",
    "solution": { ... },
    "selectedSolution": "opt-1: 手动补齐缺失参数",
    "status": "FIX_IN_PROGRESS",
    "fixCommitId": null,
    "verificationResult": null,
    "knowledgeEntryId": null,
    "createdAt": 1721234567890,
    "updatedAt": 1721234600000
}
```

> **Note**: When using `NoopIssueTracker`, `externalIssueId` is `null`, but status still advances to `FIX_IN_PROGRESS`.

### GET /issues/{issueId}

Retrieves Issue details. No state change.

**Response** (200 OK): Same IssueClosure DTO structure as above.

**Errors**: 404 `ISSUE_NOT_FOUND` | 503 `ISSUE_CLOSURE_DISABLED`

### POST /issues/{issueId}/verify

Verifies whether the fix is effective. No request body required.

**Response** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "status": "VERIFIED",
    "verificationResult": {
        "passed": true,
        "summary": "Replenishment strategy generated, verification passed",
        "beforeStatus": "SUCCEEDED",
        "afterStatus": "SUCCEEDED",
        "verifiedAt": 1721234700000
    },
    ...
}
```

### POST /issues/{issueId}/close

Closes the Issue and sediments knowledge. No request body required.

**Response** (200 OK):

```json
{
    "issueId": "issue_1721234567890_a1b2c3d4",
    "status": "CLOSED",
    "knowledgeEntryId": "sedimentation:issue_1721234567890_a1b2c3d4",
    ...
}
```

---

## 7. Built-in Skills

The issue closure uses two built-in skills (located in `starter/src/main/resources/docs/skills/`), executed as fallback paths when `SolutionSuggester` / `VerificationRunner` are not injected.

### 7.1 solution-suggest.md

**Purpose**: Generates 2-3 candidate solutions based on the diagnostic root cause.

**Inputs**:

| key | label | required | Description |
|-----|-------|----------|-------------|
| `root_cause` | Root cause summary | Yes | Root cause from diagnosis |
| `original_query` | User's original question | Yes | For context understanding |
| `task_id` | Associated diagnostic task ID | No | For traceability |

**Execution steps**:
1. Understand the root cause (`{root_cause}`)
2. Consider the user's original question (`{original_query}`)
3. If needed, use `code_graph_impact_analysis` to check modification impact scope
4. Generate solutions: each with a recommendation level (high/medium/low)
5. Recommend one solution and explain the reasoning

**Output format**: Multi-line solution descriptions, one solution per line. `IssueClosureService.suggestViaSkill()` parses each line into a `SolutionOption` (id=`opt-N`, effort=`medium`).

### 7.2 verify-fix.md

**Purpose**: Verifies whether the fix resolved the original issue by re-running diagnostic checks.

**Inputs**:

| key | label | required | Description |
|-----|-------|----------|-------------|
| `root_cause` | Original root cause | Yes | Root cause from diagnosis |
| `original_query` | User's original question | Yes | Original question |
| `issue_id` | Issue ID | No | For traceability |

**Execution steps**:
1. Understand the original problem (`{original_query}`)
2. Understand the original root cause (`{root_cause}`)
3. Verify: use available tools (`mysql_query`/`redis_read`/`metrics_query`/`log_search` etc.) to re-check symptoms
4. Determine: has the issue been resolved?

**Output format**: `Verification result: pass/fail` + checklist. `IssueClosureService.verifyViaSkill()` infers `passed` by checking whether the report contains "通过" or "pass".

> **Note**: The default-assembled `TemplateSolutionSuggester` and `SimpleVerificationRunner` **replace** these two skills — TemplateSolutionSuggester uses keyword-template matching to generate solutions (no LLM call), SimpleVerificationRunner re-runs the original diagnostic skill (not the verify-fix skill). These two skills serve as fallback only when `SolutionSuggester` / `VerificationRunner` beans are not injected.

---

## 8. Configuration & Extension

### 8.1 Configuration

```yaml
snap-agent:
  issue-closure:
    enabled: false              # Master switch, default false (zero issue beans)
    system-user-id: system      # User ID for executing solution-suggest/verify-fix skills
    storage-dir: ""             # Issue JSON storage dir; empty = {upload-skills-dir}/issues/
    tracker-type: noop          # IssueTracker type: noop / jira / github
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch. When `false`, no issue beans are assembled; controller returns 503 |
| `system-user-id` | `system` | `userId` used when executing solution/verification skills (not attributed to a real user) |
| `storage-dir` | `""` | Falls back to `{upload-skills-dir}/issues/` when empty |
| `tracker-type` | `noop` | Identifies IssueTracker type; currently only `noop` has a built-in implementation |

### 8.2 Bean Assembly Conditions

All issue beans are assembled when `snap-agent.issue-closure.enabled=true` (`@ConditionalOnProperty`):

| Bean | Type | Condition | Replacement |
|------|------|-----------|-------------|
| `FileIssueStore` | `IssueStore` | `@ConditionalOnMissingBean(IssueStore.class)` | Declare custom `IssueStore` bean |
| `NoopIssueTracker` | `IssueTracker` | `@ConditionalOnMissingBean(IssueTracker.class)` | Declare custom `IssueTracker` bean |
| `KnowledgeSedimentationExtractor` | — | `@ConditionalOnMissingBean` | Declare custom bean |
| `TemplateSolutionSuggester` | `SolutionSuggester` | `@ConditionalOnMissingBean(SolutionSuggester.class)` | Declare custom `SolutionSuggester` bean |
| `SimpleVerificationRunner` | `VerificationRunner` | `@ConditionalOnMissingBean(VerificationRunner.class)` | Declare custom `VerificationRunner` bean |
| `IssueClosureService` | — | `@ConditionalOnMissingBean`, `ObjectProvider<KnowledgeBase>` nullable | — |

`IssueClosureService` injects `KnowledgeBase` (nullable), `SolutionSuggester` (nullable, falls back to skill), and `VerificationRunner` (nullable, falls back to skill) via `ObjectProvider`.

### 8.3 Custom IssueTracker (Jira/GitHub)

Implement the `IssueTracker` interface + `@Component`:

```java
@Component
public class JiraIssueTracker implements IssueTracker {
    private final JiraClient jiraClient;

    @Override
    public String createIssue(String title, String description, String assignee) {
        // Call Jira REST API to create an issue
        return jiraClient.createIssue(
            IssueRequest.builder()
                .projectKey("OPS")
                .summary(title)
                .description(description)
                .assignee(assignee)
                .build()
        ).getKey();  // e.g. "OPS-1234"
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        jiraClient.transitionIssue(externalIssueId, status);
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        return "https://jira.company.com/browse/" + externalIssueId;
    }

    @Override
    public String type() { return "jira"; }
}
```

Once registered, `@ConditionalOnMissingBean(IssueTracker.class)` no longer assembles `NoopIssueTracker`. Official Jira/GitHub implementations are planned for v0.9.1.

### 8.4 Custom IssueStore (Database Storage)

Implement the `IssueStore` interface to replace `FileIssueStore`:

```java
@Component
public class DatabaseIssueStore implements IssueStore {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void save(IssueClosure issue) {
        jdbcTemplate.update(
            "INSERT INTO snap_agent_issues (issue_id, task_id, status, root_cause, " +
            "user_query, solution_json, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE status=?, updated_at=?",
            issue.getIssueId(), issue.getTaskId(), issue.getStatus().name(),
            issue.getRootCause(), issue.getUserQuery(),
            serializeSolution(issue.getSolution()),
            issue.getCreatedAt(), issue.getUpdatedAt(),
            issue.getStatus().name(), issue.getUpdatedAt()
        );
    }
    // ... other methods
}
```

### 8.5 Custom SolutionSuggester / VerificationRunner

Replace the default template/re-run implementations:

```java
@Component
public class LlmSolutionSuggester implements SolutionSuggester {
    @Override
    public SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary) {
        // Call an external LLM or knowledge graph for more precise solutions
        // Can use issue.getRootCause() and transcriptSummary
        return llmClient.generateSolutions(issue.getRootCause());
    }
}

@Component
public class MetricBasedVerificationRunner implements VerificationRunner {
    @Override
    public VerificationResult verify(IssueClosure issue) {
        // Directly check metrics instead of re-running a skill
        double errorRate = metricsClient.query("error_rate{service='order'}");
        boolean passed = errorRate < 0.01;
        return new VerificationResult(passed, "error rate: " + errorRate,
                "FAILED", "OK", System.currentTimeMillis());
    }
}
```

### 8.6 Roadmap

| Feature | Planned Version | Description |
|---------|------------------|-------------|
| Jira IssueTracker | v0.9.1 | Official Jira REST API implementation |
| GitHub IssueTracker | v0.9.1 | GitHub Issues API implementation |
| Auto PR creation | v0.9.1 | Auto-create Pull Request after verification passes |
| Scheduled regression verification | v0.9.2 | Periodically re-verify closed issues to detect recurrence |
| ConversationKnowledgeSource | v0.7.1 | Extract Q&A knowledge from conversation history |
