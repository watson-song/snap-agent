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
 *
 * <p>The {@link #getStatus()} method returns a structured
 * {@link WorkflowStatus}; the legacy {@link #isSuccess()} method is
 * retained for backward compatibility and returns {@code true} iff the
 * status is {@link WorkflowStatus#COMPLETED}.</p>
 */
public final class WorkflowResult {

    private final String workflowName;
    private final WorkflowStatus status;
    private final String failedStep;
    private final String errorMessage;
    private final Map<String, StepResult> stepResults;
    private final long durationMs;

    /**
     * Full-argument constructor.
     *
     * @param workflowName 工作流名
     * @param status       工作流最终状态 (null 视为 {@link WorkflowStatus#FAILED})
     * @param failedStep   失败步骤名 (可空, 仅 status=FAILED/ABORTED 时有意义)
     * @param errorMessage 失败原因 (可空)
     * @param stepResults   stepName → {@link StepResult} (null → empty map)
     * @param durationMs    执行耗时 (毫秒)
     */
    public WorkflowResult(String workflowName, WorkflowStatus status, String failedStep,
                          String errorMessage, Map<String, StepResult> stepResults,
                          long durationMs) {
        this.workflowName = workflowName;
        this.status = status != null ? status : WorkflowStatus.FAILED;
        this.failedStep = failedStep;
        this.errorMessage = errorMessage;
        this.stepResults = stepResults != null
                ? new LinkedHashMap<String, StepResult>(stepResults)
                : new LinkedHashMap<String, StepResult>();
        this.durationMs = durationMs;
    }

    /**
     * 工厂方法: 构造成功结果 (status = {@link WorkflowStatus#COMPLETED})。
     *
     * @param name        工作流名
     * @param stepResults  各步骤结果 (stepName → {@link StepResult})
     * @param durationMs   执行耗时 (毫秒)
     * @return a successful {@link WorkflowResult}
     */
    public static WorkflowResult success(String name, Map<String, StepResult> stepResults,
                                         long durationMs) {
        return new WorkflowResult(name, WorkflowStatus.COMPLETED, null, null,
                stepResults, durationMs);
    }

    /**
     * 工厂方法: 构造失败结果 (status = {@link WorkflowStatus#FAILED})。
     *
     * @param name        工作流名
     * @param failedStep  失败步骤名
     * @param error       失败原因
     * @param stepResults  各步骤结果 (含失败前已完成的步骤)
     * @param durationMs   执行耗时 (毫秒)
     * @return a failed {@link WorkflowResult}
     */
    public static WorkflowResult failure(String name, String failedStep, String error,
                                         Map<String, StepResult> stepResults, long durationMs) {
        return new WorkflowResult(name, WorkflowStatus.FAILED, failedStep, error,
                stepResults, durationMs);
    }

    /** 工作流名。 */
    public String getWorkflowName() {
        return workflowName;
    }

    /**
     * 是否成功 (向后兼容)。
     *
     * <p>Equivalent to {@code getStatus() == WorkflowStatus.COMPLETED}.</p>
     */
    public boolean isSuccess() {
        return status == WorkflowStatus.COMPLETED;
    }

    /**
     * 工作流最终状态。
     */
    public WorkflowStatus getStatus() {
        return status;
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
     * @return unmodifiable map of stepName → {@link StepResult}
     */
    public Map<String, StepResult> getStepResults() {
        return Collections.unmodifiableMap(stepResults);
    }

    /** 执行耗时 (毫秒)。 */
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "WorkflowResult{workflowName='" + workflowName + "', status=" + status
                + ", success=" + isSuccess() + ", failedStep='" + failedStep
                + "', errorMessage='" + errorMessage + "', stepResults=" + stepResults.keySet()
                + ", durationMs=" + durationMs + "}";
    }
}
