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
 * Unit tests for {@link LogSearchToolProvider}.
 *
 * <p>Uses the subclass-and-override-httpGet pattern (design doc §8.1) to inject
 * mock Loki JSON responses without a real HTTP backend.</p>
 *
 * <p>See design doc §8.2 for the test matrix.</p>
 */
class LogSearchToolProviderTest {

    private SnapAgentProperties.LogSearch config;

    @BeforeEach
    void setUp() {
        config = new SnapAgentProperties.LogSearch();
        config.setBaseUrl("http://loki:3100");
        config.setMaxLines(500);
        config.setTimeoutSeconds(15);
    }

    // ---- identity ----

    @Test
    @DisplayName("name() returns 'log_search'")
    void shouldReturnNameLogSearch() {
        LogSearchToolProvider provider = new LogSearchToolProvider(config);
        assertThat(provider.name()).isEqualTo("log_search");
    }

    @Test
    @DisplayName("schema() contains the query property and required field")
    void shouldReturnSchemaContainingQueryProperty() {
        LogSearchToolProvider provider = new LogSearchToolProvider(config);
        String schema = provider.schema();

        assertThat(schema).contains("log_search");
        assertThat(schema).contains("\"query\"");
        assertThat(schema).contains("required");
    }

    // ---- happy path ----

    @Test
    @DisplayName("range query returns log lines formatted by stream")
    void shouldExecuteQuerySuccessfully() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[{"
                + "\"stream\":{\"app\":\"order-service\",\"instance\":\"pod-abc\"},"
                + "\"values\":[[\"1689700000000000000\",\"ERROR NullPointerException at line 87\"],"
                + "[\"1689699940000000000\",\"ERROR DB connection timeout\"]]"
                + "}]}},\"stats\":{\"summary\":{\"bytesProcessedPerSecond\":1000}}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"} |= \"error\"");
        args.put("start", "1689700000");
        args.put("end", "1689700060");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getContent()).contains("NullPointerException");
        assertThat(result.getContent()).contains("DB connection timeout");
        assertThat(result.getContent()).contains("app=\"order-service\"");
    }

    // ---- default time ----

    @Test
    @DisplayName("omitted start defaults to '1h' and end defaults to 'now'")
    void shouldUseDefaultTimeRangeWhenOmitted() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        provider.execute(args, ctx());

        // The URL should have start and end as nanosecond timestamps
        assertThat(provider.capturedUrl).contains("start=");
        assertThat(provider.capturedUrl).contains("end=");
        // Verify nanosecond magnitudes (19-digit numbers, not 10-digit)
        String startPart = provider.capturedUrl.split("start=")[1].split("&")[0];
        assertThat(Long.parseLong(startPart)).isGreaterThan(1_000_000_000_000_000_000L);
    }

    // ---- limit clamp ----

    @Test
    @DisplayName("user-supplied limit exceeding config max is clamped down")
    void shouldClampLimitToConfigMaxLines() {
        config.setMaxLines(100);
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        args.put("limit", 1000); // exceeds config max of 100
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("limit=100");
    }

    // ---- multiple streams ----

    @Test
    @DisplayName("multiple streams are formatted with separate Stream headers")
    void shouldHandleMultipleStreams() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[{"
                + "\"stream\":{\"app\":\"svc-a\"},"
                + "\"values\":[[\"1689700000000000000\",\"line A\"]]"
                + "},{"
                + "\"stream\":{\"app\":\"svc-b\"},"
                + "\"values\":[[\"1689700001000000000\",\"line B\"]]"
                + "}]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{app=~\"svc-.*\"}");
        args.put("start", "1689700000");
        args.put("end", "1689700060");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getContent()).contains("svc-a");
        assertThat(result.getContent()).contains("svc-b");
        assertThat(result.getContent()).contains("line A");
        assertThat(result.getContent()).contains("line B");
    }

    // ---- empty results ----

    @Test
    @DisplayName("empty result array returns success with 'No log lines returned'")
    void shouldReturnEmptyWhenNoData() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"no-match\"}");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.getContent()).contains("No log lines");
    }

    // ---- error response ----

    @Test
    @DisplayName("Loki error status returns ToolResult.error")
    void shouldReturnErrorWhenLokiReturnsError() {
        String mockResponse = "{\"status\":\"error\",\"error\":\"invalid query syntax\"}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "invalid{");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("invalid query syntax");
    }

    // ---- direction parameter ----

    @Test
    @DisplayName("direction=forward is passed through to the URL")
    void shouldPassDirectionParameter() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        args.put("direction", "forward");
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("direction=forward");
    }

    @Test
    @DisplayName("omitted direction defaults to 'backward'")
    void shouldDefaultDirectionToBackward() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("direction=backward");
    }

    // ---- nanosecond timestamps ----

    @Test
    @DisplayName("start and end are converted from epoch seconds to nanoseconds in the URL")
    void shouldUseNanosecondTimestamps() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        args.put("start", "1689700000");
        args.put("end", "1689700060");
        provider.execute(args, ctx());

        // 1689700000 * 1_000_000_000 = 1689700000000000000
        assertThat(provider.capturedUrl).contains("start=1689700000000000000");
        assertThat(provider.capturedUrl).contains("end=1689700060000000000");
    }

    // ---- missing query ----

    @Test
    @DisplayName("missing query parameter returns error")
    void shouldReturnErrorWhenQueryMissing() {
        LogSearchToolProvider provider = new LogSearchToolProvider(config);

        Map<String, Object> args = new HashMap<String, Object>();
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("query");
    }

    // ---- IOException ----

    @Test
    @DisplayName("IOException from httpGet returns error result")
    void shouldHandleIOExceptionFromBackend() {
        LogSearchToolProvider provider = new LogSearchToolProvider(config) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                    int connectMs, int readMs) throws IOException {
                throw new IOException("HTTP 502: Loki unavailable");
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("502");
    }

    // ---- auth header ----

    @Test
    @DisplayName("auth header is injected when configured")
    void shouldInjectAuthHeader() {
        config.setAuthHeader("X-Scope-OrgID");
        config.setAuthHeaderValue("tenant-1");

        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"}");
        provider.execute(args, ctx());

        assertThat(provider.capturedHeaders).containsEntry("X-Scope-OrgID", "tenant-1");
    }

    // ---- URL encoding ----

    @Test
    @DisplayName("query parameter is URL-encoded in the request URL")
    void shouldUrlEncodeQueryParameter() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "{job=\"orders\"} |= \"error\"");
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("query=%7B");
        assertThat(provider.capturedUrl).contains("%22orders%22");
        assertThat(provider.capturedUrl).doesNotContain("query={job");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    /**
     * Subclass that captures httpGet arguments and returns a canned response.
     */
    static class CapturingProvider extends LogSearchToolProvider {
        final String mockResponse;
        String capturedUrl;
        Map<String, String> capturedHeaders;

        CapturingProvider(SnapAgentProperties.LogSearch config, String mockResponse) {
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
