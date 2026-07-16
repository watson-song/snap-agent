package cn.watsontech.snapagent.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a complete workflow execution.
 */
public class WorkflowResult {

    private final String workflowId;
    private final Map<String, StepResult> stepResults;
    private final WorkflowStatus status;
    private final String summary;

    public WorkflowResult(String workflowId, Map<String, StepResult> stepResults,
                          WorkflowStatus status, String summary) {
        this.workflowId = workflowId;
        this.stepResults = stepResults == null
                ? Collections.<String, StepResult>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, StepResult>(stepResults));
        this.status = status;
        this.summary = summary;
    }

    public String getWorkflowId() { return workflowId; }
    public Map<String, StepResult> getStepResults() { return stepResults; }
    public WorkflowStatus getStatus() { return status; }
    public String getSummary() { return summary; }
}
