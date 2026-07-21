package cn.watsontech.snapagent.core.patrol;

/**
 * Push channel SPI for delivering alert notifications when anomaly events
 * produce diagnostic reports.
 *
 * <p>Implementations are responsible for delivering alert notifications to a
 * specific destination (email, webhook, DingTalk, Slack, ...). Hosts can
 * register multiple {@code AlertPushChannel} beans — every registered channel
 * receives each alert.</p>
 *
 * <p><b>Default implementations (in starter):</b>
 * <ul>
 *   <li>{@code EmailAlertPushChannel} — sends MIME email via Spring
 *       {@code JavaMailSender} (requires {@code spring-boot-starter-mail}
 *       on classpath).</li>
 *   <li>{@code WebhookAlertPushChannel} — POSTs JSON payload to a configured
 *       webhook URL (uses the JDK {@code HttpURLConnection}).</li>
 * </ul>
 *
 * <p>Channels are triggered by the patrol subsystem only when an anomaly is
 * detected (i.e. {@link PatrolReport#isAnomalyDetected()} returns {@code true},
 * or the report was produced by an external {@link AnomalyEvent}).
 * Normal patrol cron runs with no anomaly do not trigger push channels.</p>
 */
public interface AlertPushChannel {

    /**
     * Delivers an alert notification.
     *
     * @param report the patrol report produced by the diagnostic skill; never {@code null}
     * @param event  the originating anomaly event, or {@code null} when the alert
     *               was produced by a patrol skill itself (not an external event)
     */
    void push(PatrolReport report, AnomalyEvent event);

    /**
     * Returns the channel type identifier (e.g. {@code "email"}, {@code "webhook"},
     * {@code "dingtalk"}). Used for logging.
     */
    default String type() {
        return "unknown";
    }
}
