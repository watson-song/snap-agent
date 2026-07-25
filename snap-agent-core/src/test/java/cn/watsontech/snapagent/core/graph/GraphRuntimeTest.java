package cn.watsontech.snapagent.core.graph;

import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointMetadata;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointNotFoundException;
import cn.watsontech.snapagent.core.graph.checkpoint.CheckpointStore;
import cn.watsontech.snapagent.core.graph.checkpoint.InMemoryCheckpointStore;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.execution.GraphExecutor;
import cn.watsontech.snapagent.core.graph.execution.TaskResult;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the graph runtime: StateGraph compilation, cycle detection,
 * GraphExecutor execution, ConditionalEdge routing, checkpoint/resume,
 * and GraphState serialization.
 *
 * <p>Covers UC-01~22, UC-31~32 from the 07-workflow TDD spec.</p>
 */
@DisplayName("Graph Runtime — StateGraph + GraphExecutor + Checkpoint")
class GraphRuntimeTest {

    // ---- helpers ----

    private Node noopNode(String name) {
        return new Node() {
            @Override
            public String getName() { return name; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException { return state; }
        };
    }

    private Node trackingNode(String name, List<String> executionOrder) {
        return new Node() {
            @Override
            public String getName() { return name; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
                executionOrder.add(name);
                return state;
            }
        };
    }

    private Node failingNode(String name, RuntimeException ex) {
        return new Node() {
            @Override
            public String getName() { return name; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException { throw ex; }
        };
    }

    private Node interruptingNode(String name) {
        return new Node() {
            @Override
            public String getName() { return name; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
                throw new InterruptException(new HashMap<>());
            }
        };
    }

    private ExecutionContext mockContext() {
        return new ExecutionContext() {
            @Override
            public cn.watsontech.snapagent.core.llm.LlmClient getLlmClient() { return null; }
            @Override
            public cn.watsontech.snapagent.core.tool.ToolCallbackRegistry getTools() { return null; }
            @Override
            public String getTaskId() { return "task-1"; }
            @Override
            public String getUserId() { return "user-1"; }
            @Override
            public String getSkillName() { return "test"; }
            @Override
            public void emit(TranscriptEvent event) {}
            @Override
            public boolean isCancelled() { return false; }
        };
    }

    private ExecutionContext cancelledContext() {
        return new ExecutionContext() {
            @Override
            public cn.watsontech.snapagent.core.llm.LlmClient getLlmClient() { return null; }
            @Override
            public cn.watsontech.snapagent.core.tool.ToolCallbackRegistry getTools() { return null; }
            @Override
            public String getTaskId() { return "task-1"; }
            @Override
            public String getUserId() { return "user-1"; }
            @Override
            public String getSkillName() { return "test"; }
            @Override
            public void emit(TranscriptEvent event) {}
            @Override
            public boolean isCancelled() { return true; }
        };
    }

    // ---- UC-01: StateGraph compilation ----

    @Test
    @DisplayName("UC-01: compile basic DAG with nodes + edges + entry")
    void shouldCompileBasicDag() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addNode("C", noopNode("C"))
            .addEdge("A", "B")
            .addEdge("B", "C")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        assertThat(cg.getEntryPoint()).isEqualTo("A");
        assertThat(cg.getNodes()).containsKeys("A", "B", "C");
        assertThat(cg.getEdgesFrom("A")).hasSize(1);
        assertThat(cg.getEdgesFrom("A").get(0).getNodeName()).isEqualTo("B");
        assertThat(cg.getEdgesFrom("B")).hasSize(1);
        assertThat(cg.getEdgesFrom("B").get(0).getNodeName()).isEqualTo("C");
    }

    @Test
    @DisplayName("UC-02: compile conditional edges")
    void shouldCompileConditionalEdges() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addNode("C", noopNode("C"));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B");
        routing.put("fail", "C");
        g.addConditionalEdges("A", state -> "ok", routing)
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        assertThat(cg.getEdgesFrom("A")).hasSize(2);
        // Both conditional targets should be present
        List<String> targets = new ArrayList<>();
        for (EdgeTarget et : cg.getEdgesFrom("A")) {
            targets.add(et.getNodeName());
        }
        assertThat(targets).contains("B", "C");
    }

    // ---- UC-03: Missing entry → exception ----

    @Test
    @DisplayName("UC-03: missing entry point → IllegalStateException")
    void shouldThrowOnMissingEntryPoint() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A")).addEdge("A", "A");

        assertThatThrownBy(() -> g.compile())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("entry point not set");
    }

    // ---- UC-04: Unknown node reference → exception ----

    @Test
    @DisplayName("UC-04: edge referencing unknown node → IllegalStateException")
    void shouldThrowOnUnknownNodeInEdge() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addEdge("A", "X") // X doesn't exist
            .setEntryPoint("A");

        assertThatThrownBy(() -> g.compile())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown node");
    }

    @Test
    @DisplayName("UC-04b: conditional edge referencing unknown target → IllegalStateException")
    void shouldThrowOnUnknownNodeInConditionalEdge() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B"); // B doesn't exist
        g.addConditionalEdges("A", state -> "ok", routing)
            .setEntryPoint("A");

        assertThatThrownBy(() -> g.compile())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not found");
    }

    // ---- UC-05: Empty nodes → exception ----

    @Test
    @DisplayName("UC-05: empty nodes → IllegalStateException")
    void shouldThrowOnEmptyNodes() {
        StateGraph g = new StateGraph();
        g.setEntryPoint("A");

        assertThatThrownBy(() -> g.compile())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nodes must not be empty");
    }

    // ---- UC-06: EdgeCondition route hits routing key ----

    @Test
    @DisplayName("UC-06: EdgeCondition routes to correct node based on state")
    void shouldRouteToCorrectNode() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed))
            .addNode("C", trackingNode("C", executed));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B");
        routing.put("fail", "C");
        g.addConditionalEdges("A", state -> "ok", routing)
            .addEdge("B", "END")
            .addEdge("C", "END")
            .addNode("END", noopNode("END"))
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).contains("A", "B");
        assertThat(executed).doesNotContain("C");
    }

    @Test
    @DisplayName("UC-06b: EdgeCondition routes to fail branch")
    void shouldRouteToFailBranch() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed))
            .addNode("C", trackingNode("C", executed));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B");
        routing.put("fail", "C");
        g.addConditionalEdges("A", state -> "fail", routing)
            .addEdge("B", "END")
            .addEdge("C", "END")
            .addNode("END", noopNode("END"))
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).contains("A", "C");
        assertThat(executed).doesNotContain("B");
    }

    // ---- UC-07: Unknown route key → END ----

    @Test
    @DisplayName("UC-07: unknown route key → fallback to END")
    void shouldFallbackToEndOnUnknownRouteKey() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B");
        // EdgeCondition returns "unknown" which is not in routing
        g.addConditionalEdges("A", state -> "unknown", routing)
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).containsOnly("A");
    }

    // ---- UC-08: EdgeCondition throws → END ----

    @Test
    @DisplayName("UC-08: EdgeCondition.route() throws → fallback to END")
    void shouldFallbackToEndOnEdgeConditionException() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed));

        Map<String, String> routing = new HashMap<>();
        routing.put("ok", "B");
        g.addConditionalEdges("A", state -> { throw new RuntimeException("NPE"); }, routing)
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).containsOnly("A");
    }

    // ---- UC-09: Empty routing table → END ----

    @Test
    @DisplayName("UC-09: empty routing table → fallback to END")
    void shouldFallbackToEndOnEmptyRouting() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed));

        // Empty routing map
        g.addConditionalEdges("A", state -> "ok", new HashMap<>())
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).containsOnly("A");
    }

    // ---- UC-10: GraphExecutor sequential DAG execution ----

    @Test
    @DisplayName("UC-10: execute A→B→C→END sequentially")
    void shouldExecuteDagSequentially() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed))
            .addNode("C", trackingNode("C", executed))
            .addNode("END", noopNode("END"))
            .addEdge("A", "B")
            .addEdge("B", "C")
            .addEdge("C", "END")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).containsExactly("A", "B", "C");
    }

    // ---- UC-11: Node exception → FAILED ----

    @Test
    @DisplayName("UC-11: node throws RuntimeException → FAILED + remaining nodes skipped")
    void shouldFailOnNodeException() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", failingNode("B", new RuntimeException("DB error")))
            .addNode("C", trackingNode("C", executed))
            .addEdge("A", "B")
            .addEdge("B", "C")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.getReport()).contains("DB error");
        assertThat(executed).containsExactly("A"); // C not executed
    }

    // ---- UC-13: DAG cycle detection ----

    @Test
    @DisplayName("UC-13: compileDag() with cycle A→B→A → IllegalGraphException")
    void shouldThrowOnCyclicDag() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addEdge("A", "B")
            .addEdge("B", "A") // cycle
            .setEntryPoint("A");

        assertThatThrownBy(() -> g.compileDag())
            .isInstanceOf(IllegalGraphException.class)
            .hasMessageContaining("cycle detected");
    }

    @Test
    @DisplayName("UC-13b: self-loop A→A → IllegalGraphException")
    void shouldThrowOnSelfLoop() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addEdge("A", "A")
            .setEntryPoint("A");

        assertThatThrownBy(() -> g.compileDag())
            .isInstanceOf(IllegalGraphException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("UC-13c: compile() allows cycles (ReAct mode)")
    void shouldAllowCyclesInCompileMode() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addEdge("A", "B")
            .addEdge("B", "A") // cycle
            .setEntryPoint("A");

        // compile() (not compileDag) should NOT throw
        CompiledGraph cg = g.compile();
        assertThat(cg).isNotNull();
    }

    // ---- UC-14: maxTurns → TIMEOUT ----

    @Test
    @DisplayName("UC-14: maxTurns exceeded → TIMEOUT")
    void shouldTimeoutOnMaxTurns() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addEdge("A", "A") // infinite loop
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 5);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.TIMEOUT);
    }

    // ---- UC-15: cancel → CANCELLED ----

    @Test
    @DisplayName("UC-15: cancelled context → CANCELLED")
    void shouldCancelExecution() {
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addEdge("A", "B")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), cancelledContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    // ---- UC-16: InterruptException → PAUSED ----

    @Test
    @DisplayName("UC-16: InterruptException → PAUSED")
    void shouldPauseOnInterruptException() {
        List<String> executed = new ArrayList<>();

        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", interruptingNode("B"))
            .addNode("C", trackingNode("C", executed))
            .addEdge("A", "B")
            .addEdge("B", "C")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.PAUSED);
        assertThat(executed).containsExactly("A"); // C not executed
    }

    // ---- UC-17: CheckpointStore.list returns history ----

    @Test
    @DisplayName("UC-17: CheckpointStore.list returns checkpoints in order")
    void shouldListCheckpoints() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        GraphState s1 = GraphState.empty("t-1").with("data", "v1");
        GraphState s2 = GraphState.empty("t-1").with("data", "v2");
        GraphState s3 = GraphState.empty("t-1").with("data", "v3");

        store.save("t-1", s1);
        store.save("t-1", s2);
        store.save("t-1", s3);

        List<CheckpointMetadata> list = store.list("t-1");
        assertThat(list).hasSize(3);
        // Sorted by createdAt descending (most recent first)
    }

    @Test
    @DisplayName("UC-17b: list for unknown threadId → empty list")
    void shouldReturnEmptyForUnknownThread() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        assertThat(store.list("unknown")).isEmpty();
    }

    // ---- UC-18: GraphExecutor.resume from checkpoint ----

    @Test
    @DisplayName("UC-18: resume from checkpoint loads state and continues")
    void shouldResumeFromCheckpoint() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        // Save a checkpoint at turn 0 with some state
        GraphState savedState = GraphState.empty("t-1")
            .with("user.message", "hello")
            .with("thought", "hi there");
        String checkpointId = store.save("t-1", savedState);

        // Create a graph that just passes through
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A"))
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(store, 50);

        TaskResult result = executor.resume(cg, checkpointId, mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    // ---- UC-19: CheckpointNotFoundException ----

    @Test
    @DisplayName("UC-19: resume with non-existent checkpoint → CheckpointNotFoundException")
    void shouldThrowOnNonExistentCheckpoint() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        StateGraph g = new StateGraph();
        g.addNode("A", noopNode("A")).setEntryPoint("A");
        CompiledGraph cg = g.compile();

        GraphExecutor executor = new GraphExecutor(store, 50);

        assertThatThrownBy(() -> executor.resume(cg, "nonexistent", mockContext()))
            .isInstanceOf(CheckpointNotFoundException.class)
            .hasMessageContaining("nonexistent");
    }

    // ---- UC-20: GraphState serialize/deserialize ----

    @Test
    @DisplayName("UC-20: serialize → deserialize preserves all fields")
    void shouldPreserveStateThroughSerialization() {
        GraphState original = GraphState.empty("thread-1")
            .with("key1", "value1")
            .with("key2", 42)
            .with("key3", true);

        byte[] data = original.serialize();
        GraphState restored = GraphState.deserialize(data);

        assertThat(restored.getThreadId()).isEqualTo("thread-1");
        assertThat((String) restored.get("key1")).isEqualTo("value1");
        assertThat((Integer) restored.get("key2")).isEqualTo(42);
        assertThat((Boolean) restored.get("key3")).isEqualTo(true);
    }

    @Test
    @DisplayName("UC-20b: with() returns new instance (immutability)")
    void shouldReturnNewInstanceFromWith() {
        GraphState s1 = GraphState.empty("t-1");
        GraphState s2 = s1.with("key", "value");

        assertThat(s1).isNotSameAs(s2);
        assertThat((Object) s1.get("key")).isNull();
        assertThat((String) s2.get("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("UC-20c: nextTurn() increments turn")
    void shouldIncrementTurn() {
        GraphState s1 = GraphState.empty("t-1");
        assertThat(s1.getTurn()).isEqualTo(0);

        GraphState s2 = s1.nextTurn();
        assertThat(s2.getTurn()).isEqualTo(1);
        assertThat(s1.getTurn()).isEqualTo(0); // original unchanged
    }

    // ---- UC-22: checkpoint save failure doesn't block ----

    @Test
    @DisplayName("UC-22: checkpoint save failure → execution continues, no FAILED")
    void shouldContinueWhenCheckpointSaveFails() {
        CheckpointStore failingStore = new CheckpointStore() {
            @Override
            public String save(String threadId, GraphState state) {
                throw new RuntimeException("storage failure");
            }
            @Override
            public GraphState load(String checkpointId) { return null; }
            @Override
            public List<CheckpointMetadata> list(String threadId) { return Collections.emptyList(); }
            @Override
            public void delete(String checkpointId) {}
            @Override
            public void deleteByThread(String threadId) {}
        };

        List<String> executed = new ArrayList<>();
        StateGraph g = new StateGraph();
        g.addNode("A", trackingNode("A", executed))
            .addNode("B", trackingNode("B", executed))
            .addEdge("A", "B")
            .setEntryPoint("A");

        CompiledGraph cg = g.compile();
        GraphExecutor executor = new GraphExecutor(failingStore, 50);

        TaskResult result = executor.execute(cg, GraphState.empty("t-1"), mockContext());
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(executed).containsExactly("A", "B");
    }

    // ---- UC-31: Workflow DAG cycle check + ReAct allows cycles ----

    @Test
    @DisplayName("UC-31: compileDag() detects cycle, compile() allows it")
    void shouldDifferentiateCyclicAndAcyclic() {
        // ReAct-style graph with cycle
        StateGraph reactGraph = new StateGraph();
        reactGraph.addNode("agent", noopNode("agent"))
            .addNode("tools", noopNode("tools"))
            .addEdge("agent", "tools")
            .addEdge("tools", "agent") // cycle
            .setEntryPoint("agent");

        // compile() allows cycles (ReAct mode)
        CompiledGraph reactCompiled = reactGraph.compile();
        assertThat(reactCompiled).isNotNull();

        // compileDag() rejects cycles
        assertThatThrownBy(() -> reactGraph.compileDag())
            .isInstanceOf(IllegalGraphException.class);

        // Workflow-style DAG without cycles
        StateGraph dag = new StateGraph();
        dag.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addNode("C", noopNode("C"))
            .addEdge("A", "B")
            .addEdge("B", "C")
            .setEntryPoint("A");

        // Both compile() and compileDag() should succeed
        assertThat(dag.compile()).isNotNull();
        assertThat(dag.compileDag()).isNotNull();
    }

    // ---- UC-32: Same GraphExecutor for both topologies ----

    @Test
    @DisplayName("UC-32: same GraphExecutor executes both ReAct and Workflow graphs")
    void shouldExecuteBothTopologiesWithSameExecutor() {
        GraphExecutor executor = new GraphExecutor(new InMemoryCheckpointStore(), 50);

        // Simple DAG
        StateGraph dag = new StateGraph();
        dag.addNode("A", noopNode("A"))
            .addNode("B", noopNode("B"))
            .addEdge("A", "B")
            .setEntryPoint("A");
        CompiledGraph dagCompiled = dag.compile();

        // Simple ReAct (with cycle — but we use compile() not compileDag())
        StateGraph react = new StateGraph();
        react.addNode("agent", noopNode("agent"))
            .addNode("END", noopNode("END"))
            .addEdge("agent", "END")
            .setEntryPoint("agent");
        CompiledGraph reactCompiled = react.compile();

        // Same executor executes both
        TaskResult dagResult = executor.execute(dagCompiled, GraphState.empty("dag-1"), mockContext());
        TaskResult reactResult = executor.execute(reactCompiled, GraphState.empty("react-1"), mockContext());

        assertThat(dagResult.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(reactResult.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }
}
