package cn.watsontech.snapagent.core.graph;

import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StateGraph 构建与 compile 校验")
class StateGraphTest {

    private final Node noopNode = new Node() {
        @Override public String getName() { return "noop"; }
        @Override public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException { return state; }
    };

    @Test
    @DisplayName("链式构建图")
    void chainBuild() {
        StateGraph g = new StateGraph();
        g.addNode("entry", noopNode).addNode("agent", noopNode).addEdge("entry", "agent").setEntryPoint("entry");
        CompiledGraph compiled = g.compile();
        assertThat(compiled.getNodes()).containsKeys("entry", "agent");
        assertThat(compiled.getEntryPoint()).isEqualTo("entry");
    }

    @Test
    @DisplayName("addConditionalEdges 注册条件路由")
    void addConditionalEdges() {
        StateGraph g = new StateGraph();
        g.addNode("agent", noopNode).addNode("tools", noopNode).addNode("END", noopNode);
        EdgeCondition cond = s -> "end";
        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("tools", "tools");
        g.addConditionalEdges("agent", cond, routing);
        g.setEntryPoint("agent");
        CompiledGraph compiled = g.compile();
        assertThat(compiled.getEdgesFrom("agent")).hasSize(2);
    }

    @Test
    @DisplayName("compile 校验 — 未设置 entryPoint 抛异常")
    void compileNoEntryPoint() {
        StateGraph g = new StateGraph();
        g.addNode("entry", noopNode);
        assertThatThrownBy(g::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("entry point not set");
    }

    @Test
    @DisplayName("compile 校验 — 边引用不存在的节点")
    void compileEdgeToGhost() {
        StateGraph g = new StateGraph();
        g.addNode("entry", noopNode).addEdge("entry", "ghost").setEntryPoint("entry");
        assertThatThrownBy(g::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("edge references unknown node: ghost");
    }

    @Test
    @DisplayName("compile 校验 — 条件路由目标不存在")
    void compileConditionalGhost() {
        StateGraph g = new StateGraph();
        g.addNode("agent", noopNode).addNode("tools", noopNode);
        Map<String, String> routing = new HashMap<>();
        routing.put("end", "END");
        routing.put("missing", "ghost");
        g.addConditionalEdges("agent", s -> "end", routing);
        g.setEntryPoint("agent");
        assertThatThrownBy(g::compile)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("conditional routing target not found: ghost");
    }
}
