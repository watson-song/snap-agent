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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolProvider} implementation for searching distributed traces in Jaeger.
 *
 * <p>Tool name: {@code trace_search}. Supports searching traces by
 * service/operation and fetching a single trace by ID. Note that Jaeger uses
 * <strong>microsecond</strong> timestamps (epoch seconds × 1_000_000).</p>
 *
 * <p>See design doc §4.3 for the contract.</p>
 */
public class TraceSearchToolProvider extends ObservabilityHttpClient implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(TraceSearchToolProvider.class);

    private static final long SLOW_THRESHOLD_MICROS = 500_000L; // 500ms

    private static final String SCHEMA = "{\"name\":\"trace_search\","
            + "\"description\":\"Search distributed traces in Jaeger. Find traces by service/operation, analyze call chains, and locate slow spans. Use for diagnosing latency, cascading timeouts, and identifying bottleneck services.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"service\":{\"type\":\"string\",\"description\":\"Service name to search (e.g. 'order-service'). Required unless trace_id is provided.\"},"
            + "\"operation\":{\"type\":\"string\",\"description\":\"Operation/span name filter (e.g. 'POST /api/orders'). Optional.\"},"
            + "\"trace_id\":{\"type\":\"string\",\"description\":\"Specific trace ID to fetch. When provided, skips search and fetches a single trace directly.\"},"
            + "\"start\":{\"type\":\"string\",\"description\":\"Range start. Relative ('1h'), epoch, or ISO-8601. Default '1h'.\",\"default\":\"1h\"},"
            + "\"end\":{\"type\":\"string\",\"description\":\"Range end. Default 'now'.\",\"default\":\"now\"},"
            + "\"limit\":{\"type\":\"integer\",\"description\":\"Max traces to return (default 20). Hard cap by config max-traces.\",\"default\":20},"
            + "\"min_duration\":{\"type\":\"string\",\"description\":\"Minimum trace duration filter (e.g. '500ms', '2s'). Optional.\"}"
            + "},"
            + "\"required\":[]}}";

    private final SnapAgentProperties.Trace config;

    public TraceSearchToolProvider(SnapAgentProperties.Trace config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public String name() {
        return "trace_search";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String traceId = extractString(args, "trace_id");
        String service = extractString(args, "service");
        String operation = extractString(args, "operation");
        String startStr = extractString(args, "start");
        if (startStr == null || startStr.isEmpty()) {
            startStr = "1h";
        }
        String endStr = extractString(args, "end");
        String minDuration = extractString(args, "min_duration");
        int limit = extractInt(args, "limit", config.getMaxTraces());
        if (limit <= 0 || limit > config.getMaxTraces()) {
            limit = config.getMaxTraces();
        }

        String url;
        try {
            if (traceId != null && !traceId.isEmpty()) {
                // Single trace fetch
                url = config.getBaseUrl() + "/api/traces/" + URLEncoder.encode(traceId, "UTF-8");
            } else if (service != null && !service.isEmpty()) {
                // Search by service
                long startEpoch = TimeRangeParser.parseToEpochSeconds(startStr);
                long endEpoch = TimeRangeParser.parseToEpochSeconds(endStr);
                // Jaeger uses microsecond timestamps
                long startUs = startEpoch * 1_000_000L;
                long endUs = endEpoch * 1_000_000L;

                StringBuilder urlBuilder = new StringBuilder(config.getBaseUrl())
                        .append("/api/traces?service=").append(URLEncoder.encode(service, "UTF-8"))
                        .append("&start=").append(startUs)
                        .append("&end=").append(endUs)
                        .append("&limit=").append(limit);
                if (operation != null && !operation.isEmpty()) {
                    urlBuilder.append("&operation=").append(URLEncoder.encode(operation, "UTF-8"));
                }
                if (minDuration != null && !minDuration.isEmpty()) {
                    urlBuilder.append("&minDuration=").append(URLEncoder.encode(minDuration, "UTF-8"));
                }
                url = urlBuilder.toString();
            } else {
                return ToolResult.error(
                        "either 'service' or 'trace_id' must be provided", elapsed(start));
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("invalid time format: " + e.getMessage(), elapsed(start));
        } catch (UnsupportedEncodingException e) {
            return ToolResult.error("failed to encode parameter: " + e.getMessage(), elapsed(start));
        }

        Map<String, String> headers = buildAuthHeaders();
        int timeoutMs = config.getTimeoutSeconds() * 1000;

        log.info("Trace search: service={}, trace_id={}, limit={}", service, traceId, limit);

        try {
            String body = httpGet(url, headers, timeoutMs, timeoutMs);
            JsonNode root = parseJson(body);

            JsonNode dataArray;
            if (traceId != null && !traceId.isEmpty()) {
                // Single trace: response is the trace object directly, wrapped in data array
                dataArray = root.path("data");
                if (!dataArray.isArray()) {
                    // Some Jaeger versions return the trace directly
                    dataArray = root;
                }
            } else {
                dataArray = root.path("data");
            }

            if (!dataArray.isArray() || dataArray.size() == 0) {
                String content = "# Jaeger Trace Search: ";
                if (traceId != null) {
                    content += "trace_id=" + traceId;
                } else {
                    content += "service=" + service + ", limit=" + limit;
                }
                content += "\n# No traces found\n";
                return ToolResult.success(content, 0, elapsed(start));
            }

            boolean truncated = false;
            int traceCount = dataArray.size();
            if (traceCount > limit) {
                truncated = true;
                traceCount = limit;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Jaeger Trace Search: ");
            if (traceId != null) {
                sb.append("trace_id=").append(traceId);
            } else {
                sb.append("service=").append(service);
                if (operation != null) {
                    sb.append(", operation=").append(operation);
                }
                sb.append(", limit=").append(limit);
            }
            sb.append("\n");
            sb.append("# Traces: ").append(traceCount).append("\n");

            for (int i = 0; i < traceCount; i++) {
                JsonNode trace = dataArray.get(i);
                String traceID = trace.path("traceID").asText("unknown");
                JsonNode spans = trace.path("spans");
                JsonNode processes = trace.path("processes");

                // Compute total trace duration (max span end - min span start)
                long minStart = Long.MAX_VALUE;
                long maxEnd = Long.MIN_VALUE;
                for (JsonNode span : spans) {
                    long spanStart = span.path("startTime").asLong();
                    long spanDur = span.path("duration").asLong();
                    if (spanStart < minStart) {
                        minStart = spanStart;
                    }
                    long end = spanStart + spanDur;
                    if (end > maxEnd) {
                        maxEnd = end;
                    }
                }
                long totalDurationUs = (minStart == Long.MAX_VALUE) ? 0 : (maxEnd - minStart);

                sb.append("\n## Trace ").append(traceID)
                        .append(" (total: ").append(formatDuration(totalDurationUs)).append(")\n");

                formatSpanTree(spans, processes, sb);
            }

            long duration = elapsed(start);
            if (truncated) {
                return ToolResult.truncated(sb.toString(), traceCount, duration);
            }
            return ToolResult.success(sb.toString(), traceCount, duration);
        } catch (IOException e) {
            log.warn("Trace search failed: {}", e.getMessage());
            return ToolResult.error("Trace search failed: " + e.getMessage(), elapsed(start));
        }
    }

    private void formatSpanTree(JsonNode spans, JsonNode processes, StringBuilder sb) {
        // Build span ID -> span map
        Map<String, JsonNode> spanMap = new HashMap<String, JsonNode>();
        // Build parent -> children map
        Map<String, List<String>> childrenMap = new HashMap<String, List<String>>();
        // Track root spans (no parent in this trace)
        Set<String> allSpanIds = new HashSet<String>();
        Set<String> childSpanIds = new HashSet<String>();

        for (JsonNode span : spans) {
            String spanID = span.path("spanID").asText();
            spanMap.put(spanID, span);
            allSpanIds.add(spanID);
        }

        for (JsonNode span : spans) {
            String spanID = span.path("spanID").asText();
            JsonNode refs = span.path("references");
            String parentID = null;
            if (refs.isArray()) {
                for (JsonNode ref : refs) {
                    String refType = ref.path("refType").asText();
                    if ("CHILD_OF".equals(refType)) {
                        parentID = ref.path("spanID").asText();
                        break;
                    }
                }
            }
            if (parentID != null && spanMap.containsKey(parentID)) {
                childSpanIds.add(spanID);
                List<String> children = childrenMap.get(parentID);
                if (children == null) {
                    children = new ArrayList<String>();
                    childrenMap.put(parentID, children);
                }
                children.add(spanID);
            }
        }

        // Root spans = all spans - child spans
        List<String> rootSpans = new ArrayList<String>();
        for (String id : allSpanIds) {
            if (!childSpanIds.contains(id)) {
                rootSpans.add(id);
            }
        }

        // Render tree
        for (String rootId : rootSpans) {
            renderSpan(rootId, spanMap, childrenMap, processes, sb, 0);
        }
    }

    private void renderSpan(String spanId, Map<String, JsonNode> spanMap,
                            Map<String, List<String>> childrenMap,
                            JsonNode processes, StringBuilder sb, int depth) {
        JsonNode span = spanMap.get(spanId);
        if (span == null) {
            return;
        }

        String operationName = span.path("operationName").asText("unknown");
        long durationUs = span.path("duration").asLong();
        String processID = span.path("processID").asText("");
        String serviceName = getServiceName(processes, processID);

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        sb.append(indent).append(operationName)
                .append("  [").append(formatDuration(durationUs)).append("]  ")
                .append(serviceName);

        if (durationUs > SLOW_THRESHOLD_MICROS) {
            sb.append("  ⚠ SLOW");
        }
        sb.append("\n");

        List<String> children = childrenMap.get(spanId);
        if (children != null) {
            for (String childId : children) {
                renderSpan(childId, spanMap, childrenMap, processes, sb, depth + 1);
            }
        }
    }

    private String getServiceName(JsonNode processes, String processID) {
        if (processes == null || processID == null || processID.isEmpty()) {
            return "unknown";
        }
        JsonNode proc = processes.path(processID);
        return proc.path("serviceName").asText("unknown");
    }

    private String formatDuration(long micros) {
        long millis = micros / 1000;
        if (millis >= 1000) {
            double seconds = millis / 1000.0;
            return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
        }
        return millis + "ms";
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
