package cn.watsontech.snapagent.core.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工作流定义。
 *
 * <p>Immutable value object: name, description and steps are fixed at
 * construction time. The {@code steps} list is defensively copied on both
 * input and output to prevent external mutation. A null {@code steps} list
 * is treated as an empty list.</p>
 */
public final class WorkflowDefinition {

    private final String name;
    private final String description;
    private final List<WorkflowStep> steps;

    /**
     * Construct an immutable workflow definition.
     *
     * @param name        工作流名 (用于引用)
     * @param description 工作流描述 (可空)
     * @param steps       步骤列表 (null → empty list)
     */
    public WorkflowDefinition(String name, String description, List<WorkflowStep> steps) {
        this.name = name;
        this.description = description;
        this.steps = steps != null
                ? new ArrayList<WorkflowStep>(steps)
                : new ArrayList<WorkflowStep>();
    }

    /** 工作流名。 */
    public String getName() {
        return name;
    }

    /** 工作流描述 (可空)。 */
    public String getDescription() {
        return description;
    }

    /**
     * 步骤列表 (不可变视图, 永不为 null)。
     *
     * @return unmodifiable list of steps
     */
    public List<WorkflowStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    @Override
    public String toString() {
        return "WorkflowDefinition{name='" + name + "', description='"
                + description + "', steps=" + steps.size() + "}";
    }
}
