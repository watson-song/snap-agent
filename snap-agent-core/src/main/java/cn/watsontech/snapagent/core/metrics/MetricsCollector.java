package cn.watsontech.snapagent.core.metrics;

import java.util.Map;

/**
 * SPI for collecting metrics (timers, counters).
 * The starter module provides a Micrometer-based implementation.
 * When null/unavailable, advisors noop.
 */
public interface MetricsCollector {

    void recordTimer(String name, long durationMs, Map<String, String> tags);

    void incrementCounter(String name, Map<String, String> tags);

    void incrementCounter(String name, long amount, Map<String, String> tags);

    void recordError(String errorType, Map<String, String> tags);
}
