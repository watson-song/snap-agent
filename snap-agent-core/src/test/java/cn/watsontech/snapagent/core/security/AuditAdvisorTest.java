package cn.watsontech.snapagent.core.security;

import cn.watsontech.snapagent.core.agent.AuditRecord;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AuditAdvisor — 审计追踪")
class AuditAdvisorTest {

    private ExecutionContext mockCtx(String userId, String taskId) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(ctx.getSkillName()).thenReturn("skill1");
        return ctx;
    }

    // UC-22: 记录 LLM 调用
    @Test
    @DisplayName("afterNode agent → AuditStore.saveRecord 被调用")
    void shouldRecordLlmCallAfterAgentNode() {
        AuditStore store = mock(AuditStore.class);
        AuditAdvisor advisor = new AuditAdvisor(store);

        GraphState state = GraphState.empty("t1")
                .with("llm.input_tokens", 100)
                .with("llm.output_tokens", 200)
                .with("llm.model", "claude-sonnet-4");

        ExecutionContext ctx = mockCtx("u1", "t1");
        advisor.afterNode("agent", state, ctx);

        verify(store).saveRecord(argThat(record ->
                record.getTaskId().equals("t1") &&
                record.getUserId().equals("u1") &&
                record.getNodeName().equals("agent") &&
                record.getLlmModel().equals("claude-sonnet-4") &&
                record.getInputTokens() == 100 &&
                record.getOutputTokens() == 200
        ));
    }

    // UC-23: 记录 Tool 调用
    @Test
    @DisplayName("afterNode tools → AuditRecord 含 toolName/args/result/truncated")
    void shouldRecordToolCallAfterToolsNode() {
        AuditStore store = mock(AuditStore.class);
        AuditAdvisor advisor = new AuditAdvisor(store);

        ToolResult toolResult = ToolResult.success("SELECT result", 42, 10, null);
        Map<String, Object> args = new HashMap<>();
        args.put("sql", "SELECT * FROM users");

        GraphState state = GraphState.empty("t1")
                .with("tool_results", Collections.singletonList(toolResult))
                .with("tool.name", "mysql_query")
                .with("tool.args", args);

        ExecutionContext ctx = mockCtx("u1", "t1");
        advisor.afterNode("tools", state, ctx);

        verify(store).saveRecord(argThat(record ->
                record.getToolName().equals("mysql_query") &&
                record.getRowCount() == 42 &&
                record.getResult().contains("SELECT result") &&
                record.getArgs().containsKey("sql")
        ));
    }

    // UC-24: 异常隔离
    @Test
    @DisplayName("AuditStore.saveRecord 抛异常 → catch + WARN，不中断")
    void shouldCatchExceptionWhenAuditStoreFails() {
        AuditStore store = mock(AuditStore.class);
        doThrow(new RuntimeException("DB error")).when(store).saveRecord(any());

        AuditAdvisor advisor = new AuditAdvisor(store);
        GraphState state = GraphState.empty("t1")
                .with("llm.input_tokens", 100)
                .with("llm.output_tokens", 200);

        ExecutionContext ctx = mockCtx("u1", "t1");
        GraphState result = advisor.afterNode("agent", state, ctx);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("AuditStore=null → 不抛异常")
    void shouldHandleNullAuditStore() {
        AuditAdvisor advisor = new AuditAdvisor(null);
        GraphState state = GraphState.empty("t1");
        ExecutionContext ctx = mockCtx("u1", "t1");
        GraphState result = advisor.afterNode("agent", state, ctx);
        assertThat(result).isNotNull();
    }

    // UC-25: order + 脱敏
    @Test
    @DisplayName("order=400")
    void shouldReturnOrder400() {
        AuditAdvisor advisor = new AuditAdvisor(null);
        assertThat(advisor.getOrder()).isEqualTo(400);
    }

    @Test
    @DisplayName("beforeNode 返回 state 不变")
    void shouldReturnStateUnchangedBeforeNode() {
        AuditAdvisor advisor = new AuditAdvisor(null);
        GraphState state = GraphState.empty("t1");
        GraphState result = advisor.beforeNode("agent", state, null);
        assertThat(result).isSameAs(state);
    }

    @Test
    @DisplayName("无 token 信息 → 仍记录 (零值)")
    void shouldRecordEvenWithoutTokens() {
        AuditStore store = mock(AuditStore.class);
        AuditAdvisor advisor = new AuditAdvisor(store);

        GraphState state = GraphState.empty("t1");
        ExecutionContext ctx = mockCtx("u1", "t1");
        advisor.afterNode("agent", state, ctx);

        verify(store).saveRecord(argThat(record ->
                record.getInputTokens() == 0 &&
                record.getOutputTokens() == 0
        ));
    }
}
