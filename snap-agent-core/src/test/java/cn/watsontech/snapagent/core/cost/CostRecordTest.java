package cn.watsontech.snapagent.core.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostRecord}.
 */
class CostRecordTest {

    private static final long TIMESTAMP = 1_700_000_000_000L;

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        BigDecimal cost = new BigDecimal("0.0123");
        CostRecord record = new CostRecord(
                "cost-001", "user-1", "health-check", "task-100",
                "claude-sonnet-4-20250514", 1200L, 350L, 800L, cost,
                TIMESTAMP);

        assertThat(record.getId()).isEqualTo("cost-001");
        assertThat(record.getUserId()).isEqualTo("user-1");
        assertThat(record.getSkillName()).isEqualTo("health-check");
        assertThat(record.getTaskId()).isEqualTo("task-100");
        assertThat(record.getModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(record.getInputTokens()).isEqualTo(1200L);
        assertThat(record.getOutputTokens()).isEqualTo(350L);
        assertThat(record.getCacheReadTokens()).isEqualTo(800L);
        assertThat(record.getCost()).isEqualByComparingTo("0.0123");
        assertThat(record.getTimestamp()).isEqualTo(TIMESTAMP);
    }

    @Test
    void shouldAutoGenerateIdWhenNullPassed() {
        CostRecord record = new CostRecord(
                null, "user-1", "health-check", "task-100",
                "claude-sonnet-4-20250514", 100L, 50L, 0L,
                BigDecimal.ZERO, TIMESTAMP);

        String id = record.getId();
        assertThat(id).isNotNull();
        assertThat(id).startsWith("cost_");
        // Format: cost_{millis}_{8hex} — verify three underscore-separated parts
        String[] parts = id.split("_");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("cost");
        assertThat(parts[1]).matches("\\d+");
        assertThat(parts[2]).hasSize(8);
    }

    @Test
    void shouldGenerateUniqueIdsAcrossMultipleRecords() {
        CostRecord r1 = new CostRecord(
                null, "u", "s", "t", "m", 1L, 1L, 0L,
                BigDecimal.ZERO, TIMESTAMP);
        CostRecord r2 = new CostRecord(
                null, "u", "s", "t", "m", 1L, 1L, 0L,
                BigDecimal.ZERO, TIMESTAMP);

        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    @Test
    void shouldAcceptZeroAndLargeTokenCounts() {
        CostRecord zero = new CostRecord(
                "id-0", "u", "s", null, "m", 0L, 0L, 0L,
                BigDecimal.ZERO, 0L);
        assertThat(zero.getInputTokens()).isZero();
        assertThat(zero.getOutputTokens()).isZero();
        assertThat(zero.getCacheReadTokens()).isZero();
        assertThat(zero.getCost()).isEqualByComparingTo("0");
        assertThat(zero.getTimestamp()).isZero();
        assertThat(zero.getTaskId()).isNull();

        CostRecord large = new CostRecord(
                "id-1", "u", "s", "t", "m",
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                new BigDecimal("9999999999.99"), Long.MAX_VALUE);
        assertThat(large.getInputTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(large.getOutputTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(large.getCacheReadTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(large.getTimestamp()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void shouldPreserveBigDecimalCostScale() {
        BigDecimal cost = new BigDecimal("1.23456789");
        CostRecord record = new CostRecord(
                "id", "u", "s", "t", "m", 1L, 1L, 0L, cost, TIMESTAMP);

        assertThat(record.getCost()).isEqualByComparingTo(cost);
        assertThat(record.getCost().scale()).isEqualTo(cost.scale());
    }

    @Test
    void shouldHaveMeaningfulToString() {
        BigDecimal cost = new BigDecimal("0.05");
        CostRecord record = new CostRecord(
                "cost-001", "user-1", "health-check", "task-100",
                "claude-sonnet-4-20250514", 1200L, 350L, 800L, cost,
                TIMESTAMP);

        String str = record.toString();
        assertThat(str).contains("CostRecord");
        assertThat(str).contains("cost-001");
        assertThat(str).contains("user-1");
        assertThat(str).contains("health-check");
        assertThat(str).contains("task-100");
        assertThat(str).contains("claude-sonnet-4-20250514");
        assertThat(str).contains("1200");
        assertThat(str).contains("350");
        assertThat(str).contains("800");
        assertThat(str).contains("0.05");
        assertThat(str).contains(String.valueOf(TIMESTAMP));
    }
}
