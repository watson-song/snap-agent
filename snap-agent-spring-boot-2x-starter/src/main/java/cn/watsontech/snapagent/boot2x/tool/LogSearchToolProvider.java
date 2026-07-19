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
 * {@link ToolProvider} implementation for searching logs in Loki.
 *
 * <p>Tool name: {@code log_search}. Queries Loki's LogQL via the
 * {@code /loki/api/v1/query_range} endpoint. Note that Loki uses
 * <strong>nanosecond</strong> timestamps (epoch seconds × 1_000_000_000).</p>
 *
 * <p>See design doc §4.2 for the contract.</p>
 */
public class LogSearchToolProvider extends ObservabilityHttpClient implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(LogSearchToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"log_search\","
            + "\"description\":\"Search application logs in Loki using LogQL. Returns matching log lines within a time window. Use for error pattern analysis, frequency counting, and root cause investigation.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"LogQL expression, e.g. '{job=\\\"orders\\\"} |= \\\"error\\\" | json | line_format \\\"{{.msg}}\\\"'\"},"
            + "\"start\":{\"type\":\"string\",\"description\":\"Time range start. Relative ('1h'), epoch seconds, or ISO-8601. Default '1h'.\",\"default\":\"1h\"},"
            + "\"end\":{\"type\":\"string\",\"description\":\"Time range end. Default 'now'.\",\"default\":\"now\"},"
            + "\"limit\":{\"type\":\"integer\",\"description\":\"Max log lines to return (default 500). Hard cap by config max-lines.\",\"default\":500},"
            + "\"direction\":{\"type\":\"string\",\"enum\":[\"forward\",\"backward\"],\"description\":\"Log direction. 'backward' = newest first (default).\",\"default\":\"backward\"}"
            + "},"
            + "\"required\":[\"query\"]}}";

    private final SnapAgentProperties.LogSearch config;

    public LogSearchToolProvider(SnapAgentProperties.LogSearch config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public String name() {
        return "log_search";
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
        if (startStr == null || startStr.isEmpty()) {
            startStr = "1h";
        }
        String endStr = extractString(args, "end");
        // null/empty → now (TimeRangeParser handles this)
        String direction = extractString(args, "direction");
        if (direction == null || direction.isEmpty()) {
            direction = "backward";
        }
        int limit = extractInt(args, "limit", config.getMaxLines());
        if (limit <= 0 || limit > config.getMaxLines()) {
            limit = config.getMaxLines();
        }

        long startEpoch;
        long endEpoch;
        try {
            startEpoch = TimeRangeParser.parseToEpochSeconds(startStr);
            endEpoch = TimeRangeParser.parseToEpochSeconds(endStr);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("invalid time format: " + e.getMessage(), elapsed(start));
        }

        // Loki uses nanosecond timestamps
        long startNs = startEpoch * 1_000_000_000L;
        long endNs = endEpoch * 1_000_000_000L;

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return ToolResult.error("failed to encode query: " + e.getMessage(), elapsed(start));
        }

        String url = config.getBaseUrl() + "/loki/api/v1/query_range?query=" + encodedQuery
                + "&start=" + startNs + "&end=" + endNs
                + "&limit=" + limit + "&direction=" + direction;

        Map<String, String> headers = buildAuthHeaders();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        log.info("Log search: {} (limit={}, direction={})", query, limit, direction);

        try {
            String body = httpGet(url, headers, timeoutMs, timeoutMs);
            JsonNode root = parseJson(body);

            String status = root.path("status").asText();
            if (!"success".equals(status)) {
                String errorMsg = root.path("error").asText("");
                return ToolResult.error("Loki error: " + errorMsg, elapsed(start));
            }

            JsonNode resultArray = root.path("data").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                String content = "# Loki Query: " + query + "\n# No log lines returned\n";
                return ToolResult.success(content, 0, elapsed(start));
            }

            StringBuilder streamContent = new StringBuilder();
            int totalLines = 0;
            int streamCount = resultArray.size();

            for (int i = 0; i < streamCount; i++) {
                JsonNode stream = resultArray.get(i);
                JsonNode streamLabels = stream.path("stream");
                JsonNode values = stream.path("values");

                String labels = formatStreamLabels(streamLabels);
                streamContent.append("\n## Stream: ").append(labels).append("\n");

                if (values.isArray()) {
                    for (JsonNode entry : values) {
                        if (totalLines >= limit) {
                            break;
                        }
                        if (entry.isArray() && entry.size() >= 2) {
                            String nsStr = entry.get(0).asText();
                            String logLine = entry.get(1).asText();
                            String timeStr = formatNanosToEpoch(nsStr);
                            streamContent.append("  ").append(timeStr).append("  ")
                                    .append(logLine).append("\n");
                            totalLines++;
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Loki Query: ").append(query).append("\n");
            sb.append("# Streams: ").append(streamCount)
                    .append(" | Lines: ").append(totalLines)
                    .append(" (limit ").append(limit).append(", ").append(direction).append(")\n");
            sb.append(streamContent);

            long duration = elapsed(start);
            if (totalLines >= limit) {
                return ToolResult.truncated(sb.toString(), totalLines, duration);
            }
            return ToolResult.success(sb.toString(), totalLines, duration);
        } catch (IOException e) {
            log.warn("Log search failed: {}", e.getMessage());
            return ToolResult.error("Log search failed: " + e.getMessage(), elapsed(start));
        }
    }

    private String formatNanosToEpoch(String nsStr) {
        try {
            long ns = Long.parseLong(nsStr);
            long epochSeconds = ns / 1_000_000_000L;
            return String.valueOf(epochSeconds);
        } catch (NumberFormatException e) {
            return nsStr;
        }
    }

    private String formatStreamLabels(JsonNode stream) {
        if (stream == null || !stream.isObject()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        Iterator<Map.Entry<String, JsonNode>> fields = stream.fields();
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
