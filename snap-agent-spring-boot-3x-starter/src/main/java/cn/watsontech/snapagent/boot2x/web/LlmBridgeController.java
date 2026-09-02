package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.bridge.BridgeLlmResponse;
import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.boot2x.issue.BridgeClientStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for LLM bridge endpoints.
 * 
 * <p>Provides endpoints for browser extension to communicate LLM responses.</p>
 */
@RestController
@RequestMapping("/snap-agent/bridge")
public class LlmBridgeController {

    private static final Logger log = LoggerFactory.getLogger(LlmBridgeController.class);

    private final LlmBridgeService llmBridgeService;

    public LlmBridgeController(LlmBridgeService llmBridgeService) {
        this.llmBridgeService = llmBridgeService;
    }

    /**
     * SSE endpoint for frontend to receive LLM requests.
     */
    @GetMapping("/llm/stream")
    public SseEmitter stream() {
        log.info("Frontend connected to LLM bridge SSE");
        return llmBridgeService.registerEmitter();
    }

    /**
     * Receive LLM response from frontend.
     */
    @PostMapping("/llm/result")
    public ResponseEntity<?> handleResult(@RequestBody Map<String, Object> payload) {
        String taskId = (String) payload.get("id");
        
        try {
            BridgeLlmResponse response = parseResponse(payload);
            llmBridgeService.handleResult(taskId, response);
            log.info("LLM result received: task={}", taskId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to parse LLM result: task={}", taskId, e);
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    /**
     * Receive LLM error from frontend.
     */
    @PostMapping("/llm/error")
    public ResponseEntity<?> handleError(@RequestBody Map<String, Object> payload) {
        String taskId = (String) payload.get("id");
        String error = (String) payload.get("error");
        
        llmBridgeService.handleError(taskId, error);
        log.warn("LLM error received: task={}, error={}", taskId, error);
        return ResponseEntity.ok().build();
    }

    /**
     * Update bridge client status.
     */
    @PostMapping("/llm/status")
    public ResponseEntity<?> updateStatus(@RequestBody BridgeClientStatus status) {
        llmBridgeService.updateClientStatus(status);
        return ResponseEntity.ok().build();
    }

    /**
     * Get bridge status.
     */
    @GetMapping("/llm/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(createStatusMap(llmBridgeService.isBridgeActive(), llmBridgeService.getPendingCount()));
    }


    private Map<String, Object> createStatusMap(boolean active, int pending) {
        Map<String, Object> map = new HashMap<>();
        map.put("active", active);
        map.put("pending", pending);
        return map;
    }

    private BridgeLlmResponse parseResponse(Map<String, Object> payload) {
        BridgeLlmResponse response = new BridgeLlmResponse();
        response.id = (String) payload.get("id");
        response.text = (String) payload.get("text");
        // TODO: Parse toolCalls and usage from payload
        return response;
    }
}
