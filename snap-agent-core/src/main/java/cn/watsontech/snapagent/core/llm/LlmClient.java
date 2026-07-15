package cn.watsontech.snapagent.core.llm;

import java.util.Collections;
import java.util.List;

/**
 * SPI for LLM streaming clients.
 *
 * <p>Implementations (e.g. {@code AnthropicLlmClient} in the starter module)
 * perform the actual HTTP/SSE call to the LLM gateway and push events to the
 * provided {@link LlmEventSink}.</p>
 */
public interface LlmClient {

    /**
     * Stream a completion request.
     *
     * @param req    the request payload (messages, tools, model, etc.)
     * @param events the sink that receives streaming events
     * @param taskId identifier for this task, used for cancellation tracking;
     *               may be null when cancellation is not needed
     */
    void stream(LlmRequest req, LlmEventSink events, String taskId);

    /**
     * Cancel an in-flight stream call for the given task.
     * Implementations should interrupt the underlying HTTP call.
     * Default no-op for backward compatibility.
     */
    default void cancel(String taskId) {}

    /**
     * List available models from the LLM API.
     *
     * @return list of model IDs, or empty list if not supported.
     */
    default List<String> listModels() {
        return Collections.emptyList();
    }
}
