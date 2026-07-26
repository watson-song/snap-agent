package cn.watsontech.snapagent.core.workflow;

/**
 * Workflow execution status.
 */
public enum WorkflowStatus {
    /** Workflow is currently running (not used in final results). */
    RUNNING,
    /** All steps completed successfully (or skipped). */
    COMPLETED,
    /** Workflow aborted due to a step failure with STOP policy. */
    ABORTED,
    /** Workflow failed due to an error. */
    FAILED
}
