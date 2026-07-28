package cn.watsontech.snapagent.core.metrics;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Advisor (order=10) that records metrics via MetricsCollector.
 *
 * <p>Before: records start time.</p>
 * <p>After: records node duration, LLM token counts, tool call counts,
 * and LLM errors.</p>
 *
 * <p>When MetricsCollector is null, noops without throwing.</p>
 */
public class MicrometerObservationAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(MicrometerObservationAdvisor.class);

    private final MetricsCollector metricsCollector;

    public MicrometerObservationAdvisor(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public int getOrder() { return 10; }

    @Override
    public String getName() { return "micrometer-observation"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        if (metricsCollector == null) {
            log.debug("MeterRegistry not available, observation noop");
            return state;
        }

        long startTime = System.currentTimeMillis();
        Map<String, String> tags = new HashMap<>();
        tags.put("nodeName", nodeName);
        return state.with(StateKeys.METRICS_START_TIME, startTime);
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctxObj) {
        if (metricsCollector == null) {
            return state;
        }

        Long startTime = state.get(StateKeys.METRICS_START_TIME);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        Map<String, String> nodeTags = new HashMap<>();
        nodeTags.put("nodeName", nodeName);
        metricsCollector.recordTimer("snap-agent.graph.node.duration", duration, nodeTags);

        Integer inputTokens = state.get(StateKeys.LLM_INPUT_TOKENS);
        Integer outputTokens = state.get(StateKeys.LLM_OUTPUT_TOKENS);
        if (inputTokens != null) {
            Map<String, String> tokenTags = new HashMap<>();
            tokenTags.put("type", "input");
            metricsCollector.incrementCounter("snap-agent.llm.tokens", inputTokens, tokenTags);
        }
        if (outputTokens != null) {
            Map<String, String> tokenTags = new HashMap<>();
            tokenTags.put("type", "output");
            metricsCollector.incrementCounter("snap-agent.llm.tokens", outputTokens, tokenTags);
        }

        String stopReason = state.get(StateKeys.STOP_REASON);
        if ("error".equals(stopReason)) {
            Map<String, String> errorTags = new HashMap<>();
            errorTags.put("errorType", "RuntimeException");
            metricsCollector.recordError("RuntimeException", errorTags);
        }

        java.util.List<?> toolResults = state.get(StateKeys.TOOL_RESULTS);
        if (toolResults != null && !toolResults.isEmpty()) {
            metricsCollector.incrementCounter("snap-agent.tool.calls", toolResults.size(), new HashMap<>());
        }

        return state;
    }
}
