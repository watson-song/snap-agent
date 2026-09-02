package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
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
 * 2.x Prometheus metrics query tool exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code MetricsToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to a single {@code @Tool} method
 * discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 *
 * <p>Tool name: {@code metrics_query}. Supports instant queries (current value)
 * and range queries (time series over a window) via the Prometheus HTTP API.</p>
 *
 * <p>Extends {@link ObservabilityHttpClient} for shared HTTP/JSON helpers
 * (this is a starter class, not a core shim).</p>
 */
public class MetricsTools extends ObservabilityHttpClient {

    private static final Logger log = LoggerFactory.getLogger(MetricsTools.class);

    private final SnapAgentProperties.Metrics config;

    public MetricsTools(SnapAgentProperties.Metrics config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Tool(name = "metrics_query", description = "Query Prometheus metrics using PromQL. Supports instant queries (current value) and range queries (time series over a window). Use for QPS, latency (P50/P99), error rate, CPU/Memory metrics.")
    public String query(
            @ToolParam(description = "PromQL expression, e.g. 'rate(http_requests_total{job=\"orders\"}[5m])' or 'histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{handler=\"/api/orders\"}[5m]))'") String query,
            @ToolParam(description = "Range start. Relative ('1h' = 1 hour ago), epoch seconds, or ISO-8601. Omit for instant query (current value only).", required = false) String start,
            @ToolParam(description = "Range end. Default 'now'. Only used when 'start' is provided.", required = false) String end,
            @ToolParam(description = "Resolution step for range query (e.g. '1m', '30s'). Default '1m'.", required = false) String step,
            @ToolParam(description = "Max data points to return per series (default 200). Truncates if exceeded.", required = false) Integer max_points) {

        if (query == null || query.isEmpty()) {
            return "Error: missing required parameter: query";
        }

        String endStr = end != null && !end.isEmpty() ? end : "now";
        String stepStr = step != null && !step.isEmpty() ? step : "1m";
        int maxPoints = max_points != null ? max_points : config.getMaxPoints();
        if (maxPoints <= 0 || maxPoints > config.getMaxPoints()) {
            maxPoints = config.getMaxPoints();
        }

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "Error: failed to encode query: " + e.getMessage();
        }

        boolean isRange = start != null && !start.isEmpty();
        String url;
        try {
            if (isRange) {
                long startEpoch = TimeRangeParser.parseToEpochSeconds(start);
                long endEpoch = TimeRangeParser.parseToEpochSeconds(endStr);
                String encodedStep = URLEncoder.encode(stepStr, "UTF-8");
                url = config.getBaseUrl() + "/api/v1/query_range?query=" + encodedQuery
                        + "&start=" + startEpoch + "&end=" + endEpoch + "&step=" + encodedStep;
            } else {
                url = config.getBaseUrl() + "/api/v1/query?query=" + encodedQuery;
            }
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return "Error: invalid time format or encoding: " + e.getMessage();
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
                return "Error: Prometheus error: " + errorType + " - " + errorMsg;
            }

            JsonNode resultArray = root.path("data").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                return "# Prometheus Query: " + query + "\n# No data points returned\n";
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
                sb.append("# Mode: range query (step ").append(stepStr).append(")\n");
            } else {
                sb.append("# Mode: instant query\n");
            }
            sb.append(seriesContent);
            if (truncated) {
                sb.append("\n# (output truncated to ").append(maxPoints)
                        .append(" points per series)\n");
            }

            return sb.toString();
        } catch (IOException e) {
            log.warn("Metrics query failed: {}", e.getMessage());
            return "Error: Metrics query failed: " + e.getMessage();
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
}
