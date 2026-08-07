package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.boot2x.bridge.BridgeLlmResponse;
import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmBridgeControllerTest {

    @Mock
    private LlmBridgeService llmBridgeService;

    @Test
    void shouldHandleLlmResult() {
        // Given
        LlmBridgeController controller = new LlmBridgeController(llmBridgeService);
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "task-1");
        payload.put("text", "Hello!");

        // When
        ResponseEntity<?> response = controller.handleResult(payload);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(llmBridgeService).handleResult(eq("task-1"), any(BridgeLlmResponse.class));
    }

    @Test
    void shouldHandleLlmError() {
        // Given
        LlmBridgeController controller = new LlmBridgeController(llmBridgeService);
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "task-2");
        payload.put("error", "API error");

        // When
        ResponseEntity<?> response = controller.handleError(payload);

        // Then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(llmBridgeService).handleError("task-2", "API error");
    }

    @Test
    void shouldGetBridgeStatus() {
        // Given
        LlmBridgeController controller = new LlmBridgeController(llmBridgeService);
        when(llmBridgeService.isBridgeActive()).thenReturn(true);
        when(llmBridgeService.getPendingCount()).thenReturn(2);

        // When
        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        // Then
        assertThat(response.getBody().get("active")).isEqualTo(true);
        assertThat(response.getBody().get("pending")).isEqualTo(2);
    }
}
