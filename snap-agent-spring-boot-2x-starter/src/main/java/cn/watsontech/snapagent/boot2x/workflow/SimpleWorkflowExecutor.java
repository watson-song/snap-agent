package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.StepResult;
import cn.watsontech.snapagent.core.workflow.Workflow;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStatus;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import cn.watsontech.snapagent.core.workflow.WorkflowStepFailureStrategy;
import cn.watsontech.snapagent.core.workflow.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link WorkflowExecutor} that runs workflow steps sequentially.
 *
 * <p>For each step:
 * <ol>
 *   <li>Resolve input references ({@code ${stepName.field}}) from prior step results.</li>
 *   <li>Check condition (simple string equality: {@code ${stepName.status} == 'SUCCEEDED'}).</li>
 *   <li>Create an {@link AgentTask} and execute via {@link AgentExecutor}.</li>
 *   <li>Apply failure strategy if the task did not succeed.</li>
 * </ol>
 */
public class SimpleWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(SimpleWorkflowExecutor.class);

    private final AgentExecutor agentExecutor;
    private final SkillRegistry skillRegistry;

    public SimpleWorkflowExecutor(AgentExecutor agentExecutor, SkillRegistry skillRegistry) {
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public WorkflowResult execute(Workflow workflow, String userId, String model,
                                  Map<String, String> triggerInputs) {
        Map<String, StepResult> results = new LinkedHashMap<String, StepResult>();
        Map<String, String> resolvedContext = new HashMap<String, String>();

        // Seed context with trigger inputs (prefixed with "trigger.")
        if (triggerInputs != null) {
            for (Map.Entry<String, String> entry : triggerInputs.entrySet()) {
                resolvedContext.put("trigger." + entry.getKey(), entry.getValue());
            }
        }

        for (WorkflowStep step : workflow.getSteps()) {
            // Check condition
            if (step.getCondition() != null && !step.getCondition().isEmpty()) {
                String resolved = resolveRefs(step.getCondition(), resolvedContext);
                if (!evaluateCondition(resolved)) {
                    log.info("Workflow {}: skipping step '{}' (condition not met: {})",
                            workflow.getId(), step.getName(), step.getCondition());
                    results.put(step.getName(), new StepResult(
                            step.getName(), null, "SKIPPED", "Condition not met"));
                    continue;
                }
            }

            // Resolve inputs
            Map<String, String> resolvedInputs = new HashMap<String, String>();
            for (Map.Entry<String, String> entry : step.getInputs().entrySet()) {
                resolvedInputs.put(entry.getKey(),
                        resolveRefs(entry.getValue(), resolvedContext));
            }

            // Look up skill
            SkillMeta skill = skillRegistry.get(step.getSkillId());
            if (skill == null) {
                String msg = "Skill not found: " + step.getSkillId();
                log.error("Workflow {}: step '{}' — {}", workflow.getId(), step.getName(), msg);
                results.put(step.getName(), new StepResult(
                        step.getName(), null, "FAILED", msg));
                if (step.getOnFailure() == WorkflowStepFailureStrategy.ABORT
                        || step.getOnFailure() == WorkflowStepFailureStrategy.RETRY) {
                    return new WorkflowResult(workflow.getId(), results,
                            WorkflowStatus.ABORTED, "Aborted at step '" + step.getName() + "': " + msg);
                }
                continue;
            }

            // Execute skill
            log.info("Workflow {}: executing step '{}' with skill '{}'",
                    workflow.getId(), step.getName(), step.getSkillId());

            boolean retried = false;
            AgentTask task;
            TaskStatus taskStatus;
            String report;

            do {
                task = AgentTask.create(userId, step.getSkillId(), resolvedInputs, model);
                agentExecutor.execute(task, skill);
                taskStatus = task.getStatus();
                report = task.getReport();

                if (taskStatus == TaskStatus.SUCCEEDED) {
                    break;
                }

                if (!retried && step.getOnFailure() == WorkflowStepFailureStrategy.RETRY) {
                    log.info("Workflow {}: retrying step '{}'", workflow.getId(), step.getName());
                    retried = true;
                    continue;
                }
                break;
            } while (true);

            StepResult sr = new StepResult(
                    step.getName(), task.getTaskId(),
                    taskStatus != null ? taskStatus.name() : "UNKNOWN",
                    report);
            results.put(step.getName(), sr);

            // Store results in context for reference resolution
            resolvedContext.put(step.getName() + ".report",
                    report != null ? report : "");
            resolvedContext.put(step.getName() + ".taskId",
                    task.getTaskId());
            resolvedContext.put(step.getName() + ".status",
                    taskStatus != null ? taskStatus.name() : "UNKNOWN");

            // Apply failure strategy
            if (taskStatus != TaskStatus.SUCCEEDED) {
                if (step.getOnFailure() == WorkflowStepFailureStrategy.ABORT
                        || (step.getOnFailure() == WorkflowStepFailureStrategy.RETRY && retried)) {
                    String msg = "Step '" + step.getName() + "' failed with status " + taskStatus;
                    log.warn("Workflow {}: {}", workflow.getId(), msg);
                    return new WorkflowResult(workflow.getId(), results,
                            WorkflowStatus.ABORTED, msg);
                }
                // SKIP: continue to next step
                log.info("Workflow {}: step '{}' failed, continuing (SKIP strategy)",
                        workflow.getId(), step.getName());
            }
        }

        return new WorkflowResult(workflow.getId(), results,
                WorkflowStatus.COMPLETED, "Workflow completed successfully");
    }

    /**
     * Resolves {@code ${ref}} patterns in the given string.
     * Supported refs: {@code ${trigger.field}}, {@code ${stepName.report}},
     * {@code ${stepName.taskId}}, {@code ${stepName.status}}.
     */
    String resolveRefs(String text, Map<String, String> context) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = text.indexOf('}', i + 2);
                if (end < 0) {
                    sb.append(text.charAt(i));
                    i++;
                    continue;
                }
                String ref = text.substring(i + 2, end);
                String value = context.get(ref);
                sb.append(value != null ? value : "${" + ref + "}");
                i = end + 1;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Evaluates a simple condition: {@code ${step.status} == 'SUCCEEDED'}.
     * Supports == and != operators with single-quoted values.
     */
    boolean evaluateCondition(String condition) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }
        String trimmed = condition.trim();
        // Simple == check
        int eqIdx = trimmed.indexOf("==");
        if (eqIdx >= 0) {
            String left = trimmed.substring(0, eqIdx).trim();
            String right = trimmed.substring(eqIdx + 2).trim();
            if (right.startsWith("'") && right.endsWith("'") && right.length() >= 2) {
                right = right.substring(1, right.length() - 1);
            }
            return left.equals(right);
        }
        // Simple != check
        int neqIdx = trimmed.indexOf("!=");
        if (neqIdx >= 0) {
            String left = trimmed.substring(0, neqIdx).trim();
            String right = trimmed.substring(neqIdx + 2).trim();
            if (right.startsWith("'") && right.endsWith("'") && right.length() >= 2) {
                right = right.substring(1, right.length() - 1);
            }
            return !left.equals(right);
        }
        // No operator: treat as boolean expression (non-empty = true)
        return true;
    }
}
