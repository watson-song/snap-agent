package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.graph.GraphState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShouldContinue 条件路由")
class ShouldContinueTest {

    private final ShouldContinue router = new ShouldContinue();

    @Test
    @DisplayName("end_turn → end (END)")
    void endTurnRoutesToEnd() {
        GraphState state = GraphState.empty("t1").with("stop_reason", "end_turn");
        assertThat(router.route(state)).isEqualTo("end");
    }

    @Test
    @DisplayName("tool_use → tools")
    void toolUseRoutesToTools() {
        GraphState state = GraphState.empty("t1").with("stop_reason", "tool_use");
        assertThat(router.route(state)).isEqualTo("tools");
    }

    @Test
    @DisplayName("max_tokens (无 tool_use) → agent (续传)")
    void maxTokensRoutesToAgent() {
        GraphState state = GraphState.empty("t1").with("stop_reason", "max_tokens");
        assertThat(router.route(state)).isEqualTo("agent");
    }

    @Test
    @DisplayName("error → end (终止图)")
    void errorRoutesToEnd() {
        GraphState state = GraphState.empty("t1").with("stop_reason", "error");
        assertThat(router.route(state)).isEqualTo("end");
    }
}
