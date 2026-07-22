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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

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
                AgentTask agentTask = AgentTask.create(
                        task.getUserId(), task.getSkillName(),
                        task.getInputs(), null);
                agentExecutor.execute(agentTask, skill);

                TaskStatus status = agentTask.getStatus();
                String statusStr = status != null ? status.name() : "UNKNOWN";
                String summary = agentTask.getReport() != null
                        ? agentTask.getReport() : "Patrol completed";

                // Heuristic: if the report summary mentions anomaly keywords, mark as
                // anomaly-detected so push channels fire. This lets patrol skills signal
                // problems purely via their final text output, without code changes.
                // Custom alert keywords from the task config are also checked.
                boolean anomaly = detectAnomaly(summary, statusStr, task.getAlertKeywords());
                PatrolReport report = new PatrolReport(
                        null, task.getId(), agentTask.getTaskId(),
                        task.getSkillName(), triggeredAt, statusStr, summary, anomaly);
                report.setUserId(task.getUserId());
                reportStore.save(report);
                if (anomaly) {
                    recordAlert(report, task);
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
     * Detects anomaly indicators in the patrol report summary. Looks for
     * keywords like "CRITICAL", "WARNING", "异常", "错误", "失败". Override
     * threshold by subclassing if a different heuristic is needed.
     *
     * @param summary       the patrol report summary text
     * @param statusStr     the task status string (SUCCEEDED, FAILED, TIMEOUT, etc.)
     * @param alertKeywords comma-separated custom keywords from the patrol task config;
     *                      if the summary contains any of these, an anomaly is flagged.
     *                      May be null or empty to use only the default markers.
     */
    protected boolean detectAnomaly(String summary, String statusStr, String alertKeywords) {
        if ("FAILED".equals(statusStr) || "TIMEOUT".equals(statusStr)) {
            return true;
        }
        if (summary == null || summary.isEmpty()) return false;
        String lower = summary.toLowerCase(Locale.ROOT);

        // Phase 1: built-in keyword matching (cheap, no tokens)
        String[] markers = {"critical", "warning", "error", "exception", "failed",
                "异常", "错误", "失败", "告警", "风险"};
        for (String m : markers) {
            if (lower.contains(m)) return true;
        }

        // Phase 2: custom alert keywords from task config
        if (alertKeywords != null && !alertKeywords.isEmpty()) {
            for (String kw : alertKeywords.split(",")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty() && lower.contains(trimmed.toLowerCase(Locale.ROOT))) {
                    log.info("Patrol anomaly detected via custom keyword '{}'", trimmed);
                    return true;
                }
            }
        }

        // Phase 3: smart regex fallback — catches common phrasings that keywords miss
        // e.g. "无数据" keyword won't match "没有新增数据", but these patterns will
        String[] anomalyPatterns = {
                "没有.*数据", "没有.*记录", "没有.*新增",
                "无.*数据", "无.*记录", "无.*新增",
                "0条", "0行", "count.*0",
                "为空", "不存在", "未找到", "未.*发现",
                "not found", "no data", "no records", "empty result",
                "未运行", "未执行", "未完成",
                "超时", "timeout", "不可用", "unavailable"
        };
        for (String pattern : anomalyPatterns) {
            if (java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(lower).find()) {
                log.info("Patrol anomaly detected via smart pattern '{}'", pattern);
                return true;
            }
        }
        return false;
    }

    private void recordAlert(PatrolReport report, PatrolTask task) {
        if (alertConverger == null) return;
        try {
            String alertSource = task.getName() != null && !task.getName().isEmpty()
                    ? task.getName() : task.getId();
            AnomalyEvent event = new AnomalyEvent(
                    "patrol", alertSource,
                    report.getSummary() != null ? report.getSummary() : "Anomaly detected",
                    task.getSkillName(),
                    null, task.getInputs());
            alertConverger.record(event);
            log.info("Patrol anomaly recorded as alert (patrolId={}, source={})",
                    task.getId(), alertSource);
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
