package cn.watsontech.snapagent.core.cost;

/**
 * 成本追踪 SPI。记录每次 LLM 调用, 检查预算。
 *
 * <p>Host applications typically do not implement this SPI directly; the
 * starter module provides a default {@code DefaultCostTracker} that combines
 * a {@link CostStore} with a budget enforcer. A {@code NoopCostTracker}
 * is also available when cost tracking is disabled, so callers always have
 * a non-null tracker to invoke.</p>
 */
public interface CostTracker {

    /**
     * 记录一次 LLM 调用的成本。
     *
     * @param record the cost record to track
     */
    void record(CostRecord record);

    /**
     * 检查是否在预算内。
     *
     * <p>Returns {@code true} when the user/skill combination is within all
     * applicable budgets (per-user, per-skill, global). Returns
     * {@code false} when any budget is exceeded; callers should reject
     * new requests in that case.</p>
     *
     * @param userId    the user ID
     * @param skillName the skill name
     * @return {@code true} if within budget, {@code false} if exceeded
     */
    boolean isWithinBudget(String userId, String skillName);

    /**
     * 获取指定维度和维度值在时间范围内的成本汇总。
     *
     * @param dimension      维度 ("user" / "skill" / "global")
     * @param dimensionValue 维度值 (userId / skillName / "global")
     * @param from           inclusive lower bound (epoch millis)
     * @param to             inclusive upper bound (epoch millis)
     * @return the cost summary (never null)
     */
    CostSummary getSummary(String dimension, String dimensionValue, long from, long to);

    /**
     * 返回追踪器源类型标识 (e.g. {@code "default"}, {@code "noop"}).
     *
     * @return the tracker type identifier
     */
    String type();
}
