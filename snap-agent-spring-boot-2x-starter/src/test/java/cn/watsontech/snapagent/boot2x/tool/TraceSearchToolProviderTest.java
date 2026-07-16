package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TraceSearchToolProvider}.
 *
 * <p>Uses the subclass-and-override-httpGet pattern (design doc §8.1) to inject
 * mock Jaeger JSON responses without a real HTTP backend.</p>
 *
 * <p>See design doc §8.2 for the test matrix.</p>
 */
class TraceSearchToolProviderTest {

    private SnapAgentProperties.Trace config;

    @BeforeEach
    void setUp() {
        config = new SnapAgentProperties.Trace();
        config.setBaseUrl("http://jaeger:16686");
        config.setMaxTraces(20);
        config.setTimeoutSeconds(15);
    }

    // ---- identity ----

    @Test
    @DisplayName("name() returns 'trace_search'")
    void shouldReturnNameTraceSearch() {
        TraceSearchToolProvider provider = new TraceSearchToolProvider(config);
        assertThat(provider.name()).isEqualTo("trace_search");
    }

    @Test
    @DisplayName("schema() contains service and trace_id properties")
    void shouldReturnSchemaWithServiceAndTraceId() {
        TraceSearchToolProvider provider = new TraceSearchToolProvider(config);
        String schema = provider.schema();

        assertThat(schema).contains("trace_search");
        assertThat(schema).contains("\"service\"");
        assertThat(schema).contains("\"trace_id\"");
    }

    // ---- search by service ----

    @Test
    @DisplayName("search by service calls /api/traces with service and microsecond timestamps")
    void shouldSearchByServiceSuccessfully() {
        String mockResponse = buildTraceResponse("abc123", "order-service", "POST /api/orders");

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        args.put("start", "1689700000");
        args.put("end", "1689700060");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getContent()).contains("abc123");
        assertThat(result.getContent()).contains("POST /api/orders");
        assertThat(provider.capturedUrl).contains("/api/traces");
        assertThat(provider.capturedUrl).contains("service=order-service");
        // Verify microsecond timestamps: 1689700000 * 1_000_000 = 1689700000000000
        assertThat(provider.capturedUrl).contains("start=1689700000000000");
        assertThat(provider.capturedUrl).contains("end=1689700060000000");
    }

    // ---- fetch single trace by ID ----

    @Test
    @DisplayName("trace_id fetch calls /api/traces/{traceId} without search params")
    void shouldFetchSingleTraceById() {
        String mockResponse = "{\"data\":[" + buildTraceJson("xyz789",
                "payment-service", "POST /pay") + "]}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("trace_id", "xyz789");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("xyz789");
        assertThat(provider.capturedUrl).contains("/api/traces/xyz789");
        assertThat(provider.capturedUrl).doesNotContain("service=");
    }

    // ---- min_duration filter ----

    @Test
    @DisplayName("min_duration parameter is passed through to the URL")
    void shouldPassMinDurationFilter() {
        String mockResponse = "{\"data\":[]}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        args.put("min_duration", "500ms");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(provider.capturedUrl).contains("minDuration=500ms");
    }

    // ---- operation filter ----

    @Test
    @DisplayName("operation parameter is passed through to the URL")
    void shouldPassOperationFilter() {
        String mockResponse = "{\"data\":[]}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        args.put("operation", "POST /api/orders");
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("operation=POST");
    }

    // ---- missing service and trace_id ----

    @Test
    @DisplayName("neither service nor trace_id provided returns error")
    void shouldReturnErrorWhenNeitherServiceNorTraceId() {
        TraceSearchToolProvider provider = new TraceSearchToolProvider(config);

        Map<String, Object> args = new HashMap<String, Object>();
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("service");
        assertThat(result.getError()).contains("trace_id");
    }

    // ---- max-traces clamp ----

    @Test
    @DisplayName("user-supplied limit exceeding config max is clamped down")
    void shouldClampLimitToConfigMaxTraces() {
        config.setMaxTraces(5);
        String mockResponse = "{\"data\":[]}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        args.put("limit", 100);
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("limit=5");
    }

    // ---- span tree formatting ----

    @Test
    @DisplayName("span tree is rendered with indentation reflecting parent-child hierarchy")
    void shouldFormatSpanTree() {
        // Root span + one child span (CHILD_OF reference)
        String traceJson = "{"
                + "\"traceID\":\"abc123\","
                + "\"spans\":["
                + "{\"spanID\":\"span1\",\"operationName\":\"POST /api/orders\","
                + "\"duration\":3200000,\"startTime\":1689700000000000,\"processID\":\"p1\","
                + "\"references\":[]},"
                + "{\"spanID\":\"span2\",\"operationName\":\"InventoryService.check\","
                + "\"duration\":100000,\"startTime\":16897000001000000,\"processID\":\"p2\","
                + "\"references\":[{\"refType\":\"CHILD_OF\",\"traceID\":\"abc123\",\"spanID\":\"span1\"}]}"
                + "],"
                + "\"processes\":{"
                + "\"p1\":{\"serviceName\":\"order-service\",\"tags\":[]},"
                + "\"p2\":{\"serviceName\":\"inventory-service\",\"tags\":[]}"
                + "}"
                + "}";
        String mockResponse = "{\"data\":[" + traceJson + "]}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("POST /api/orders");
        assertThat(result.getContent()).contains("InventoryService.check");
        assertThat(result.getContent()).contains("order-service");
        assertThat(result.getContent()).contains("inventory-service");
        // Child span should be indented more than root
        String content = result.getContent();
        int rootIdx = content.indexOf("POST /api/orders");
        int childIdx = content.indexOf("InventoryService.check");
        assertThat(childIdx).isGreaterThan(rootIdx);
        // Verify child line has more leading spaces than root
        String rootLine = content.substring(content.lastIndexOf('\n', rootIdx) + 1, rootIdx);
        String childLine = content.substring(content.lastIndexOf('\n', childIdx) + 1, childIdx);
        assertThat(childLine.length() - childLine.trim().length())
                .isGreaterThan(rootLine.length() - rootLine.trim().length());
    }

    // ---- slow span marking ----

    @Test
    @DisplayName("spans with duration > 500ms are marked with SLOW")
    void shouldMarkSlowSpanssWithSLOW() {
        // Duration 600000 microseconds = 600ms > 500ms threshold
        String traceJson = "{"
                + "\"traceID\":\"slow123\","
                + "\"spans\":["
                + "{\"spanID\":\"s1\",\"operationName\":\"slow-op\","
                + "\"duration\":600000,\"startTime\":1689700000000000,\"processID\":\"p1\","
                + "\"references\":[]},"
                + "{\"spanID\":\"s2\",\"operationName\":\"fast-op\","
                + "\"duration\":100000,\"startTime\":1689700000000000,\"processID\":\"p1\","
                + "\"references\":[]}"
                + "],"
                + "\"processes\":{\"p1\":{\"serviceName\":\"svc\",\"tags\":[]}}"
                + "}";
        String mockResponse = "{\"data\":[" + traceJson + "]}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "svc");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("slow-op");
        assertThat(result.getContent()).contains("SLOW");
        assertThat(result.getContent()).contains("fast-op");
        // The fast span should NOT be marked SLOW
        int fastIdx = result.getContent().indexOf("fast-op");
        int lineEnd = result.getContent().indexOf('\n', fastIdx);
        String fastLine = result.getContent().substring(fastIdx, lineEnd);
        assertThat(fastLine).doesNotContain("SLOW");
    }

    // ---- empty results ----

    @Test
    @DisplayName("empty data array returns success with 'No traces found'")
    void shouldReturnEmptyWhenNoTraces() {
        String mockResponse = "{\"data\":[]}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "no-such-service");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.getContent()).contains("No traces found");
    }

    // ---- IOException ----

    @Test
    @DisplayName("IOException from httpGet returns error result")
    void shouldHandleIOExceptionFromBackend() {
        TraceSearchToolProvider provider = new TraceSearchToolProvider(config) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) throws IOException {
                throw new IOException("HTTP 503: Jaeger down");
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("503");
    }

    // ---- auth header ----

    @Test
    @DisplayName("auth header is injected when configured")
    void shouldInjectAuthHeader() {
        config.setAuthHeader("Authorization");
        config.setAuthHeaderValue("Bearer jaeger-token");

        String mockResponse = "{\"data\":[]}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("service", "order-service");
        provider.execute(args, ctx());

        assertThat(provider.capturedHeaders).containsEntry("Authorization", "Bearer jaeger-token");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    private String buildTraceResponse(String traceId, String serviceName, String operation) {
        return "{\"data\":[" + buildTraceJson(traceId, serviceName, operation) + "]}";
    }

    private String buildTraceJson(String traceId, String serviceName, String operation) {
        return "{"
                + "\"traceID\":\"" + traceId + "\","
                + "\"spans\":[{"
                + "\"spanID\":\"span1\","
                + "\"operationName\":\"" + operation + "\","
                + "\"duration\":200000,"
                + "\"startTime\":1689700000000000,"
                + "\"processID\":\"p1\","
                + "\"references\":[]"
                + "}],"
                + "\"processes\":{\"p1\":{\"serviceName\":\"" + serviceName + "\",\"tags\":[]}}"
                + "}";
    }

    /**
     * Subclass that captures httpGet arguments and returns a canned response.
     */
    static class CapturingProvider extends TraceSearchToolProvider {
        final String mockResponse;
        String capturedUrl;
        Map<String, String> capturedHeaders;

        CapturingProvider(SnapAgentProperties.Trace config, String mockResponse) {
            super(config);
            this.mockResponse = mockResponse;
        }

        @Override
        protected String httpGet(String url, Map<String, String> headers,
                                 int connectMs, int readMs) {
            this.capturedUrl = url;
            this.capturedHeaders = headers;
            return mockResponse;
        }
    }
}
