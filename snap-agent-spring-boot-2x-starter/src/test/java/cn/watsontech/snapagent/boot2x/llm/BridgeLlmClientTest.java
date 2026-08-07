package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.boot2x.bridge.BridgeLlmResponse;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BridgeLlmClientTest {

    @Mock
    private LlmBridgeService bridgeService;

    @Mock
    private LlmEventSink eventSink;

    private BridgeLlmClient client;

    @BeforeEach
    void setUp() {
        client = new BridgeLlmClient(bridgeService, 1000);
    }

    @Test
    void shouldSendPromptThroughBridge() {
        // Given
        LlmRequest req = createTestRequest();
        String taskId = "test-task-1";
        
        when(bridgeService.isBridgeActive()).thenReturn(true);
        when(bridgeService.proxyLlmRequest(any(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(createTestResponse()));

        // When
        client.stream(req, eventSink, taskId);

        // Then
        verify(bridgeService).proxyLlmRequest(eq(req), eq(taskId));
        verify(eventSink).onThought("Hello!");
        verify(eventSink).onStop("end_turn");
    }

    @Test
    void shouldHandleToolCalls() {
        // Given
        LlmRequest req = createTestRequest();
        String taskId = "test-task-2";
        
        BridgeLlmResponse response = new BridgeLlmResponse();
        response.text = "Let me query the database.";
        response.toolCalls = Arrays.asList(
            createToolCall("call_1", "mysql_query", Collections.singletonMap("sql", "SELECT 1"))
        );

        when(bridgeService.isBridgeActive()).thenReturn(true);
        when(bridgeService.proxyLlmRequest(any(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(response));

        // When
        client.stream(req, eventSink, taskId);

        // Then
        verify(eventSink).onToolUse("call_1", "mysql_query", Collections.singletonMap("sql", "SELECT 1"));
    }

    @Test
    void shouldCallOnErrorWhenBridgeNotActive() {
        // Given
        LlmRequest req = createTestRequest();
        when(bridgeService.isBridgeActive()).thenReturn(false);

        // When
        client.stream(req, eventSink, "task-3");

        // Then
        verify(eventSink).onError(org.mockito.ArgumentMatchers.contains("not active"));
    }

    @Test
    void shouldHandleTimeout() {
        // Given
        LlmRequest req = createTestRequest();
        CompletableFuture<BridgeLlmResponse> future = new CompletableFuture<>();
        
        when(bridgeService.isBridgeActive()).thenReturn(true);
        when(bridgeService.proxyLlmRequest(any(), anyString())).thenReturn(future);

        // When
        client.stream(req, eventSink, "task-4");

        // Then - timeout should call onError
        verify(eventSink).onError(org.mockito.ArgumentMatchers.contains("timed out"));
    }


    @Test
    void shouldHandleError() {
        // Given
        LlmRequest req = createTestRequest();
        
        when(bridgeService.isBridgeActive()).thenReturn(true);
        when(bridgeService.proxyLlmRequest(any(), anyString()))
            .thenReturn(failedFuture(new RuntimeException("LLM bridge error: LLM API error")));

        // When
        client.stream(req, eventSink, "task-5");

        // Then
        verify(eventSink).onError(org.mockito.ArgumentMatchers.contains("LLM API error"));
    }

    // ---- Helper methods ----

    private LlmRequest createTestRequest() {
        return new LlmRequest(
            "You are a helpful assistant.",
            Arrays.asList(cn.watsontech.snapagent.core.llm.Message.user("Hello")),
            Collections.emptyList(),
            "claude-sonnet-4-20250514",
            8192,
            true
        );
    }

    private BridgeLlmResponse createTestResponse() {
        BridgeLlmResponse response = new BridgeLlmResponse();
        response.text = "Hello!";
        response.toolCalls = Collections.emptyList();
        response.usage = new BridgeLlmResponse.Usage(10, 5, 0);
        return response;
    }

    private BridgeLlmResponse.ToolCall createToolCall(String id, String name, Map<String, Object> input) {
        BridgeLlmResponse.ToolCall call = new BridgeLlmResponse.ToolCall();
        call.id = id;
        call.name = name;
        call.input = input;
        return call;
    }

    private <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
