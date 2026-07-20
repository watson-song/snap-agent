package cn.watsontech.snapagent.boot2x.patrol;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
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
    private final ConcurrentHashMap<String, PatrolTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore) {
        this(taskScheduler, agentExecutor, skillRegistry, reportStore,
                new NoopPatrolLockProvider(), 300L, Collections.<AlertPushChannel>emptyList());
    }

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore,
                                    PatrolLockProvider lockProvider, long lockTtlSeconds) {
        this(taskScheduler, agentExecutor, skillRegistry, reportStore,
                lockProvider, lockTtlSeconds, Collections.<AlertPushChannel>emptyList());
    }

    public ScheduledPatrolScheduler(TaskScheduler taskScheduler, AgentExecutor agentExecutor,
                                    SkillRegistry skillRegistry, PatrolReportStore reportStore,
                                    PatrolLockProvider lockProvider, long lockTtlSeconds,
                                    List<AlertPushChannel> pushChannels) {
        this.taskScheduler = taskScheduler;
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
        this.reportStore = reportStore;
        this.lockProvider = lockProvider;
        this.lockTtlSeconds = lockTtlSeconds;
        this.pushChannels = pushChannels != null ? new ArrayList<>(pushChannels)
                : new ArrayList<>();
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
                boolean anomaly = detectAnomaly(summary, statusStr);
                PatrolReport report = new PatrolReport(
                        null, task.getId(), agentTask.getTaskId(),
                        task.getSkillName(), triggeredAt, statusStr, summary, anomaly);
                report.setUserId(task.getUserId());
                reportStore.save(report);
                if (anomaly) {
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
     */
    protected boolean detectAnomaly(String summary, String statusStr) {
        if ("FAILED".equals(statusStr) || "TIMEOUT".equals(statusStr)) {
            return true;
        }
        if (summary == null || summary.isEmpty()) return false;
        String lower = summary.toLowerCase(Locale.ROOT);
        String[] markers = {"critical", "warning", "error", "exception", "failed",
                "异常", "错误", "失败", "告警", "风险"};
        for (String m : markers) {
            if (lower.contains(m)) return true;
        }
        return false;
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
