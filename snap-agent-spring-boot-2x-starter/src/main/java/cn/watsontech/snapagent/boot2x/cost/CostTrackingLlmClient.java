package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostTracker;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Decorator that wraps an {@link LlmClient} to automatically record token
 * usage and cost for every LLM call.
 *
 * <p>When {@code snap-agent.cost.enabled=true}, the auto-configuration wraps
 * the original LlmClient (AnthropicLlmClient or OpenAiLlmClient) in this
 * decorator before injecting it into AgentExecutor. The decorator intercepts
 * streaming events via a tracking {@link LlmEventSink} and, upon completion
 * ({@code onStop}), creates a {@link CostRecord} and calls
 * {@link CostTracker#record}.</p>
 *
 * <p>If the underlying LlmClient implementation does not emit usage information
 * (i.e. {@link LlmEventSink#onUsage} is never called), the decorator skips
 * recording — it does not crash or create zero-cost records.</p>
 *
 * <p>Cost computation is delegated to a {@link CostCalculator} instance
 * passed in the constructor, allowing the same pricing logic to be shared
 * with other components (budget enforcers, summary services).</p>
 */
public class CostTrackingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingLlmClient.class);

    private final LlmClient delegate;
    private final CostTracker costTracker;
    private final CostCalculator costCalculator;
    private final String defaultUserId;
    private final String defaultSkillName;

    /**
     * Construct a tracking decorator.
     *
     * @param delegate        the underlying LLM client to wrap
     * @param costTracker     where to record {@link CostRecord}s
     * @param costCalculator  the calculator that maps token usage to cost
     * @param defaultUserId   user id to attribute runs to when none is available
     * @param defaultSkillName skill name to attribute runs to when none is available
     */
    public CostTrackingLlmClient(LlmClient delegate, CostTracker costTracker,
                                  CostCalculator costCalculator,
                                  String defaultUserId,
                                  String defaultSkillName) {
        this.delegate = delegate;
        this.costTracker = costTracker;
        this.costCalculator = costCalculator;
        this.defaultUserId = defaultUserId;
        this.defaultSkillName = defaultSkillName;
    }

    @Override
    public void stream(LlmRequest req, LlmEventSink events, String taskId) {
        TrackingEventSink trackingSink = new TrackingEventSink(events, req, taskId);
        delegate.stream(req, trackingSink, taskId);
    }

    @Override
    public void cancel(String taskId) {
        delegate.cancel(taskId);
    }

    @Override
    public List<String> listModels() {
        return delegate.listModels();
    }

    // ---- inner class: tracking event sink ----

    /**
     * Wraps the original {@link LlmEventSink} to intercept usage and stop events.
     * On stop, if usage was reported, computes cost and records a CostRecord.
     */
    private class TrackingEventSink implements LlmEventSink {

        private final LlmEventSink delegate;
        private final LlmRequest request;
        private final String taskId;

        private long inputTokens = 0;
        private long outputTokens = 0;
        private long cacheReadTokens = 0;
        private boolean usageReceived = false;

        TrackingEventSink(LlmEventSink delegate, LlmRequest request, String taskId) {
            this.delegate = delegate;
            this.request = request;
            this.taskId = taskId;
        }

        @Override
        public void onThought(String text) {
            delegate.onThought(text);
        }

        @Override
        public void onToolUse(String id, String name, Map<String, Object> input) {
            delegate.onToolUse(id, name, input);
        }

        @Override
        public void onToolResult(String toolUseId, String result) {
            delegate.onToolResult(toolUseId, result);
        }

        @Override
        public void onStop(String stopReason) {
            // Record cost if usage information was received
            if (usageReceived) {
                try {
                    java.math.BigDecimal cost = costCalculator.computeCost(
                            inputTokens, outputTokens, cacheReadTokens);
                    CostRecord record = new CostRecord(
                            null,
                            defaultUserId,
                            defaultSkillName,
                            taskId,
                            request.getModel(),
                            inputTokens,
                            outputTokens,
                            cacheReadTokens,
                            cost,
                            System.currentTimeMillis()
                    );
                    costTracker.record(record);
                    log.debug("Recorded LLM cost: model={}, input={}, output={}, cacheRead={}, cost={}",
                            request.getModel(), inputTokens, outputTokens, cacheReadTokens, cost);
                } catch (Exception e) {
                    log.warn("Failed to record cost: {}", e.getMessage());
                }
            }
            delegate.onStop(stopReason);
        }

        @Override
        public void onError(String message) {
            delegate.onError(message);
        }

        @Override
        public void onUsage(long inputTokens, long outputTokens, long cacheReadTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadTokens = cacheReadTokens;
            this.usageReceived = true;
            // Forward to the delegate in case it also wants to observe usage
            delegate.onUsage(inputTokens, outputTokens, cacheReadTokens);
        }
    }
}
