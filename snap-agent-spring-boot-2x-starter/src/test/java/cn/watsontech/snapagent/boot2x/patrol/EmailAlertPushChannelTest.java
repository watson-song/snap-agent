package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailAlertPushChannel}.
 *
 * <p>Uses Mockito to mock {@link JavaMailSender} so no real SMTP server is
 * required. Verifies: anomaly filtering, recipient list handling, subject
 * format, body shape, and error swallowing.</p>
 */
class EmailAlertPushChannelTest {

    private JavaMailSender mailSender;
    private EmailAlertPushChannel channel;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        channel = new EmailAlertPushChannel(mailSender, "snap-agent@example.com",
                Arrays.asList("ops@example.com", "dev@example.com"), "[Test 告警]");
    }

    @Test
    @DisplayName("push does nothing for a non-anomaly report")
    void pushShouldSkipNonAnomalyReport() {
        channel.push(anomalyReport(false, "patrol_1", "ok"), null);

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>any());
    }

    @Test
    @DisplayName("push does nothing for a null report")
    void pushShouldSkipNullReport() {
        channel.push(null, null);

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>any());
    }

    @Test
    @DisplayName("push does nothing when recipients list is empty")
    void pushShouldSkipWhenNoRecipients() {
        EmailAlertPushChannel emptyChannel = new EmailAlertPushChannel(
                mailSender, "from@example.com", Collections.<String>emptyList(), "[Test]");

        emptyChannel.push(anomalyReport(true, "patrol_1", "critical"), null);

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>any());
    }

    @Test
    @DisplayName("push sends a SimpleMailMessage with expected from/to/subject/body")
    void pushShouldSendEmailWithExpectedFields() {
        PatrolReport report = anomalyReport(true, "patrol_42", "CRITICAL: db-1 down");
        AnomalyEvent event = new AnomalyEvent("DB_DOWN", "metrics", "db-1 unreachable",
                "ops-health-check", null, null);

        channel.push(report, event);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getFrom()).isEqualTo("snap-agent@example.com");
        assertThat(msg.getTo()).containsExactly("ops@example.com", "dev@example.com");
        assertThat(msg.getSubject())
                .isEqualTo("[Test 告警] DB_DOWN (CRITICAL)");
        assertThat(msg.getText()).contains("SnapAgent 巡检告警");
        assertThat(msg.getText()).contains("Skill: ops-health-check");
        assertThat(msg.getText()).contains("状态: CRITICAL");
        assertThat(msg.getText()).contains("报告ID: rep_patrol_42");
        assertThat(msg.getText()).contains("巡检ID: patrol_42");
        assertThat(msg.getText()).contains("任务ID: task_patrol_42");
        assertThat(msg.getText()).contains("事件类型: DB_DOWN");
        assertThat(msg.getText()).contains("事件来源: metrics");
        assertThat(msg.getText()).contains("事件消息: db-1 unreachable");
        assertThat(msg.getText()).contains("CRITICAL: db-1 down");
    }

    @Test
    @DisplayName("push uses default subject prefix when none supplied")
    void pushShouldUseDefaultSubjectPrefix() {
        EmailAlertPushChannel bare = new EmailAlertPushChannel(
                mailSender, "from@example.com", Arrays.asList("to@example.com"), null);
        PatrolReport report = anomalyReport(true, "p1", "warning");
        report.setStatus("WARNING");

        bare.push(report, null);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).startsWith("[SnapAgent 告警]");
    }

    @Test
    @DisplayName("push uses default from address when null")
    void pushShouldUseDefaultFromWhenNull() {
        EmailAlertPushChannel bare = new EmailAlertPushChannel(
                mailSender, null, Arrays.asList("to@example.com"), "[X]");
        PatrolReport report = anomalyReport(true, "p1", "critical");

        bare.push(report, null);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("snap-agent@local");
    }

    @Test
    @DisplayName("push uses 'ANOMALY' event type when event is null")
    void pushShouldUseDefaultEventTypeWhenEventNull() {
        PatrolReport report = anomalyReport(true, "p1", "warning");
        report.setStatus("WARNING");

        channel.push(report, null);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo("[Test 告警] ANOMALY (WARNING)");
    }

    @Test
    @DisplayName("push swallows MailException without propagating")
    void pushShouldSwallowMailException() {
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>any());
        PatrolReport report = anomalyReport(true, "p1", "critical");

        // Should not throw — failures are logged, not propagated.
        channel.push(report, null);

        verify(mailSender).send(org.mockito.ArgumentMatchers.<SimpleMailMessage>any());
    }

    @Test
    @DisplayName("type returns 'email'")
    void typeShouldReturnEmail() {
        assertThat(channel.type()).isEqualTo("email");
    }

    private PatrolReport anomalyReport(boolean anomaly, String patrolId, String summary) {
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
}
