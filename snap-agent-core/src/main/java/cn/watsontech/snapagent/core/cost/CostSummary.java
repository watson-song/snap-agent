package cn.watsontech.snapagent.core.cost;

/**
 * Aggregated cost summary for a dimension (user, skill, or global).
 */
public class CostSummary {

    private final String dimension;
    private final String dimensionValue;
    private final double totalCost;
    private final long totalInputTokens;
    private final long totalOutputTokens;
    private final long totalCacheReadTokens;
    private final int requestCount;
    private final double budget;
    private final double utilization;

    public CostSummary(String dimension, String dimensionValue,
                       double totalCost, long totalInputTokens, long totalOutputTokens,
                       long totalCacheReadTokens, int requestCount,
                       double budget, double utilization) {
        this.dimension = dimension;
        this.dimensionValue = dimensionValue;
        this.totalCost = totalCost;
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
        this.totalCacheReadTokens = totalCacheReadTokens;
        this.requestCount = requestCount;
        this.budget = budget;
        this.utilization = utilization;
    }

    public String getDimension() { return dimension; }
    public String getDimensionValue() { return dimensionValue; }
    public double getTotalCost() { return totalCost; }
    public long getTotalInputTokens() { return totalInputTokens; }
    public long getTotalOutputTokens() { return totalOutputTokens; }
    public long getTotalCacheReadTokens() { return totalCacheReadTokens; }
    public int getRequestCount() { return requestCount; }
    public double getBudget() { return budget; }
    public double getUtilization() { return utilization; }
}
