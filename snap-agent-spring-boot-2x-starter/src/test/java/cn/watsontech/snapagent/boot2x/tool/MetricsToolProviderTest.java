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
 * Unit tests for {@link MetricsToolProvider}.
 *
 * <p>Uses the subclass-and-override-httpGet pattern (design doc §8.1) to inject
 * mock Prometheus JSON responses without a real HTTP backend.</p>
 *
 * <p>See design doc §8.2 for the test matrix.</p>
 */
class MetricsToolProviderTest {

    private SnapAgentProperties.Metrics config;

    @BeforeEach
    void setUp() {
        config = new SnapAgentProperties.Metrics();
        config.setBaseUrl("http://prometheus:9090");
        config.setMaxPoints(200);
        config.setTimeoutSeconds(15);
    }

    // ---- identity ----

    @Test
    @DisplayName("name() returns 'metrics_query'")
    void shouldReturnNameMetricsQuery() {
        MetricsToolProvider provider = new MetricsToolProvider(config);
        assertThat(provider.name()).isEqualTo("metrics_query");
    }

    @Test
    @DisplayName("schema() contains the query property and required field")
    void shouldReturnSchemaContainingQueryProperty() {
        MetricsToolProvider provider = new MetricsToolProvider(config);
        String schema = provider.schema();

        assertThat(schema).contains("metrics_query");
        assertThat(schema).contains("\"query\"");
        assertThat(schema).contains("required");
    }

    // ---- instant query ----

    @Test
    @DisplayName("instant query (no start) calls /api/v1/query and formats single value")
    void shouldExecuteInstantQuerySuccessfully() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                + "\"result\":[{\"metric\":{\"job\":\"orders\"},\"value\":[1689700000,\"42.5\"]}]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "up");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getContent()).contains("42.5");
        assertThat(result.getContent()).contains("job=\"orders\"");
        assertThat(provider.capturedUrl).contains("/api/v1/query");
        assertThat(provider.capturedUrl).doesNotContain("/api/v1/query_range");
    }

    @Test
    @DisplayName("range query (with start) calls /api/v1/query_range with start, end, step")
    void shouldExecuteRangeQuerySuccessfully() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{\"job\":\"orders\"},"
                + "\"values\":[[1689700000,\"42.5\"],[1689700060,\"45.1\"]]}]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "rate(http_requests_total[5m])");
        args.put("start", "1689700000");
        args.put("end", "1689700060");
        args.put("step", "1m");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getContent()).contains("42.5");
        assertThat(result.getContent()).contains("45.1");
        assertThat(provider.capturedUrl).contains("/api/v1/query_range");
        assertThat(provider.capturedUrl).contains("start=1689700000");
        assertThat(provider.capturedUrl).contains("end=1689700060");
        assertThat(provider.capturedUrl).contains("step=1m");
    }

    // ---- multiple series ----

    @Test
    @DisplayName("multiple series are formatted with Series headers")
    void shouldHandleMultipleSeries() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                + "\"result\":["
                + "{\"metric\":{\"job\":\"orders\",\"status\":\"200\"},\"value\":[1689700000,\"42.5\"]},"
                + "{\"metric\":{\"job\":\"orders\",\"status\":\"500\"},\"value\":[1689700000,\"0.5\"]}"
                + "]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "rate(http_requests_total[5m])");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getContent()).contains("Series 1");
        assertThat(result.getContent()).contains("Series 2");
        assertThat(result.getContent()).contains("status=\"200\"");
        assertThat(result.getContent()).contains("status=\"500\"");
    }

    // ---- empty results ----

    @Test
    @DisplayName("empty result array returns success with 'No data points returned'")
    void shouldReturnEmptyWhenNoData() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                + "\"result\":[]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "nonexistent_metric");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(0);
        assertThat(result.getContent()).contains("No data points");
    }

    // ---- error response ----

    @Test
    @DisplayName("Prometheus error status returns ToolResult.error")
    void shouldReturnErrorWhenPrometheusReturnsError() {
        String mockResponse = "{\"status\":\"error\",\"errorType\":\"bad_data\","
                + "\"error\":\"unknown function\"}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "invalid_query(");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("bad_data");
        assertThat(result.getError()).contains("unknown function");
    }

    // ---- max_points truncation ----

    @Test
    @DisplayName("range query with more points than max_points truncates and marks truncated")
    void shouldTruncatePointsWhenExceedingMaxPoints() {
        config.setMaxPoints(3);

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("[").append(1689700000 + i * 60L).append(",\"")
                    .append(i).append("\"]");
        }
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{\"job\":\"test\"},"
                + "\"values\":[" + values + "]}]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "rate(test[5m])");
        args.put("start", "1689700000");
        args.put("end", "1689700600");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(3);
        assertThat(result.getContent()).contains("truncated");
    }

    @Test
    @DisplayName("user-supplied max_points exceeding config max is clamped down")
    void shouldClampUserMaxPointsToConfigMax() {
        config.setMaxPoints(2);

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                values.append(",");
            }
            values.append("[").append(1689700000 + i * 60L).append(",\"")
                    .append(i).append("\"]");
        }
        String mockResponse = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":[{\"metric\":{\"job\":\"test\"},"
                + "\"values\":[" + values + "]}]}}";

        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "rate(test[5m])");
        args.put("start", "1689700000");
        args.put("max_points", 1000); // exceeds config max of 2
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    // ---- URL encoding ----

    @Test
    @DisplayName("query parameter is URL-encoded in the request URL")
    void shouldUrlEncodeQueryParameter() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "rate(http_requests_total{job=\"orders\"}[5m])");
        provider.execute(args, ctx());

        // The raw query has special chars; URL should contain encoded form
        assertThat(provider.capturedUrl).contains("query=rate%28");
        assertThat(provider.capturedUrl).contains("%22orders%22");
        assertThat(provider.capturedUrl).doesNotContain("query=rate(http");
    }

    // ---- auth header ----

    @Test
    @DisplayName("auth header is injected when configured")
    void shouldInjectAuthHeader() {
        config.setAuthHeader("Authorization");
        config.setAuthHeaderValue("Bearer secret-token");

        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "up");
        provider.execute(args, ctx());

        assertThat(provider.capturedHeaders).containsEntry("Authorization", "Bearer secret-token");
    }

    @Test
    @DisplayName("no auth header sent when not configured")
    void shouldNotSendAuthHeaderWhenNotConfigured() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "up");
        provider.execute(args, ctx());

        assertThat(provider.capturedHeaders).isNull();
    }

    // ---- missing query ----

    @Test
    @DisplayName("missing query parameter returns error")
    void shouldReturnErrorWhenQueryMissing() {
        MetricsToolProvider provider = new MetricsToolProvider(config);

        Map<String, Object> args = new HashMap<String, Object>();
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("query");
    }

    // ---- IOException ----

    @Test
    @DisplayName("IOException from httpGet returns error result")
    void shouldHandleIOExceptionFromBackend() {
        MetricsToolProvider provider = new MetricsToolProvider(config) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) throws IOException {
                throw new IOException("HTTP 503: backend down");
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "up");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("503");
    }

    // ---- default step ----

    @Test
    @DisplayName("range query without step uses default '1m'")
    void shouldUseDefaultStepWhenOmitted() {
        String mockResponse = "{\"status\":\"success\",\"data\":{\"result\":[]}}";
        final CapturingProvider provider = new CapturingProvider(config, mockResponse);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("query", "up");
        args.put("start", "1689700000");
        provider.execute(args, ctx());

        assertThat(provider.capturedUrl).contains("step=1m");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }

    /**
     * Subclass that captures httpGet arguments and returns a canned response.
     */
    static class CapturingProvider extends MetricsToolProvider {
        final String mockResponse;
        String capturedUrl;
        Map<String, String> capturedHeaders;

        CapturingProvider(SnapAgentProperties.Metrics config, String mockResponse) {
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
