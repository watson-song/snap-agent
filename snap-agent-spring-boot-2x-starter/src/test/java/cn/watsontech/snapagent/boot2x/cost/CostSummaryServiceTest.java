package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CostSummaryService}.
 */
class CostSummaryServiceTest {

    @TempDir
    Path tempDir;

    private CostStore costStore;
    private CostSummaryService service;

    @BeforeEach
    void setUp() {
        costStore = new FileCostStore(tempDir.toString());
        service = new CostSummaryService(costStore,
                new BigDecimal("10.00"), new BigDecimal("50.00"),
                new BigDecimal("500.00"));
    }

    private void recordCost(String userId, String skillName, BigDecimal cost, long timestamp) {
        costStore.save(new CostRecord(null, userId, skillName, null,
                "claude-sonnet-4-6", 100, 50, 0, cost, timestamp));
    }

    @Test
    void shouldGetUserSummaryWithBudget() {
        long now = System.currentTimeMillis();
        recordCost("user-A", "health-check", new BigDecimal("5.00"), now);
        recordCost("user-A", "database-query", new BigDecimal("2.00"), now + 1000);
        recordCost("user-B", "health-check", new BigDecimal("3.00"), now + 2000);

        CostSummary summary = service.getUserSummary("user-A", 0, now + 10000);
        assertThat(summary.getDimension()).isEqualTo("user");
        assertThat(summary.getDimensionValue()).isEqualTo("user-A");
        assertThat(summary.getTotalCost()).isEqualByComparingTo(new BigDecimal("7.00"));
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getBudget()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(summary.getUtilization()).isCloseTo(0.7, within(0.01));
    }

    @Test
    void shouldGetSkillSummaryWithBudget() {
        long now = System.currentTimeMillis();
        recordCost("user-A", "health-check", new BigDecimal("5.00"), now);
        recordCost("user-B", "health-check", new BigDecimal("10.00"), now + 1000);
        recordCost("user-A", "database-query", new BigDecimal("2.00"), now + 2000);

        CostSummary summary = service.getSkillSummary("health-check", 0, now + 10000);
        assertThat(summary.getDimension()).isEqualTo("skill");
        assertThat(summary.getDimensionValue()).isEqualTo("health-check");
        assertThat(summary.getTotalCost()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getBudget()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void shouldGetGlobalSummaryWithBudget() {
        long now = System.currentTimeMillis();
        recordCost("user-A", "health-check", new BigDecimal("5.00"), now);
        recordCost("user-B", "database-query", new BigDecimal("10.00"), now + 1000);

        CostSummary summary = service.getGlobalSummary(0, now + 10000);
        assertThat(summary.getDimension()).isEqualTo("global");
        assertThat(summary.getDimensionValue()).isEqualTo("global");
        assertThat(summary.getTotalCost()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getBudget()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void shouldReturnZeroCostWhenNoRecords() {
        CostSummary summary = service.getGlobalSummary(0, System.currentTimeMillis() + 10000);
        assertThat(summary.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldReturnNegativeOneUtilizationWhenNoBudget() {
        CostSummaryService noBudgetService = new CostSummaryService(costStore,
                null, null, null);
        long now = System.currentTimeMillis();
        recordCost("user-A", "health-check", new BigDecimal("5.00"), now);

        CostSummary summary = noBudgetService.getUserSummary("user-A", 0, now + 10000);
        assertThat(summary.getBudget()).isNull();
        assertThat(summary.getUtilization()).isEqualTo(-1.0);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
