package cn.watsontech.snapagent.boot2x.bridge;

import cn.watsontech.snapagent.boot2x.issue.BridgeClientStatus;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

/**
 * Bridge service for LLM requests.
 * 
 * <p>Similar to {@link cn.watsontech.snapagent.boot2x.issue.IssueBridgeService}
 * but specialized for LLM streaming.</p>
 */
public class LlmBridgeService {

    private static final Logger log = LoggerFactory.getLogger(LlmBridgeService.class);

    private final long requestTimeoutMs;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<BridgeLlmResponse>> pending = new ConcurrentHashMap<>();

    private volatile BridgeClientStatus clientStatus = BridgeClientStatus.inactive();

    public LlmBridgeService(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * Update extension status.
     */
    public void updateClientStatus(BridgeClientStatus status) {
        this.clientStatus = status;
        log.info("LLM bridge client status updated: installed={}, masterEnabled={}, llm={}", 
                 status.isInstalled(), status.isMasterEnabled(), 
                 status.getServices().get("llm"));
    }

    /**
     * Check if bridge is active for LLM.
     */
    public boolean isBridgeActive() {
        return clientStatus.isInstalled() 
            && clientStatus.isMasterEnabled()
            && clientStatus.getServices().getOrDefault("llm", false);
    }

    /**
     * Register SSE emitter for frontend.
     */
    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * Send LLM request through bridge.
     */
    public CompletableFuture<BridgeLlmResponse> proxyLlmRequest(LlmRequest request, String taskId) {
        CompletableFuture<BridgeLlmResponse> future = new CompletableFuture<>();
        pending.put(taskId, future);

        try {
            // Send SSE event to frontend
            for (SseEmitter emitter : emitters) {
                try {
                    java.util.Map<String, Object> eventData = new java.util.HashMap<>();
                    eventData.put("id", taskId);
                    eventData.put("request", request);
                    emitter.send(SseEmitter.event().name("llm-request").data(eventData));
                } catch (Exception e) {
                    log.warn("Failed to send LLM request to emitter: {}", e.getMessage());
                    emitters.remove(emitter);
                }
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Handle LLM response from frontend.
     */
    public void handleResult(String taskId, BridgeLlmResponse response) {
        CompletableFuture<BridgeLlmResponse> future = pending.remove(taskId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("Received LLM result for unknown task: {}", taskId);
        }
    }

    /**
     * Handle LLM error from frontend.
     */
    public void handleError(String taskId, String error) {
        CompletableFuture<BridgeLlmResponse> future = pending.remove(taskId);
        if (future != null) {
            future.completeExceptionally(new RuntimeException(error));
        }
    }

    /**
     * Get pending request count.
     */
    public int getPendingCount() {
        return pending.size();
    }
}
