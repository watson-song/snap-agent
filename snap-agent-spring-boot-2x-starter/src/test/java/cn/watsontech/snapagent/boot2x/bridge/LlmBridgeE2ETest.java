package cn.watsontech.snapagent.boot2x.bridge;

import cn.watsontech.snapagent.boot2x.issue.BridgeClientStatus;
import cn.watsontech.snapagent.boot2x.llm.BridgeLlmClient;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for LLM Bridge flow.
 * 
 * <p>Simulates the full flow without Spring context:
 * <ol>
 *   <li>Create BridgeLlmClient with LlmBridgeService</li>
 *   <li>Activate bridge (simulate extension connected)</li>
 *   <li>Call LLM through bridge</li>
 *   <li>Simulate frontend sending result back</li>
 *   <li>Verify LLM call completes</li>
 * </ol>
 * </p>
 */
class LlmBridgeE2ETest {

    private LlmBridgeService bridgeService;
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        bridgeService = new LlmBridgeService(30000);
        llmClient = new BridgeLlmClient(bridgeService, 5000);
    }

    @Test
    void shouldCompleteLlmCallThroughBridge() throws Exception {
        // Given: Bridge is active (extension installed + master ON + LLM ON)
        Map<String, Boolean> services = new HashMap<>();
        services.put("llm", true);
        bridgeService.updateClientStatus(new BridgeClientStatus(true, true, services));

        // Given: LLM request
        LlmRequest request = new LlmRequest(
            "You are a helpful assistant.",
            Arrays.asList(Message.user("Hello")),
            Collections.emptyList(),
            "claude-sonnet-4-20250514",
            8192,
            true
        );
        
        AtomicReference<String> receivedText = new AtomicReference<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        
        LlmEventSink sink = new LlmEventSink() {
            @Override
            public void onThought(String text) {
                receivedText.set(text);
                responseLatch.countDown();
            }
            
            @Override
            public void onToolUse(String id, String name, Map<String, Object> input) {}
            
            @Override
            public void onToolResult(String toolUseId, String result) {}
            
            @Override
            public void onStop(String stopReason) {}
            
            @Override
            public void onError(String message) {
                responseLatch.countDown();
            }
        };
        
        // When: Agent calls LLM through bridge (in background thread)
        Thread llmThread = new Thread(() -> {
            llmClient.stream(request, sink, "e2e-test-task");
        });
        llmThread.start();
        
        // Wait for request to be pending
        Thread.sleep(200);
        assertThat(bridgeService.getPendingCount()).isEqualTo(1);
        
        // Simulate frontend sending result back
        BridgeLlmResponse response = new BridgeLlmResponse();
        response.id = "e2e-test-task";
        response.text = "Hello! How can I help you?";
        response.usage = new BridgeLlmResponse.Usage(10, 5, 0);
        
        bridgeService.handleResult("e2e-test-task", response);
        
        // Then: LLM call completes
        boolean completed = responseLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(receivedText.get()).isEqualTo("Hello! How can I help you?");
    }

    @Test
    void shouldHandleToolCallThroughBridge() throws Exception {
        // Given: Bridge is active
        Map<String, Boolean> services = new HashMap<>();
        services.put("llm", true);
        bridgeService.updateClientStatus(new BridgeClientStatus(true, true, services));
        
        LlmRequest request = new LlmRequest(
            "You are a helpful assistant with tools.",
            Arrays.asList(Message.user("Query the database")),
            Arrays.asList(new cn.watsontech.snapagent.core.llm.ToolDef(
                "mysql_query", "Execute SQL", "{\"sql\": \"string\"}"
            )),
            "claude-sonnet-4-20250514",
            8192,
            true
        );
        
        AtomicReference<String> toolCallName = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        
        LlmEventSink sink = new LlmEventSink() {
            @Override
            public void onThought(String text) {}
            
            @Override
            public void onToolUse(String id, String name, Map<String, Object> input) {
                toolCallName.set(name);
                latch.countDown();
            }
            
            @Override
            public void onToolResult(String toolUseId, String result) {}
            
            @Override
            public void onStop(String stopReason) {}
            
            @Override
            public void onError(String message) {
                latch.countDown();
            }
        };
        
        Thread llmThread = new Thread(() -> {
            llmClient.stream(request, sink, "tool-test-task");
        });
        llmThread.start();
        
        Thread.sleep(200);
        
        // Simulate LLM response with tool call
        BridgeLlmResponse response = new BridgeLlmResponse();
        response.id = "tool-test-task";
        response.text = "Let me query the database.";
        response.toolCalls = Arrays.asList(new BridgeLlmResponse.ToolCall());
        response.toolCalls.get(0).id = "call_1";
        response.toolCalls.get(0).name = "mysql_query";
        response.toolCalls.get(0).input = Collections.singletonMap("sql", "SELECT 1");
        response.usage = new BridgeLlmResponse.Usage(20, 10, 0);
        
        bridgeService.handleResult("tool-test-task", response);
        
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(toolCallName.get()).isEqualTo("mysql_query");
    }

    @Test
    void shouldReportErrorWhenBridgeNotActive() throws Exception {
        // Given: Bridge is NOT active
        bridgeService.updateClientStatus(BridgeClientStatus.inactive());
        
        LlmRequest request = new LlmRequest(
            "You are a helpful assistant.",
            Arrays.asList(Message.user("Hello")),
            Collections.emptyList(),
            "claude-sonnet-4-20250514",
            8192,
            true
        );
        
        AtomicReference<String> errorMsg = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        
        LlmEventSink sink = new LlmEventSink() {
            @Override
            public void onThought(String text) {}
            
            @Override
            public void onToolUse(String id, String name, Map<String, Object> input) {}
            
            @Override
            public void onToolResult(String toolUseId, String result) {}
            
            @Override
            public void onStop(String stopReason) {}
            
            @Override
            public void onError(String message) {
                errorMsg.set(message);
                latch.countDown();
            }
        };
        
        // When: Call LLM
        llmClient.stream(request, sink, "error-test-task");
        
        // Then: Error is reported immediately
        boolean completed = latch.await(1, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(errorMsg.get()).contains("not active");
    }
}
