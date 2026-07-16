package cn.watsontech.snapagent.core.agent;

import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.skill.InputSpec;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentExecutor}.
 */
class AgentExecutorTest {

    private LlmClient llmClient;
    private ToolDispatcher dispatcher;
    private ToolProvider mysqlProvider;
    private TaskStore taskStore;
    private SkillMeta skill;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        mysqlProvider = mock(ToolProvider.class);
        when(mysqlProvider.name()).thenReturn("mysql_query");
        when(mysqlProvider.schema()).thenReturn(
                "{\"name\":\"mysql_query\",\"description\":\"Execute a read-only SQL query.\","
                + "\"input_schema\":{\"type\":\"object\","
                + "\"properties\":{\"sql\":{\"type\":\"string\"}},"
                + "\"required\":[\"sql\"]}}");
        dispatcher = new ToolDispatcher(Arrays.asList(mysqlProvider), 50000);
        taskStore = new TaskStore();

        skill = new SkillMeta("test-skill", "test description",
                Arrays.asList("mysql_query"),
                Collections.<InputSpec>emptyList(),
                "## Phase 1\nWHERE sku_code='{skuCode}'",
                SkillAvailability.AVAILABLE, null);
    }

    private AgentExecutor newExecutor() {
        return new AgentExecutor(llmClient, dispatcher, taskStore, 20, 8192);
    }

    private AgentTask newTask() {
        Map<String, String> inputs = new HashMap<>();
        inputs.put("skuCode", "A001");
        return AgentTask.create("user-1", "test-skill", inputs, "claude-sonnet-4-6");
    }

    @Test
    void shouldSucceedWhenSingleTurnEndTurn() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("诊断完成，一切正常。");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.getReport()).contains("诊断完成");
        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).isNotEmpty();
        assertThat(transcript.get(transcript.size() - 1).getType()).isEqualTo(TranscriptEvent.TYPE_DONE);
    }

    @Test
    void shouldSucceedWhenMultiTurnToolUseThenEndTurn() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("我先查一下数据。");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("查询结果正常，诊断完成。");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenReturn(ToolResult.success("1", 1, 10L));

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).extracting(TranscriptEvent::getType)
                .contains(TranscriptEvent.TYPE_THOUGHT,
                        TranscriptEvent.TYPE_TOOL_CALL,
                        TranscriptEvent.TYPE_TOOL_RESULT,
                        TranscriptEvent.TYPE_DONE);
    }

    @Test
    void shouldHandleMultipleToolUsesInOneTurn() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("查两个东西。");
            Map<String, Object> input1 = new LinkedHashMap<>();
            input1.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input1);
            Map<String, Object> input2 = new LinkedHashMap<>();
            input2.put("sql", "SELECT 2");
            sink.onToolUse("toolu_02", "mysql_query", input2);
            sink.onStop("tool_use");
            return null;
        }).doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("两个都查完了。");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenReturn(ToolResult.success("1", 1, 5L));

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        // Two tool_call events and two tool_result events
        List<TranscriptEvent> transcript = task.getTranscript();
        long toolCalls = transcript.stream()
                .filter(e -> TranscriptEvent.TYPE_TOOL_CALL.equals(e.getType()))
                .count();
        assertThat(toolCalls).isEqualTo(2);
    }

    @Test
    void shouldPassAssistantMessageWithToolUseBlocksToLlm() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("查一下。");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("done");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(captor.capture(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenReturn(ToolResult.success("1", 1, 10L));

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        // Second call (turn 1) should have the assistant message with tool_use blocks
        List<Message> turn1Messages = captor.getAllValues().get(1).getMessages();
        // Find the assistant message with tool_use
        boolean hasAssistantWithToolUse = turn1Messages.stream()
                .anyMatch(m -> "assistant".equals(m.getRole()) && m.hasToolUses());
        assertThat(hasAssistantWithToolUse).isTrue();

        // Verify the tool_use block carries the id and name
        Message assistantMsg = turn1Messages.stream()
                .filter(m -> "assistant".equals(m.getRole()) && m.hasToolUses())
                .findFirst().orElseThrow(() -> new AssertionError("no assistant with tool_use"));
        assertThat(assistantMsg.getToolUses()).hasSize(1);
        assertThat(assistantMsg.getToolUses().get(0).getId()).isEqualTo("toolu_01");
        assertThat(assistantMsg.getToolUses().get(0).getName()).isEqualTo("mysql_query");
    }

    @Test
    void shouldFailWhenLlmThrowsException() {
        doThrow(new RuntimeException("LLM connection timeout"))
                .when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).extracting(TranscriptEvent::getType)
                .contains(TranscriptEvent.TYPE_ERROR);
    }

    @Test
    void shouldFailWhenLlmReportsError() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onError("rate limit exceeded");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
                .contains(TranscriptEvent.TYPE_ERROR);
    }

    @Test
    void shouldNotBreakLoopWhenToolThrowsException() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("调用工具。");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("工具出错了，但诊断可以继续。");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenThrow(new RuntimeException("DB connection failed"));

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).extracting(TranscriptEvent::getType)
                .contains(TranscriptEvent.TYPE_TOOL_RESULT);
    }

    @Test
    void shouldStopWhenMaxTurnsExceeded() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("继续查。");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenReturn(ToolResult.success("1", 1, 5L));

        AgentExecutor executor = new AgentExecutor(llmClient, dispatcher, taskStore, 3, 8192);
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.TIMEOUT);
        assertThat(task.getReport()).contains("max-turns");
    }

    @Test
    void shouldStopImmediatelyWhenTaskCancelledBeforeExecution() {
        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        task.setStatus(TaskStatus.CANCELLED);

        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        // No LLM call should have been made
        org.mockito.Mockito.verifyNoInteractions(llmClient);
    }

    @Test
    void shouldKeepCancelledStatusWhenLlmThrowsAfterCancel() {
        // Simulate Call.cancel() causing a RuntimeException during stream:
        // the task is cancelled mid-stream, then the stream throws.
        // We set CANCELLED inside the mock (not before execute) so the
        // early-return guard at line 90 is bypassed and the catch block
        // at line 128 is the code path under test.
        AgentTask task = newTask();
        doAnswer(invocation -> {
            task.setStatus(TaskStatus.CANCELLED);
            throw new RuntimeException("Canceled");
        }).when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        executor.execute(task, skill);

        // Status should remain CANCELLED, not be overridden to FAILED
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        // Should NOT have an error transcript event
        List<TranscriptEvent> transcript = task.getTranscript();
        assertThat(transcript).extracting(TranscriptEvent::getType)
                .doesNotContain(TranscriptEvent.TYPE_ERROR);
    }

    @Test
    void shouldKeepCancelledStatusWhenLlmReportsErrorAfterCancel() {
        // Simulate Call.cancel() causing LLM to report an error mid-stream.
        // We set CANCELLED inside the mock (not before execute) so the
        // early-return guard at line 90 is bypassed and the error check
        // at line 137 is the code path under test.
        AgentTask task = newTask();
        doAnswer(invocation -> {
            task.setStatus(TaskStatus.CANCELLED);
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onError("Canceled");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
                .doesNotContain(TranscriptEvent.TYPE_ERROR);
    }

    @Test
    void shouldStopWhenCancelledBetweenTurns() {
        // First turn: normal tool_use; Second turn: should never be called (task cancelled).
        // Cancellation happens during tool dispatch (simulating POST /runs/{id}/cancel
        // called while the first turn's tool is executing). The between-turn check
        // at line 116 catches it on the next iteration.
        AgentTask task = newTask();
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("查一下。");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        // During tool dispatch, simulate cancel (as if POST /runs/{id}/cancel was called)
        when(mysqlProvider.execute(any(), any())).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.CANCELLED);
            return ToolResult.success("1", 1, 10L);
        });

        AgentExecutor executor = newExecutor();
        executor.execute(task, skill);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        // Should have a "任务已取消" thought, NOT error
        assertThat(task.getTranscript()).extracting(TranscriptEvent::getType)
                .doesNotContain(TranscriptEvent.TYPE_ERROR);
    }

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

    @Test
    void shouldBuildSystemPromptWithReadOnlyPrefix() {
        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();

        String prompt = executor.buildSystemPrompt(skill, task);

        assertThat(prompt).startsWith("你是只读诊断 agent");
        assertThat(prompt).contains("严禁");
        assertThat(prompt).contains("只读");
        assertThat(prompt).contains("test-skill");
        assertThat(prompt).contains("user-1");
        // Input values must NOT be in the system prompt (they go in the user message)
        assertThat(prompt).doesNotContain("A001");
        // Tool names must NOT be in the system prompt (they go in the tools array)
        assertThat(prompt).doesNotContain("mysql_query");
        // Input reference instruction must be present
        assertThat(prompt).contains("user_inputs");
    }

    @Test
    void shouldKeepPlaceholdersAsReferencesInSystemPrompt() {
        AgentExecutor executor = newExecutor();
        Map<String, String> inputs = new HashMap<>();
        inputs.put("skuCode", "A001");
        AgentTask task = AgentTask.create("user-1", "test-skill", inputs, "model-1");

        String prompt = executor.buildSystemPrompt(skill, task);

        // The placeholder is kept as a reference; the value is NOT in the system prompt
        assertThat(prompt).contains("{skuCode}");
        assertThat(prompt).doesNotContain("A001");
    }

    @Test
    void shouldKeepUnprovidedOptionalPlaceholderAsReference() {
        SkillMeta skillWithOptional = new SkillMeta("s", "d",
                Arrays.asList("mysql_query"),
                Collections.<InputSpec>emptyList(),
                "WHERE wh='{warehouseCode}' AND sku='{skuCode}'",
                SkillAvailability.AVAILABLE, null);

        AgentExecutor executor = newExecutor();
        Map<String, String> inputs = new HashMap<>();
        inputs.put("skuCode", "A001");
        AgentTask task = AgentTask.create("u", "s", inputs, "m");

        String prompt = executor.buildSystemPrompt(skillWithOptional, task);

        // Both placeholders kept as references (values go in user message)
        assertThat(prompt).contains("{warehouseCode}");
        assertThat(prompt).contains("{skuCode}");
    }

    @Test
    void shouldPlaceInputValuesInUserMessage() {
        AgentExecutor executor = newExecutor();
        Map<String, String> inputs = new HashMap<>();
        inputs.put("skuCode", "A001");
        inputs.put("env", "sit");
        AgentTask task = AgentTask.create("u", "s", inputs, "m");

        String userMsg = executor.buildInputMessage(task.getInputs());

        assertThat(userMsg).contains("<user_inputs>");
        assertThat(userMsg).contains("</user_inputs>");
        assertThat(userMsg).contains("skuCode=A001");
        assertThat(userMsg).contains("env=sit");
        assertThat(userMsg).contains("请开始诊断");
    }

    @Test
    void shouldBuildToolDefsFromProviderSchema() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("ok");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(captor.capture(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        List<ToolDef> tools = captor.getValue().getTools();
        assertThat(tools).hasSize(1);
        ToolDef tool = tools.get(0);
        assertThat(tool.getName()).isEqualTo("mysql_query");
        // Description should come from the schema, not "tool: mysql_query"
        assertThat(tool.getDescription()).isEqualTo("Execute a read-only SQL query.");
        // input_schema should be the real schema, not "{}"
        assertThat(tool.getInputSchema()).contains("\"sql\"");
        assertThat(tool.getInputSchema()).contains("\"required\"");
    }

    @Test
    void shouldSaveTaskToStoreWhenExecutionCompletes() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("done");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(taskStore.get(task.getTaskId())).isSameAs(task);
    }

    @Test
    void shouldRecordAuditWhenToolDispatched() {
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("call tool");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("toolu_01", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("done");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        when(mysqlProvider.execute(any(), any()))
                .thenReturn(ToolResult.success("1", 1, 10L));

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(task.getAuditRecords()).hasSize(1);
        AuditRecord audit = task.getAuditRecords().get(0);
        assertThat(audit.getToolName()).isEqualTo("mysql_query");
        assertThat(audit.getUserId()).isEqualTo("user-1");
        assertThat(audit.getTaskId()).isEqualTo(task.getTaskId());
    }

    @Test
    void shouldPassCorrectModelToLlmRequest() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = (LlmEventSink) invocation.getArgument(1);
            sink.onThought("ok");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(captor.capture(), any(), any());

        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();
        executor.execute(task, skill);

        assertThat(captor.getValue().getModel()).isEqualTo("claude-sonnet-4-6");
    }

    // ---- SystemPromptExtender integration (v0.3) ----

    @Test
    void shouldAppendExtenderContextToSystemPrompt() {
        SystemPromptExtender extender = (s, t) ->
                "## 项目结构\n模块: snap-agent-core";
        AgentExecutor executor = new AgentExecutor(llmClient, dispatcher, taskStore, 20, 8192, extender);
        AgentTask task = newTask();

        String prompt = executor.buildSystemPrompt(skill, task);

        assertThat(prompt).contains("项目结构");
        assertThat(prompt).contains("snap-agent-core");
        // The extender content should be near the end, after userId
        assertThat(prompt.indexOf("user-1")).isLessThan(prompt.indexOf("snap-agent-core"));
    }

    @Test
    void shouldNotAppendAnythingWhenExtenderReturnsEmpty() {
        SystemPromptExtender extender = (s, t) -> "";
        AgentExecutor executor = new AgentExecutor(llmClient, dispatcher, taskStore, 20, 8192, extender);
        AgentTask task = newTask();

        String prompt = executor.buildSystemPrompt(skill, task);

        // Prompt should end with the userId line (no extra context)
        assertThat(prompt.trim()).endsWith("user-1");
    }

    @Test
    void shouldNotAppendAnythingWhenExtenderReturnsNull() {
        SystemPromptExtender extender = (s, t) -> null;
        AgentExecutor executor = new AgentExecutor(llmClient, dispatcher, taskStore, 20, 8192, extender);
        AgentTask task = newTask();

        String prompt = executor.buildSystemPrompt(skill, task);

        assertThat(prompt.trim()).endsWith("user-1");
    }

    @Test
    void shouldWorkWithoutExtenderForBackwardCompat() {
        // Old 5-arg constructor — no extender, no context injection
        AgentExecutor executor = newExecutor();
        AgentTask task = newTask();

        String prompt = executor.buildSystemPrompt(skill, task);

        assertThat(prompt).startsWith("你是只读诊断 agent");
        assertThat(prompt).contains("user-1");
        // No project structure context should be present
        assertThat(prompt).doesNotContain("项目结构");
    }
}
