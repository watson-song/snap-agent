package cn.watsontech.snapagent.boot2x.cost;

/**
 * Converts LLM token counts to monetary cost based on configured pricing.
 */
public class CostCalculator {

    private final double inputPricePerMillion;
    private final double outputPricePerMillion;
    private final double cacheReadPricePerMillion;
    private final String currency;

    public CostCalculator(double inputPricePerMillion, double outputPricePerMillion,
                           double cacheReadPricePerMillion, String currency) {
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
        this.cacheReadPricePerMillion = cacheReadPricePerMillion;
        this.currency = currency;
    }

    /**
     * Calculate the cost for a single LLM call.
     *
     * @param inputTokens      input token count
     * @param outputTokens     output token count
     * @param cacheReadTokens  cache-hit input tokens (discounted)
     * @return cost in the configured currency
     */
    public double calculate(long inputTokens, long outputTokens, long cacheReadTokens) {
        double inputCost = (inputTokens / 1_000_000.0) * inputPricePerMillion;
        double outputCost = (outputTokens / 1_000_000.0) * outputPricePerMillion;
        double cacheCost = (cacheReadTokens / 1_000_000.0) * cacheReadPricePerMillion;
        return round(inputCost + outputCost + cacheCost, 6);
    }

    public String getCurrency() {
        return currency;
    }

    static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();
        long factor = (long) Math.pow(10, places);
        long tmp = Math.round(value * factor);
        return (double) tmp / factor;
    }
}
