package cn.watsontech.snapagent.core.workflow;

/**
 * Result of a single workflow step execution.
 *
 * <p>Immutable value object.</p>
 */
public final class StepResult {
    private final String stepName;
    private final String taskId;
    private final String status;   // TaskStatus name (SUCCEEDED/FAILED/etc), or null if skipped
    private final String report;   // Agent report text, or null

    public StepResult(String stepName, String taskId, String status, String report) {
        this.stepName = stepName;
        this.taskId = taskId;
        this.status = status;
        this.report = report;
    }

    public String getStepName() { return stepName; }
    public String getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public String getReport() { return report; }

    @Override
    public String toString() {
        return "StepResult{stepName='" + stepName + "', taskId='" + taskId
                + "', status=" + status + ", report="
                + (report != null ? report.length() + " chars" : "null") + "}";
    }
}
