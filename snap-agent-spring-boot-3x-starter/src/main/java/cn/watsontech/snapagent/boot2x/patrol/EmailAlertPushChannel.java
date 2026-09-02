package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Default {@link AlertPushChannel} that sends a plain-text email via Spring
 * {@link JavaMailSender}.
 *
 * <p>Only fires for anomaly reports ({@code report.isAnomalyDetected() == true}).
 * Normal patrol cron runs without anomaly do not trigger push.</p>
 *
 * <p><b>Dependency note:</b> Requires {@code spring-context-support} +
 * {@code javax.mail} (or {@code spring-boot-starter-mail}) on the host
 * classpath. Wiring is guarded by {@code @ConditionalOnClass(name =
 * "org.springframework.mail.javamail.JavaMailSender")} so hosts without
 * mail support simply skip this bean.</p>
 */
public class EmailAlertPushChannel implements AlertPushChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertPushChannel.class);
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final String from;
    private final List<String> to;
    private final String subjectPrefix;

    public EmailAlertPushChannel(JavaMailSender mailSender, String from,
                                 List<String> to, String subjectPrefix) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
        this.subjectPrefix = subjectPrefix != null && !subjectPrefix.isEmpty()
                ? subjectPrefix : "[SnapAgent 告警]";
    }

    @Override
    public void push(PatrolReport report, AnomalyEvent event) {
        if (report == null) return;
        if (!report.isAnomalyDetected()) return;
        if (to == null || to.isEmpty()) {
            log.warn("EmailAlertPushChannel: no recipients configured, skipping (reportId={})",
                    report.getId());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from != null ? from : "snap-agent@local");
            msg.setTo(to.toArray(new String[0]));
            String eventType = event != null && event.getType() != null ? event.getType() : "ANOMALY";
            msg.setSubject(subjectPrefix + " " + eventType + " (" + report.getStatus() + ")");
            msg.setText(buildBody(report, event));
            mailSender.send(msg);
            log.info("Email alert pushed (reportId={}, patrolId={}, recipients={})",
                    report.getId(), report.getPatrolId(), to);
        } catch (Exception e) {
            log.error("Email alert push failed (reportId={}): {}", report.getId(), e.getMessage());
        }
    }

    @Override
    public String type() {
        return "email";
    }

    private String buildBody(PatrolReport report, AnomalyEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("SnapAgent 巡检告警\n\n");
        sb.append("时间: ").append(formatDate(report.getTriggeredAt())).append("\n");
        sb.append("Skill: ").append(report.getSkillName()).append("\n");
        sb.append("状态: ").append(report.getStatus()).append("\n");
        sb.append("报告ID: ").append(report.getId()).append("\n");
        sb.append("巡检ID: ").append(report.getPatrolId()).append("\n");
        sb.append("任务ID: ").append(report.getTaskId()).append("\n\n");
        if (event != null) {
            sb.append("事件类型: ").append(event.getType()).append("\n");
            sb.append("事件来源: ").append(event.getSource()).append("\n");
            sb.append("事件消息: ").append(event.getMessage()).append("\n\n");
        }
        sb.append("--- 诊断摘要 ---\n");
        sb.append(report.getSummary() != null ? report.getSummary() : "(无)").append("\n");
        return sb.toString();
    }

    private String formatDate(long ts) {
        synchronized (DATE_FMT) {
            return DATE_FMT.format(new Date(ts));
        }
    }
}
