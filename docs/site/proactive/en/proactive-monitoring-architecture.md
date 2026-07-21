# SnapAgent Proactive Monitoring Architecture

> Version: v1.1 | Updated: 2026-07-20

SnapAgent's proactive monitoring capability is provided jointly by the `patrol`
package in `snap-agent-core` (SPI + data models) and the `patrol` package in
`snap-agent-spring-boot-2x-starter` (default implementations + auto-configuration).
This document describes the complete architecture of two complementary monitoring
modes — **Patrol** and **Alert**.

> **v1.1 highlights** (2026-07-20):
> - `PatrolReportStore` refactored from a concrete class to an SPI interface; the
>   ring-buffer implementation now lives in `boot2x/patrol/InMemoryPatrolReportStore`
>   and hosts can replace it with DB/Redis storage
> - New `PatrolLockProvider` SPI for multi-Pod coordination. Default
>   `NoopPatrolLockProvider` always grants (single-Pod); hosts with multiple Pods
>   inject a Redis / k8s lease / DB row lock implementation
> - New `AlertPushChannel` SPI with two default implementations
>   (`WebhookAlertPushChannel` + `EmailAlertPushChannel`) — anomaly reports are now
>   pushed to external channels automatically

---

## 1. Architecture Overview

Proactive monitoring consists of two independent data flows that share the same
`AgentExecutor` execution engine and `PatrolReportStore` report storage:

```
Patrol Flow:
  ┌──────────────┐   cron trigger  ┌──────────────────┐   execute()   ┌──────────────┐
  │ PatrolTask   │ ─────────────► │ ScheduledPatrol  │ ───────────► │ AgentExecutor │
  │ (skill+cron) │                │ Scheduler         │               │ (runs skill)  │
  └──────────────┘                └────────┬─────────┘               └──────┬───────┘
                                           │                                 │
                                           │  PatrolReport                  │ task.getReport()
                                           ▼                                 ▼
                                         ┌──────────────────────────────────────┐
                                         │ PatrolReportStore (ring buffer, 500)  │
                                         └──────────────────────────────────────┘

Alert Flow:
  ┌──────────────┐  onEvent()   ┌──────────────────────┐  record()   ┌────────────────────┐
  │ AnomalyEvent │ ──────────► │ DefaultAnomaly        │ ─────────► │ InMemoryAlert      │
  │ (type/source)│             │ EventListener          │            │ Converger (dedup)   │
  └──────────────┘             └────────┬─────────────┘            └─────────┬──────────┘
                                           │                                      │
                                           │ determine skill                      │ AlertConvergence
                                           │ (default: error-spike-investigation)  │ (fingerprint dedup)
                                           ▼                                      ▼
                                         ┌──────────────┐   execute()   ┌──────────────────────────┐
                                         │ AgentExecutor │ ───────────► │ PatrolReportStore (save) │
                                         │ (runs skill)  │               │ + AlertConverger (link)  │
                                         └──────────────┘               └──────────────────────────┘
```

### Mode Comparison

| Dimension | Patrol | Alert |
|-----------|--------|-------|
| Trigger | cron expression timer | External system pushes `AnomalyEvent` |
| Entry point | `PatrolScheduler.schedule(PatrolTask)` | `AnomalyEventListener.onEvent(AnomalyEvent)` |
| Skill executed | PatrolTask.skillName (any skill) | AnomalyEvent.skillName (default `error-spike-investigation`) |
| Deduplication | None (each cron run is independent) | SHA-256 fingerprint dedup (type\|source) |
| Report flag | `anomalyDetected=false` | `anomalyDetected=true` |
| Storage | PatrolReportStore | PatrolReportStore + AlertConverger |

### Core Interface Overview

```
core/patrol/
├── PatrolScheduler.java        (SPI: cron scheduling + report query)
├── AlertConverger.java         (SPI: alert dedup + query)
├── AnomalyEventListener.java   (SPI: receive anomaly events)
├── BugfixSuggester.java        (SPI: extract fix suggestions from transcript)
├── PatrolLockProvider.java     (SPI: multi-Pod patrol coordination lock, new in v1.1)
├── AlertPushChannel.java       (SPI: anomaly report push channel, new in v1.1)
├── PatrolTask.java             (value object: scheduled task definition)
├── PatrolReport.java           (value object: patrol/alert report)
├── PatrolReportStore.java     (SPI: report storage interface, refactored in v1.1)
├── AnomalyEvent.java           (value object: anomaly event)
├── AlertConvergence.java       (value object: deduplicated alert record)
└── BugfixSuggestion.java       (value object: fix suggestion)

boot2x/patrol/
├── ScheduledPatrolScheduler.java      (PatrolScheduler default impl)
├── InMemoryAlertConverger.java        (AlertConverger default impl)
├── DefaultAnomalyEventListener.java   (AnomalyEventListener default impl)
├── TemplateBugfixSuggester.java       (BugfixSuggester default impl)
├── InMemoryPatrolReportStore.java     (PatrolReportStore default impl, new in v1.1)
├── NoopPatrolLockProvider.java        (PatrolLockProvider default impl, new in v1.1)
├── WebhookAlertPushChannel.java       (AlertPushChannel default impl - Webhook, new in v1.1)
└── EmailAlertPushChannel.java         (AlertPushChannel default impl - Email, new in v1.1)
```

---

## 2. Core SPI

All SPI definitions reside in the `cn.watsontech.snapagent.core.patrol` package.

### 2.1 PatrolScheduler

```java
public interface PatrolScheduler {
    /** Schedule a cron-based patrol task */
    void schedule(PatrolTask task);

    /** Cancel a scheduled patrol task */
    void cancel(String patrolId);

    /** Return all registered patrol tasks */
    List<PatrolTask> listTasks();

    /** Paginated report query (sorted by triggeredAt desc, filtered by userId) */
    List<PatrolReport> getReports(String userId, int limit, int offset);

    /** Return total report count for a given user */
    long countReports(String userId);
}
```

### 2.2 AlertConverger

```java
public interface AlertConverger {
    /** Record an anomaly event, creating or updating a convergence record */
    AlertConvergence record(AnomalyEvent event);

    /** Paginated query of convergence records (optional type filter) */
    List<AlertConvergence> query(String userId, String type, int limit, int offset);

    /** Return total count matching the given filters */
    long count(String userId, String type);

    /** Mark an alert as resolved */
    void resolve(String alertId);
}
```

### 2.3 AnomalyEventListener

```java
public interface AnomalyEventListener {
    /** Called when an anomaly event is detected */
    void onEvent(AnomalyEvent event);
}
```

### 2.4 BugfixSuggester

```java
public interface BugfixSuggester {
    /**
     * Analyze a patrol task transcript and produce a bugfix suggestion
     * @return a bugfix suggestion, or null if none can be made
     */
    BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript);
}
```

### 2.5 PatrolLockProvider (multi-Pod coordination lock, new in v1.1)

```java
public interface PatrolLockProvider {
    /**
     * Try to acquire the patrol lock. In multi-Pod deployments, only the Pod
     * that acquires the lock runs the patrol — others skip to avoid duplicate
     * execution and duplicate reports.
     * @param patrolId patrol task ID
     * @param ttlSeconds lock TTL in seconds (auto-expires if the Pod crashes)
     * @return true = acquired (this Pod runs), false = held by another Pod
     */
    boolean tryAcquire(String patrolId, long ttlSeconds);

    /** Release the lock. Should be called in a finally block to ensure cleanup on failures. */
    void release(String patrolId);

    /** Lock implementation type identifier (e.g. "noop" / "redis" / "k8s-lease"), used in logs. */
    default String type() { return "noop"; }
}
```

**Default implementation**: `NoopPatrolLockProvider` (`boot2x/patrol/`) — in single-Pod
mode `tryAcquire` always returns `true` and `release` is a no-op. Hosts running
multiple Pods can replace it with:

| Implementation | Use case | Typical API |
|----------------|----------|-------------|
| Redis `SET NX EX` | Multi-Pod with shared Redis | `SET lock:{patrolId} {podName} NX EX {ttl}` |
| Kubernetes Lease | K8s-native | `coordination.k8s.io/v1` Lease object |
| DB row lock | Shared RDBMS | `SELECT ... FOR UPDATE` or optimistic versioning |

### 2.6 AlertPushChannel (anomaly report push channel, new in v1.1)

```java
public interface AlertPushChannel {
    /**
     * Push an anomaly patrol report. Implementations should only push for
     * anomalyDetected=true reports — normal patrol runs do not push.
     * @param report patrol report (non-null)
     * @param event anomaly event that triggered this report; may be null for patrol-triggered anomalies
     */
    void push(PatrolReport report, AnomalyEvent event);

    /** Push channel type identifier (e.g. "webhook" / "email" / "dingtalk"), used in logs. */
    default String type() { return "unknown"; }
}
```

**Default implementations** (`boot2x/patrol/`):

| Implementation | type() | Trigger condition | Dependencies |
|----------------|--------|-------------------|--------------|
| `WebhookAlertPushChannel` | `webhook` | `report.isAnomalyDetected()==true` | JDK `HttpURLConnection` (no external deps) |
| `EmailAlertPushChannel` | `email` | `report.isAnomalyDetected()==true` | `spring-context-support` + `javax.mail` (optional) |

`WebhookAlertPushChannel` uses `ObservabilityHttpClient.httpPost()` to send a JSON
payload (containing `report_id` / `patrol_id` / `task_id` / `skill_name` /
`status` / `triggered_at` / `report_summary` / `event` fields) to the configured
webhook URL.

`EmailAlertPushChannel` uses Spring `JavaMailSender` to send a plain-text email
whose body includes time, skill, status, report ID, patrol ID, task ID, event
details, and diagnostic summary. When `spring-context-support` or `javax.mail`
is not on the classpath, this bean is skipped.

### 2.7 PatrolReportStore (report storage SPI, refactored in v1.1)

As of v1.1, `PatrolReportStore` is an interface rather than a concrete class;
the original ring-buffer implementation moved to
`boot2x/patrol/InMemoryPatrolReportStore.java`:

```java
public interface PatrolReportStore {
    /** Save a report. Implementations must be concurrency-safe and auto-generate an ID if null. */
    void save(PatrolReport report);

    /** Paginated query (sorted by triggeredAt desc, filtered by userId; null userId returns system reports). */
    List<PatrolReport> getReports(String userId, int limit, int offset);

    /** Total report count for the user. */
    long count(String userId);

    /** Fetch a single report by ID; null if not found. */
    PatrolReport get(String reportId);
}
```

Hosts can implement this interface to replace the in-memory store with DB/Redis/file:

```java
@Component
public class JdbcPatrolReportStore implements PatrolReportStore {
    private final JdbcTemplate jdbc;
    // INSERT INTO patrol_report (...) VALUES (...)
    // SELECT ... FROM patrol_report WHERE user_id = ? ORDER BY triggered_at DESC LIMIT ? OFFSET ?
}
```

`@ConditionalOnMissingBean` lets the custom implementation take precedence over
the in-memory default.

### 2.8 Data Models

**PatrolTask** (scheduled task definition):

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Task ID, auto-generated as `patrol_{n}` |
| `skillName` | String | Skill to execute |
| `cron` | String | Spring CronTrigger expression (6 fields) |
| `userId` | String | Executing user ID (for permission isolation) |
| `enabled` | boolean | Whether enabled (default true) |
| `inputs` | Map\<String,String\> | Skill input parameters |

**PatrolReport** (patrol/alert report):

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Report ID, auto-generated as `pr_{ts}_{uuid}` |
| `patrolId` | String | Associated patrol task ID |
| `taskId` | String | AgentTask ID (may be null) |
| `userId` | String | User ID (null = system-generated, visible to all) |
| `skillName` | String | Skill executed |
| `triggeredAt` | long | Trigger timestamp (ms) |
| `status` | String | `COMPLETED` / `FAILED` / `UNKNOWN` |
| `summary` | String | Report summary (from `task.getReport()`) |
| `anomalyDetected` | boolean | Whether alert-triggered (patrol=false, alert=true) |

**AnomalyEvent** (anomaly event):

| Field | Type | Description |
|-------|------|-------------|
| `type` | String | Anomaly type (e.g. `error-spike`, `cpu-high`) |
| `source` | String | Anomaly source (service name / instance ID) |
| `message` | String | Anomaly description |
| `timestamp` | long | Event timestamp (auto-set to current time) |
| `metadata` | Map\<String,Object\> | Additional metadata (defensive copy) |
| `skillName` | String | Skill to trigger (null uses default) |
| `inputs` | Map\<String,String\> | Input parameters passed to skill |

**AlertConvergence** (deduplicated alert record):

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Alert ID, format `alert_{n}` |
| `fingerprint` | String | SHA-256(type\|source) first 8 bytes |
| `type` | String | Anomaly type |
| `source` | String | Anomaly source |
| `firstMessage` | String | Message from first occurrence |
| `count` | AtomicInteger | Occurrence count (atomic increment) |
| `firstSeen` | long (volatile) | First occurrence timestamp |
| `lastSeen` | long (volatile) | Last occurrence timestamp |
| `status` | String (volatile) | `ACTIVE` / `RESOLVED` |
| `relatedTaskId` | String | Associated diagnostic task ID |

`AlertConvergence` uses `AtomicInteger` + `volatile` fields to ensure thread safety
between concurrent `record()` and `autoResolveStale()` calls.

**BugfixSuggestion** (fix suggestion):

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | String | Associated task ID |
| `rootCause` | String | Root cause analysis (from transcript done event) |
| `affectedFiles` | List\<String\> | List of affected files |
| `suggestion` | String | Markdown-formatted suggestion body |
| `confidence` | String | `HIGH` / `MEDIUM` / `LOW` |
| `commitRefs` | List\<String\> | Related commit hashes |

---

## 3. Patrol Mechanism — ScheduledPatrolScheduler

`ScheduledPatrolScheduler` is the default `PatrolScheduler` implementation, using
Spring `TaskScheduler` + `CronTrigger` for cron-based scheduling.

### 3.1 Scheduling Flow

```
POST /patrol/tasks (skillName, cron, inputs)
    │
    ▼
ScheduledPatrolScheduler.schedule(task)
    │
    ├─ If task.id is null → generate "patrol_{n}" (AtomicLong increment)
    ├─ tasks.put(id, task)                          // ConcurrentHashMap register
    ├─ if task.enabled:
    │     taskScheduler.schedule(
    │         () -> executePatrol(task),            // lambda as scheduling target
    │         new CronTrigger(task.cron))           // Spring CronTrigger parses cron
    │     scheduledFutures.put(id, future)          // record ScheduledFuture
    └─ Log: "Scheduled patrol task {} (skill={}, cron={})"
```

### 3.2 executePatrol Logic

When the cron fires, `executePatrol(task)` runs in the scheduling thread pool. As of
v1.1, it now uses a **multi-Pod coordination lock** (`PatrolLockProvider`) and pushes
anomaly reports to all configured **`AlertPushChannel`**s:

```java
private void executePatrol(PatrolTask task) {
    long triggeredAt = System.currentTimeMillis();

    // 0. Multi-Pod coordination: try to acquire the lock; skip if another Pod holds it.
    //    NoopPatrolLockProvider always returns true (single-Pod mode).
    if (!lockProvider.tryAcquire(task.getId(), lockTtlSeconds)) {
        log.info("Patrol {} skipped (lock held by another Pod, lockProvider={})",
                task.getId(), lockProvider.type());
        return;
    }
    try {
        // 1. Look up skill
        SkillMeta skill = skillRegistry.get(task.getSkillName());
        if (skill == null) {
            // Skill not found → record FAILED report
            PatrolReport report = new PatrolReport(
                null, task.getId(), null,
                task.getSkillName(), triggeredAt, "FAILED",
                "Skill not found: " + task.getSkillName(), false);
            report.setUserId(task.getUserId());
            reportStore.save(report);
            return;
        }

        try {
            // 2. Create AgentTask and execute
            AgentTask agentTask = AgentTask.create(
                task.getUserId(), task.getSkillName(),
                task.getInputs(), null);
            agentExecutor.execute(agentTask, skill);

            // 3. Extract result, store report
            TaskStatus status = agentTask.getStatus();
            String summary = agentTask.getReport() != null
                ? agentTask.getReport() : "Patrol completed";

            // 4. Heuristic anomaly detection: status or summary contains anomaly keywords → anomalyDetected=true
            boolean anomaly = detectAnomaly(summary, status.name());

            PatrolReport report = new PatrolReport(
                null, task.getId(), agentTask.getTaskId(),
                task.getSkillName(), triggeredAt,
                status.name(), summary, anomaly);
            report.setUserId(task.getUserId());
            reportStore.save(report);

            // 5. Anomaly → invoke all AlertPushChannels (Webhook / Email / custom)
            if (anomaly) {
                pushToChannels(report, null);
            }
        } catch (Exception e) {
            // 4. Exception → record FAILED report (also pushed to channels as anomaly)
            PatrolReport report = new PatrolReport(
                null, task.getId(), null,
                task.getSkillName(), triggeredAt, "FAILED",
                e.getMessage(), false);
            report.setUserId(task.getUserId());
            reportStore.save(report);
        }
    } finally {
        // 6. Release the lock (always, regardless of success/failure)
        lockProvider.release(task.getId());
    }
}

/** Heuristic: status FAILED/TIMEOUT or summary contains keywords → true */
protected boolean detectAnomaly(String summary, String statusStr) {
    if ("FAILED".equals(statusStr) || "TIMEOUT".equals(statusStr)) {
        return true;
    }
    if (summary == null || summary.isEmpty()) return false;
    String lower = summary.toLowerCase(Locale.ROOT);
    String[] markers = {"critical", "warning", "error", "exception", "failed",
            "异常", "错误", "失败", "告警", "风险"};
    for (String m : markers) {
        if (lower.contains(m)) return true;
    }
    return false;
}

/** Iterate all AlertPushChannels, push one-by-one (single channel failure doesn't affect others) */
private void pushToChannels(PatrolReport report, AnomalyEvent event) {
    if (pushChannels.isEmpty()) return;
    for (AlertPushChannel channel : pushChannels) {
        try {
            channel.push(report, event);
        } catch (Exception e) {
            log.error("Push channel '{}' failed (reportId={}): {}",
                    channel.type(), report.getId(), e.getMessage());
        }
    }
}
```

#### Multi-Pod coordination deployment modes

| Deployment | lockProvider.type() | tryAcquire behavior | Recommended for |
|------------|---------------------|---------------------|-----------------|
| Single Pod | `noop` | Always `true` | Dev/test/small production |
| Multi Pod | Custom impl (Redis / k8s lease / DB row lock) | Only one Pod wins per `patrolId` | Multi-replica K8s deployments |

Hosts implement `PatrolLockProvider` + `@Component` (or `@Bean`); `@ConditionalOnMissingBean`
prefers the custom bean over `NoopPatrolLockProvider`.

### 3.3 Canceling a Patrol

```java
public void cancel(String patrolId) {
    ScheduledFuture<?> future = scheduledFutures.remove(patrolId);
    if (future != null) {
        future.cancel(false);    // Does not interrupt running tasks
    }
    tasks.remove(patrolId);
}
```

`cancel(false)` does not interrupt a currently running patrol task; it only
cancels future scheduled executions.

### 3.4 Thread Pool Configuration

Patrol uses a dedicated `ThreadPoolTaskScheduler` (bean name `patrolTaskScheduler`):

```yaml
snap-agent:
  patrol:
    enabled: true
    scheduler-pool-size: 2        # Default 2 threads
    report-buffer-size: 500      # PatrolReportStore ring buffer capacity
```

- `schedulerPoolSize`: scheduling thread pool size, controls concurrent patrols
- `reportBufferSize`: report storage ring buffer capacity; evicts oldest when full
- Thread name prefix `patrol-`; `waitForTasksToCompleteOnShutdown=true` for graceful shutdown

---

## 4. Alert Mechanism — DefaultAnomalyEventListener + AlertConverger

The alert flow is triggered by an external system pushing an `AnomalyEvent`,
processed by `DefaultAnomalyEventListener`, which then executes a diagnostic skill.

### 4.1 Event Processing Flow

```
AnomalyEvent (pushed by external system)
    │
    ▼
DefaultAnomalyEventListener.onEvent(event)
    │
    ├─ 1. Record alert: alertConverger.record(event) → AlertConvergence
    │     (fingerprint dedup; if an ACTIVE same-source alert exists, count++ without creating new)
    │
    ├─ 2. Determine skill: event.skillName ?? "error-spike-investigation"
    │
    ├─ 3. Build inputs: merge event.inputs + _event_type/source/message
    │
    ├─ 4. Look up skill: skillRegistry.get(skillName)
    │     └─ Not found → save FAILED PatrolReport (anomalyDetected=true), return
    │
    ├─ 5. Execute diagnosis: AgentTask.create("patrol-user", skillName, inputs, null)
    │                       agentExecutor.execute(agentTask, skill)
    │
    └─ 6. Store report: PatrolReport(patrolId="event-{alertId}", anomalyDetected=true)
                       reportStore.save(report)
```

### 4.2 Default Skill and Input Injection

When `AnomalyEvent.skillName` is empty, the default skill `error-spike-investigation`
is used (6 phases: locate time window → extract error logs → correlate changes →
trace analysis → code location → output root cause).

Event information is automatically injected into skill inputs:

```java
inputs.put("_event_type", event.getType());
inputs.put("_event_source", event.getSource());
inputs.put("_event_message", event.getMessage());
```

Skill markdown can reference these via `{_event_type}`, `{_event_source}`,
`{_event_message}`.

### 4.3 Exception Handling

`onEvent` catches all exceptions to ensure a single event failure does not
affect subsequent events:

```java
} catch (Exception e) {
    log.error("Anomaly-triggered diagnosis failed: {}", e.getMessage(), e);
    PatrolReport report = new PatrolReport(
        null, patrolId, null, skillName, triggeredAt,
        "FAILED", e.getMessage(), true);
    reportStore.save(report);
}
```

---

## 5. Alert Convergence Algorithm — InMemoryAlertConverger

`InMemoryAlertConverger` is the default `AlertConverger` implementation, using
fingerprint dedup + ring buffer + auto-stale-resolution.

### 5.1 Fingerprint Computation

```java
private String computeFingerprint(AnomalyEvent event) {
    String key = event.getType() + "|" + event.getSource();
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hash = md.digest(key.getBytes("UTF-8"));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {           // Take first 8 bytes (16 hex chars)
        sb.append(String.format("%02x", hash[i]));
    }
    return sb.toString();
}
```

- Fingerprint = first 8 bytes of `SHA-256(type|source)`
- Events with the same type + source converge into a single `AlertConvergence` record
- Falls back to `String.valueOf(key.hashCode())` if SHA-256 is unavailable

### 5.2 Deduplication Logic

```java
public AlertConvergence record(AnomalyEvent event) {
    String fingerprint = computeFingerprint(event);
    synchronized (fingerprint.intern()) {          // Per-fingerprint lock, no global lock
        AlertConvergence existing = findActiveByFingerprint(fingerprint);
        if (existing != null) {
            existing.incrementCount();              // count++ (AtomicInteger)
            existing.setLastSeen(event.getTimestamp());
            return existing;                       // Do not create new record
        }
        // Create new alert
        AlertConvergence alert = new AlertConvergence(
            "alert_" + idCounter.incrementAndGet(),
            fingerprint, event.getType(), event.getSource(),
            event.getMessage(), null);
        alerts.put(id, alert);
        // Ring buffer full → evict oldest
        if (!ringBuffer.offer(id)) {
            String evicted = ringBuffer.poll();
            alerts.remove(evicted);
            ringBuffer.offer(id);
        }
        return alert;
    }
}
```

Key design points:
- `synchronized (fingerprint.intern())`: locks per fingerprint string, allowing
  different fingerprints to be processed in parallel
- `findActiveByFingerprint`: iterates `alerts.values()` to find a record with the
  same fingerprint and `ACTIVE` status
- Hit on existing record → `count++` + update `lastSeen`, no new record created
- Miss → create new `AlertConvergence`, store in ring buffer

### 5.3 Ring Buffer Eviction

```java
private final ArrayBlockingQueue<String> ringBuffer;   // stores alertIds
private final ConcurrentHashMap<String, AlertConvergence> alerts;
```

- Ring buffer capacity = `snap-agent.alert.buffer-size` (default 1000)
- When full, `poll()` evicts the oldest alertId and removes the corresponding
  record from the `alerts` map
- Dual structure (queue + map) ensures FIFO eviction + O(1) lookup

### 5.4 Auto Stale Resolution

```java
private void autoResolveStale() {
    long threshold = System.currentTimeMillis()
        - (autoResolveMinutes * 60_000L);
    for (AlertConvergence alert : alerts.values()) {
        if (STATUS_ACTIVE.equals(alert.getStatus())
                && alert.getLastSeen() < threshold) {
            alert.setStatus(STATUS_RESOLVED);
        }
    }
}
```

- Lazy trigger: executed only on `query()` and `count()` calls
- `autoResolveMinutes` = `snap-agent.alert.auto-resolve-minutes` (default 30 minutes)
- ACTIVE alerts not updated within the threshold are automatically marked `RESOLVED`

### 5.5 Trend Prediction

SnapAgent's trend prediction is implemented via a **skill** rather than a standalone
Java algorithm class. The built-in `trend-prediction` skill can be scheduled as a
patrol task:

```
trend-prediction skill (4 steps):
  1. Query 7-day metric trend → metrics_query
  2. Detect growth rate → linear/exponential/stable
  3. Estimate time-to-threshold → alert if < 7 days
  4. Generate early warning → current value/trend direction/ETA/confidence/recommendation
```

Configured as a patrol task:

```bash
POST /patrol/tasks
{
  "skillName": "trend-prediction",
  "cron": "0 0 8 * * *",          # Every day at 8 AM
  "inputs": {
    "metric": "node_memory_MemAvailable_bytes"
  }
}
```

---

## 6. Bugfix Suggestion and Report Delivery

### 6.1 TemplateBugfixSuggester

`TemplateBugfixSuggester` is the default `BugfixSuggester` implementation, extracting
fix clues from the transcript of a patrol/alert task.

**Extraction Logic:**

```
Iterate transcript events:
  ├─ TYPE_TOOL_CALL (name=code_read)     → extract file_path from args
  ├─ TYPE_TOOL_CALL (name=git_log)       → extract file_path from args
  ├─ TYPE_TOOL_RESULT (matching git_log) → extract commit hashes via regex [a-f0-9]{6,40}
  └─ TYPE_DONE                           → extract rootCause from data.report
```

**Confidence Rules:**

| Condition | Confidence | Description |
|-----------|------------|-------------|
| Has affectedFiles **and** commitRefs | `HIGH` | Code + change dual evidence |
| Has affectedFiles **or** commitRefs | `MEDIUM` | Single evidence |
| Neither | `LOW` | No diagnostic data |

**File Path Extraction (dual strategy):**

```java
private void extractFilePaths(Object argsObj, Set<String> files) {
    if (argsObj instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) argsObj;
        Object filePath = map.get("file_path");      // Extract directly from Map
        if (filePath != null) files.add(String.valueOf(filePath));
    }
    Matcher m = FILE_PATH_PATTERN.matcher(argsObj.toString());  // Regex fallback
    while (m.find()) files.add(m.group(1));
}
```

### 6.2 Report Delivery

Patrol/alert reports are delivered via REST API rather than independent push channels:

```
GET /patrol/reports?page=0&size=20    → Paginated report list
GET /patrol/reports/{id}              → Single report detail
GET /alerts?page=0&size=20&type=...  → Paginated alert list
POST /alerts/{id}/resolve            → Manually resolve an alert
POST /runs/{id}/bugfix-suggestion    → Generate fix suggestion from task transcript
```

External systems can poll `/patrol/reports` and `/alerts` endpoints to retrieve the
latest reports, or integrate with alerting platforms (e.g., PagerDuty, WeCom) via
webhook polling.

---

## 7. Auto-Configuration and Lifecycle

### 7.1 Bean Assembly

All patrol/alert beans are conditionally assembled in `SnapAgentAutoConfiguration`
via `@ConditionalOnProperty`:

| Bean | Condition | Default Impl | Description |
|------|-----------|-------------|-------------|
| `patrolReportStore` | `snap-agent.patrol.enabled=true` | `InMemoryPatrolReportStore` | Ring buffer report storage (v1.1 SPI) |
| `patrolLockProvider` | `snap-agent.patrol.enabled=true` | `NoopPatrolLockProvider` | Multi-Pod patrol coordination lock (new in v1.1) |
| `patrolTaskScheduler` | `snap-agent.patrol.enabled=true` | `ThreadPoolTaskScheduler` | Dedicated scheduling pool (pool-size=2, prefix `patrol-`) |
| `scheduledPatrolScheduler` | `snap-agent.patrol.enabled=true` | `ScheduledPatrolScheduler` | Cron scheduler (v1.1 injects lockProvider + pushChannels) |
| `inMemoryAlertConverger` | `snap-agent.alert.enabled=true` | `InMemoryAlertConverger` | Alert deduplicator |
| `defaultAnomalyEventListener` | `snap-agent.patrol.enabled=true` | `DefaultAnomalyEventListener` | Anomaly event listener (v1.1 injects pushChannels) |
| `templateBugfixSuggester` | Unconditional (`@ConditionalOnMissingBean`) | `TemplateBugfixSuggester` | Fix suggestion generator |
| `webhookAlertPushChannel` | `snap-agent.alert.push.webhook.enabled=true` and `url` non-empty | `WebhookAlertPushChannel` | Webhook push channel (new in v1.1) |
| `emailAlertPushChannel` | `snap-agent.alert.push.email.enabled=true` and classpath contains `JavaMailSender` | `EmailAlertPushChannel` | Email push channel (new in v1.1, optional deps) |

> **Optional dependencies for `emailAlertPushChannel`**: `spring-context-support`
> and `javax.mail` are marked `<optional>true</optional>` in the starter pom. When
> the host doesn't include them, `@ConditionalOnClass(name =
> "org.springframework.mail.javamail.JavaMailSender")` skips the bean. Add
> `spring-boot-starter-mail` to enable Email push.

### 7.2 Lifecycle

The patrol thread pool `patrolTaskScheduler` sets `waitForTasksToCompleteOnShutdown=true`,
so when the Spring container shuts down, it waits for running patrol tasks to complete
before terminating the thread pool. `ScheduledFuture` is canceled via `cancel(false)`,
which does not interrupt currently running tasks.

### 7.3 Controller Integration

`SnapAgentController` injects patrol components via `ObjectProvider`; when disabled,
endpoints return 503:

```java
PatrolScheduler scheduler = patrolScheduler;
if (scheduler == null) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Collections.singletonMap("error", "patrol is not enabled"));
}
```

- `patrolScheduler`, `alertConverger`, `bugfixSuggester` are all optional dependencies
- When not enabled, corresponding endpoints return 503 Service Unavailable
- `TemplateBugfixSuggester` is assembled unconditionally and always available

---

## 8. Configuration and Extension

### 8.1 Configuration Reference

```yaml
snap-agent:
  patrol:
    enabled: false                    # Patrol master switch (default off)
    scheduler-pool-size: 2            # Scheduling thread pool size
    report-buffer-size: 500           # Report ring buffer capacity
    lock-ttl-seconds: 300             # Multi-Pod coordination lock TTL (new in v1.1, default 5 min)
  alert:
    enabled: false                    # Alert convergence master switch (default off)
    buffer-size: 1000                  # Alert ring buffer capacity
    auto-resolve-minutes: 30           # Auto-resolve threshold (minutes)
    push:                              # Anomaly report push (new in v1.1)
      email:
        enabled: false                # Email push switch (default off)
        from: snap-agent@example.com  # Sender
        to:                            # Recipients list
          - ops@example.com
          - dev@example.com
        subject-prefix: "[SnapAgent Alert]"  # Subject prefix
      webhook:
        enabled: false                # Webhook push switch (default off)
        url: https://hook.example.com/snap-agent  # Push URL
        auth-header: Authorization     # Auth header name
        auth-token: Bearer secret      # Auth token (supports ${ENV} placeholder)
        connect-timeout-ms: 5000       # Connect timeout (default 5000ms)
        read-timeout-ms: 10000         # Read timeout (default 10000ms)
```

| Property | Default | Description |
|----------|---------|-------------|
| `snap-agent.patrol.enabled` | `false` | Patrol master switch |
| `snap-agent.patrol.scheduler-pool-size` | `2` | Scheduling thread pool size |
| `snap-agent.patrol.report-buffer-size` | `500` | Report storage capacity (evicts oldest when full) |
| `snap-agent.patrol.lock-ttl-seconds` | `300` | Multi-Pod coordination lock TTL (new in v1.1) |
| `snap-agent.alert.enabled` | `false` | Alert dedup master switch |
| `snap-agent.alert.buffer-size` | `1000` | Alert storage capacity (evicts oldest when full) |
| `snap-agent.alert.auto-resolve-minutes` | `30` | Threshold for auto-marking RESOLVED |
| `snap-agent.alert.push.email.enabled` | `false` | Email push switch (new in v1.1) |
| `snap-agent.alert.push.email.from` | `snap-agent@local` | Email sender |
| `snap-agent.alert.push.email.to` | `[]` | Email recipient list |
| `snap-agent.alert.push.email.subject-prefix` | `[SnapAgent Alert]` | Email subject prefix |
| `snap-agent.alert.push.webhook.enabled` | `false` | Webhook push switch (new in v1.1) |
| `snap-agent.alert.push.webhook.url` | _empty_ | Webhook push URL; bean only wired when non-empty |
| `snap-agent.alert.push.webhook.auth-header` | `Authorization` | Webhook auth header name |
| `snap-agent.alert.push.webhook.auth-token` | _empty_ | Webhook auth token |
| `snap-agent.alert.push.webhook.connect-timeout-ms` | `5000` | Webhook connect timeout |
| `snap-agent.alert.push.webhook.read-timeout-ms` | `10000` | Webhook read timeout |

### 8.2 Custom PatrolScheduler

Implement the `PatrolScheduler` interface + `@Component` (or `@Bean`) to replace the
default `ScheduledPatrolScheduler`. `@ConditionalOnMissingBean` ensures your custom
bean takes precedence:

```java
@Component
public class QuartzPatrolScheduler implements PatrolScheduler {
    @Override
    public void schedule(PatrolTask task) {
        // Use Quartz Scheduler instead of Spring CronTrigger
        // Supports persistent tasks, misfire policies, etc.
    }
    // ...
}
```

### 8.3 Custom AlertConverger

Implement the `AlertConverger` interface to use Redis/DB-backed alert storage:

```java
@Component
public class RedisAlertConverger implements AlertConverger {
    @Override
    public AlertConvergence record(AnomalyEvent event) {
        // Redis SETEX sliding window dedup
        // Supports multi-instance shared alert state
    }
    // ...
}
```

### 8.4 Custom AnomalyEventListener

Implement the `AnomalyEventListener` SPI to connect external event sources (Kafka,
RabbitMQ, etc.):

```java
@Component
public class KafkaAnomalyEventListener implements AnomalyEventListener {
    @KafkaListener(topics = "anomaly-alerts")
    public void onMessage(ConsumerRecord<String, String> record) {
        AnomalyEvent event = parseEvent(record.value());
        onEvent(event);     // Trigger diagnosis
    }
    @Override
    public void onEvent(AnomalyEvent event) {
        // Custom handling logic
    }
}
```

### 8.5 Custom BugfixSuggester

Implement the `BugfixSuggester` interface to leverage LLM for enhanced fix suggestion
generation:

```java
@Component
public class LlmBugfixSuggester implements BugfixSuggester {
    @Override
    public BugfixSuggestion suggest(String taskId, List<TranscriptEvent> transcript) {
        // Pass transcript to LLM for analysis, generate more precise root cause and suggestions
        // Can leverage KnowledgeBase to inject business context
    }
}
```

### 8.6 Custom PatrolLockProvider (multi-Pod coordination, new in v1.1)

Implement the `PatrolLockProvider` interface to replace the default
`NoopPatrolLockProvider`. Common patterns:

**Redis-based**:

```java
@Component
public class RedisPatrolLockProvider implements PatrolLockProvider {
    private final StringRedisTemplate redis;
    private final String podName = System.getenv().getOrDefault("MY_POD_NAME", "pod-0");

    @Override
    public boolean tryAcquire(String patrolId, long ttlSeconds) {
        String key = "patrol:lock:" + patrolId;
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, podName, Duration.ofSeconds(ttlSeconds)));
    }

    @Override
    public void release(String patrolId) {
        // Use a Lua script to verify owner before deleting, to avoid releasing someone else's lock
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList("patrol:lock:" + patrolId), podName);
    }

    @Override
    public String type() { return "redis"; }
}
```

**Kubernetes Lease-based**:

```java
@Component
public class K8sLeasePatrolLockProvider implements PatrolLockProvider {
    private final KubernetesClient k8s;
    private final String namespace = System.getenv().getOrDefault("POD_NAMESPACE", "default");
    private final String holder = System.getenv().getOrDefault("MY_POD_NAME", "pod-0");

    @Override
    public boolean tryAcquire(String patrolId, long ttlSeconds) {
        // Create coordination.k8s.io/v1 Lease; spec.holderIdentity = holder
        // spec.leaseDurationSeconds = ttlSeconds; update fails if already held → false
        return createLeaseOrRenew(patrolId, ttlSeconds);
    }

    @Override
    public void release(String patrolId) {
        // Delete the Lease (only if holderIdentity matches)
    }

    @Override
    public String type() { return "k8s-lease"; }
}
```

### 8.7 Custom AlertPushChannel (push channel extension, new in v1.1)

Implement `AlertPushChannel` to extend push channels (DingTalk, Feishu, PagerDuty, etc.):

```java
@Component
public class DingTalkAlertPushChannel implements AlertPushChannel {
    private final String webhookUrl;
    private final String secret;

    @Override
    public void push(PatrolReport report, AnomalyEvent event) {
        if (report == null || !report.isAnomalyDetected()) return;
        // Build DingTalk markdown message
        // POST https://oapi.dingtalk.com/robot/send?access_token=...
        // { "msgtype": "markdown", "markdown": { "title": ..., "text": ... } }
    }

    @Override
    public String type() { return "dingtalk"; }
}
```

> Custom `AlertPushChannel` beans work alongside the default Webhook/Email channels.
> All `AlertPushChannel` Spring beans are collected as a `List<AlertPushChannel>` by
> both `scheduledPatrolScheduler` and `defaultAnomalyEventListener`. Anomaly reports
> are pushed to all channels; a single channel failure doesn't affect others.

### 8.8 Custom PatrolReportStore (storage extension, new in v1.1)

Implement `PatrolReportStore` to persist reports to DB/Redis/file:

```java
@Component
public class JdbcPatrolReportStore implements PatrolReportStore {
    private final JdbcTemplate jdbc;

    @Override
    public void save(PatrolReport report) {
        if (report.getId() == null) report.setId("rep_" + UUID.randomUUID());
        jdbc.update("INSERT INTO patrol_report (id, patrol_id, task_id, user_id, "
                + "skill_name, triggered_at, status, summary, anomaly_detected) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)", ...);
    }

    @Override
    public List<PatrolReport> getReports(String userId, int limit, int offset) {
        return jdbc.query("SELECT * FROM patrol_report WHERE user_id = ? "
                + "ORDER BY triggered_at DESC LIMIT ? OFFSET ?", ...);
    }

    @Override
    public long count(String userId) { ... }

    @Override
    public PatrolReport get(String reportId) { ... }
}
```

### 8.9 Built-in Skills

| Skill | Tools | Purpose |
|-------|-------|---------|
| `health-patrol` | metrics_query | Comprehensive health patrol: CPU/memory/error rate/latency, threshold checks |
| `trend-prediction` | metrics_query | 7-day trend analysis, predict time-to-threshold |
| `error-spike-investigation` | metrics_query, log_search, trace_search, code_read, git_log | Error spike investigation (default alert skill, 6 phases) |
| `ops-health-check` | metrics_query | Operational health check |

Typical patrol configuration example:

```yaml
snap-agent:
  patrol:
    enabled: true
    scheduler-pool-size: 2
    report-buffer-size: 500
  alert:
    enabled: true
    buffer-size: 1000
    auto-resolve-minutes: 30
```

```bash
# Register a daily 8 AM health patrol
curl -X POST http://localhost:8080/snap-agent/patrol/tasks \
  -H "Content-Type: application/json" \
  -u admin:password \
  -d '{
    "skillName": "health-patrol",
    "cron": "0 0 8 * * *",
    "inputs": { "service": "order-service" }
  }'

# Query patrol reports
curl -u admin:password \
  "http://localhost:8080/snap-agent/patrol/reports?page=0&size=20"

# Manually trigger an anomaly event (via external system calling onEvent)
# View converged alerts
curl -u admin:password \
  "http://localhost:8080/snap-agent/alerts?type=error-spike"

# Generate a bugfix suggestion from a diagnostic task
curl -X POST -u admin:password \
  "http://localhost:8080/snap-agent/runs/{taskId}/bugfix-suggestion"
```
