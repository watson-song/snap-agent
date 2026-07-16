package cn.watsontech.snapagent.core.workflow;

import java.util.List;

/**
 * Immutable workflow definition.
 *
 * <p>A workflow orchestrates multiple skills into a sequential pipeline
 * with conditional steps and failure strategies.</p>
 */
public interface Workflow {

    /** Unique workflow identifier. */
    String getId();

    /** Human-readable description. */
    String getDescription();

    /** Ordered list of steps. */
    List<WorkflowStep> getSteps();
}
