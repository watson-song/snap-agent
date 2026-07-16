package cn.watsontech.snapagent.core.workflow;

import java.util.Collections;
import java.util.Map;

/**
 * A single step in a workflow.
 *
 * <p>Each step executes a skill with resolved inputs. Inputs may contain
 * references to prior step results in the form {@code ${stepName.field}},
 * where {@code field} is one of: {@code report}, {@code taskId},
 * {@code status}.</p>
 */
public class WorkflowStep {

    private final String name;
    private final String skillId;
    private final Map<String, String> inputs;
    private final String condition;
    private final WorkflowStepFailureStrategy onFailure;

    public WorkflowStep(String name, String skillId, Map<String, String> inputs,
                        String condition, WorkflowStepFailureStrategy onFailure) {
        this.name = name;
        this.skillId = skillId;
        this.inputs = inputs == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(inputs);
        this.condition = condition;
        this.onFailure = onFailure == null ? WorkflowStepFailureStrategy.ABORT : onFailure;
    }

    public String getName() { return name; }
    public String getSkillId() { return skillId; }
    public Map<String, String> getInputs() { return inputs; }
    public String getCondition() { return condition; }
    public WorkflowStepFailureStrategy getOnFailure() { return onFailure; }
}
