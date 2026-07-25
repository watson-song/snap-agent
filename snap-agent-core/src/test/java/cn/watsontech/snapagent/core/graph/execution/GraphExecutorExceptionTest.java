package cn.watsontech.snapagent.core.graph.execution;

import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.*;
import cn.watsontech.snapagent.core.graph.checkpoint.InMemoryCheckpointStore;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GraphExecutor 异常处理")
class GraphExecutorExceptionTest {

    @Test
    @DisplayName("InterruptException → PAUSED + checkpoint + SSE paused")
    void interruptToPaused() {
        Node interruptNode = new Node() {
            @Override public String getName() { return "tools"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) throws InterruptException {
                Map<String, Object> payload = new HashMap<>();
                payload.put("toolName", "ddl_tool");
                payload.put("toolInput", new HashMap<>());
                throw new InterruptException(payload);
            }
        };
        StateGraph g = new StateGraph();
        g.addNode("entry", interruptNode).setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.isCancelled()).thenReturn(false);

        CheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, 20);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), ctx);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(store.list("t1")).isNotEmpty();
        verify(ctx).emit(argThat(e -> "paused".equals(e.getType())));
    }

    @Test
    @DisplayName("RuntimeException → FAILED + checkpoint")
    void runtimeToFailed() {
        Node failNode = new Node() {
            @Override public String getName() { return "agent"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) {
                throw new RuntimeException("LLM timeout");
            }
        };
        StateGraph g = new StateGraph();
        g.addNode("entry", failNode).setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.isCancelled()).thenReturn(false);

        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 20);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), ctx);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getReport()).contains("LLM timeout");
    }

    @Test
    @DisplayName("cancel 信号 → CANCELLED")
    void cancelSignal() {
        Node noopNode = new Node() {
            @Override public String getName() { return "entry"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s; }
        };
        StateGraph g = new StateGraph();
        g.addNode("entry", noopNode).addNode("END", noopNode)
            .addEdge("entry", "END").setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.isCancelled()).thenReturn(true);

        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 20);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), ctx);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("maxTurns → TIMEOUT")
    void maxTurnsTimeout() {
        Node loopNode = new Node() {
            @Override public String getName() { return "agent"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) {
                return s.with("stop_reason", "tool_use");
            }
        };
        Node toolsNode = new Node() {
            @Override public String getName() { return "tools"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s; }
        };
        EdgeCondition cond = s -> "tool_use".equals(s.get("stop_reason")) ? "tools" : "end";

        Map<String, String> routing = new HashMap<>();
        routing.put("tools", "tools");
        routing.put("end", "END");

        StateGraph g = new StateGraph();
        g.addNode("agent", loopNode).addNode("tools", toolsNode).addNode("END", loopNode)
            .addConditionalEdges("agent", cond, routing)
            .addEdge("tools", "agent")
            .setEntryPoint("agent");
        CompiledGraph compiled = g.compile();

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.isCancelled()).thenReturn(false);

        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 3);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), ctx);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.TIMEOUT);
        assertThat(result.getReport()).contains("max-turns");
    }

    @Test
    @DisplayName("checkpoint 保存失败不阻塞主流程")
    void checkpointFailureDegrades() {
        Node noopNode = new Node() {
            @Override public String getName() { return "entry"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s.with("stop_reason", "end_turn"); }
        };
        CheckpointStore failingStore = mock(CheckpointStore.class);
        doThrow(new RuntimeException("disk full")).when(failingStore).save(any(), any());

        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");

        StateGraph g = new StateGraph();
        g.addNode("entry", noopNode).addNode("END", noopNode)
            .addConditionalEdges("entry", s -> "end", routing)
            .setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.isCancelled()).thenReturn(false);

        GraphExecutor executor = new GraphExecutor(failingStore, 20);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), ctx);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }
}
