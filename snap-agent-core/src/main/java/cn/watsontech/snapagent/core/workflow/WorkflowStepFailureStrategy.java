package cn.watsontech.snapagent.core.workflow;

/**
 * Strategy when a workflow step fails.
 */
public enum WorkflowStepFailureStrategy {
    /** Abort the entire workflow (default). */
    ABORT,
    /** Skip the failed step and continue to the next. */
    SKIP,
    /** Retry the step once, then abort if it fails again. */
    RETRY
}
