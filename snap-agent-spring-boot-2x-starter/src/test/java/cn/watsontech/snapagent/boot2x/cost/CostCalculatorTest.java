package cn.watsontech.snapagent.boot2x.cost;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator(3.0, 15.0, 0.3, "CNY");

    @Test
    void calculate_basicCost() {
        // 1M input @ 3.0 = 3.0, 1M output @ 15.0 = 15.0, 1M cache @ 0.3 = 0.3
        double cost = calculator.calculate(1_000_000, 1_000_000, 1_000_000);
        assertThat(cost).isEqualTo(18.3);
    }

    @Test
    void calculate_zeroTokens() {
        double cost = calculator.calculate(0, 0, 0);
        assertThat(cost).isEqualTo(0.0);
    }

    @Test
    void calculate_smallTokenCount() {
        // 1000 input = 0.003, 500 output = 0.0075, 0 cache = 0
        double cost = calculator.calculate(1000, 500, 0);
        assertThat(cost).isCloseTo(0.0105, within(0.0001));
    }

    @Test
    void calculate_cacheDiscounted() {
        // 1M input, 0 output, 1M cache
        double withCache = calculator.calculate(1_000_000, 0, 1_000_000);
        // 3.0 (input) + 0.0 (output) + 0.3 (cache) = 3.3
        assertThat(withCache).isCloseTo(3.3, within(0.0001));
    }

    @Test
    void getCurrency_returnsConfiguredValue() {
        assertThat(calculator.getCurrency()).isEqualTo("CNY");
    }

    @Test
    void round_roundsToSixPlaces() {
        assertThat(CostCalculator.round(1.23456789, 6)).isEqualTo(1.234568);
    }
}
