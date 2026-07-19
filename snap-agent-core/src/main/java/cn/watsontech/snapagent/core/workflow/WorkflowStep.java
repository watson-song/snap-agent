package cn.watsontech.snapagent.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流中的一个步骤。
 *
 * <p>Immutable value object: all fields are fixed at construction time.
 * The {@code inputs} map is defensively copied on both input and output to
 * prevent external mutation. A null {@code inputs} is treated as an empty
 * map. A null {@code onFailure} defaults to {@link #STOP}.</p>
 */
public final class WorkflowStep {

    /** 失败策略: 终止整个工作流。 */
    public static final String STOP = "STOP";
    /** 失败策略: 跳过当前步骤, 继续执行下一步。 */
    public static final String SKIP = "SKIP";
    /** 失败策略: 重试当前步骤一次。 */
    public static final String RETRY = "RETRY";

    private final String name;
    private final String skill;
    private final String condition;
    private final Map<String, String> inputs;
    private final String onFailure;

    /**
     * Construct an immutable workflow step.
     *
     * @param name      步骤名 (用于在 condition/inputs 中引用)
     * @param skill     要执行的 Skill 名
     * @param condition 条件表达式 (可空, 空则总是执行)
     *                  格式如 {@code "${stepName.result != null}"} 或
     *                  {@code "${stepName.result.contains('error')}"}
     * @param inputs    输入参数 (可含 {@code ${trigger.xxx}} /
     *                  {@code ${stepName.result}} 引用; null → empty map)
     * @param onFailure 失败策略 (可空, 空= {@link #STOP})
     */
    public WorkflowStep(String name, String skill, String condition,
                        Map<String, String> inputs, String onFailure) {
        this.name = name;
        this.skill = skill;
        this.condition = condition;
        this.inputs = inputs != null
                ? new LinkedHashMap<String, String>(inputs)
                : new LinkedHashMap<String, String>();
        this.onFailure = onFailure != null ? onFailure : STOP;
    }

    /** 步骤名 (用于引用)。 */
    public String getName() {
        return name;
    }

    /** 要执行的 Skill 名。 */
    public String getSkill() {
        return skill;
    }

    /** 条件表达式 (可空, 空则总是执行)。 */
    public String getCondition() {
        return condition;
    }

    /**
     * 输入参数 (不可变视图, 永不为 null)。
     *
     * @return unmodifiable map of inputs
     */
    public Map<String, String> getInputs() {
        return Collections.unmodifiableMap(inputs);
    }

    /**
     * 失败策略 (永不为 null, 默认 {@link #STOP})。
     *
     * @return one of {@link #STOP}, {@link #SKIP}, {@link #RETRY}
     */
    public String getOnFailure() {
        return onFailure;
    }

    @Override
    public String toString() {
        return "WorkflowStep{name='" + name + "', skill='" + skill
                + "', condition='" + condition + "', inputs=" + inputs.keySet()
                + ", onFailure='" + onFailure + "'}";
    }
}
