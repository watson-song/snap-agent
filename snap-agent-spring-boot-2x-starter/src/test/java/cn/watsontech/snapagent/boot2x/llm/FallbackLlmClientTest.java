package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FallbackLlmClient} bridge-first fallback logic.
 */
class FallbackLlmClientTest {

    @Test
    void usesBridgeWhenBridgeIsActive() {
        LlmBridgeService bridgeService = mock(LlmBridgeService.class);
        when(bridgeService.isBridgeActive()).thenReturn(true);

        BridgeLlmClient bridgeClient = mock(BridgeLlmClient.class);
        LlmClient primaryClient = mock(LlmClient.class);

        FallbackLlmClient client = new FallbackLlmClient(primaryClient, bridgeClient, bridgeService);

        LlmRequest req = new LlmRequest("test", Collections.singletonList(new Message("user", "hello", null)), null, "model", 1000, true);
        LlmEventSink sink = mock(LlmEventSink.class);

        client.stream(req, sink, "task-1");

        // Should use bridge, not primary
        verify(bridgeClient).stream(req, sink, "task-1");
        verify(primaryClient, never()).stream(any(), any(), any());
    }

    @Test
    void fallsBackToPrimaryWhenBridgeIsNotActive() {
        LlmBridgeService bridgeService = mock(LlmBridgeService.class);
        when(bridgeService.isBridgeActive()).thenReturn(false);

        BridgeLlmClient bridgeClient = mock(BridgeLlmClient.class);
        LlmClient primaryClient = mock(LlmClient.class);

        FallbackLlmClient client = new FallbackLlmClient(primaryClient, bridgeClient, bridgeService);

        LlmRequest req = new LlmRequest("test", Collections.singletonList(new Message("user", "hello", null)), null, "model", 1000, true);
        LlmEventSink sink = mock(LlmEventSink.class);

        client.stream(req, sink, "task-1");

        // Should use primary, not bridge
        verify(primaryClient).stream(req, sink, "task-1");
        verify(bridgeClient, never()).stream(any(), any(), any());
    }

    @Test
    void reportsErrorWhenNoClientAvailable() {
        LlmBridgeService bridgeService = mock(LlmBridgeService.class);
        when(bridgeService.isBridgeActive()).thenReturn(false);

        FallbackLlmClient client = new FallbackLlmClient(null, null, bridgeService);

        LlmRequest req = new LlmRequest("test", Collections.singletonList(new Message("user", "hello", null)), null, "model", 1000, true);
        LlmEventSink sink = mock(LlmEventSink.class);

        client.stream(req, sink, "task-1");

        // Should report error
        verify(sink).onError(contains("No LLM client available"));
    }
}
