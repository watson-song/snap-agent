package cn.watsontech.snapagent.core.cost;

import java.math.BigDecimal;

/**
 * Computes cost from token counts.
 * Host implementations use model-specific pricing tables.
 */
public interface CostCalculator {
    BigDecimal computeCost(long inputTokens, long outputTokens, long cacheReadTokens);
}
