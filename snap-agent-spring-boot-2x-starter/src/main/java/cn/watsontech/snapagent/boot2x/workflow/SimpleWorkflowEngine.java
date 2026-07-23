package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStatus;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.workflow.StepResult;
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
 *   <li>Evaluate the condition (if present) — skip the step if false.
 *       Skipped steps are recorded with a {@link StepResult} whose
 *       {@code taskId}, {@code status}, and {@code report} are all null.</li>
 *   <li>Resolve input placeholders: {@code ${trigger.xxx}} from trigger
 *       inputs, {@code ${stepName.result}} / {@code ${stepName.status}} /
 *       {@code ${stepName.taskId}} from prior step results.</li>
 *   <li>Look up the {@link SkillMeta} via {@link SkillRegistry}, construct an
 *       {@link AgentTask}, and call {@link AgentExecutor#execute}
 *       synchronously.</li>
 *   <li>Build a {@link StepResult} from the resulting {@link AgentTask}
 *       (status = {@link TaskStatus#name()}, report = {@link AgentTask#getReport()}).</li>
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
 *   <li>{@code ${stepName.status == 'SUCCEEDED'}} — status equals value</li>
 *   <li>{@code ${stepName.status}} — truthy if status non-null and non-empty</li>
 *   <li>{@code ${stepName.taskId}} — truthy if taskId non-null</li>
 *   <li>{@code ${stepName.result}} — truthy if report non-null and non-empty</li>
 * </ul>
 */
public class SimpleWorkflowEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(SimpleWorkflowEngine.class);

    /**
     * Matches condition expressions. Captures:
     * <ol>
     *   <li>stepName (word chars and hyphens)</li>
     *   <li>field — one of {@code result}, {@code status}, or {@code taskId}</li>
     *   <li>containsText — inner text of {@code .contains('...')} (may be null)</li>
     *   <li>equalsValue — RHS of {@code == '...'} (may be null)</li>
     * </ol>
     * The trailing operators ({@code != null}, {@code .contains('...')},
     * {@code .size > 0}, {@code == '...'}) are all optional, allowing
     * bare truthy checks like {@code ${stepName.result}}.
     */
    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "\\$\\{([\\w-]+)\\.(result|status|taskId)" +
            "(?:\\s*!=\\s*null" +
            "|\\.contains\\('([^']*)'\\)" +
            "|\\.size\\s*>\\s*0" +
            "|\\s*==\\s*'([^']*)'" +
            ")?\\}"
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
        Map<String, StepResult> stepResults = new LinkedHashMap<String, StepResult>();

        for (WorkflowStep step : workflow.getSteps()) {
            // 1. Evaluate condition — skip if false
            if (!evaluateCondition(step.getCondition(), stepResults)) {
                log.info("Workflow '{}': step '{}' skipped (condition false)",
                        workflow.getName(), step.getName());
                stepResults.put(step.getName(),
                        new StepResult(step.getName(), null, null, null));
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
                    stepResults.put(step.getName(),
                            new StepResult(step.getName(), null, null, null));
                    continue;
                }
            }

            // 4. Execute step (with optional retry)
            StepResult stepResult = executeStep(workflow.getName(), step, skill, resolvedInputs);

            // 5. Handle failure — retry once if RETRY policy, then SKIP/STOP
            if (stepResult == null || !TaskStatus.SUCCEEDED.name().equals(stepResult.getStatus())) {
                String onFailure = step.getOnFailure();
                if (WorkflowStep.RETRY.equals(onFailure)) {
                    log.info("Workflow '{}': step '{}' retrying", workflow.getName(),
                            step.getName());
                    stepResult = executeStep(workflow.getName(), step, skill, resolvedInputs);
                }
                if (stepResult == null || !TaskStatus.SUCCEEDED.name().equals(stepResult.getStatus())) {
                    if (WorkflowStep.STOP.equals(onFailure)) {
                        // Record the failed step's result before aborting.
                        // When the executor threw an exception, stepResult is
                        // null; synthesize a FAILED marker so the step is still
                        // visible in the workflow result (per UC-06).
                        stepResults.put(step.getName(),
                                stepResult != null ? stepResult
                                        : new StepResult(step.getName(), null,
                                                TaskStatus.FAILED.name(), null));
                        return WorkflowResult.failure(workflow.getName(), step.getName(),
                                "step execution failed", stepResults,
                                System.currentTimeMillis() - startTime);
                    }
                    // SKIP — record a FAILED-marker StepResult and continue
                    log.info("Workflow '{}': step '{}' failed, skipping",
                            workflow.getName(), step.getName());
                    stepResults.put(step.getName(),
                            stepResult != null ? stepResult
                                    : new StepResult(step.getName(), null,
                                            TaskStatus.FAILED.name(), null));
                    continue;
                }
            }

            // 6. Store successful result
            stepResults.put(step.getName(), stepResult);
            String report = stepResult.getReport();
            log.info("Workflow '{}': step '{}' completed ({} chars)",
                    workflow.getName(), step.getName(),
                    report != null ? report.length() : 0);
        }

        long durationMs = System.currentTimeMillis() - startTime;
        return WorkflowResult.success(workflow.getName(), stepResults, durationMs);
    }

    @Override
    public String type() {
        return "simple";
    }

    /**
     * Executes a single step synchronously and returns the resulting
     * {@link StepResult}.
     *
     * @return the step result, or {@code null} on exception (no task produced)
     */
    private StepResult executeStep(String workflowName, WorkflowStep step,
                                   SkillMeta skill, Map<String, String> inputs) {
        try {
            AgentTask task = AgentTask.create(systemUserId, step.getSkill(), inputs, null);
            agentExecutor.execute(task, skill);

            String statusName = task.getStatus() != null ? task.getStatus().name() : null;
            if (task.getStatus() == TaskStatus.SUCCEEDED) {
                return new StepResult(step.getName(), task.getTaskId(),
                        statusName, task.getReport());
            }
            log.warn("Workflow '{}': step '{}' ended with status {}",
                    workflowName, step.getName(), task.getStatus());
            return new StepResult(step.getName(), task.getTaskId(), statusName, null);
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
    boolean evaluateCondition(String condition, Map<String, StepResult> stepResults) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }

        Matcher matcher = CONDITION_PATTERN.matcher(condition.trim());
        if (!matcher.matches()) {
            log.warn("Unrecognized condition format '{}'; defaulting to true", condition);
            return true;
        }

        String stepName = matcher.group(1);
        String field = matcher.group(2);
        String containsText = matcher.group(3);
        String equalsValue = matcher.group(4);

        StepResult stepResult = stepResults.get(stepName);

        // Determine which check was applied by inspecting the full match
        String fullCondition = condition.trim();
        if (fullCondition.contains("!= null")) {
            // Only meaningful for .result today
            return stepResult != null && stepResult.getReport() != null;
        }
        if (fullCondition.contains(".contains(")) {
            return stepResult != null && stepResult.getReport() != null
                    && containsText != null && stepResult.getReport().contains(containsText);
        }
        if (fullCondition.contains(".size > 0")) {
            return stepResult != null && stepResult.getReport() != null
                    && !stepResult.getReport().isEmpty();
        }
        if (fullCondition.contains("== '")) {
            String value = extractValue(stepResult, field);
            return value != null && value.equals(equalsValue);
        }

        // Bare truthy check — semantics depend on field
        return isTruthy(extractValue(stepResult, field));
    }

    /**
     * Extracts the textual value of the named field from a {@link StepResult}.
     *
     * @param stepResult the step result (may be {@code null})
     * @param field      one of {@code result}, {@code status}, or {@code taskId}
     * @return the field value, or {@code null} if step or field is missing
     */
    private String extractValue(StepResult stepResult, String field) {
        if (stepResult == null) {
            return null;
        }
        if ("result".equals(field)) {
            return stepResult.getReport();
        }
        if ("status".equals(field)) {
            return stepResult.getStatus();
        }
        if ("taskId".equals(field)) {
            return stepResult.getTaskId();
        }
        return null;
    }

    /** Truthy: non-null and (for strings) non-empty. */
    private boolean isTruthy(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Resolves input placeholder values by substituting
     * {@code ${trigger.xxx}} and {@code ${stepName.result|status|taskId}}
     * references.
     *
     * @param inputs        the raw step inputs
     * @param triggerInputs the trigger context
     * @param stepResults   the accumulated step results
     * @return a new map with resolved values
     */
    Map<String, String> resolveInputs(Map<String, String> inputs,
                                     Map<String, String> triggerInputs,
                                     Map<String, StepResult> stepResults) {
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
                                Map<String, StepResult> stepResults) {
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
     *   <li>{@code stepName.result} → step report text</li>
     *   <li>{@code stepName.status} → step status name</li>
     *   <li>{@code stepName.taskId} → step task id</li>
     * </ul>
     */
    private String resolvePlaceholder(String expr, Map<String, String> triggerInputs,
                                     Map<String, StepResult> stepResults) {
        if (expr.startsWith("trigger.")) {
            String key = expr.substring("trigger.".length());
            return triggerInputs != null ? triggerInputs.get(key) : null;
        }
        // Split "stepName.field" — field is the last segment after '.'
        int dot = expr.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String stepName = expr.substring(0, dot);
        String field = expr.substring(dot + 1);
        StepResult stepResult = stepResults != null ? stepResults.get(stepName) : null;
        if (stepResult == null) {
            return null;
        }
        if ("result".equals(field)) {
            return stepResult.getReport();
        }
        if ("status".equals(field)) {
            return stepResult.getStatus();
        }
        if ("taskId".equals(field)) {
            return stepResult.getTaskId();
        }
        return null;
    }
}
