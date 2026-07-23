package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultAnomalyEventListener}.
 * <p>
 * Covers the {@code onEvent()} flow (alert recording, skill lookup, agent
 * execution, report storage, push) and defensive-null scenarios.
 */
class DefaultAnomalyEventListenerTest {

    private AgentExecutor agentExecutor;
    private SkillRegistry skillRegistry;
    private AlertConverger alertConverger;
    private PatrolReportStore reportStore;
    private AlertPushChannel pushChannel;

    @BeforeEach
    void setUp() {
        agentExecutor = mock(AgentExecutor.class);
        skillRegistry = mock(SkillRegistry.class);
        alertConverger = mock(AlertConverger.class);
        reportStore = mock(PatrolReportStore.class);
        pushChannel = mock(AlertPushChannel.class);
        when(pushChannel.type()).thenReturn("webhook");
    }

    private SkillMeta mockSkill() {
        return new SkillMeta("error-spike-investigation", "desc",
                null, null,
                "body", null, null);
    }

    private AnomalyEvent event(String type, String source, String message,
                               String skillName, Map<String, String> inputs) {
        AnomalyEvent event = new AnomalyEvent();
        event.setType(type);
        event.setSource(source);
        event.setMessage(message);
        if (skillName != null) event.setSkillName(skillName);
        if (inputs != null) event.setInputs(inputs);
        return event;
    }

    // ── onEvent with skill found + execution succeeds ─────────────

    @Test
    void onEventShouldRecordAlertAndTriggerSkill() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-1", "fp", "ERROR_SPIKE", "svc", "msg", "t-1"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Collections.singletonList(pushChannel));

        AnomalyEvent ev = event("ERROR_SPIKE", "order-svc", "NPE at line 87", null, null);
        listener.onEvent(ev);

        verify(alertConverger).record(any());
        verify(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
        verify(reportStore).save(argThat(r ->
                "SUCCEEDED".equals(r.getStatus()) && r.isAnomalyDetected()));
        verify(pushChannel).push(any(PatrolReport.class), eq(ev));
    }

    // ── onEvent with explicit skill name ──────────────────────────

    @Test
    void onEventShouldUseEventSkillNameWhenPresent() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-2", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("custom-skill")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore);

        AnomalyEvent ev = event("TIMEOUT", "db", "conn timeout", "custom-skill", null);
        listener.onEvent(ev);

        verify(skillRegistry).get("custom-skill");
        verify(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    // ── onEvent with empty skill name (defaults) ───────────────────

    @Test
    void onEventShouldDefaultToErrorSpikeWhenSkillNameEmpty() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-3", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore);

        AnomalyEvent ev = event("TIMEOUT", "db", "conn timeout", "", null);
        listener.onEvent(ev);

        verify(skillRegistry).get("error-spike-investigation");
    }

    // ── onEvent with skill not found ───────────────────────────────

    @Test
    void onEventShouldStoreFailureReportWhenSkillNotFound() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-4", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("nonexistent-skill")).thenReturn(null);

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Collections.singletonList(pushChannel));

        AnomalyEvent ev = event("ERR", "svc", "boom", "nonexistent-skill", null);
        listener.onEvent(ev);

        verify(reportStore).save(argThat(r ->
                "FAILED".equals(r.getStatus())
                        && r.getSummary().contains("Skill not found")
                        && r.isAnomalyDetected()));
        verify(agentExecutor, never()).execute(any(), any());
        verify(pushChannel).push(any(PatrolReport.class), eq(ev));
    }

    // ── onEvent with execution exception ───────────────────────────

    @Test
    void onEventShouldStoreFailureReportOnException() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-5", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doThrow(new RuntimeException("LLM timeout"))
                .when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Collections.singletonList(pushChannel));

        AnomalyEvent ev = event("ERR", "svc", "boom", null, null);
        listener.onEvent(ev);

        verify(reportStore).save(argThat(r ->
                "FAILED".equals(r.getStatus())
                        && r.getSummary().contains("LLM timeout")));
        verify(pushChannel).push(any(PatrolReport.class), eq(ev));
    }

    // ── onEvent with null alertConverger ────────────────────────────

    @Test
    void onEventShouldWorkWithoutConverger() {
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, null, reportStore);

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        verify(alertConverger, never()).record(any());
        verify(reportStore).save(any(PatrolReport.class));
    }

    // ── onEvent with null reportStore ───────────────────────────────

    @Test
    void onEventShouldWorkWithoutReportStore() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-6", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, null);

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        verify(reportStore, never()).save(any());
    }

    // ── onEvent with null reportStore + skill not found ─────────────

    @Test
    void onEventShouldNotStoreWhenReportStoreIsNullAndSkillNotFound() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-7", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(null);

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, null);

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        verify(reportStore, never()).save(any());
    }

    // ── onEvent with event inputs ───────────────────────────────────

    @Test
    void onEventShouldMergeEventInputs() {
        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-8", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore);

        Map<String, String> inputs = new HashMap<>();
        inputs.put("service", "order-svc");
        AnomalyEvent ev = event("ERR", "svc", "boom", null, inputs);
        listener.onEvent(ev);

        verify(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    // ── pushChannels exception handling ─────────────────────────────

    @Test
    void pushToChannelsShouldSwallowExceptionAndContinue() {
        AlertPushChannel failingChannel = mock(AlertPushChannel.class);
        when(failingChannel.type()).thenReturn("failing");
        doThrow(new RuntimeException("channel error"))
                .when(failingChannel).push(any(), any());

        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-9", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Arrays.asList(failingChannel, pushChannel));

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        // Both channels should have been called
        verify(failingChannel).push(any(), any());
        verify(pushChannel).push(any(), any());
    }

    // ── multi-channel concurrent scenario (GAP-7) ──────────────────

    @Test
    void shouldCallAllChannelsEvenWhenMiddleOnesFail() {
        // Three channels: first ok, second throws, third ok.
        // Verifies exception isolation — a failure in one channel does not
        // prevent subsequent channels from receiving the alert.
        AlertPushChannel ch1 = mock(AlertPushChannel.class);
        AlertPushChannel ch2 = mock(AlertPushChannel.class);
        AlertPushChannel ch3 = mock(AlertPushChannel.class);
        when(ch1.type()).thenReturn("ch1");
        when(ch2.type()).thenReturn("ch2");
        when(ch3.type()).thenReturn("ch3");
        doThrow(new RuntimeException("ch2 down"))
                .when(ch2).push(any(), any());

        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-multi", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Arrays.asList(ch1, ch2, ch3));

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        // All three channels must have been invoked despite ch2 throwing.
        verify(ch1).push(any(), any());
        verify(ch2).push(any(), any());
        verify(ch3).push(any(), any());
    }

    @Test
    void shouldCallAllChannelsWhenAllFail() {
        // Edge case: every channel throws — the listener must still not
        // propagate the exception to the caller.
        AlertPushChannel ch1 = mock(AlertPushChannel.class);
        AlertPushChannel ch2 = mock(AlertPushChannel.class);
        when(ch1.type()).thenReturn("ch1");
        when(ch2.type()).thenReturn("ch2");
        doThrow(new RuntimeException("ch1 down")).when(ch1).push(any(), any());
        doThrow(new RuntimeException("ch2 down")).when(ch2).push(any(), any());

        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a-all-fail", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                Arrays.asList(ch1, ch2));

        // Must not throw — all failures should be swallowed.
        listener.onEvent(event("ERR", "svc", "boom", null, null));

        verify(ch1).push(any(), any());
        verify(ch2).push(any(), any());
        // Report should still be saved.
        verify(reportStore).save(any(PatrolReport.class));
    }

    // ── null pushChannels in constructor ────────────────────────────

    @Test
    void constructorShouldHandleNullPushChannels() {
        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore,
                null); // null push channels

        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        listener.onEvent(event("ERR", "svc", "boom", null, null));
        verify(reportStore).save(any());
    }

    // ── four-arg constructor ─────────────────────────────────────────

    @Test
    void fourArgConstructorShouldUseEmptyPushChannels() {
        DefaultAnomalyEventListener listener = new DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConverger, reportStore);

        when(alertConverger.record(any())).thenReturn(
                new AlertConvergence("a", "fp", "T", "S", "M", "t"));
        when(skillRegistry.get("error-spike-investigation")).thenReturn(mockSkill());
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        listener.onEvent(event("ERR", "svc", "boom", null, null));

        verify(reportStore).save(argThat(r -> "FAILED".equals(r.getStatus())));
    }
}
