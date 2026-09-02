package cn.watsontech.snapagent.boot2x.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MicrometerMetricsCollector")
class MicrometerMetricsCollectorTest {

    private SimpleMeterRegistry registry;
    private MicrometerMetricsCollector collector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        collector = new MicrometerMetricsCollector(registry);
    }

    @Test
    @DisplayName("recordTimer → Timer registered with correct duration")
    void shouldRecordTimer() {
        Map<String, String> tags = new HashMap<String, String>();
        tags.put("nodeName", "agent");

        collector.recordTimer("snap-agent.graph.node.duration", 500L, tags);

        Timer timer = registry.find("snap-agent.graph.node.duration")
                .tag("nodeName", "agent")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500);
    }

    @Test
    @DisplayName("recordTimer with empty tags → works without error")
    void shouldRecordTimerWithEmptyTags() {
        collector.recordTimer("snap-agent.test", 100L, Collections.<String, String>emptyMap());

        Timer timer = registry.find("snap-agent.test").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100);
    }

    @Test
    @DisplayName("incrementCounter by 1")
    void shouldIncrementCounter() {
        Map<String, String> tags = new HashMap<String, String>();
        tags.put("type", "input");

        collector.incrementCounter("snap-agent.llm.tokens", tags);

        Counter counter = registry.find("snap-agent.llm.tokens")
                .tag("type", "input")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("incrementCounter by amount")
    void shouldIncrementCounterByAmount() {
        collector.incrementCounter("snap-agent.tool.calls", 3L, Collections.<String, String>emptyMap());

        Counter counter = registry.find("snap-agent.tool.calls").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("recordError → counter with errorType tag under snap-agent.errors")
    void shouldRecordError() {
        Map<String, String> tags = new HashMap<String, String>();
        tags.put("extra", "info");

        collector.recordError("TimeoutException", tags);

        Counter counter = registry.find("snap-agent.errors")
                .tag("errorType", "TimeoutException")
                .tag("extra", "info")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordError with null tags → no error")
    void shouldRecordErrorWithNullTags() {
        collector.recordError("RuntimeException", null);

        Counter counter = registry.find("snap-agent.errors")
                .tag("errorType", "RuntimeException")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("constructor rejects null registry")
    void shouldRejectNullRegistry() {
        assertThatThrownBy(() -> new MicrometerMetricsCollector(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MeterRegistry");
    }

    @Test
    @DisplayName("multiple recordTimer calls accumulate")
    void shouldAccumulateTimerRecords() {
        Map<String, String> tags = Collections.<String, String>emptyMap();

        collector.recordTimer("snap-agent.test.timer", 100L, tags);
        collector.recordTimer("snap-agent.test.timer", 200L, tags);
        collector.recordTimer("snap-agent.test.timer", 300L, tags);

        Timer timer = registry.find("snap-agent.test.timer").timer();
        assertThat(timer.count()).isEqualTo(3);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(600);
    }
}
