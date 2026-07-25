package cn.watsontech.snapagent.core.metrics;

import cn.watsontech.snapagent.core.graph.GraphState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("MicrometerObservationAdvisor — metrics + tracing")
class MicrometerObservationAdvisorTest {

    // UC-15: metrics tokens + duration
    @Test
    @DisplayName("afterNode 记录 node duration + LLM tokens")
    void shouldRecordDurationAndTokens() {
        MetricsCollector collector = mock(MetricsCollector.class);
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(collector);

        GraphState state = GraphState.empty("t1")
                .with("__metrics_start_time", System.currentTimeMillis() - 500)
                .with("llm.input_tokens", 100)
                .with("llm.output_tokens", 200);

        advisor.afterNode("agent", state, null);

        verify(collector).recordTimer(eq("snap-agent.graph.node.duration"), anyLong(), any());
        verify(collector).incrementCounter(eq("snap-agent.llm.tokens"), eq(100L), any());
        verify(collector).incrementCounter(eq("snap-agent.llm.tokens"), eq(200L), any());
    }

    @Test
    @DisplayName("tool_results 存在 → 记录 tool call 次数")
    void shouldRecordToolCalls() {
        MetricsCollector collector = mock(MetricsCollector.class);
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(collector);

        GraphState state = GraphState.empty("t1")
                .with("__metrics_start_time", System.currentTimeMillis())
                .with("tool_results", java.util.Arrays.asList(
                        cn.watsontech.snapagent.core.tool.ToolResult.success("r1", 1, 0, null),
                        cn.watsontech.snapagent.core.tool.ToolResult.success("r2", 1, 0, null),
                        cn.watsontech.snapagent.core.tool.ToolResult.success("r3", 1, 0, null)
                ));

        advisor.afterNode("tools", state, null);

        verify(collector).incrementCounter(eq("snap-agent.tool.calls"), eq(3L), any());
    }

    // UC-16: traces 全链路
    @Test
    @DisplayName("stop_reason=error → 记录 error counter")
    void shouldRecordErrors() {
        MetricsCollector collector = mock(MetricsCollector.class);
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(collector);

        GraphState state = GraphState.empty("t1")
                .with("__metrics_start_time", System.currentTimeMillis())
                .with("stop_reason", "error");

        advisor.afterNode("agent", state, null);

        verify(collector).recordError(eq("RuntimeException"), any());
    }

    // UC-17: noop 降级
    @Test
    @DisplayName("MeterRegistry=null → noop 不抛异常")
    void shouldNoopWhenCollectorNull() {
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(null);

        GraphState state = GraphState.empty("t1")
                .with("llm.input_tokens", 100);

        GraphState result1 = advisor.beforeNode("agent", state, null);
        GraphState result2 = advisor.afterNode("agent", state, null);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
    }

    @Test
    @DisplayName("order=10")
    void shouldReturnOrder10() {
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(null);
        assertThat(advisor.getOrder()).isEqualTo(10);
    }

    @Test
    @DisplayName("beforeNode 存入 start time")
    void shouldStoreStartTimeBeforeNode() {
        MetricsCollector collector = mock(MetricsCollector.class);
        MicrometerObservationAdvisor advisor = new MicrometerObservationAdvisor(collector);

        GraphState state = GraphState.empty("t1");
        GraphState result = advisor.beforeNode("agent", state, null);

        Long startTime = result.get("__metrics_start_time");
        assertThat(startTime).isNotNull();
        assertThat(startTime).isGreaterThan(0);
    }
}
