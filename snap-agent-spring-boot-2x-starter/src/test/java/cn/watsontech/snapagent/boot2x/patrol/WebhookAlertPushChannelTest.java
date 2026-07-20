package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.boot2x.tool.ObservabilityHttpClient;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookAlertPushChannel}.
 *
 * <p>Subclasses the channel to capture the {@code httpPost} arguments without
 * real HTTP. Verifies: anomaly filtering, payload shape, header injection,
 * and type identifier.</p>
 */
class WebhookAlertPushChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("push does nothing for a non-anomaly report")
    void pushShouldSkipNonAnomalyReport() {
        CapturingChannel channel = new CapturingChannel();
        PatrolReport report = anomalyReport(false, "patrol_1", "ok summary");

        channel.push(report, null);

        assertThat(channel.postCalled).isFalse();
    }

    @Test
    @DisplayName("push does nothing for a null report")
    void pushShouldSkipNullReport() {
        CapturingChannel channel = new CapturingChannel();

        channel.push(null, null);

        assertThat(channel.postCalled).isFalse();
    }

    @Test
    @DisplayName("push POSTs JSON payload with report fields for an anomaly report")
    void pushShouldPostJsonPayloadForAnomalyReport() throws Exception {
        CapturingChannel channel = new CapturingChannel();
        PatrolReport report = anomalyReport(true, "patrol_42",
                "CRITICAL: CPU > 90% on host db-1");

        channel.push(report, null);

        assertThat(channel.postCalled).isTrue();
        assertThat(channel.lastUrl).isEqualTo("http://hook.example.com/alert");
        assertThat(channel.lastHeaders)
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Authorization", "Bearer secret-token");
        assertThat(channel.lastConnectMs).isEqualTo(3000);
        assertThat(channel.lastReadMs).isEqualTo(7000);

        JsonNode payload = MAPPER.readTree(channel.lastBody);
        assertThat(payload.get("patrol_id").asText()).isEqualTo("patrol_42");
        assertThat(payload.get("status").asText()).isEqualTo("CRITICAL");
        assertThat(payload.get("anomaly_detected").booleanValue()).isTrue();
        assertThat(payload.get("report_summary").asText())
                .isEqualTo("CRITICAL: CPU > 90% on host db-1");
        assertThat(payload.has("event")).isFalse();
    }

    @Test
    @DisplayName("push includes event block when an AnomalyEvent is supplied")
    void pushShouldIncludeEventBlockWhenEventSupplied() throws Exception {
        CapturingChannel channel = new CapturingChannel();
        PatrolReport report = anomalyReport(true, "patrol_1", "error detected");
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("service", "orders");
        AnomalyEvent event = new AnomalyEvent("HIGH_CPU", "metrics", "CPU > 90%",
                "ops-health-check", meta, null);

        channel.push(report, event);

        JsonNode payload = MAPPER.readTree(channel.lastBody);
        assertThat(payload.has("event")).isTrue();
        JsonNode ev = payload.get("event");
        assertThat(ev.get("type").asText()).isEqualTo("HIGH_CPU");
        assertThat(ev.get("source").asText()).isEqualTo("metrics");
        assertThat(ev.get("message").asText()).isEqualTo("CPU > 90%");
    }

    @Test
    @DisplayName("push does not add auth header when authToken is null")
    void pushShouldNotAddAuthHeaderWhenTokenNull() {
        CapturingChannel channel = new CapturingChannel(null);
        PatrolReport report = anomalyReport(true, "patrol_1", "warning");

        channel.push(report, null);

        assertThat(channel.postCalled).isTrue();
        assertThat(channel.lastHeaders).containsEntry("Content-Type", "application/json");
        assertThat(channel.lastHeaders).hasSize(1);
        assertThat(channel.lastHeaders).doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("push swallows IOException from httpPost without throwing")
    void pushShouldSwallowIOException() {
        CapturingChannel channel = new CapturingChannel() {
            @Override
            protected String httpPost(String url, Map<String, String> headers, String body,
                                      int connectTimeoutMs, int readTimeoutMs)
                    throws java.io.IOException {
                // Mark that we entered httpPost, then fail — push() should catch and log.
                this.postCalled = true;
                throw new java.io.IOException("connection refused");
            }
        };
        PatrolReport report = anomalyReport(true, "patrol_1", "critical");

        // Should not propagate — failures are logged, not re-thrown.
        channel.push(report, null);

        assertThat(channel.postCalled).isTrue();
    }

    @Test
    @DisplayName("type returns 'webhook'")
    void typeShouldReturnWebhook() {
        WebhookAlertPushChannel channel = new WebhookAlertPushChannel(
                "http://hook", "Authorization", "token", 1000, 2000);

        assertThat(channel.type()).isEqualTo("webhook");
    }

    @Test
    @DisplayName("constructor clamps non-positive timeouts to defaults")
    void constructorShouldClampNonPositiveTimeouts() {
        WebhookAlertPushChannel channel = new WebhookAlertPushChannel(
                "http://hook", "Authorization", "token", 0, -1);

        // The internal defaults are 5000ms / 10000ms — observable via push.
        CapturingChannel test = new CapturingChannel(0, -1);
        test.push(anomalyReport(true, "p1", "warning"), null);

        assertThat(test.lastConnectMs).isEqualTo(5000);
        assertThat(test.lastReadMs).isEqualTo(10000);
    }

    private static PatrolReport anomalyReport(boolean anomaly, String patrolId, String summary) {
        PatrolReport r = new PatrolReport();
        r.setId("rep_" + patrolId);
        r.setPatrolId(patrolId);
        r.setTaskId("task_" + patrolId);
        r.setSkillName("ops-health-check");
        r.setTriggeredAt(System.currentTimeMillis());
        r.setStatus(anomaly ? "CRITICAL" : "OK");
        r.setSummary(summary);
        r.setAnomalyDetected(anomaly);
        return r;
    }

    /**
     * Subclass that captures httpPost args instead of doing real HTTP. Mirrors
     * the {@code ObservabilityHttpClientTest.CapturingClient} pattern.
     */
    static class CapturingChannel extends WebhookAlertPushChannel {
        boolean postCalled = false;
        String lastUrl;
        Map<String, String> lastHeaders;
        String lastBody;
        int lastConnectMs;
        int lastReadMs;

        CapturingChannel() {
            this("Bearer secret-token");
        }

        CapturingChannel(String authToken) {
            this(authToken, 3000, 7000);
        }

        CapturingChannel(int connectMs, int readMs) {
            this("Bearer secret-token", connectMs, readMs);
        }

        CapturingChannel(String authToken, int connectMs, int readMs) {
            super("http://hook.example.com/alert", "Authorization", authToken, connectMs, readMs);
        }

        @Override
        protected String httpPost(String url, Map<String, String> headers, String body,
                                  int connectTimeoutMs, int readTimeoutMs) throws java.io.IOException {
            this.postCalled = true;
            this.lastUrl = url;
            this.lastHeaders = headers;
            this.lastBody = body;
            this.lastConnectMs = connectTimeoutMs;
            this.lastReadMs = readTimeoutMs;
            return "{\"ok\":true}";
        }
    }
}
