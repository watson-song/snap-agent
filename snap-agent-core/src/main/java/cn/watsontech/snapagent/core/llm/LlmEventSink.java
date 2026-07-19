package cn.watsontech.snapagent.core.llm;

import java.util.Map;

/**
 * Callback interface receiving streaming events from {@link LlmClient#stream}.
 *
 * <p>Implementations (typically the {@code AgentExecutor}) translate these
 * events into transcript entries and SSE pushes.</p>
 */
public interface LlmEventSink {

    /** A chunk of assistant text (a "thought"). */
    void onThought(String text);

    /** The LLM requested a tool call. */
    void onToolUse(String id, String name, Map<String, Object> input);

    /** A tool result has been produced and will be fed back to the LLM. */
    void onToolResult(String toolUseId, String result);

    /** The LLM stopped generating. {@code stopReason} e.g. {@code end_turn}, {@code max_tokens}. */
    void onStop(String stopReason);

    /** An error occurred during streaming. */
    void onError(String message);

    /**
     * Token usage reported by the LLM API (v1.0 cost accounting).
     *
     * <p>Called when the API sends usage information (e.g. Anthropic's
     * {@code message_start} and {@code message_delta} events). Implementations
     * can capture these counts to compute cost per call. The default
     * implementation is a no-op so existing sinks are unaffected.</p>
     *
     * @param inputTokens     input (prompt) token count
     * @param outputTokens    output (completion) token count
     * @param cacheReadTokens cache-read token count (discounted tokens)
     */
    default void onUsage(long inputTokens, long outputTokens, long cacheReadTokens) {
        // no-op by default
    }
}
