package cn.watsontech.snapagent.core.workflow;

import java.util.Map;

/**
 * 工作流执行引擎 SPI。
 *
 * <p>Host applications can implement this interface to provide alternative
 * execution strategies (e.g. parallel, DAG-based, human-in-the-loop). The
 * starter module provides a default {@code SimpleWorkflowEngine} that
 * executes steps sequentially with condition-based branching.</p>
 */
public interface WorkflowEngine {

    /**
     * 执行工作流。
     *
     * <p>The {@code triggerInputs} map provides the initial context for
     * variable resolution (referenced as {@code ${trigger.xxx}} in step
     * inputs and conditions). The engine returns a {@link WorkflowResult}
     * capturing success/failure, per-step results, and total duration.</p>
     *
     * @param workflow      the workflow definition to execute
     * @param triggerInputs the trigger context (may be empty, never null)
     * @return the workflow execution result (never null)
     */
    WorkflowResult execute(WorkflowDefinition workflow, Map<String, String> triggerInputs);

    /**
     * 返回引擎类型标识 (e.g. {@code "simple"}, {@code "dag"}).
     *
     * @return the engine type identifier
     */
    String type();
}
