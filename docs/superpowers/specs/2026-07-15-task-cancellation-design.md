# Task Cancellation Design Spec

> v0.2 Feature 1: `POST /runs/{id}/cancel` — mid-stream task cancellation via OkHttp `Call.cancel()`

## Problem

Current AgentExecutor checks `TaskStatus.CANCELLED` between turns (lines 90, 116), but there's no way to trigger cancellation externally. The OkHttp `Call` object is created inside `executeCall()` and never exposed, so in-flight HTTP calls can't be interrupted. Users must wait for the current LLM call to finish (potentially 30s+) before cancellation takes effect.

## Solution: Three-layer cancellation

1. **HTTP layer**: `Call.cancel()` interrupts the in-flight OkHttp request → throws `IOException("Canceled")`
2. **AgentExecutor layer**: checks `TaskStatus.CANCELLED` at turn boundaries → stops the loop gracefully
3. **Controller layer**: `POST /runs/{id}/cancel` endpoint → sets status + calls `llmClient.cancel()`

## Changes

### 1. SPI: `LlmClient` interface (`core/llm/LlmClient.java`)

```java
public interface LlmClient {
    void stream(LlmRequest req, LlmEventSink events, String taskId);  // +taskId
    default void cancel(String taskId) {}  // new, default no-op
    default List<String> listModels() { return Collections.emptyList(); }
}
```

- `stream()` adds `String taskId` parameter — identifies the task for Call tracking
- `cancel(String taskId)` — default no-op, implementations override to interrupt HTTP

### 2. LlmClient implementations (`starter/llm/AnthropicLlmClient.java`, `OpenAiLlmClient.java`)

**Pattern** (identical for both):

```java
private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();
private final ThreadLocal<String> currentTaskId = new ThreadLocal<>();

@Override
public void stream(LlmRequest req, LlmEventSink events, String taskId) {
    currentTaskId.set(taskId);
    try {
        // existing stream logic unchanged
    } finally {
        activeCalls.remove(taskId);
        currentTaskId.remove();
    }
}

@Override
protected Response executeCall(Request request) throws IOException {
    Call call = httpClient.newCall(request);
    String tid = currentTaskId.get();
    if (tid != null) {
        activeCalls.put(tid, call);
    }
    return call.execute();
    // NOT removed here — stream() finally cleans up after SSE consumption
}

@Override
public void cancel(String taskId) {
    Call call = activeCalls.get(taskId);
    if (call != null) {
        log.info("Cancelling LLM call for task {}", taskId);
        call.cancel();
    }
}
```

**Why ThreadLocal**: `executeCall()` is the test seam (tests override it to return mock Responses). ThreadLocal passes taskId into `executeCall()` without changing its signature, so existing test overrides continue working.

**Lifecycle**: Call is registered in `executeCall()` (when HTTP starts), removed in `stream()` finally (after SSE body fully consumed). This ensures the Call stays in the map during the entire HTTP round-trip.

### 3. AgentExecutor (`core/agent/AgentExecutor.java`)

**Update `stream()` call** (line 127):
```java
llmClient.stream(req, collector, task.getTaskId());  // pass taskId
```

**Add CANCELLED guard in catch block** (line 128):
```java
} catch (RuntimeException e) {
    if (task.getStatus() == TaskStatus.CANCELLED) {
        task.addTranscriptEvent(TranscriptEvent.thought("任务已取消"));
        taskStore.update(task);
        return;
    }
    // existing FAILED handling...
}
```

**Add CANCELLED guard in errorMessage check** (line 137):
```java
if (collector.errorMessage != null) {
    if (task.getStatus() == TaskStatus.CANCELLED) {
        task.addTranscriptEvent(TranscriptEvent.thought("任务已取消"));
        taskStore.update(task);
        return;
    }
    // existing FAILED handling...
}
```

No other changes needed — the existing CANCELLED checks at lines 90 and 116 handle between-turn cancellation.

### 4. Controller (`starter/web/SnapAgentController.java`)

**New endpoint**:

```java
@PostMapping("/runs/{id}/cancel")
public ResponseEntity<Object> cancelRun(@PathVariable String id) {
    String userId = securityGateway.currentUserId();
    if (userId == null) {
        return errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "not authenticated");
    }

    AgentTask task = taskStore.get(id);
    if (task == null) {
        return errorResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "task not found: " + id);
    }

    // Ownership check
    if (!userId.equals(task.getUserId())) {
        return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "not task owner");
    }

    // Idempotent: already in terminal state
    TaskStatus status = task.getStatus();
    if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED
            || status == TaskStatus.TIMEOUT || status == TaskStatus.CANCELLED) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", id);
        result.put("status", status.name());
        result.put("message", "task already in terminal state");
        return ResponseEntity.ok().body(result);
    }

    // Set CANCELLED first (so AgentExecutor sees it), then interrupt HTTP
    task.setStatus(TaskStatus.CANCELLED);
    task.addTranscriptEvent(TranscriptEvent.done("CANCELLED", "用户取消任务"));
    taskStore.update(task);

    // Interrupt in-flight HTTP call
    if (llmClient != null) {
        llmClient.cancel(id);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("taskId", id);
    result.put("status", TaskStatus.CANCELLED.name());
    return ResponseEntity.ok().body(result);
}
```

**Key decisions**:
- **Ownership check**: only the user who created the task can cancel it
- **Idempotent**: cancelling an already-finished task returns 200 with current status (not 409)
- **Order**: set CANCELLED status first, then call `llmClient.cancel()` — ensures AgentExecutor sees CANCELLED even if the HTTP interrupt doesn't propagate cleanly

### 5. Frontend (`starter/resources/static/snap-agent/app.js`)

Existing `cancelSkillStream(name, savePartial)` closes the SSE EventSource. Update to also call the cancel endpoint:

```javascript
function cancelSkillStream(name, savePartial) {
    var state = skillChatState[name];
    if (!state || !state.stream) return;

    // Call server cancel endpoint if we have a taskId
    if (state.stream.taskId) {
        fetch(BASE + '/runs/' + state.stream.taskId + '/cancel', {
            method: 'POST',
            headers: authHeaders()
        }).catch(function(e) {
            console.error('[cancel] Failed to cancel task:', e);
        });
    }

    // Close SSE connection (existing logic)
    if (state.stream.es) {
        state.stream.es.close();
    }
    // ... rest of existing cleanup
}
```

## Test Strategy (TDD — tests first)

### Core module tests (`AgentExecutorTest.java`)

| Test | Description |
|------|-------------|
| `testCancelledBeforeStart` | Task with CANCELLED status → executor returns immediately, no LLM call |
| `testCancelledMidExecution` | Task cancelled after first turn → executor stops gracefully, status stays CANCELLED |
| `testCancelledDuringLlmStream` | LlmClient throws RuntimeException after cancel → executor checks CANCELLED status, doesn't set FAILED |
| `testCancelDoesNotOverrideFailed` | Task FAILED (not cancelled) → RuntimeException → status stays FAILED |

Mock LlmClient that throws `RuntimeException("Canceled")` when cancel is called, simulating `Call.cancel()` behavior.

### Starter module tests

| File | Test | Description |
|------|------|-------------|
| `AnthropicLlmClientTest` | `testCancelWithActiveCall` | Call registered in activeCalls → cancel() invokes Call.cancel() |
| `AnthropicLlmClientTest` | `testCancelWithNoActiveCall` | No active call → cancel() is no-op, no exception |
| `AnthropicLlmClientTest` | `testStreamCleansUpActiveCalls` | After stream() completes → activeCalls is empty |
| `OpenAiLlmClientTest` | Same 3 tests | Identical pattern for OpenAI client |
| `SnapAgentControllerTest` | `testCancelRunningTask` | POST /runs/{id}/cancel on RUNNING task → 200, status CANCELLED |
| `SnapAgentControllerTest` | `testCancelNonExistentTask` | POST /runs/{id}/cancel with bad id → 404 |
| `SnapAgentControllerTest` | `testCancelOtherUserTask` | POST /runs/{id}/cancel on another user's task → 403 |
| `SnapAgentControllerTest` | `testCancelAlreadyFinished` | POST /runs/{id}/cancel on SUCCEEDED task → 200, idempotent |
| `SnapAgentControllerTest` | `testCancelCallsLlmClientCancel` | Verify llmClient.cancel(taskId) was called |

## Files Changed

| File | Change |
|------|--------|
| `core/llm/LlmClient.java` | Add `taskId` param to `stream()`, add `cancel()` default method |
| `core/agent/AgentExecutor.java` | Pass taskId to `stream()`, add CANCELLED guards in catch/error blocks |
| `core/agent/AgentExecutorTest.java` | 4 new tests for cancellation scenarios |
| `starter/llm/AnthropicLlmClient.java` | activeCalls map, ThreadLocal, cancel(), stream() finally cleanup |
| `starter/llm/AnthropicLlmClientTest.java` | 3 new tests for cancel tracking |
| `starter/llm/OpenAiLlmClient.java` | Same pattern as Anthropic |
| `starter/llm/OpenAiLlmClientTest.java` | 3 new tests |
| `starter/web/SnapAgentController.java` | New POST /runs/{id}/cancel endpoint |
| `starter/web/SnapAgentControllerTest.java` | 4 new tests for cancel endpoint |
| `starter/resources/static/snap-agent/app.js` | cancelSkillStream calls POST /runs/{id}/cancel |

## Non-goals

- No SSE event for cancellation notification to other clients (the transcript event suffices)
- No timeout-based auto-cancellation (existing `max-turns` + `task-timeout-minutes` handle that)
- No cancellation of tool execution (JDBC queries are typically fast; if needed, future feature)
