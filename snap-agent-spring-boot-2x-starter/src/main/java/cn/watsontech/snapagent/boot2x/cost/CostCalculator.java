package cn.watsontech.snapagent.boot2x.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates monetary cost from token usage and pricing.
 *
 * <p>Stateless helper extracted from {@link CostTrackingLlmClient} so the
 * same cost computation can be reused outside of the LLM streaming
 * lifecycle (e.g. for budget forecasting or historical reconciliation).</p>
 */
public class CostCalculator {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;
    private final BigDecimal cacheReadPricePerMillion;

    /**
     * Construct a calculator with per-million token prices.
     *
     * @param inputPricePerMillion      price per 1M input tokens
     * @param outputPricePerMillion     price per 1M output tokens
     * @param cacheReadPricePerMillion  price per 1M cache-read tokens
     */
    public CostCalculator(BigDecimal inputPricePerMillion,
                          BigDecimal outputPricePerMillion,
                          BigDecimal cacheReadPricePerMillion) {
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
        this.cacheReadPricePerMillion = cacheReadPricePerMillion;
    }

    /**
     * Computes the total cost from token counts and per-million pricing.
     *
     * <p>Each component is computed independently with 6-decimal HALF_UP
     * rounding before being summed, matching the historical behavior of
     * {@code CostTrackingLlmClient.computeCost}.</p>
     *
     * @param inputTokens     input token count
     * @param outputTokens    output token count
     * @param cacheReadTokens cache-read token count
     * @return the computed cost (never null)
     */
    public BigDecimal computeCost(long inputTokens, long outputTokens, long cacheReadTokens) {
        BigDecimal inputCost = new BigDecimal(inputTokens)
                .multiply(inputPricePerMillion)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = new BigDecimal(outputTokens)
                .multiply(outputPricePerMillion)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal cacheCost = new BigDecimal(cacheReadTokens)
                .multiply(cacheReadPricePerMillion)
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).add(cacheCost);
    }
}
