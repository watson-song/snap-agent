package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.VerificationResult;
import cn.watsontech.snapagent.core.closure.VerificationRunner;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * {@link VerificationRunner} that re-runs the diagnostic skill and compares
 * the task status before and after.
 *
 * <p>If the original task ended in SUCCEEDED (meaning the diagnostic query
 * returned data), verification re-runs the same skill with the same inputs
 * and checks if the new task also SUCCEEDED. If the original task FAILED,
 * verification checks if the re-run now SUCCEEDS.</p>
 */
public class SimpleVerificationRunner implements VerificationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimpleVerificationRunner.class);

    private final TaskStore taskStore;
    private final AgentExecutor agentExecutor;
    private final SkillRegistry skillRegistry;

    public SimpleVerificationRunner(TaskStore taskStore, AgentExecutor agentExecutor,
                                      SkillRegistry skillRegistry) {
        this.taskStore = taskStore;
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public VerificationResult verify(IssueClosure issue) {
        String resultId = "ver_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        if (issue.getTaskId() == null || issue.getTaskId().isEmpty()) {
            return new VerificationResult(resultId, false,
                    "无法验证：缺少原始任务 ID", null, null);
        }

        AgentTask originalTask = taskStore.get(issue.getTaskId());
        if (originalTask == null) {
            return new VerificationResult(resultId, false,
                    "无法验证：原始任务不存在 (" + issue.getTaskId() + ")", null, null);
        }

        String beforeStatus = originalTask.getStatus() != null
                ? originalTask.getStatus().name() : "UNKNOWN";

        // Re-run the diagnostic skill with the same inputs
        if (issue.getSkillName() == null || issue.getSkillName().isEmpty()) {
            issue.setSkillName(originalTask.getSkillId());
        }

        SkillMeta skill = skillRegistry.get(issue.getSkillName());
        if (skill == null) {
            return new VerificationResult(resultId, false,
                    "无法验证：skill 不存在 (" + issue.getSkillName() + ")",
                    beforeStatus, null);
        }

        try {
            AgentTask verifyTask = AgentTask.create(
                    originalTask.getUserId(),
                    issue.getSkillName(),
                    originalTask.getInputs(),
                    originalTask.getModel());

            agentExecutor.execute(verifyTask, skill);

            String afterStatus = verifyTask.getStatus() != null
                    ? verifyTask.getStatus().name() : "UNKNOWN";

            boolean passed = TaskStatus.SUCCEEDED.equals(verifyTask.getStatus());
            String summary = passed
                    ? "验证通过：重跑诊断成功，问题已修复。"
                    : "验证失败：重跑诊断未成功，问题可能未完全修复。";

            log.info("Verification for issue {}: before={}, after={}, passed={}",
                    issue.getId(), beforeStatus, afterStatus, passed);
            return new VerificationResult(resultId, passed, summary, beforeStatus, afterStatus);
        } catch (Exception e) {
            log.error("Verification failed for issue {}: {}", issue.getId(), e.getMessage());
            return new VerificationResult(resultId, false,
                    "验证异常: " + e.getMessage(), beforeStatus, "ERROR");
        }
    }
}
