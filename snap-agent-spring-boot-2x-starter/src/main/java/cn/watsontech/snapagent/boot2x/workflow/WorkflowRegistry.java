package cn.watsontech.snapagent.boot2x.workflow;

import cn.watsontech.snapagent.core.workflow.Workflow;
import cn.watsontech.snapagent.core.workflow.WorkflowStep;
import cn.watsontech.snapagent.core.workflow.WorkflowStepFailureStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry of workflow definitions, loaded from configuration.
 */
public class WorkflowRegistry {

    private final Map<String, Workflow> workflows;

    public WorkflowRegistry(List<Workflow> workflowList) {
        Map<String, Workflow> map = new LinkedHashMap<String, Workflow>();
        if (workflowList != null) {
            for (Workflow wf : workflowList) {
                if (wf.getId() != null) {
                    map.put(wf.getId(), wf);
                }
            }
        }
        this.workflows = Collections.unmodifiableMap(map);
    }

    public Workflow get(String id) {
        return workflows.get(id);
    }

    public List<Workflow> list() {
        return new ArrayList<Workflow>(workflows.values());
    }

    public int size() {
        return workflows.size();
    }

    /**
     * Creates a Workflow from a definition map (parsed from YAML config).
     */
    public static Workflow fromConfig(String id, String description,
                                       List<Map<String, Object>> stepDefs) {
        List<WorkflowStep> steps = new ArrayList<WorkflowStep>();
        if (stepDefs != null) {
            for (Map<String, Object> sd : stepDefs) {
                String name = (String) sd.get("name");
                String skill = (String) sd.get("skill");
                @SuppressWarnings("unchecked")
                Map<String, String> inputs = (Map<String, String>) sd.get("inputs");
                String condition = (String) sd.get("condition");
                String onFailureStr = (String) sd.get("onFailure");
                WorkflowStepFailureStrategy onFailure = onFailureStr != null
                        ? WorkflowStepFailureStrategy.valueOf(onFailureStr.toUpperCase())
                        : WorkflowStepFailureStrategy.ABORT;
                steps.add(new WorkflowStep(name, skill, inputs, condition, onFailure));
            }
        }
        final List<WorkflowStep> finalSteps = Collections.unmodifiableList(steps);
        return new Workflow() {
            @Override
            public String getId() { return id; }
            @Override
            public String getDescription() { return description; }
            @Override
            public List<WorkflowStep> getSteps() { return finalSteps; }
        };
    }
}
