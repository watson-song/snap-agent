package cn.watsontech.snapagent.boot2x.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostCalculator}.
 */
class CostCalculatorTest {

    private CostCalculator calculator = new CostCalculator(
            new BigDecimal("3.00"),
            new BigDecimal("15.00"),
            new BigDecimal("0.30"));

    @Test
    void shouldComputeCostForKnownTokenCounts() {
        // (1000 * 3 + 500 * 15 + 200 * 0.3) / 1M = (3000 + 7500 + 60) / 1M = 0.010560
        BigDecimal cost = calculator.computeCost(1000, 500, 200);

        assertThat(cost).isCloseTo(new BigDecimal("0.010560"),
                within(new BigDecimal("0.000001")));
    }

    @Test
    void shouldReturnZeroForAllZeroTokens() {
        BigDecimal cost = calculator.computeCost(0, 0, 0);
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldComputeCostForInputTokensOnly() {
        // 1000 * 3 / 1M = 0.003
        BigDecimal cost = calculator.computeCost(1000, 0, 0);
        assertThat(cost).isCloseTo(new BigDecimal("0.003000"),
                within(new BigDecimal("0.000001")));
    }

    @Test
    void shouldComputeCostForOutputTokensOnly() {
        // 500 * 15 / 1M = 0.0075
        BigDecimal cost = calculator.computeCost(0, 500, 0);
        assertThat(cost).isCloseTo(new BigDecimal("0.007500"),
                within(new BigDecimal("0.000001")));
    }

    @Test
    void shouldComputeCostForCacheReadTokensOnly() {
        // 200 * 0.3 / 1M = 0.000060
        BigDecimal cost = calculator.computeCost(0, 0, 200);
        assertThat(cost).isCloseTo(new BigDecimal("0.000060"),
                within(new BigDecimal("0.000001")));
    }

    @Test
    void shouldRoundToSixDecimalPlacesHalfUp() {
        // Price 0.33 per million, 1 token → 0.00000033 → rounds to 0.000000 (HALF_UP at 6dp)
        CostCalculator tiny = new CostCalculator(
                new BigDecimal("0.33"),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        BigDecimal cost = tiny.computeCost(1, 0, 0);
        assertThat(cost.scale()).isLessThanOrEqualTo(6);
        // 1 * 0.33 / 1000000 = 0.00000033, HALF_UP at 6 decimals → 0.000000
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.000000"));

        // 3 tokens * 0.33 = 0.99 / 1M = 0.00000099 → HALF_UP at 6dp → 0.000001
        BigDecimal cost3 = tiny.computeCost(3, 0, 0);
        assertThat(cost3).isEqualByComparingTo(new BigDecimal("0.000001"));
    }

    @Test
    void shouldHandleLargeTokenCounts() {
        // 1M input tokens at $3.00/M = $3.00
        BigDecimal cost = calculator.computeCost(1_000_000L, 0, 0);
        assertThat(cost).isCloseTo(new BigDecimal("3.000000"),
                within(new BigDecimal("0.000001")));
    }

    private static org.assertj.core.data.Offset<BigDecimal> within(BigDecimal tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
