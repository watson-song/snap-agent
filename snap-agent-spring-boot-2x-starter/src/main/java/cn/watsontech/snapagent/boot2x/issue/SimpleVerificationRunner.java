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

import java.util.Map;

/**
 * Default {@link VerificationRunner} implementation that re-runs the original
 * diagnostic skill with the same inputs (under the system user) and checks
 * whether the new task reaches {@link TaskStatus#SUCCEEDED}.
 *
 * <p>The before-status is captured from the original diagnostic task, and the
 * after-status from the re-run task. A fix is considered verified only when the
 * re-run succeeds.</p>
 */
public class SimpleVerificationRunner implements VerificationRunner {

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
            return new VerificationResult(false, "task not found: taskId is null", null, null, now);
        }

        AgentTask originalTask = taskStore.get(taskId);
        if (originalTask == null) {
            return new VerificationResult(false, "task not found: " + taskId, null, null, now);
        }

        String skillName = originalTask.getSkillId();
        SkillMeta skill = skillRegistry.get(skillName);
        if (skill == null) {
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
