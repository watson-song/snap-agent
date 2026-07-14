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
}
