package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostTracker;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CostTrackingLlmClient}.
 */
class CostTrackingLlmClientTest {

    private LlmClient delegate;
    private CostTracker costTracker;
    private CostTrackingLlmClient trackingClient;

    @BeforeEach
    void setUp() {
        delegate = mock(LlmClient.class);
        costTracker = mock(CostTracker.class);
        trackingClient = new CostTrackingLlmClient(delegate, costTracker,
                new BigDecimal("3.00"), new BigDecimal("15.00"),
                new BigDecimal("0.30"), "default-user", "default-skill");
    }

    private LlmRequest newRequest() {
        return new LlmRequest("system prompt",
                Collections.emptyList(), Collections.emptyList(),
                "claude-sonnet-4-6", 8192, true);
    }

    @Test
    void shouldRecordCostWhenUsageIsReported() {
        // Simulate delegate calling onUsage + onStop when stream() is invoked
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onUsage(1000, 500, 200);
            sink.onStop("end_turn");
            return null;
        }).when(delegate).stream(any(), any(), any());

        trackingClient.stream(newRequest(), mock(LlmEventSink.class), "task-123");

        ArgumentCaptor<CostRecord> captor = ArgumentCaptor.forClass(CostRecord.class);
        verify(costTracker).record(captor.capture());
        CostRecord record = captor.getValue();
        assertThat(record.getUserId()).isEqualTo("default-user");
        assertThat(record.getSkillName()).isEqualTo("default-skill");
        assertThat(record.getTaskId()).isEqualTo("task-123");
        assertThat(record.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(record.getInputTokens()).isEqualTo(1000);
        assertThat(record.getOutputTokens()).isEqualTo(500);
        assertThat(record.getCacheReadTokens()).isEqualTo(200);
        // cost = (1000 * 3 + 500 * 15 + 200 * 0.3) / 1M = (3000 + 7500 + 60) / 1M = 0.01056
        assertThat(record.getCost()).isCloseTo(new BigDecimal("0.010560"), within(new BigDecimal("0.000001")));
    }

    @Test
    void shouldNotRecordCostWhenUsageNotReported() {
        // Simulate delegate only calling onStop (no usage info)
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onStop("end_turn");
            return null;
        }).when(delegate).stream(any(), any(), any());

        trackingClient.stream(newRequest(), mock(LlmEventSink.class), "task-456");

        verify(costTracker, never()).record(any());
    }

    @Test
    void shouldDelegateCancelToDelegate() {
        trackingClient.cancel("task-789");
        verify(delegate).cancel("task-789");
    }

    @Test
    void shouldDelegateListModelsToDelegate() {
        when(delegate.listModels()).thenReturn(java.util.Arrays.asList("model-a", "model-b"));
        java.util.List<String> models = trackingClient.listModels();
        assertThat(models).containsExactly("model-a", "model-b");
        verify(delegate).listModels();
    }

    @Test
    void shouldForwardEventsToDelegateSink() {
        LlmEventSink realSink = mock(LlmEventSink.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("hello");
            sink.onUsage(100, 50, 0);
            sink.onStop("end_turn");
            return null;
        }).when(delegate).stream(any(), any(), any());

        trackingClient.stream(newRequest(), realSink, "task-fwd");

        // Verify events were forwarded to the real sink
        verify(realSink).onThought("hello");
        verify(realSink).onUsage(100, 50, 0);
        verify(realSink).onStop("end_turn");
        // Cost should be recorded
        verify(costTracker).record(any(CostRecord.class));
    }

    private static org.assertj.core.data.Offset<BigDecimal> within(BigDecimal tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
