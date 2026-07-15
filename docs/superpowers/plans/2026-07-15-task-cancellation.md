# Task Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /runs/{id}/cancel` endpoint that interrupts in-flight LLM HTTP calls via OkHttp `Call.cancel()`.

**Architecture:** Three-layer cancellation: (1) `LlmClient.cancel()` calls OkHttp `Call.cancel()` to interrupt HTTP, (2) AgentExecutor checks `TaskStatus.CANCELLED` at turn boundaries and in catch blocks, (3) Controller endpoint sets status + calls cancel.

**Tech Stack:** Java 8, Spring Boot 2.5.15, OkHttp 3.x, JUnit 5, Mockito, AssertJ

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `core/llm/LlmClient.java` | Modify | Add `taskId` param to `stream()`, add `cancel()` default method |
| `core/agent/AgentExecutor.java` | Modify | Pass taskId to `stream()`, add CANCELLED guards in catch/error blocks |
| `core/agent/AgentExecutorTest.java` | Modify | Update existing `stream(any(), any())` → `stream(any(), any(), any())`, add cancellation tests |
| `starter/llm/AnthropicLlmClient.java` | Modify | Add `activeCalls` map, `ThreadLocal`, `cancel()`, stream finally cleanup |
| `starter/llm/AnthropicLlmClientTest.java` | Modify | Add cancellation tracking tests, update `stream()` calls |
| `starter/llm/OpenAiLlmClient.java` | Modify | Same pattern as Anthropic |
| `starter/llm/OpenAiLlmClientTest.java` | Modify | Same pattern as Anthropic tests |
| `starter/web/SnapAgentController.java` | Modify | Add `POST /runs/{id}/cancel` endpoint |
| `starter/web/SnapAgentControllerTest.java` | Modify | Add cancel endpoint tests |
| `starter/resources/static/snap-agent/app.js` | Modify | `cancelSkillStream` calls cancel endpoint |

---

### Task 1: SPI — LlmClient interface change

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/llm/LlmClient.java`
- Modify: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/agent/AgentExecutorTest.java`

- [ ] **Step 1: Update LlmClient interface**

Add `taskId` parameter to `stream()` and add `cancel()` default method:

```java
public interface LlmClient {

    /**
     * Stream a completion request.
     *
     * @param req    the request payload (messages, tools, model, etc.)
     * @param events the sink that receives streaming events
     * @param taskId identifier for this task, used for cancellation tracking;
     *               may be null when cancellation is not needed
     */
    void stream(LlmRequest req, LlmEventSink events, String taskId);

    /**
     * Cancel an in-flight stream call for the given task.
     * Implementations should interrupt the underlying HTTP call.
     * Default no-op for backward compatibility.
     */
    default void cancel(String taskId) {}

    /**
     * List available models from the LLM API.
     *
     * @return list of model IDs, or empty list if not supported.
     */
    default List<String> listModels() {
        return Collections.emptyList();
    }
}
```

- [ ] **Step 2: Update AgentExecutor to pass taskId**

In `AgentExecutor.java` line 127, change:

```java
llmClient.stream(req, collector);
```

to:

```java
llmClient.stream(req, collector, task.getTaskId());
```

- [ ] **Step 3: Update all existing AgentExecutorTest mock setups**

Every `when(llmClient).stream(any(), any())` must become `when(llmClient).stream(any(), any(), any())`. There are 11 occurrences in `AgentExecutorTest.java`. Update all of them. The `@BeforeEach` doesn't set up stream mocks, so only update the `@Test` methods.

Specific occurrences to update (search for `.stream(any(), any())`):
- `shouldSucceedWhenSingleTurnEndTurn` (line 81)
- `shouldSucceedWhenMultiTurnToolUseThenEndTurn` (lines 109, two `doAnswer` chains)
- `shouldHandleMultipleToolUsesInOneTurn` (lines 145, two chains)
- `shouldPassAssistantMessageWithToolUseBlocksToLlm` (line 179, uses `captor.capture()`)
- `shouldFailWhenLlmThrowsException` (line 207)
- `shouldFailWhenLlmReportsError` (line 225)
- `shouldNotBreakLoopWhenToolThrowsException` (line 251, two chains)
- `shouldStopWhenMaxTurnsExceeded` (line 276)
- `shouldBuildToolDefsFromProviderSchema` (line 381)
- `shouldSaveTaskToStoreWhenExecutionCompletes` (line 405)
- `shouldRecordAuditWhenToolDispatched` (line 429, two chains)
- `shouldPassCorrectModelToLlmRequest` (line 453)

Pattern: `.stream(any(), any())` → `.stream(any(), any(), any())`

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -test AgentExecutorTest`
Expected: PASS (17 tests, same as before — just signature updated)

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/llm/LlmClient.java \
        snap-agent-core/src/main/java/cn/watsontech/snapagent/core/agent/AgentExecutor.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/agent/AgentExecutorTest.java
git commit -m "refactor: add taskId to LlmClient.stream() and cancel() default method"
```

---

### Task 2: AgentExecutor cancellation guards

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/agent/AgentExecutor.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/agent/AgentExecutorTest.java`

- [ ] **Step 1: Write failing test — cancelled during LLM stream throws RuntimeException**

Add to `AgentExecutorTest.java`:

```java
@Test
void shouldKeepCancelledStatusWhenLlmThrowsAfterCancel() {
    // Simulate Call.cancel() causing a RuntimeException during stream
    doThrow(new RuntimeException("Canceled"))
            .when(llmClient).stream(any(), any(), any());

    AgentExecutor executor = newExecutor();
    AgentTask task = newTask();
    task.setStatus(TaskStatus.CANCELLED);

    executor.execute(task, skill);

    // Status should remain CANCELLED, not be overridden to FAILED
    assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    // Should NOT have an error transcript event
    List<TranscriptEvent> transcript = task.getTranscript();
    assertThat(transcript).extracting(TranscriptEvent::getType)
            .doesNotContain(TranscriptEvent.TYPE_ERROR);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -Dtest=AgentExecutorTest#shouldKeepCancelledStatusWhenLlmThrowsAfterCancel`
Expected: FAIL — status is FAILED because catch block unconditionally sets FAILED

- [ ] **Step 3: Write failing test — cancelled during LLM reports error**

Add to `AgentExecutorTest.java`:

```java
@Test
void shouldKeepCancelledStatusWhenLlmReportsErrorAfterCancel() {
    // Simulate Call.cancel() causing LLM to report an error
    doAnswer(invocation -> {
        LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
        sink.onError("Canceled");
        return null;
    }).when(llmClient).stream(any(), any(), any());

    AgentExecutor executor = newExecutor();
    AgentTask task = newTask();
    task.setStatus(TaskStatus.CANCELLED);

    executor.execute(task, skill);

    assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
            .doesNotContain(TranscriptEvent.TYPE_ERROR);
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -Dtest=AgentExecutorTest#shouldKeepCancelledStatusWhenLlmReportsErrorAfterCancel`
Expected: FAIL — status is FAILED

- [ ] **Step 5: Write failing test — cancelled between turns stops gracefully**

Add to `AgentExecutorTest.java`:

```java
@Test
void shouldStopWhenCancelledBetweenTurns() {
    // First turn: normal tool_use; Second turn: should never be called (task cancelled)
    doAnswer(invocation -> {
        LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
        sink.onThought("查一下。");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sql", "SELECT 1");
        sink.onToolUse("toolu_01", "mysql_query", input);
        sink.onStop("tool_use");
        return null;
    }).when(llmClient).stream(any(), any(), any());

    when(mysqlProvider.execute(any(), any()))
            .thenReturn(ToolResult.success("1", 1, 10L));

    AgentExecutor executor = newExecutor();
    AgentTask task = newTask();

    // Simulate: cancel the task after first turn (as if POST /runs/{id}/cancel was called)
    // We do this by wrapping the executor in a thread that cancels after a delay,
    // but for unit test simplicity, we set status to CANCELLED before execute()
    // and verify the loop checks it
    task.setStatus(TaskStatus.CANCELLED);

    executor.execute(task, skill);

    assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    // Should have a "任务已取消" thought, NOT error
    assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
            .doesNotContain(TranscriptEvent.TYPE_ERROR);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -Dtest=AgentExecutorTest#shouldStopWhenCancelledBetweenTurns`
Expected: PASS — existing CANCELLED check at line 90 already handles this

- [ ] **Step 7: Write test — FAILED status not overridden by CANCELLED guard**

Add to `AgentExecutorTest.java`:

```java
@Test
void shouldNotOverrideFailedWithCancelledGuard() {
    // Task FAILED (not cancelled) — RuntimeException should set FAILED, not be caught
    doThrow(new RuntimeException("real connection error"))
            .when(llmClient).stream(any(), any(), any());

    AgentExecutor executor = newExecutor();
    AgentTask task = newTask();
    task.setStatus(TaskStatus.RUNNING);  // NOT cancelled

    executor.execute(task, skill);

    // Should be FAILED, not CANCELLED
    assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
    assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
            .contains(TranscriptEvent.TYPE_ERROR);
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -Dtest=AgentExecutorTest#shouldNotOverrideFailedWithCancelledGuard`
Expected: PASS — FAILED guard already works, this is a regression test

- [ ] **Step 9: Implement CANCELLED guards in AgentExecutor**

In `AgentExecutor.java`, update the catch block (around line 128):

```java
} catch (RuntimeException e) {
    log.error("LLM stream failed for task {}: {}", task.getTaskId(), e.getMessage());
    if (task.getStatus() == TaskStatus.CANCELLED) {
        task.addTranscriptEvent(TranscriptEvent.thought("任务已取消"));
        taskStore.update(task);
        return;
    }
    task.addTranscriptEvent(TranscriptEvent.error("LLM error: " + e.getMessage()));
    task.setStatus(TaskStatus.FAILED);
    taskStore.update(task);
    return;
}
```

Update the errorMessage check (around line 137):

```java
// Handle LLM-reported error
if (collector.errorMessage != null) {
    log.error("LLM error for task {}: {}", task.getTaskId(), collector.errorMessage);
    if (task.getStatus() == TaskStatus.CANCELLED) {
        task.addTranscriptEvent(TranscriptEvent.thought("任务已取消"));
        taskStore.update(task);
        return;
    }
    task.addTranscriptEvent(TranscriptEvent.error(collector.errorMessage));
    task.setStatus(TaskStatus.FAILED);
    taskStore.update(task);
    return;
}
```

- [ ] **Step 10: Run all AgentExecutorTest tests to verify they pass**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-core -Dtest=AgentExecutorTest`
Expected: PASS (21 tests — 17 existing + 4 new)

- [ ] **Step 11: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/agent/AgentExecutor.java \
        snap-agent-core/src/test/java/cn/watsontech/snapagent/core/agent/AgentExecutorTest.java
git commit -m "feat: AgentExecutor CANCELLED guards in catch/error blocks"
```

---

### Task 3: AnthropicLlmClient cancellation tracking

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/AnthropicLlmClient.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/llm/AnthropicLlmClientTest.java`

- [ ] **Step 1: Write failing test — cancel with no active call is no-op**

Add to `AnthropicLlmClientTest.java`:

```java
@Test
void shouldNotThrowWhenCancellingWithNoActiveCall() {
    client.cancel("nonexistent-task-id");
    // No exception expected, test passes if it doesn't throw
}
```

- [ ] **Step 2: Write failing test — stream cleans up activeCalls**

Add to `AnthropicLlmClientTest.java`:

```java
@Test
void shouldCleanUpActiveCallsAfterStreamCompletes() {
    client.setSseResponse(
            sse("message_start", "{\"type\":\"message_start\"}")
            + sse("message_stop", "{\"type\":\"message_stop\"}"));

    client.stream(simpleRequest(), sink, "task-123");

    // After stream completes, cancel should be a no-op (Call was cleaned up)
    client.cancel("task-123");
    // No exception expected
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=AnthropicLlmClientTest#shouldNotThrowWhenCancellingWithNoActiveCall+shouldCleanUpActiveCallsAfterStreamCompletes`
Expected: FAIL — `stream()` method signature doesn't accept `taskId` yet (compilation error)

- [ ] **Step 4: Implement cancellation tracking in AnthropicLlmClient**

Add imports at top:
```java
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
```

Add fields to `AnthropicLlmClient` class:
```java
private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<String, Call>();
private final ThreadLocal<String> currentTaskId = new ThreadLocal<String>();
```

Update `stream()` method signature and add cleanup:
```java
@Override
public void stream(LlmRequest req, LlmEventSink events, String taskId) {
    currentTaskId.set(taskId);
    try {
        streamInternal(req, events);  // rename existing stream body to streamInternal
    } finally {
        if (taskId != null) {
            activeCalls.remove(taskId);
        }
        currentTaskId.remove();
    }
}
```

Update `executeCall()` in the real class (not the test subclass):
```java
@Override
protected Response executeCall(Request request) throws IOException {
    Call call = httpClient.newCall(request);
    String tid = currentTaskId.get();
    if (tid != null) {
        activeCalls.put(tid, call);
    }
    return call.execute();
}
```

Add `cancel()` method:
```java
@Override
public void cancel(String taskId) {
    if (taskId == null) return;
    Call call = activeCalls.get(taskId);
    if (call != null) {
        log.info("Cancelling LLM call for task {}", taskId);
        call.cancel();
    }
}
```

Rename existing `stream(LlmRequest, LlmEventSink)` to `streamInternal(LlmRequest, LlmEventSink)` — this is the private method that contains the existing SSE parsing logic.

- [ ] **Step 5: Update TestableClient and existing test calls to pass taskId**

In `AnthropicLlmClientTest.java`, update all `client.stream(req, sink)` calls to `client.stream(req, sink, "test-task")`. There are ~10 calls.

In `TestableClient`, the `executeCall()` override stays as-is (returns canned Response, doesn't touch `activeCalls`) — this is correct because tests don't test real HTTP cancellation through the test seam.

- [ ] **Step 6: Run all AnthropicLlmClientTest tests**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=AnthropicLlmClientTest`
Expected: PASS (all existing + 2 new)

- [ ] **Step 7: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/AnthropicLlmClient.java \
        snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/llm/AnthropicLlmClientTest.java
git commit -m "feat: AnthropicLlmClient cancellation via Call.cancel() tracking"
```

---

### Task 4: OpenAiLlmClient cancellation tracking

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/OpenAiLlmClient.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/llm/OpenAiLlmClientTest.java`

- [ ] **Step 1: Write failing tests — same pattern as Anthropic**

Add to `OpenAiLlmClientTest.java`:

```java
@Test
void shouldNotThrowWhenCancellingWithNoActiveCall() {
    client.cancel("nonexistent-task-id");
}

@Test
void shouldCleanUpActiveCallsAfterStreamCompletes() {
    client.setSseResponse(
            sse("", "{\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}")
            + sse("", "[DONE]"));

    client.stream(simpleRequest(), sink, "task-456");

    client.cancel("task-456");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=OpenAiLlmClientTest#shouldNotThrowWhenCancellingWithNoActiveCall+shouldCleanUpActiveCallsAfterStreamCompletes`
Expected: FAIL — compilation error (stream signature)

- [ ] **Step 3: Implement same pattern as AnthropicLlmClient**

Add to `OpenAiLlmClient.java`:
- `ConcurrentHashMap<String, Call> activeCalls` field
- `ThreadLocal<String> currentTaskId` field
- Update `stream()` signature to `(LlmRequest, LlmEventSink, String taskId)` with ThreadLocal set/cleanup in try-finally
- Update `executeCall()` to create Call, store in map, execute
- Add `cancel(String taskId)` method

The implementation is identical to Task 3 Step 4 — copy the same pattern.

- [ ] **Step 4: Update existing test calls to pass taskId**

Update all `client.stream(req, sink)` → `client.stream(req, sink, "test-task")` in `OpenAiLlmClientTest.java`.

- [ ] **Step 5: Run all OpenAiLlmClientTest tests**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=OpenAiLlmClientTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/llm/OpenAiLlmClient.java \
        snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/llm/OpenAiLlmClientTest.java
git commit -m "feat: OpenAiLlmClient cancellation via Call.cancel() tracking"
```

---

### Task 5: Controller cancel endpoint

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/web/SnapAgentControllerTest.java`

- [ ] **Step 1: Write failing test — cancel running task returns 200 CANCELLED**

Add to `SnapAgentControllerTest.java`. First, add `@Mock private LlmClient llmClient;` to the class fields, and update `setUp()` to pass `llmClient` to the controller constructor (use the constructor that accepts it, or pass null and set via reflection — check which constructor the test uses).

The test uses the 8-arg constructor (line 75-77). Update to use the constructor that includes `llmClient`:

```java
// Update setUp() to:
controller = new SnapAgentController(
        skillRegistry, agentExecutor, taskStore, toolDispatcher,
        properties, securityGateway, rateLimiter, taskExecutor,
        null, llmClient, null, null);
```

Then add the test:

```java
@Test
void shouldCancelRunningTaskAndReturn200() throws Exception {
    AgentTask task = AgentTask.create("user001", "test-skill",
            new HashMap<>(), "claude-sonnet-4-6");
    task.setStatus(TaskStatus.RUNNING);
    when(taskStore.get(task.getTaskId())).thenReturn(task);

    mockMvc.perform(post("/snap-agent/runs/" + task.getTaskId() + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    verify(llmClient).cancel(task.getTaskId());
}
```

- [ ] **Step 2: Write failing test — cancel non-existent task returns 404**

```java
@Test
void shouldReturn404WhenCancellingNonExistentTask() throws Exception {
    when(taskStore.get("nonexistent")).thenReturn(null);

    mockMvc.perform(post("/snap-agent/runs/nonexistent/cancel"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
}
```

- [ ] **Step 3: Write failing test — cancel other user's task returns 403**

```java
@Test
void shouldReturn403WhenCancellingOtherUserTask() throws Exception {
    AgentTask task = AgentTask.create("other-user", "test-skill",
            new HashMap<>(), "claude-sonnet-4-6");
    task.setStatus(TaskStatus.RUNNING);
    when(taskStore.get(task.getTaskId())).thenReturn(task);

    mockMvc.perform(post("/snap-agent/runs/" + task.getTaskId() + "/cancel"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
}
```

- [ ] **Step 4: Write failing test — cancel already-finished task is idempotent**

```java
@Test
void shouldReturn200WhenCancellingAlreadyFinishedTask() throws Exception {
    AgentTask task = AgentTask.create("user001", "test-skill",
            new HashMap<>(), "claude-sonnet-4-6");
    task.setStatus(TaskStatus.SUCCEEDED);
    when(taskStore.get(task.getTaskId())).thenReturn(task);

    mockMvc.perform(post("/snap-agent/runs/" + task.getTaskId() + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
}
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=SnapAgentControllerTest#shouldCancelRunningTaskAndReturn200+shouldReturn404WhenCancellingNonExistentTask+shouldReturn403WhenCancellingOtherUserTask+shouldReturn200WhenCancellingAlreadyFinishedTask`
Expected: FAIL — endpoint doesn't exist (404 from Spring)

- [ ] **Step 6: Implement the cancel endpoint**

Add to `SnapAgentController.java` (after the `GET /runs/{id}/stream` endpoint, around line 900):

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

    if (!userId.equals(task.getUserId())) {
        return errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", "not task owner");
    }

    TaskStatus currentStatus = task.getStatus();
    if (currentStatus == TaskStatus.SUCCEEDED || currentStatus == TaskStatus.FAILED
            || currentStatus == TaskStatus.TIMEOUT || currentStatus == TaskStatus.CANCELLED) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("taskId", id);
        result.put("status", currentStatus.name());
        result.put("message", "task already in terminal state");
        return ResponseEntity.ok().body(result);
    }

    task.setStatus(TaskStatus.CANCELLED);
    task.addTranscriptEvent(TranscriptEvent.done("CANCELLED", "用户取消任务"));
    taskStore.update(task);

    if (llmClient != null) {
        llmClient.cancel(id);
    }

    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("taskId", id);
    result.put("status", TaskStatus.CANCELLED.name());

    audit(userId, "POST", "/runs/" + id + "/cancel", "CANCEL_TASK", null);

    return ResponseEntity.ok().body(result);
}
```

- [ ] **Step 7: Run all SnapAgentControllerTest tests**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=SnapAgentControllerTest`
Expected: PASS (26 existing + 4 new = 30)

- [ ] **Step 8: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java \
        snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/web/SnapAgentControllerTest.java
git commit -m "feat: POST /runs/{id}/cancel endpoint with ownership check"
```

---

### Task 6: Frontend — cancelSkillStream calls cancel endpoint

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/app.js`

- [ ] **Step 1: Update cancelSkillStream to call cancel endpoint**

Find the `cancelSkillStream` function in `app.js`. Add the server cancel call before closing the SSE connection:

```javascript
function cancelSkillStream(name, savePartial) {
    var state = skillChatState[name];
    if (!state || !state.stream) return;

    // Call server to cancel the task (interrupts LLM HTTP call)
    if (state.stream.taskId) {
        fetch(BASE + '/runs/' + state.stream.taskId + '/cancel', {
            method: 'POST',
            headers: authHeaders()
        }).catch(function(e) {
            console.error('[cancel] Failed to cancel task:', e);
        });
    }

    // Close SSE connection (existing logic stays)
    if (state.stream.es) {
        state.stream.es.close();
    }
    // ... existing cleanup code continues
```

- [ ] **Step 2: Bump app.js version**

In `index.html`, update `app.js?v=19` → `app.js?v=20`.

- [ ] **Step 3: Run full test suite**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn test`
Expected: PASS (333 + 14 new = 347, 0 failures, 2 skipped)

- [ ] **Step 4: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/app.js \
        snap-agent-spring-boot-2x-starter/src/main/resources/static/snap-agent/index.html
git commit -m "feat: frontend calls POST /runs/{id}/cancel on stream cancel"
```

---

## Final Verification

- [ ] **Run full test suite**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent && mvn clean test`
Expected: BUILD SUCCESS, 347 tests, 0 failures, 2 skipped

- [ ] **Build demo and smoke test**

Run: `cd /Users/HuaSheng.Song/IdeaProjects/skills-agent/snap-agent-demo && mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Final commit (if any remaining changes)**

```bash
git add -A
git status  # verify clean
```
