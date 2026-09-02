package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM client that tries browser bridge first, falls back to direct API.
 * 
 * <p>When the browser extension's LLM bridge is active, requests are routed
 * through it (no API key needed on server). If bridge is unavailable,
 * falls back to the primary client (Anthropic/OpenAI).</p>
 */
public class FallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmClient.class);

    private final LlmClient primaryClient;
    private final BridgeLlmClient bridgeClient;
    private final LlmBridgeService bridgeService;

    public FallbackLlmClient(LlmClient primaryClient, BridgeLlmClient bridgeClient,
                             LlmBridgeService bridgeService) {
        this.primaryClient = primaryClient;
        this.bridgeClient = bridgeClient;
        this.bridgeService = bridgeService;
    }

    @Override
    public void stream(LlmRequest req, LlmEventSink events, String taskId) {
        // Try bridge first if available and active
        if (bridgeClient != null && bridgeService != null && bridgeService.isBridgeActive()) {
            log.info("LLM bridge is active, routing through browser extension: task={}", taskId);
            bridgeClient.stream(req, events, taskId);
            return;
        }

        // Fall back to primary client
        if (primaryClient != null) {
            log.info("LLM bridge not active, using direct API: task={}", taskId);
            primaryClient.stream(req, events, taskId);
            return;
        }

        // No client available
        String msg = "No LLM client available: bridge is not active and no direct API configured";
        log.error(msg);
        events.onError(msg);
    }
}
