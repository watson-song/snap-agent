package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.patrol.AlertConverger;
import cn.watsontech.snapagent.core.patrol.AlertPushChannel;
import cn.watsontech.snapagent.core.patrol.AnomalyEvent;
import cn.watsontech.snapagent.core.patrol.PatrolLockProvider;
import cn.watsontech.snapagent.core.patrol.PatrolReport;
import cn.watsontech.snapagent.core.patrol.PatrolReportStore;
import cn.watsontech.snapagent.core.patrol.PatrolScheduler;
import cn.watsontech.snapagent.core.patrol.PatrolTask;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring {@link TaskScheduler}-backed implementation of {@link PatrolScheduler}.
 *
 * <p>Schedules patrol tasks by cron expression. When a patrol fires, it looks up
 * the skill via {@link SkillRegistry}, creates an {@link AgentTask}, and calls
 * {@link AgentExecutor#execute}. The resulting status and report are stored as
 * a {@link PatrolReport}.</p>
 */
public class ScheduledPatrolScheduler implements PatrolScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPatrolScheduler.class);

    private final TaskScheduler taskScheduler;
    private final AgentExecutor agentExecutor;
    private final SkillRegistry skillRegistry;
    private final PatrolReportStore reportStore;
    private final PatrolLockProvider lockProvider;
    private final long lockTtlSeconds;
    private final List<AlertPushChannel> pushChannels;
    private final AlertConverger alertConverger;
    private final ConcurrentHashMap<String, PatrolTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore) {
        this(taskScheduler, agentExecutor, skillRegistry, reportStore,
                new NoopPatrolLockProvider(), 300L, Collections.<AlertPushChannel>emptyList(), null);
    }

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore,
                                    PatrolLockProvider lockProvider, long lockTtlSeconds) {
        this(taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, lockTtlSeconds, Collections.<AlertPushChannel>emptyList(), null);
    }

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore,
                                    PatrolLockProvider lockProvider, long lockTtlSeconds,
                                    List<AlertPushChannel> pushChannels) {
        this(taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, lockTtlSeconds, pushChannels, null);
    }

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore,
                                    PatrolLockProvider lockProvider, long lockTtlSeconds,
                                    List<AlertPushChannel> pushChannels,
                                    AlertConverger alertConverger) {
        this.taskScheduler = taskScheduler;
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
        this.reportStore = reportStore;
        this.lockProvider = lockProvider;
        this.lockTtlSeconds = lockTtlSeconds;
        this.pushChannels = pushChannels != null ? new ArrayList<>(pushChannels)
                : new ArrayList<>();
        this.alertConverger = alertConverger;
    }

    @Override
    public void schedule(PatrolTask task) {
        if (task.getId() == null) {
            task.setId("patrol_" + idCounter.incrementAndGet());
        }
        tasks.put(task.getId(), task);
        if (task.isEnabled()) {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executePatrol(task),
                    new CronTrigger(task.getCron()));
            scheduledFutures.put(task.getId(), future);
            log.info("Scheduled patrol task {} (skill={}, cron={})",
                    task.getId(), task.getSkillName(), task.getCron());
        }
    }

    @Override
    public void cancel(String patrolId) {
        ScheduledFuture<?> future = scheduledFutures.remove(patrolId);
        if (future != null) {
            future.cancel(false);
        }
        tasks.remove(patrolId);
        log.info("Cancelled patrol task {}", patrolId);
    }

    @Override
    public Boolean toggleEnabled(String patrolId) {
        PatrolTask task = tasks.get(patrolId);
        if (task == null) return null;
        boolean newEnabled = !task.isEnabled();
        task.setEnabled(newEnabled);
        if (newEnabled) {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executePatrol(task),
                    new CronTrigger(task.getCron()));
            scheduledFutures.put(patrolId, future);
            log.info("Patrol task {} enabled", patrolId);
        } else {
            ScheduledFuture<?> future = scheduledFutures.remove(patrolId);
            if (future != null) {
                future.cancel(false);
            }
            log.info("Patrol task {} disabled", patrolId);
        }
        return newEnabled;
    }

    @Override
    public List<PatrolTask> listTasks() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public List<PatrolReport> getReports(String userId, int limit, int offset) {
        return reportStore.getReports(userId, limit, offset);
    }

    @Override
    public long countReports(String userId) {
        return reportStore.count(userId);
    }

    private void executePatrol(PatrolTask task) {
        long triggeredAt = System.currentTimeMillis();
        // Multi-Pod coordination: only this Pod runs the patrol if it acquires the lock.
        // Default NoopPatrolLockProvider always grants (single-Pod mode); hosts with
        // multiple Pods must inject a distributed-lock implementation (Redis, DB, k8s lease).
        if (!lockProvider.tryAcquire(task.getId(), lockTtlSeconds)) {
            log.info("Patrol {} skipped (lock held by another Pod, lockProvider={})",
                    task.getId(), lockProvider.type());
            return;
        }
        try {
            log.info("Executing patrol {} (skill={}, lockProvider={})",
                    task.getId(), task.getSkillName(), lockProvider.type());

            SkillMeta skill = skillRegistry.get(task.getSkillName());
            if (skill == null) {
                log.error("Patrol {} failed: skill '{}' not found", task.getId(), task.getSkillName());
                PatrolReport report = new PatrolReport(
                        null, task.getId(), null,
                        task.getSkillName(), triggeredAt, "FAILED",
                        "Skill not found: " + task.getSkillName(), false);
                report.setUserId(task.getUserId());
                reportStore.save(report);
                return;
            }

            try {
                // Inject patrol-mode instruction: tell the LLM to output an
                // ALERT_SUMMARY line when an anomaly is detected. This replaces
                // all keyword/regex matching — the LLM itself decides whether
                // the result constitutes an anomaly and writes a human-readable
                // conclusion that is shown verbatim on the alert page.
                Map<String, String> patrolInputs = new LinkedHashMap<>(
                        task.getInputs() != null ? task.getInputs()
                                : Collections.<String, String>emptyMap());
                String patrolSuffix = "\n\n[巡检模式] 如果检测到异常，请在回复最后一行严格输出: ALERT_SUMMARY: <一句话告警总结>";
                String existingMsg = patrolInputs.get("_user_message");
                if (existingMsg != null) {
                    patrolInputs.put("_user_message", existingMsg + patrolSuffix);
                } else {
                    patrolInputs.put("_user_message", patrolSuffix.trim());
                }

                AgentTask agentTask = AgentTask.create(
                        task.getUserId(), task.getSkillName(),
                        patrolInputs, null);
                agentExecutor.execute(agentTask, skill);

                TaskStatus status = agentTask.getStatus();
                String statusStr = status != null ? status.name() : "UNKNOWN";
                String summary = agentTask.getReport() != null
                        ? agentTask.getReport() : "Patrol completed";

                // Extract the ALERT_SUMMARY line that the LLM was asked to emit.
                // If present, it's a definitive anomaly signal and its text is
                // used as the alert message (no further matching needed).
                String alertSummary = extractAlertSummary(summary);

                // Anomaly if: LLM emitted ALERT_SUMMARY, or task FAILED/TIMEOUT.
                boolean anomaly = alertSummary != null
                        || "FAILED".equals(statusStr) || "TIMEOUT".equals(statusStr);

                PatrolReport report = new PatrolReport(
                        null, task.getId(), agentTask.getTaskId(),
                        task.getSkillName(), triggeredAt, statusStr, summary, anomaly);
                report.setUserId(task.getUserId());
                reportStore.save(report);
                if (anomaly) {
                    recordAlert(report, task, alertSummary);
                    pushToChannels(report, null);
                }
            } catch (Exception e) {
                log.error("Patrol {} failed: {}", task.getId(), e.getMessage(), e);
                PatrolReport report = new PatrolReport(
                        null, task.getId(), null,
                        task.getSkillName(), triggeredAt, "FAILED", e.getMessage(), false);
                report.setUserId(task.getUserId());
                reportStore.save(report);
            }
        } finally {
            lockProvider.release(task.getId());
        }
    }

    /**
     * Extracts the {@code ALERT_SUMMARY:} line from the patrol report, if present.
     *
     * <p>During patrol execution, the LLM is instructed to output a final line
     * starting with {@code ALERT_SUMMARY:} when it detects an anomaly. This method
     * finds that line and returns the text after the prefix. The LLM — not
     * keyword/regex matching — decides whether the result is anomalous.</p>
     *
     * @param summary the full patrol report text
     * @return the alert summary text, or {@code null} if no ALERT_SUMMARY line found
     */
    protected String extractAlertSummary(String summary) {
        if (summary == null || summary.isEmpty()) return null;
        Pattern p = Pattern.compile("ALERT_SUMMARY:\\s*(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(summary);
        if (m.find()) {
            String text = m.group(1).trim();
            // Remove trailing markdown or quotes
            text = text.replaceAll("\\*+$", "").trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private void recordAlert(PatrolReport report, PatrolTask task, String alertSummary) {
        if (alertConverger == null) return;
        try {
            String alertSource = task.getName() != null && !task.getName().isEmpty()
                    ? task.getName() : task.getId();
            // Use the LLM-generated alert summary if available; otherwise fall back
            // to the full report summary.
            String alertMessage = alertSummary != null ? alertSummary
                    : (report.getSummary() != null ? report.getSummary() : "Anomaly detected");
            AnomalyEvent event = new AnomalyEvent(
                    "patrol", alertSource,
                    alertMessage,
                    task.getSkillName(),
                    null, task.getInputs());
            alertConverger.record(event);
            log.info("Patrol anomaly recorded as alert (patrolId={}, source={}, summary={})",
                    task.getId(), alertSource, alertSummary);
        } catch (Exception e) {
            log.error("Failed to record alert for patrol {}: {}", task.getId(), e.getMessage());
        }
    }

    private void pushToChannels(PatrolReport report, AnomalyEvent event) {
        if (pushChannels.isEmpty()) return;
        for (AlertPushChannel channel : pushChannels) {
            try {
                channel.push(report, event);
            } catch (Exception e) {
                log.error("Push channel '{}' failed (reportId={}): {}",
                        channel.type(), report.getId(), e.getMessage());
            }
        }
    }
}
