package cn.watsontech.snapagent.core.cost;

import java.math.BigDecimal;

/**
 * 成本聚合结果。
 *
 * <p>Immutable value object capturing an aggregated cost view along a single
 * dimension (user, skill, or global). When no budget is configured the
 * {@link #getUtilization()} returns {@code -1.0} to indicate "no budget
 * applicable".</p>
 */
public final class CostSummary {

    private final String dimension;
    private final String dimensionValue;
    private final BigDecimal totalCost;
    private final long totalInputTokens;
    private final long totalOutputTokens;
    private final int requestCount;
    private final BigDecimal budget;
    private final double utilization;

    /**
     * Full-argument constructor.
     *
     * <p>When {@code budget} is {@code null}, the {@code utilization} argument
     * is ignored and {@link #getUtilization()} will return {@code -1.0}.
     * This prevents callers from accidentally reporting a misleading
     * utilization ratio when no budget is in effect.</p>
     *
     * @param dimension        维度 ("user" / "skill" / "global")
     * @param dimensionValue   维度值 (userId 或 skillName 或 "global")
     * @param totalCost        总费用
     * @param totalInputTokens 总输入 token 数
     * @param totalOutputTokens 总输出 token 数
     * @param requestCount     请求数
     * @param budget           预算 (可空, 空=无预算)
     * @param utilization      预算使用率 (0.0-1.0); 当 budget 为 null 时被忽略
     */
    public CostSummary(String dimension, String dimensionValue, BigDecimal totalCost,
                       long totalInputTokens, long totalOutputTokens, int requestCount,
                       BigDecimal budget, double utilization) {
        this.dimension = dimension;
        this.dimensionValue = dimensionValue;
        this.totalCost = totalCost;
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
        this.requestCount = requestCount;
        this.budget = budget;
        this.utilization = budget != null ? utilization : -1.0;
    }

    /** 维度 ("user" / "skill" / "global")。 */
    public String getDimension() {
        return dimension;
    }

    /** 维度值 (userId 或 skillName 或 "global")。 */
    public String getDimensionValue() {
        return dimensionValue;
    }

    /** 总费用。 */
    public BigDecimal getTotalCost() {
        return totalCost;
    }

    /** 总输入 token 数。 */
    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    /** 总输出 token 数。 */
    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    /** 请求数。 */
    public int getRequestCount() {
        return requestCount;
    }

    /** 预算 (可空, 空=无预算)。 */
    public BigDecimal getBudget() {
        return budget;
    }

    /**
     * 预算使用率 (0.0-1.0)。
     *
     * <p>Returns {@code -1.0} when no budget is configured
     * (i.e. {@link #getBudget()} is {@code null}).</p>
     */
    public double getUtilization() {
        return utilization;
    }

    @Override
    public String toString() {
        return "CostSummary{dimension='" + dimension + "', dimensionValue='"
                + dimensionValue + "', totalCost=" + totalCost
                + ", totalInputTokens=" + totalInputTokens
                + ", totalOutputTokens=" + totalOutputTokens
                + ", requestCount=" + requestCount
                + ", budget=" + budget + ", utilization=" + utilization + "}";
    }
}
