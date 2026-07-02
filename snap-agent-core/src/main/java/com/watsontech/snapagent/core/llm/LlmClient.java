package com.watsontech.snapagent.core.llm;

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
     */
    void stream(LlmRequest req, LlmEventSink events);

    /**
     * List available models from the LLM API.
     *
     * @return list of model IDs, or empty list if not supported.
     */
    default List<String> listModels() {
        return Collections.emptyList();
    }
}
