package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.boot2x.tool.ObservabilityHttpClient;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link AlertPushChannel} that POSTs a JSON payload to a configured
 * webhook URL via the JDK {@code HttpURLConnection}.
 *
 * <p>The JSON payload includes the event type/source/message, the diagnostic
 * report summary, the report ID, the task ID, and the triggered-at timestamp.
 * Consumers can use this to route, deduplicate, or render the alert.</p>
 *
 * <p>Only fires for anomaly reports ({@code report.isAnomalyDetected() == true}).
 * Normal patrol cron runs without anomaly do not push.</p>
 */
public class WebhookAlertPushChannel extends ObservabilityHttpClient implements AlertPushChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertPushChannel.class);

    private final String url;
    private final String authHeader;
    private final String authToken;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookAlertPushChannel(String url, String authHeader, String authToken,
                                   int connectTimeoutMs, int readTimeoutMs) {
        this.url = url;
        this.authHeader = authHeader != null && !authHeader.isEmpty() ? authHeader : "Authorization";
        this.authToken = authToken;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 5000;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 10000;
    }

    @Override
    public void push(PatrolReport report, AnomalyEvent event) {
        if (report == null) return;
        if (!report.isAnomalyDetected()) return;
        Map<String, Object> payload = buildPayload(report, event);
        try {
            String json = mapper.writeValueAsString(payload);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                headers.put(authHeader, authToken);
            }
            String resp = httpPost(url, headers, json, connectTimeoutMs, readTimeoutMs);
            log.info("Webhook alert pushed (reportId={}, patrolId={}, status={}): {}",
                    report.getId(), report.getPatrolId(), report.getStatus(),
                    resp != null && resp.length() > 200 ? resp.substring(0, 200) + "..." : resp);
        } catch (Exception e) {
            log.error("Webhook alert push failed (url={}, reportId={}): {}",
                    url, report.getId(), e.getMessage());
        }
    }

    @Override
    public String type() {
        return "webhook";
    }

    private Map<String, Object> buildPayload(PatrolReport report, AnomalyEvent event) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("report_id", report.getId());
        p.put("patrol_id", report.getPatrolId());
        p.put("task_id", report.getTaskId());
        p.put("skill_name", report.getSkillName());
        p.put("status", report.getStatus());
        p.put("anomaly_detected", report.isAnomalyDetected());
        p.put("triggered_at", report.getTriggeredAt());
        p.put("report_summary", report.getSummary());
        if (event != null) {
            Map<String, Object> ev = new LinkedHashMap<String, Object>();
            ev.put("type", event.getType());
            ev.put("source", event.getSource());
            ev.put("message", event.getMessage());
            ev.put("event_timestamp", event.getTimestamp());
            ev.put("metadata", event.getMetadata());
            p.put("event", ev);
        }
        return p;
    }
}
