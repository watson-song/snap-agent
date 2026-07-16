package cn.watsontech.snapagent.core.workflow;

/**
 * Result of a single workflow step execution.
 */
public class StepResult {

    private final String stepName;
    private final String taskId;
    private final String status;
    private final String report;

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
}
