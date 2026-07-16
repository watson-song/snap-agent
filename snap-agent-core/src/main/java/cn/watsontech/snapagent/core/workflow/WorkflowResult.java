package cn.watsontech.snapagent.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流执行结果。
 *
 * <p>Immutable value object: all fields are fixed at construction time.
 * The {@code stepResults} map is defensively copied on both input and
 * output to prevent external mutation. A null {@code stepResults} is
 * treated as an empty map.</p>
 */
public final class WorkflowResult {

    private final String workflowName;
    private final boolean success;
    private final String failedStep;
    private final String errorMessage;
    private final Map<String, String> stepResults;
    private final long durationMs;

    /**
     * Full-argument constructor.
     *
     * @param workflowName 工作流名
     * @param success      是否成功
     * @param failedStep   失败步骤名 (可空, 仅 success=false 时有意义)
     * @param errorMessage 失败原因 (可空)
     * @param stepResults   stepName → result text (null → empty map)
     * @param durationMs    执行耗时 (毫秒)
     */
    public WorkflowResult(String workflowName, boolean success, String failedStep,
                          String errorMessage, Map<String, String> stepResults,
                          long durationMs) {
        this.workflowName = workflowName;
        this.success = success;
        this.failedStep = failedStep;
        this.errorMessage = errorMessage;
        this.stepResults = stepResults != null
                ? new LinkedHashMap<String, String>(stepResults)
                : new LinkedHashMap<String, String>();
        this.durationMs = durationMs;
    }

    /**
     * 工厂方法: 构造成功结果。
     *
     * @param name        工作流名
     * @param stepResults  各步骤结果 (stepName → result text)
     * @param durationMs   执行耗时 (毫秒)
     * @return a successful {@link WorkflowResult}
     */
    public static WorkflowResult success(String name, Map<String, String> stepResults,
                                         long durationMs) {
        return new WorkflowResult(name, true, null, null, stepResults, durationMs);
    }

    /**
     * 工厂方法: 构造失败结果。
     *
     * @param name        工作流名
     * @param failedStep  失败步骤名
     * @param error       失败原因
     * @param stepResults  各步骤结果 (含失败前已完成的步骤)
     * @param durationMs   执行耗时 (毫秒)
     * @return a failed {@link WorkflowResult}
     */
    public static WorkflowResult failure(String name, String failedStep, String error,
                                         Map<String, String> stepResults, long durationMs) {
        return new WorkflowResult(name, false, failedStep, error, stepResults, durationMs);
    }

    /** 工作流名。 */
    public String getWorkflowName() {
        return workflowName;
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return success;
    }

    /** 失败步骤名 (可空)。 */
    public String getFailedStep() {
        return failedStep;
    }

    /** 失败原因 (可空)。 */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 各步骤结果 (不可变视图, 永不为 null)。
     *
     * @return unmodifiable map of stepName → result text
     */
    public Map<String, String> getStepResults() {
        return Collections.unmodifiableMap(stepResults);
    }

    /** 执行耗时 (毫秒)。 */
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "WorkflowResult{workflowName='" + workflowName + "', success="
                + success + ", failedStep='" + failedStep + "', errorMessage='"
                + errorMessage + "', stepResults=" + stepResults.keySet()
                + ", durationMs=" + durationMs + "}";
    }
}
