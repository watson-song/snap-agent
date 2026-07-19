# SnapAgent Proactive Monitoring Architecture

> Version: v0.5 | Updated: 2026-07-17

SnapAgent's proactive monitoring capability is provided jointly by the `patrol`
package in `snap-agent-core` (SPI + data models) and the `patrol` package in
`snap-agent-spring-boot-2x-starter` (default implementations + auto-configuration).
This document describes the complete architecture of two complementary monitoring
modes — **Patrol** and **Alert**.

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
├── PatrolTask.java             (value object: scheduled task definition)
├── PatrolReport.java           (value object: patrol/alert report)
├── PatrolReportStore.java     (ring buffer report storage)
├── AnomalyEvent.java           (value object: anomaly event)
├── AlertConvergence.java       (value object: deduplicated alert record)
└── BugfixSuggestion.java       (value object: fix suggestion)

boot2x/patrol/
├── ScheduledPatrolScheduler.java      (PatrolScheduler default impl)
├── InMemoryAlertConverger.java        (AlertConverger default impl)
├── DefaultAnomalyEventListener.java   (AnomalyEventListener default impl)
└── TemplateBugfixSuggester.java       (BugfixSuggester default impl)
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

### 2.5 Data Models

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

When the cron fires, `executePatrol(task)` runs in the scheduling thread pool:

```java
private void executePatrol(PatrolTask task) {
    long triggeredAt = System.currentTimeMillis();

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

        PatrolReport report = new PatrolReport(
            null, task.getId(), agentTask.getTaskId(),
            task.getSkillName(), triggeredAt,
            status.name(), summary, false);
        report.setUserId(task.getUserId());
        reportStore.save(report);
    } catch (Exception e) {
        // 4. Exception → record FAILED report
        PatrolReport report = new PatrolReport(
            null, task.getId(), null,
            task.getSkillName(), triggeredAt, "FAILED",
            e.getMessage(), false);
        report.setUserId(task.getUserId());
        reportStore.save(report);
    }
}
```

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
| `patrolReportStore` | `snap-agent.patrol.enabled=true` | `PatrolReportStore` | Ring buffer report storage |
| `patrolTaskScheduler` | `snap-agent.patrol.enabled=true` | `ThreadPoolTaskScheduler` | Dedicated scheduling pool (pool-size=2, prefix `patrol-`) |
| `scheduledPatrolScheduler` | `snap-agent.patrol.enabled=true` | `ScheduledPatrolScheduler` | Cron scheduler |
| `inMemoryAlertConverger` | `snap-agent.alert.enabled=true` | `InMemoryAlertConverger` | Alert deduplicator |
| `defaultAnomalyEventListener` | `snap-agent.patrol.enabled=true` | `DefaultAnomalyEventListener` | Anomaly event listener |
| `templateBugfixSuggester` | Unconditional (`@ConditionalOnMissingBean`) | `TemplateBugfixSuggester` | Fix suggestion generator |

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
  alert:
    enabled: false                    # Alert convergence master switch (default off)
    buffer-size: 1000                  # Alert ring buffer capacity
    auto-resolve-minutes: 30           # Auto-resolve threshold (minutes)
```

| Property | Default | Description |
|----------|---------|-------------|
| `snap-agent.patrol.enabled` | `false` | Patrol master switch |
| `snap-agent.patrol.scheduler-pool-size` | `2` | Scheduling thread pool size |
| `snap-agent.patrol.report-buffer-size` | `500` | Report storage capacity (evicts oldest when full) |
| `snap-agent.alert.enabled` | `false` | Alert dedup master switch |
| `snap-agent.alert.buffer-size` | `1000` | Alert storage capacity (evicts oldest when full) |
| `snap-agent.alert.auto-resolve-minutes` | `30` | Threshold for auto-marking RESOLVED |

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

### 8.6 Built-in Skills

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
