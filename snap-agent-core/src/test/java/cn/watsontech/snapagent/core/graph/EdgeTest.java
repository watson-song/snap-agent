package cn.watsontech.snapagent.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Edge / ConditionalEdge / EdgeCondition")
class EdgeTest {

    @Test
    @DisplayName("Edge 不可变 from/to")
    void edgeImmutable() {
        Edge edge = new Edge("agent", "tools");
        assertThat(edge.getFrom()).isEqualTo("agent");
        assertThat(edge.getTo()).isEqualTo("tools");
    }

    @Test
    @DisplayName("ConditionalEdge 按 condition.route 返回的 key 路由")
    void conditionalEdgeRouting() {
        GraphState state = GraphState.empty("t1").with("stop", "end");
        EdgeCondition cond = s -> "end".equals(s.get("stop")) ? "end" : "tools";
        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");
        ConditionalEdge ce = new ConditionalEdge("agent", cond, routing);
        String routeKey = cond.route(state);
        assertThat(ce.getRouting().get(routeKey)).isEqualTo("END");

        GraphState state2 = GraphState.empty("t1").with("stop", "continue");
        assertThat(ce.getRouting().get(cond.route(state2))).isEqualTo("tools");
    }

    @Test
    @DisplayName("EdgeCondition 是 FunctionalInterface")
    void edgeConditionIsFunctional() {
        EdgeCondition ec = state -> "end";
        assertThat(ec.route(GraphState.empty("t1"))).isEqualTo("end");
    }
}
