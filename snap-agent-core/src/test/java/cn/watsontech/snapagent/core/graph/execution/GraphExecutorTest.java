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

@DisplayName("GraphExecutor 正常执行")
class GraphExecutorTest {

    private ExecutionContext mockCtx() {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTaskId()).thenReturn("task-1");
        when(ctx.isCancelled()).thenReturn(false);
        return ctx;
    }

    @Test
    @DisplayName("单节点图执行到 END → SUCCEEDED")
    void singleNodeToSuccess() throws InterruptException {
        Node entryNode = new Node() {
            @Override public String getName() { return "entry"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s.with("stop_reason", "end_turn"); }
        };
        Node endNode = new Node() {
            @Override public String getName() { return "END"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s; }
        };
        EdgeCondition shouldContinue = s -> "end_turn".equals(s.get("stop_reason")) ? "end" : "tools";

        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");

        StateGraph g = new StateGraph();
        g.addNode("entry", entryNode).addNode("END", endNode).addNode("tools", endNode)
            .addConditionalEdges("entry", shouldContinue, routing)
            .setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        CheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, 20);
        GraphState state = GraphState.empty("t1");

        TaskResult result = executor.execute(compiled, state, mockCtx());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("ReAct 双轮循环: thought → tool_call → tool_result → thought → done")
    void reactTwoTurnLoop() throws InterruptException {
        Node agentNode = new Node() {
            int turn = 0;
            @Override public String getName() { return "agent"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) {
                if (turn++ == 0) return s.with("stop_reason", "tool_use");
                return s.with("stop_reason", "end_turn");
            }
        };
        Node toolsNode = new Node() {
            @Override public String getName() { return "tools"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) {
                return s;
            }
        };
        Node entryNode = new Node() {
            @Override public String getName() { return "entry"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s; }
        };
        Node endNode = new Node() {
            @Override public String getName() { return "END"; }
            @Override public GraphState execute(GraphState s, ExecutionContext c) { return s; }
        };
        EdgeCondition shouldContinue = s -> {
            String stop = s.get("stop_reason");
            if ("end_turn".equals(stop)) return "end";
            if ("tool_use".equals(stop)) return "tools";
            return "end";
        };

        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");

        StateGraph g = new StateGraph();
        g.addNode("entry", entryNode).addNode("agent", agentNode).addNode("tools", toolsNode).addNode("END", endNode)
            .addEdge("entry", "agent")
            .addConditionalEdges("agent", shouldContinue, routing)
            .addEdge("tools", "agent")
            .setEntryPoint("entry");
        CompiledGraph compiled = g.compile();

        CheckpointStore store = new InMemoryCheckpointStore();
        GraphExecutor executor = new GraphExecutor(store, 20);
        TaskResult result = executor.execute(compiled, GraphState.empty("t1"), mockCtx());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(store.list("task-1").size()).isGreaterThanOrEqualTo(3);
    }
}
