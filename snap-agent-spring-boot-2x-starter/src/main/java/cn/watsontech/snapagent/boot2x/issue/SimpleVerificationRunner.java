package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
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

    private final AgentExecutor agentExecutor;
    private final TaskStore taskStore;
    private final SkillRegistry skillRegistry;
    private final String systemUserId;

    /**
     * Construct the verification runner.
     *
     * @param agentExecutor the agent executor (for re-running the diagnostic skill)
     * @param taskStore      the task store (for loading the original diagnostic task)
     * @param skillRegistry the skill registry (for resolving skill metadata)
     * @param systemUserId  the system user ID used when executing the verification task
     */
    public SimpleVerificationRunner(AgentExecutor agentExecutor,
                                    TaskStore taskStore,
                                    SkillRegistry skillRegistry,
                                    String systemUserId) {
        this.agentExecutor = agentExecutor;
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
        agentExecutor.execute(verifyTask, skill);

        String beforeStatus = originalTask.getStatus() != null
                ? originalTask.getStatus().name() : null;
        String afterStatus = verifyTask.getStatus() != null
                ? verifyTask.getStatus().name() : null;
        boolean passed = TaskStatus.SUCCEEDED.equals(verifyTask.getStatus());
        String summary = verifyTask.getReport();

        return new VerificationResult(passed, summary, beforeStatus, afterStatus, now);
    }
}
