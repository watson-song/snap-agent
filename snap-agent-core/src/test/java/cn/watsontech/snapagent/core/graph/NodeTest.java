package cn.watsontech.snapagent.core.graph;

import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.execution.TaskResult;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Node + ExecutionContext 接口")
class NodeTest {

    @Test
    @DisplayName("Node.execute 返回新 state")
    void nodeExecuteReturnsNewState() throws InterruptException {
        Node node = (state, ctx) -> state.with("result", "ok");
        GraphState state1 = GraphState.empty("t1").with("k", "v1");
        GraphState state2 = node.execute(state1, null);
        assertThat(state2).isNotSameAs(state1);
        assertThat((String) state2.get("result")).isEqualTo("ok");
        assertThat((String) state1.get("k")).isEqualTo("v1");
        assertThat((String) state1.get("result")).isNull();
    }

    @Test
    @DisplayName("Node.execute 抛 InterruptException")
    void nodeExecuteThrowsInterrupt() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("toolName", "ddl_tool");
        Node node = (state, ctx) -> {
            throw new InterruptException(payload);
        };
        assertThatThrownBy(() -> node.execute(GraphState.empty("t1"), null))
            .isInstanceOf(InterruptException.class);
    }

    @Test
    @DisplayName("Node.getName 返回节点名")
    void nodeGetName() {
        Node node = new Node() {
            @Override
            public String getName() { return "test-node"; }
            @Override
            public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
                return state;
            }
        };
        assertThat(node.getName()).isEqualTo("test-node");
    }

    @Test
    @DisplayName("TaskResult 包含 status + report")
    void taskResultFields() {
        TaskResult result = new TaskResult(TaskStatus.SUCCEEDED, "diagnosis complete");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.getReport()).isEqualTo("diagnosis complete");
    }
}
