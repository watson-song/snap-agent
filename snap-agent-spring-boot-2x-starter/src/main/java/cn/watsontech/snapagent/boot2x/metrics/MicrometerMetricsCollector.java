package cn.watsontech.snapagent.boot2x.metrics;

import cn.watsontech.snapagent.core.metrics.MetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed implementation of {@link MetricsCollector}.
 *
 * <p>Translates SnapAgent metrics calls into Micrometer primitives:</p>
 * <ul>
 *   <li>{@code recordTimer} → {@link Timer#record(long, TimeUnit)}</li>
 *   <li>{@code incrementCounter} → {@link Counter#increment(double)}</li>
 *   <li>{@code recordError} → counter with {@code error.errorType} tag</li>
 * </ul>
 *
 * <p>Thread-safe — delegates to Micrometer's concurrent meter registries.</p>
 */
public class MicrometerMetricsCollector implements MetricsCollector {

    private final MeterRegistry registry;

    public MicrometerMetricsCollector(MeterRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("MeterRegistry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public void recordTimer(String name, long durationMs, Map<String, String> tags) {
        registry.timer(name, toTags(tags))
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        registry.counter(name, toTags(tags)).increment();
    }

    @Override
    public void incrementCounter(String name, long amount, Map<String, String> tags) {
        registry.counter(name, toTags(tags)).increment(amount);
    }

    @Override
    public void recordError(String errorType, Map<String, String> tags) {
        List<Tag> errorTags = new ArrayList<Tag>();
        errorTags.add(Tag.of("errorType", errorType));
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                errorTags.add(Tag.of(entry.getKey(), entry.getValue()));
            }
        }
        registry.counter("snap-agent.errors", errorTags).increment();
    }

    private Iterable<Tag> toTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Tags.empty();
        }
        List<Tag> result = new ArrayList<Tag>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            result.add(Tag.of(entry.getKey(), entry.getValue()));
        }
        return result;
    }
}
