package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.boot2x.bridge.BridgeLlmResponse;
import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM client that routes requests through the browser bridge.
 * 
 * <p>Instead of calling LLM API directly, this client sends the request
 * through {@link LlmBridgeService} to the browser extension, which
 * then calls the LLM API and returns the response.</p>
 */
public class BridgeLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(BridgeLlmClient.class);

    private final LlmBridgeService bridgeService;
    private final long timeoutMs;

    public BridgeLlmClient(LlmBridgeService bridgeService, long timeoutMs) {
        this.bridgeService = bridgeService;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void stream(LlmRequest req, LlmEventSink events, String taskId) {
        if (!bridgeService.isBridgeActive()) {
            throw new BridgeNotActiveException("LLM bridge is not active. Check extension installation and configuration.");
        }

        try {
            log.info("Sending LLM request through bridge: task={}", taskId);
            
            CompletableFuture<BridgeLlmResponse> future = bridgeService.proxyLlmRequest(req, taskId);
            BridgeLlmResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            // Emit thought text
            if (response.text != null && !response.text.isEmpty()) {
                events.onThought(response.text);
            }

            // Emit tool calls
            if (response.toolCalls != null) {
                for (BridgeLlmResponse.ToolCall call : response.toolCalls) {
                    events.onToolUse(call.id, call.name, call.input);
                }
            }

            // Emit usage
            if (response.usage != null) {
                events.onUsage(response.usage.inputTokens, response.usage.outputTokens, 
                              response.usage.cacheReadTokens);
            }

            events.onStop("end_turn");
            log.info("LLM bridge response received: task={}, tokens={}", taskId, 
                    response.usage != null ? response.usage.outputTokens : 0);

        } catch (TimeoutException e) {
            String msg = "LLM bridge request timed out after " + timeoutMs + "ms";
            log.error(msg);
            events.onError(msg);
        } catch (ExecutionException e) {
            String msg = "LLM bridge error: " + e.getCause().getMessage();
            log.error(msg, e);
            events.onError(msg);
        } catch (Exception e) {
            String msg = "LLM bridge unexpected error: " + e.getMessage();
            log.error(msg, e);
            events.onError(msg);
        }
    }

    @Override
    public void cancel(String taskId) {
        log.info("Cancelling LLM bridge request: task={}", taskId);
        // Bridge cancellation would require additional protocol support
    }

    /**
     * Exception thrown when bridge is not active.
     */
    public static class BridgeNotActiveException extends RuntimeException {
        public BridgeNotActiveException(String message) {
            super(message);
        }
    }
}
