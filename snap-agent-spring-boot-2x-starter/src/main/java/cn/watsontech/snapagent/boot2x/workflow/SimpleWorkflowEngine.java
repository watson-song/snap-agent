package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.WorkflowDefinition;
import cn.watsontech.snapagent.core.workflow.WorkflowEngine;
import cn.watsontech.snapagent.core.workflow.WorkflowResult;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link WorkflowEngine} implementation that executes steps
 * sequentially with condition-based branching.
 *
 * <p>For each step:</p>
 * <ol>
 *   <li>Evaluate the condition (if present) — skip the step if false.</li>
 *   <li>Resolve input placeholders: {@code ${trigger.xxx}} from trigger
 *       inputs, {@code ${stepName.result}} from prior step results.</li>
 *   <li>Look up the {@link SkillMeta} via {@link SkillRegistry}, construct an
 *       {@link AgentTask}, and call {@link AgentExecutor#execute}
 *       synchronously.</li>
 *   <li>Extract the result from {@link AgentTask#getReport()}.</li>
 *   <li>On failure: respect {@link WorkflowStep#getOnFailure()} — STOP
 *       terminates, SKIP continues, RETRY re-executes once.</li>
 * </ol>
 *
 * <p>Condition evaluation is intentionally simple (string-based, not SpEL).
 * Supported patterns:</p>
 * <ul>
 *   <li>{@code ${stepName.result != null}} — step ran and produced a result</li>
 *   <li>{@code ${stepName.result.contains('text')}} — result contains text</li>
 *   <li>{@code ${stepName.result.size > 0}} — result is non-empty</li>
 * </ul>
 */
public class SimpleWorkflowEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(SimpleWorkflowEngine.class);

    /**
     * Matches condition expressions like:
     * <ul>
     *   <li>{@code ${stepName.result != null}}</li>
     *   <li>{@code ${stepName.result.contains('text')}}</li>
     *   <li>{@code ${stepName.result.size > 0}}</li>
     *   <li>{@code ${stepName.result}} (truthy if non-null and non-empty)</li>
     * </ul>
     */
    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "\\$\\{([\\w-]+)\\.result(?:\\s*!=\\s*null|\\.contains\\('([^']*)'\\)|\\.size\\s*>\\s*0)?\\}"
    );

    /** Matches input placeholders like {@code ${trigger.service}} or {@code ${stepName.result}}. */
    private static final Pattern INPUT_PLACEHOLDER_PATTERN = Pattern.compile(
            "\\$\\{([^}]+)\\}"
    );

    private final AgentExecutor agentExecutor;
    private final SkillRegistry skillRegistry;
    private final String systemUserId;

    /**
     * Construct the engine.
     *
     * @param agentExecutor the agent execution loop (synchronous calls)
     * @param skillRegistry the skill registry for looking up {@link SkillMeta}
     *                      by step skill name
     * @param systemUserId  the user identity to attribute workflow runs to
     */
    public SimpleWorkflowEngine(AgentExecutor agentExecutor,
                                SkillRegistry skillRegistry,
                                String systemUserId) {
        this.agentExecutor = agentExecutor;
        this.skillRegistry = skillRegistry;
        this.systemUserId = systemUserId;
    }

    @Override
    public WorkflowResult execute(WorkflowDefinition workflow,
                                   Map<String, String> triggerInputs) {
        long startTime = System.currentTimeMillis();
        Map<String, String> stepResults = new LinkedHashMap<String, String>();

        for (WorkflowStep step : workflow.getSteps()) {
            // 1. Evaluate condition — skip if false
            if (!evaluateCondition(step.getCondition(), stepResults)) {
                log.info("Workflow '{}': step '{}' skipped (condition false)",
                        workflow.getName(), step.getName());
                stepResults.put(step.getName(), null);
                continue;
            }

            // 2. Resolve input placeholders
            Map<String, String> resolvedInputs = resolveInputs(
                    step.getInputs(), triggerInputs, stepResults);

            // 3. Look up skill
            SkillMeta skill = skillRegistry != null ? skillRegistry.get(step.getSkill()) : null;
            if (skill == null) {
                String error = "skill not found: " + step.getSkill();
                log.error("Workflow '{}': step '{}' — {}", workflow.getName(),
                        step.getName(), error);
                if (WorkflowStep.STOP.equals(step.getOnFailure())) {
                    return WorkflowResult.failure(workflow.getName(), step.getName(),
                            error, stepResults, System.currentTimeMillis() - startTime);
                } else {
                    stepResults.put(step.getName(), null);
                    continue;
                }
            }

            // 4. Execute step (with optional retry)
            String result = executeStep(workflow.getName(), step, skill, resolvedInputs);

            // 5. Handle failure
            if (result == null) {
                String onFailure = step.getOnFailure();
                if (WorkflowStep.RETRY.equals(onFailure)) {
                    log.info("Workflow '{}': step '{}' retrying", workflow.getName(),
                            step.getName());
                    result = executeStep(workflow.getName(), step, skill, resolvedInputs);
                }
                if (result == null) {
                    if (WorkflowStep.STOP.equals(onFailure)) {
                        return WorkflowResult.failure(workflow.getName(), step.getName(),
                                "step execution failed", stepResults,
                                System.currentTimeMillis() - startTime);
                    }
                    // SKIP — record null and continue
                    log.info("Workflow '{}': step '{}' failed, skipping",
                            workflow.getName(), step.getName());
                    stepResults.put(step.getName(), null);
                    continue;
                }
            }

            // 6. Store result
            stepResults.put(step.getName(), result);
            log.info("Workflow '{}': step '{}' completed ({} chars)",
                    workflow.getName(), step.getName(),
                    result != null ? result.length() : 0);
        }

        long durationMs = System.currentTimeMillis() - startTime;
        return WorkflowResult.success(workflow.getName(), stepResults, durationMs);
    }

    @Override
    public String type() {
        return "simple";
    }

    /**
     * Executes a single step synchronously and returns the result text.
     *
     * @return the agent report text, or {@code null} on failure
     */
    private String executeStep(String workflowName, WorkflowStep step,
                              SkillMeta skill, Map<String, String> inputs) {
        try {
            AgentTask task = AgentTask.create(systemUserId, step.getSkill(), inputs, null);
            agentExecutor.execute(task, skill);

            if (task.getStatus() == TaskStatus.SUCCEEDED) {
                return task.getReport();
            }
            log.warn("Workflow '{}': step '{}' ended with status {}",
                    workflowName, step.getName(), task.getStatus());
            return null;
        } catch (RuntimeException e) {
            log.error("Workflow '{}': step '{}' threw exception: {}",
                    workflowName, step.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Evaluates a condition expression against the accumulated step results.
     *
     * @param condition   the condition string (may be {@code null})
     * @param stepResults the accumulated results so far
     * @return {@code true} if the condition is met (or null — always execute)
     */
    boolean evaluateCondition(String condition, Map<String, String> stepResults) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }

        Matcher matcher = CONDITION_PATTERN.matcher(condition.trim());
        if (!matcher.matches()) {
            log.warn("Unrecognized condition format '{}'; defaulting to true", condition);
            return true;
        }

        String stepName = matcher.group(1);
        String result = stepResults.get(stepName);

        // The second group captures the contains('text') inner text
        String containsText = matcher.group(2);

        // Determine which check was applied by inspecting the full match
        String fullCondition = condition.trim();
        if (fullCondition.contains("!= null")) {
            return result != null;
        }
        if (fullCondition.contains(".contains(")) {
            return result != null && containsText != null && result.contains(containsText);
        }
        if (fullCondition.contains(".size > 0")) {
            return result != null && !result.isEmpty();
        }

        // Bare ${stepName.result} — truthy if non-null and non-empty
        return result != null && !result.isEmpty();
    }

    /**
     * Resolves input placeholder values by substituting
     * {@code ${trigger.xxx}} and {@code ${stepName.result}} references.
     *
     * @param inputs        the raw step inputs
     * @param triggerInputs the trigger context
     * @param stepResults   the accumulated step results
     * @return a new map with resolved values
     */
    Map<String, String> resolveInputs(Map<String, String> inputs,
                                     Map<String, String> triggerInputs,
                                     Map<String, String> stepResults) {
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        if (inputs == null) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String value = entry.getValue();
            if (value != null) {
                value = resolveValue(value, triggerInputs, stepResults);
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * Resolves all {@code ${...}} placeholders in a single string value.
     */
    private String resolveValue(String value, Map<String, String> triggerInputs,
                                Map<String, String> stepResults) {
        Matcher matcher = INPUT_PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String expr = matcher.group(1);
            String replacement = resolvePlaceholder(expr, triggerInputs, stepResults);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement != null ? replacement : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a single placeholder expression (without the surrounding
     * {@code ${ }}).
     *
     * <p>Supported forms:</p>
     * <ul>
     *   <li>{@code trigger.xxx} → {@code triggerInputs.get("xxx")}</li>
     *   <li>{@code stepName.result} → {@code stepResults.get("stepName")}</li>
     * </ul>
     */
    private String resolvePlaceholder(String expr, Map<String, String> triggerInputs,
                                     Map<String, String> stepResults) {
        if (expr.startsWith("trigger.")) {
            String key = expr.substring("trigger.".length());
            return triggerInputs != null ? triggerInputs.get(key) : null;
        }
        if (expr.endsWith(".result")) {
            String stepName = expr.substring(0, expr.length() - ".result".length());
            return stepResults.get(stepName);
        }
        return null;
    }
}
