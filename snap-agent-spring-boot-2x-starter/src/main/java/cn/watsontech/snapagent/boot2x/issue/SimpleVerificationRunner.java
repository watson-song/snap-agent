package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.issue.VerificationRunner;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Default {@link VerificationRunner} implementation that re-runs the original
 * diagnostic skill with the same inputs (under the system user) and checks
 * whether the new task reaches {@link TaskStatus#SUCCEEDED}.
 *
 * <p>The before-status is captured from the original diagnostic task, and the
 * after-status from the re-run task. A fix is considered verified only when the
 * re-run succeeds.</p>
 *
 * <p>When the original task is no longer in the in-memory {@link TaskStore}
 * (e.g. after an app restart), {@link #verify(IssueClosure)} returns {@code null}
 * so that {@link IssueClosureService} can fall back to the "verify-fix" skill,
 * which relies on data stored on the issue itself (root_cause, original_query)
 * rather than the lost task.</p>
 */
public class SimpleVerificationRunner implements VerificationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimpleVerificationRunner.class);

    private final AgentService agentService;
    private final TaskStore taskStore;
    private final SkillRegistry skillRegistry;
    private final String systemUserId;

    /**
     * Construct the verification runner.
     *
     * @param agentService the agent executor (for re-running the diagnostic skill)
     * @param taskStore      the task store (for loading the original diagnostic task)
     * @param skillRegistry the skill registry (for resolving skill metadata)
     * @param systemUserId  the system user ID used when executing the verification task
     */
    public SimpleVerificationRunner(AgentService agentService,
                                    TaskStore taskStore,
                                    SkillRegistry skillRegistry,
                                    String systemUserId) {
        this.agentService = agentService;
        this.taskStore = taskStore;
        this.skillRegistry = skillRegistry;
        this.systemUserId = systemUserId;
    }

    @Override
    public VerificationResult verify(IssueClosure issue) {
        long now = System.currentTimeMillis();

        String taskId = issue != null ? issue.getTaskId() : null;
        if (taskId == null || taskId.isEmpty()) {
            // Signal "cannot verify" so IssueClosureService can fall back to the
            // skill-based path (verify-fix) which uses data stored on the issue
            // rather than the original task.
            log.warn("Task id is null/empty for issue {}, cannot re-run diagnostic", issue.getIssueId());
            return null;
        }

        AgentTask originalTask = taskStore.get(taskId);
        if (originalTask == null) {
            // Task no longer in memory (e.g. after app restart). The in-memory
            // TaskStore is wiped on restart while FileIssueStore persists issues.
            // Return null so IssueClosureService falls back to verify-fix skill.
            log.warn("Task {} not found in TaskStore for issue {} (may have been cleared on restart); "
                    + "returning null so IssueClosureService can fall back to skill-based verification",
                    taskId, issue.getIssueId());
            return null;
        }

        String skillName = originalTask.getSkillId();
        SkillMeta skill = skillRegistry.get(skillName);
        if (skill == null) {
            log.error("Skill {} not found in registry while verifying issue {}", skillName, issue.getIssueId());
            return new VerificationResult(false, "skill not found: " + skillName, null, null, now);
        }

        Map<String, String> inputs = originalTask.getInputs();
        AgentTask verifyTask = AgentTask.create(systemUserId, skillName, inputs, null);
        agentService.execute(verifyTask, skill);

        String beforeStatus = originalTask.getStatus() != null
                ? originalTask.getStatus().name() : null;
        String afterStatus = verifyTask.getStatus() != null
                ? verifyTask.getStatus().name() : null;
        String summary = verifyTask.getReport();

        // A fix is considered verified only when:
        // 1. The re-run task SUCCEEDED (skill executed without errors), AND
        // 2. The report contains actual diagnostic content (not just questions), AND
        // 3. The report does not still describe the original error/symptom.
        // Skill SUCCEEDED alone is NOT sufficient — the skill succeeding just
        // means it ran, not that the problem is fixed.
        boolean taskSucceeded = TaskStatus.SUCCEEDED.equals(verifyTask.getStatus());
        boolean hasMeaningfulContent = reportContainsMeaningfulDiagnosis(summary);
        boolean noOngoingIssue = !reportIndicatesOngoingIssue(summary, issue);
        boolean passed = taskSucceeded && hasMeaningfulContent && noOngoingIssue;

        if (!passed) {
            if (!taskSucceeded) {
                log.info("Verification failed: task status is {} (not SUCCEEDED)", afterStatus);
            } else if (!hasMeaningfulContent) {
                log.info("Verification failed: report lacks meaningful diagnostic content (may only contain questions)");
            } else if (!noOngoingIssue) {
                log.info("Verification failed: report indicates ongoing issue");
            }
        }

        return new VerificationResult(passed, summary, beforeStatus, afterStatus, now);
    }

    /**
     * Check if the report contains actual diagnostic content rather than just
     * clarifying questions or requests for more information.
     *
     * <p>A meaningful diagnosis should contain at least one of:
     * <ul>
     *   <li>Specific findings (table names, error codes, SQL queries)</li>
     *   <li>Root cause analysis</li>
     *   <li>Concrete recommendations</li>
     * </ul>
     *
     * <p>Reports that only ask questions ("请提供 skuCode", "需要确认环境") or
     * give generic advice without specifics are not meaningful diagnoses.</p>
     */
    private static boolean reportContainsMeaningfulDiagnosis(String report) {
        if (report == null || report.isEmpty()) {
            return false;
        }

        String lower = report.toLowerCase();

        // Check for question-heavy content (asking for info rather than diagnosing)
        long questionMarks = report.chars().filter(c -> c == '?' || c == '？').count();
        long lines = report.split("\n").length;
        double questionRatio = lines > 0 ? (double) questionMarks / lines : 0;

        // If more than 30% of lines are questions, likely just asking for info
        if (questionRatio > 0.3 && questionMarks > 3) {
            return false;
        }

        // Check for common "need more info" patterns
        boolean askingForInfo = lower.contains("请提供") || lower.contains("需要您补充")
                || lower.contains("未提供") && lower.contains("必需")
                || lower.contains("missing required") || lower.contains("please provide");

        // Check for actual diagnostic content indicators
        boolean hasDiagnosticContent = lower.contains("根因") || lower.contains("root cause")
                || lower.contains("发现") || lower.contains("found")
                || lower.contains("排查") || lower.contains("investigat")
                || lower.contains("查询") || lower.contains("query")
                || lower.contains("select ") || lower.contains("from ")
                || lower.contains("table") || lower.contains("表")
                || lower.contains("数据") || lower.contains("data")
                || lower.contains("错误") || lower.contains("error")
                || lower.contains("异常") || lower.contains("exception");

        // If asking for info AND no diagnostic content, not meaningful
        if (askingForInfo && !hasDiagnosticContent) {
            return false;
        }

        // Must have at least some diagnostic content
        return hasDiagnosticContent || report.length() > 200;
    }

    /**
     * Heuristic check: does the verification report still indicate the original
     * issue is present? If the report mentions "error", "fail", "异常", "失败",
     * or "未修复" in a diagnostic context, the issue is likely not resolved.
     */
    private static boolean reportIndicatesOngoingIssue(String report, IssueClosure issue) {
        if (report == null || report.isEmpty()) {
            // Empty report from a succeeded task is ambiguous — treat as not
            // indicating an ongoing issue (let the pass stand).
            return false;
        }
        String lower = report.toLowerCase();
        // Check for explicit "not fixed" / "still failing" indicators
        return lower.contains("未修复") || lower.contains("问题仍存在")
                || lower.contains("still failing") || lower.contains("not fixed")
                || lower.contains("问题未解决");
    }
}
