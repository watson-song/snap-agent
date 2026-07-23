package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.patrol.AlertConvergence;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolLockProvider;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;
import cn.watsontech.snapagent.core.patrol.PatrolTask;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScheduledPatrolScheduler}.
 * <p>
 * Covers the scheduling lifecycle (schedule / cancel / toggle / list) and the
 * private {@code executePatrol()} flow (lock acquire → skill lookup → agent
 * execution → report → alert/push) by using a TaskScheduler mock that
 * immediately runs the submitted Runnable.
 */
class ScheduledPatrolSchedulerTest {

    private TaskScheduler taskScheduler;
    private AgentExecutor agentExecutor;
    private SkillRegistry skillRegistry;
    private PatrolReportStore reportStore;
    private PatrolLockProvider lockProvider;
    private AlertConverger alertConverger;
    private AlertPushChannel pushChannel;

    private ScheduledPatrolScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        agentExecutor = mock(AgentExecutor.class);
        skillRegistry = mock(SkillRegistry.class);
        reportStore = mock(PatrolReportStore.class);
        lockProvider = mock(PatrolLockProvider.class);
        alertConverger = mock(AlertConverger.class);
        pushChannel = mock(AlertPushChannel.class);

        when(lockProvider.tryAcquire(anyString(), anyLong())).thenReturn(true);
        when(lockProvider.type()).thenReturn("test");
        when(pushChannel.type()).thenReturn("webhook");

        scheduler = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L,
                Collections.singletonList(pushChannel),
                alertConverger);
    }

    // ── extractAlertSummary ───────────────────────────────────────

    @Test
    void extractAlertSummaryShouldReturnTextWhenPresent() {
        String result = scheduler.extractAlertSummary(
                "CPU normal\nALERT_SUMMARY: CPU spiked to 95%");
        assertThat(result).isEqualTo("CPU spiked to 95%");
    }

    @Test
    void extractAlertSummaryShouldHandleCaseInsensitive() {
        String result = scheduler.extractAlertSummary(
                "alert_summary: Memory leak detected");
        assertThat(result).isEqualTo("Memory leak detected");
    }

    @Test
    void extractAlertSummaryShouldStripTrailingMarkdownAndQuotes() {
        String result = scheduler.extractAlertSummary(
                "ALERT_SUMMARY: **disk full**");
        assertThat(result).isEqualTo("**disk full");
    }

    @Test
    void extractAlertSummaryShouldReturnNullWhenAbsent() {
        assertThat(scheduler.extractAlertSummary("All systems normal")).isNull();
    }

    @Test
    void extractAlertSummaryShouldReturnNullForNullInput() {
        assertThat(scheduler.extractAlertSummary(null)).isNull();
    }

    @Test
    void extractAlertSummaryShouldReturnNullForEmptyInput() {
        assertThat(scheduler.extractAlertSummary("")).isNull();
    }

    @Test
    void extractAlertSummaryShouldReturnNullWhenTextAfterPrefixIsEmpty() {
        assertThat(scheduler.extractAlertSummary("ALERT_SUMMARY:   ")).isNull();
    }

    // ── schedule ───────────────────────────────────────────────────

    @Test
    void scheduleShouldAutoGenerateIdWhenNull() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        PatrolTask task = new PatrolTask(null, "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        assertThat(task.getId()).isNotNull().startsWith("patrol_");
        assertThat(scheduler.listTasks()).hasSize(1);
    }

    @Test
    void scheduleEnabledTaskShouldRegisterWithScheduler() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        PatrolTask task = new PatrolTask("p-1", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        assertThat(scheduler.listTasks()).hasSize(1);
        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void scheduleDisabledTaskShouldNotRegisterWithScheduler() {
        PatrolTask task = new PatrolTask("p-2", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        task.setEnabled(false);
        scheduler.schedule(task);

        assertThat(scheduler.listTasks()).hasSize(1);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    // ── cancel ─────────────────────────────────────────────────────

    @Test
    void cancelShouldRemoveTaskAndCancelFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        PatrolTask task = new PatrolTask("p-3", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        scheduler.cancel("p-3");

        assertThat(scheduler.listTasks()).isEmpty();
        verify(future).cancel(false);
    }

    @Test
    void cancelNonExistingTaskShouldNotThrow() {
        scheduler.cancel("nonexistent");
        assertThat(scheduler.listTasks()).isEmpty();
    }

    // ── toggleEnabled ──────────────────────────────────────────────

    @Test
    void toggleEnabledShouldDisableThenEnable() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        PatrolTask task = new PatrolTask("p-4", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        // Disable
        Boolean result = scheduler.toggleEnabled("p-4");
        assertThat(result).isFalse();
        verify(future).cancel(false);

        // Re-enable
        doReturn(mock(ScheduledFuture.class)).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        Boolean result2 = scheduler.toggleEnabled("p-4");
        assertThat(result2).isTrue();
    }

    @Test
    void toggleEnabledNonExistingShouldReturnNull() {
        Boolean result = scheduler.toggleEnabled("nonexistent");
        assertThat(result).isNull();
    }

    // ── listTasks / getReports / countReports ──────────────────────

    @Test
    void listTasksShouldReturnCopy() {
        doReturn(mock(ScheduledFuture.class)).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        PatrolTask task = new PatrolTask("p-5", "sk", "0 */5 * * * ?", "u", null);
        scheduler.schedule(task);
        scheduler.schedule(task); // duplicate should not matter, same id

        List<PatrolTask> tasks = scheduler.listTasks();
        assertThat(tasks).hasSize(1);
    }

    @Test
    void getReportsShouldDelegateToStore() {
        PatrolReport report = new PatrolReport(
                "r-1", "p-1", "t-1", "sk", 1L, "SUCCEEDED", "ok", false);
        when(reportStore.getReports("user-1", 10, 0))
                .thenReturn(Collections.singletonList(report));

        List<PatrolReport> results = scheduler.getReports("user-1", 10, 0);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("r-1");
    }

    @Test
    void countReportsShouldDelegateToStore() {
        when(reportStore.count("user-1")).thenReturn(42L);
        assertThat(scheduler.countReports("user-1")).isEqualTo(42L);
    }

    // ── executePatrol (via immediate schedule) ─────────────────────

    /**
     * Helper: configure taskScheduler to immediately run the submitted Runnable.
     */
    private void enableImmediateExecution() {
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenAnswer(invocation -> {
                    Runnable r = invocation.getArgument(0);
                    r.run();
                    return mock(ScheduledFuture.class);
                });
    }

    @Test
    void executePatrolShouldSucceedAndSaveReportWhenSkillFound() {
        enableImmediateExecution();
        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("All systems normal");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        PatrolTask task = new PatrolTask("p-exec-1", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        verify(reportStore).save(argThat(r ->
                "SUCCEEDED".equals(r.getStatus())
                        && !r.isAnomalyDetected()
                        && "All systems normal".equals(r.getSummary())));
        verify(lockProvider).release("p-exec-1");
    }

    @Test
    void executePatrolShouldDetectAnomalyWhenAlertSummaryPresent() {
        enableImmediateExecution();
        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("CPU at 95%\nALERT_SUMMARY: CPU critically high");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
        when(alertConverger.record(any(AnomalyEvent.class)))
                .thenReturn(new AlertConvergence("a-1", "fp", "patrol", "p-exec-2", "msg", "t"));

        PatrolTask task = new PatrolTask("p-exec-2", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        task.setName("Nightly Check");
        scheduler.schedule(task);

        verify(reportStore).save(argThat(r ->
                r.isAnomalyDetected()
                        && r.getSummary().contains("ALERT_SUMMARY")));
        verify(alertConverger).record(any(AnomalyEvent.class));
        verify(pushChannel).push(any(PatrolReport.class), isNull());
    }

    @Test
    void executePatrolShouldDetectAnomalyOnFailedStatus() {
        enableImmediateExecution();
        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            task.setReport("Execution error");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
        when(alertConverger.record(any(AnomalyEvent.class)))
                .thenReturn(new AlertConvergence("a-2", "fp", "patrol", "p-exec-3", "msg", "t"));

        PatrolTask task = new PatrolTask("p-exec-3", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        verify(reportStore).save(argThat(PatrolReport::isAnomalyDetected));
        verify(alertConverger).record(any(AnomalyEvent.class));
    }

    @Test
    void executePatrolShouldHandleSkillNotFound() {
        enableImmediateExecution();
        when(skillRegistry.get("unknown-skill")).thenReturn(null);

        PatrolTask task = new PatrolTask("p-exec-4", "unknown-skill",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        verify(reportStore).save(argThat(r ->
                "FAILED".equals(r.getStatus())
                        && r.getSummary().contains("Skill not found")));
        verify(agentExecutor, never()).execute(any(), any());
        verify(lockProvider).release("p-exec-4");
    }

    @Test
    void executePatrolShouldHandleAgentExecutionException() {
        enableImmediateExecution();
        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doThrow(new RuntimeException("LLM timeout"))
                .when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        PatrolTask task = new PatrolTask("p-exec-5", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        verify(reportStore).save(argThat(r ->
                "FAILED".equals(r.getStatus())
                        && r.getSummary().contains("LLM timeout")));
        verify(lockProvider).release("p-exec-5");
    }

    @Test
    void executePatrolShouldSkipWhenLockNotAcquired() {
        enableImmediateExecution();
        when(lockProvider.tryAcquire(anyString(), anyLong())).thenReturn(false);

        PatrolTask task = new PatrolTask("p-exec-6", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        scheduler.schedule(task);

        verify(skillRegistry, never()).get(anyString());
        verify(reportStore, never()).save(any());
        verify(lockProvider, never()).release("p-exec-6");
    }

    @Test
    void executePatrolShouldInjectPatrolSuffixIntoUserMessage() {
        enableImmediateExecution();
        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);

        // Capture the AgentTask passed to executor to verify _user_message was injected
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("OK");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        Map<String, String> inputs = new HashMap<>();
        inputs.put("_user_message", "Check CPU");
        PatrolTask task = new PatrolTask("p-exec-7", "health-patrol",
                "0 */5 * * * ?", "user-1", inputs);
        scheduler.schedule(task);

        // Verify the agentExecutor was called (meaning execution proceeded)
        verify(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
    }

    // ── recordAlert with null alertConverger ───────────────────────

    @Test
    void executePatrolShouldNotRecordAlertWhenConvergerIsNull() {
        // Create scheduler without alertConverger
        ScheduledPatrolScheduler schedulerNoConverger = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L,
                Collections.singletonList(pushChannel),
                null); // alertConverger = null

        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return mock(ScheduledFuture.class);
                });

        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setReport("ALERT_SUMMARY: anomaly");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));

        PatrolTask task = new PatrolTask("p-exec-8", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        schedulerNoConverger.schedule(task);

        // anomaly should still be detected and pushed, but not recorded via converger
        verify(reportStore).save(argThat(PatrolReport::isAnomalyDetected));
        verify(pushChannel).push(any(PatrolReport.class), isNull());
    }

    // ── pushChannels exception handling ────────────────────────────

    @Test
    void pushToChannelsShouldSwallowExceptionsFromChannel() {
        AlertPushChannel failingChannel = mock(AlertPushChannel.class);
        when(failingChannel.type()).thenReturn("failing");
        doThrow(new RuntimeException("channel down"))
                .when(failingChannel).push(any(), any());

        ScheduledPatrolScheduler schedulerWithFailing = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L,
                Arrays.asList(failingChannel, pushChannel),
                alertConverger);

        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return mock(ScheduledFuture.class);
                });

        SkillMeta skill = new SkillMeta("health-patrol", "desc",
                null, null,
                "body", null, null);
        when(skillRegistry.get("health-patrol")).thenReturn(skill);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setStatus(TaskStatus.FAILED);
            task.setReport("Error occurred");
            return null;
        }).when(agentExecutor).execute(any(AgentTask.class), any(SkillMeta.class));
        when(alertConverger.record(any()))
                .thenReturn(new AlertConvergence("a", "fp", "t", "s", "m", "t"));

        PatrolTask task = new PatrolTask("p-exec-9", "health-patrol",
                "0 */5 * * * ?", "user-1", null);
        schedulerWithFailing.schedule(task);

        // Both channels should have been called, the exception from the first should not prevent the second
        verify(failingChannel).push(any(), any());
        verify(pushChannel).push(any(), any());
    }

    // ── constructors coverage ──────────────────────────────────────

    @Test
    void fourArgConstructorShouldUseDefaults() {
        ScheduledPatrolScheduler s = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore);
        // Should work for basic operations
        assertThat(s.listTasks()).isEmpty();
        s.getReports("u", 10, 0);
        verify(reportStore).getReports("u", 10, 0);
    }

    @Test
    void sixArgConstructorShouldUseDefaultChannels() {
        ScheduledPatrolScheduler s = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L);
        assertThat(s.listTasks()).isEmpty();
    }

    @Test
    void sevenArgConstructorShouldUseNullConverger() {
        ScheduledPatrolScheduler s = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L,
                Collections.<AlertPushChannel>emptyList());
        assertThat(s.listTasks()).isEmpty();
    }

    @Test
    void fullConstructorShouldHandleNullPushChannels() {
        ScheduledPatrolScheduler s = new ScheduledPatrolScheduler(
                taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, 300L,
                null, // null push channels
                null);
        assertThat(s.listTasks()).isEmpty();
    }
}
