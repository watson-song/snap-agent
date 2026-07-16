package cn.watsontech.snapagent.core.workflow;

import java.util.Map;

/**
 * SPI for executing workflows.
 *
 * <p>Implementations run each step sequentially, resolving input references
 * from prior step results, and applying failure strategies when steps fail.</p>
 */
public interface WorkflowExecutor {

    /**
     * Execute the given workflow.
     *
     * @param workflow      the workflow definition
     * @param userId        the user initiating the workflow
     * @param model         the LLM model to use for each step
     * @param triggerInputs initial inputs provided by the trigger (e.g., event payload, manual start)
     * @return the aggregated workflow result
     */
    WorkflowResult execute(Workflow workflow, String userId, String model,
                           Map<String, String> triggerInputs);
}
