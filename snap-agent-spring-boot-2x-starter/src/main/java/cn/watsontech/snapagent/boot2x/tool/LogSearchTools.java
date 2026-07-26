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
 * 2.x log search tools exposed via {@code @Tool} annotation methods.
 *
 * <p>Refactored from the 1.x {@code LogSearchToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI) to a {@code @Tool} method discovered by
 * {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.</p>
 *
 * <p>Tool name: {@code log_search}. Queries Loki's LogQL via the
 * {@code /loki/api/v1/query_range} endpoint. Note that Loki uses
 * <strong>nanosecond</strong> timestamps (epoch seconds &times; 1_000_000_000).</p>
 *
 * <p>See design doc &sect;4.2 for the contract.</p>
 */
public class LogSearchTools extends ObservabilityHttpClient {

    private static final Logger log = LoggerFactory.getLogger(LogSearchTools.class);

    private final SnapAgentProperties.LogSearch config;

    public LogSearchTools(SnapAgentProperties.LogSearch config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Tool(name = "log_search", description = "Search application logs in Loki using LogQL. Returns matching log lines within a time window. Use for error pattern analysis, frequency counting, and root cause investigation.")
    public String search(
            @ToolParam(description = "LogQL expression, e.g. '{job=\"orders\"} |= \"error\" | json | line_format \"{{.msg}}\"'") String query,
            @ToolParam(description = "Time range start. Relative ('1h'), epoch seconds, or ISO-8601. Default '1h'.", required = false) String start,
            @ToolParam(description = "Time range end. Default 'now'.", required = false) String end,
            @ToolParam(description = "Max log lines to return (default 500). Hard cap by config max-lines.", required = false) Integer limit,
            @ToolParam(description = "Log direction. 'forward' or 'backward' (default 'backward' = newest first).", required = false) String direction) {

        if (query == null || query.isEmpty()) {
            return "Error: missing required parameter: query";
        }

        String startStr = (start == null || start.isEmpty()) ? "1h" : start;
        // null/empty end -> now (TimeRangeParser handles this)
        String endStr = end;
        String directionStr = (direction == null || direction.isEmpty()) ? "backward" : direction;
        int limitVal = limit != null ? limit : config.getMaxLines();
        if (limitVal <= 0 || limitVal > config.getMaxLines()) {
            limitVal = config.getMaxLines();
        }

        long startEpoch;
        long endEpoch;
        try {
            startEpoch = TimeRangeParser.parseToEpochSeconds(startStr);
            endEpoch = TimeRangeParser.parseToEpochSeconds(endStr);
        } catch (IllegalArgumentException e) {
            return "Error: invalid time format: " + e.getMessage();
        }

        // Loki uses nanosecond timestamps
        long startNs = startEpoch * 1_000_000_000L;
        long endNs = endEpoch * 1_000_000_000L;

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "Error: failed to encode query: " + e.getMessage();
        }

        String url = config.getBaseUrl() + "/loki/api/v1/query_range?query=" + encodedQuery
                + "&start=" + startNs + "&end=" + endNs
                + "&limit=" + limitVal + "&direction=" + directionStr;

        Map<String, String> headers = buildAuthHeaders();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        log.info("Log search: {} (limit={}, direction={})", query, limitVal, directionStr);

        try {
            String body = httpGet(url, headers, timeoutMs, timeoutMs);
            JsonNode root = parseJson(body);

            String status = root.path("status").asText();
            if (!"success".equals(status)) {
                String errorMsg = root.path("error").asText("");
                return "Error: Loki error: " + errorMsg;
            }

            JsonNode resultArray = root.path("data").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                return "# Loki Query: " + query + "\n# No log lines returned\n";
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
                        if (totalLines >= limitVal) {
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
                    .append(" (limit ").append(limitVal).append(", ").append(directionStr).append(")\n");
            sb.append(streamContent);

            return sb.toString();
        } catch (IOException e) {
            log.warn("Log search failed: {}", e.getMessage());
            return "Error: Log search failed: " + e.getMessage();
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
}
