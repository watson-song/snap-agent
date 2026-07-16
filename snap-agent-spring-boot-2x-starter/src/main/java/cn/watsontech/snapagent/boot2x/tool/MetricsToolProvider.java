package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ToolProvider} implementation for querying Prometheus metrics.
 *
 * <p>Tool name: {@code metrics_query}. Supports instant queries (current value)
 * and range queries (time series over a window) via the Prometheus HTTP API.</p>
 *
 * <p>See design doc §4.1 for the contract.</p>
 */
public class MetricsToolProvider extends ObservabilityHttpClient implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MetricsToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"metrics_query\","
            + "\"description\":\"Query Prometheus metrics using PromQL. Supports instant queries (current value) and range queries (time series over a window). Use for QPS, latency (P50/P99), error rate, CPU/Memory metrics.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"PromQL expression, e.g. 'rate(http_requests_total{job=\\\"orders\\\"}[5m])' or 'histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{handler=\\\"/api/orders\\\"}[5m]))'\"},"
            + "\"start\":{\"type\":\"string\",\"description\":\"Range start. Relative ('1h' = 1 hour ago), epoch seconds, or ISO-8601. Omit for instant query (current value only).\"},"
            + "\"end\":{\"type\":\"string\",\"description\":\"Range end. Default 'now'. Only used when 'start' is provided.\",\"default\":\"now\"},"
            + "\"step\":{\"type\":\"string\",\"description\":\"Resolution step for range query (e.g. '1m', '30s'). Default '1m'.\",\"default\":\"1m\"},"
            + "\"max_points\":{\"type\":\"integer\",\"description\":\"Max data points to return per series (default 200). Truncates if exceeded.\",\"default\":200}"
            + "},"
            + "\"required\":[\"query\"]}}";

    private final SnapAgentProperties.Metrics config;

    public MetricsToolProvider(SnapAgentProperties.Metrics config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public String name() {
        return "metrics_query";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String query = extractString(args, "query");
        if (query == null || query.isEmpty()) {
            return ToolResult.error("missing required parameter: query", elapsed(start));
        }

        String startStr = extractString(args, "start");
        String endStr = extractString(args, "end");
        String step = extractString(args, "step");
        if (step == null || step.isEmpty()) {
            step = "1m";
        }
        int maxPoints = extractInt(args, "max_points", config.getMaxPoints());
        if (maxPoints <= 0 || maxPoints > config.getMaxPoints()) {
            maxPoints = config.getMaxPoints();
        }

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return ToolResult.error("failed to encode query: " + e.getMessage(), elapsed(start));
        }

        boolean isRange = startStr != null && !startStr.isEmpty();
        String url;
        try {
            if (isRange) {
                long startEpoch = TimeRangeParser.parseToEpochSeconds(startStr);
                long endEpoch = TimeRangeParser.parseToEpochSeconds(endStr);
                String encodedStep = URLEncoder.encode(step, "UTF-8");
                url = config.getBaseUrl() + "/api/v1/query_range?query=" + encodedQuery
                        + "&start=" + startEpoch + "&end=" + endEpoch + "&step=" + encodedStep;
            } else {
                url = config.getBaseUrl() + "/api/v1/query?query=" + encodedQuery;
            }
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return ToolResult.error("invalid time format or encoding: " + e.getMessage(), elapsed(start));
        }

        Map<String, String> headers = buildAuthHeaders();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        log.info("Metrics query: {} (range={}, maxPoints={})", query, isRange, maxPoints);

        try {
            String body = httpGet(url, headers, timeoutMs, timeoutMs);
            JsonNode root = parseJson(body);

            String status = root.path("status").asText();
            if (!"success".equals(status)) {
                String errorType = root.path("errorType").asText("");
                String errorMsg = root.path("error").asText("");
                return ToolResult.error("Prometheus error: " + errorType + " - " + errorMsg,
                        elapsed(start));
            }

            JsonNode resultArray = root.path("data").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                String content = "# Prometheus Query: " + query + "\n# No data points returned\n";
                return ToolResult.success(content, 0, elapsed(start));
            }

            StringBuilder seriesContent = new StringBuilder();
            int totalPoints = 0;
            boolean truncated = false;
            int seriesCount = resultArray.size();

            for (int i = 0; i < seriesCount; i++) {
                JsonNode series = resultArray.get(i);
                JsonNode metric = series.path("metric");
                JsonNode valueNode = series.path("value");
                JsonNode valuesNode = series.path("values");

                String labels = formatMetricLabels(metric);
                seriesContent.append("\n## Series ").append(i + 1).append(": ")
                        .append(labels).append("\n");

                if (valueNode.isArray() && valueNode.size() >= 2) {
                    // Instant query: single value [timestamp, "value"]
                    long ts = (long) valueNode.get(0).asDouble();
                    String val = valueNode.get(1).asText();
                    seriesContent.append("  ").append(ts).append("  ->  ")
                            .append(val).append("\n");
                    totalPoints++;
                } else if (valuesNode.isArray()) {
                    // Range query: multiple values [[timestamp, "value"], ...]
                    int points = 0;
                    for (JsonNode point : valuesNode) {
                        if (points >= maxPoints) {
                            truncated = true;
                            break;
                        }
                        if (point.isArray() && point.size() >= 2) {
                            long ts = (long) point.get(0).asDouble();
                            String val = point.get(1).asText();
                            seriesContent.append("  ").append(ts).append("  ->  ")
                                    .append(val).append("\n");
                            totalPoints++;
                            points++;
                        }
                    }
                    if (points >= maxPoints && valuesNode.size() > maxPoints) {
                        truncated = true;
                        seriesContent.append("  ... (truncated to ").append(maxPoints)
                                .append(" points)\n");
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Prometheus Query: ").append(query).append("\n");
            sb.append("# Series: ").append(seriesCount)
                    .append(" | Points: ").append(totalPoints)
                    .append(" (max ").append(maxPoints).append(")\n");
            if (isRange) {
                sb.append("# Mode: range query (step ").append(step).append(")\n");
            } else {
                sb.append("# Mode: instant query\n");
            }
            sb.append(seriesContent);

            long duration = elapsed(start);
            if (truncated) {
                return ToolResult.truncated(sb.toString(), totalPoints, duration);
            }
            return ToolResult.success(sb.toString(), totalPoints, duration);
        } catch (IOException e) {
            log.warn("Metrics query failed: {}", e.getMessage());
            return ToolResult.error("Metrics query failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String formatMetricLabels(JsonNode metric) {
        if (metric == null || !metric.isObject()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
        boolean first = true;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=\"").append(entry.getValue().asText()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> buildAuthHeaders() {
        String header = config.getAuthHeader();
        String value = config.getAuthHeaderValue();
        if (header != null && !header.isEmpty() && value != null && !value.isEmpty()) {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(header, value);
            return headers;
        }
        return null;
    }

    // ---- arg extraction helpers (same pattern as LogReadToolProvider) ----

    private String extractString(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? String.valueOf(value) : null;
    }

    private int extractInt(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
