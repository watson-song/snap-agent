package cn.watsontech.snapagent.core.issue;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link IssueClosureNode} — the optional terminal graph node
 * that calls {@link IssueClosureHandler#close(String)} after agent end_turn.
 *
 * <p>Covers UC-17 from the 08-patrol-alert TDD spec:
 * "IssueClosureNode 作为可选终态节点".</p>
 */
@DisplayName("IssueClosureNode — optional terminal graph node")
class IssueClosureNodeTest {

    private static class StubExecutionContext implements ExecutionContext {
        private final String taskId;
        StubExecutionContext(String taskId) { this.taskId = taskId; }
        @Override public cn.watsontech.snapagent.core.llm.LlmClient getLlmClient() { return null; }
        @Override public cn.watsontech.snapagent.core.tool.ToolCallbackRegistry getTools() { return null; }
        @Override public String getTaskId() { return taskId; }
        @Override public String getUserId() { return "user-1"; }
        @Override public String getSkillName() { return "verify-fix"; }
        @Override public void emit(cn.watsontech.snapagent.core.agent.TranscriptEvent event) {}
        @Override public boolean isCancelled() { return false; }
    }

    @Test
    @DisplayName("UC-17: getName returns 'issue_closure'")
    void shouldReturnNodeName() {
        IssueClosureNode node = new IssueClosureNode(t -> {});
        assertThat(node.getName()).isEqualTo("issue_closure");
    }

    @Test
    @DisplayName("UC-17: execute calls handler.close(taskId)")
    void shouldCallHandlerClose() throws InterruptException {
        AtomicReference<String> capturedTaskId = new AtomicReference<>();
        IssueClosureHandler handler = capturedTaskId::set;
        IssueClosureNode node = new IssueClosureNode(handler);

        GraphState state = GraphState.empty("thread-1");
        ExecutionContext ctx = new StubExecutionContext("task-99");

        node.execute(state, ctx);

        assertThat(capturedTaskId.get()).isEqualTo("task-99");
    }

    @Test
    @DisplayName("UC-17: execute returns original state unchanged")
    void shouldReturnStateUnchanged() throws InterruptException {
        IssueClosureNode node = new IssueClosureNode(t -> {});
        GraphState state = GraphState.empty("thread-1").with("stop_reason", "end_turn");

        GraphState result = node.execute(state, new StubExecutionContext("task-1"));

        assertThat(result).isSameAs(state);
    }

    @Test
    @DisplayName("UC-17: handler exception is caught, does not propagate")
    void shouldCatchHandlerException() {
        IssueClosureHandler throwingHandler = taskId -> {
            throw new RuntimeException("simulated closure failure");
        };
        IssueClosureNode node = new IssueClosureNode(throwingHandler);

        assertThatNoException()
            .isThrownBy(() -> node.execute(
                GraphState.empty("thread-1"),
                new StubExecutionContext("task-1")));
    }

    @Test
    @DisplayName("UC-17: null taskId → handler not called, no exception")
    void shouldHandleNullTaskId() {
        AtomicReference<String> captured = new AtomicReference<>("not-called");
        IssueClosureNode node = new IssueClosureNode(captured::set);

        assertThatNoException()
            .isThrownBy(() -> node.execute(
                GraphState.empty("thread-1"),
                new StubExecutionContext(null)));

        assertThat(captured.get()).isEqualTo("not-called");
    }

    @Test
    @DisplayName("UC-17: null handler → no-op, no exception")
    void shouldHandleNullHandler() {
        IssueClosureNode node = new IssueClosureNode(null);

        assertThatNoException()
            .isThrownBy(() -> node.execute(
                GraphState.empty("thread-1"),
                new StubExecutionContext("task-1")));
    }
}
