package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostSummary;
import cn.watsontech.snapagent.core.cost.CostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultCostTracker}.
 */
class DefaultCostTrackerTest {

    @TempDir
    Path tempDir;

    private CostStore costStore;
    private BudgetEnforcer budgetEnforcer;
    private CostTracker tracker;

    @BeforeEach
    void setUp() {
        costStore = new FileCostStore(tempDir.toString());
        budgetEnforcer = new BudgetEnforcer(costStore,
                new BigDecimal("10.00"), null, null);
        tracker = new DefaultCostTracker(costStore, budgetEnforcer);
    }

    private CostRecord newRecord(String id, String userId, String skillName,
                                 BigDecimal cost, long timestamp) {
        return new CostRecord(id, userId, skillName, null, "claude-sonnet-4-6",
                100, 50, 0, cost, timestamp);
    }

    @Test
    void shouldRecordCost() {
        long now = System.currentTimeMillis();
        CostRecord record = newRecord("rec-1", "user-A", "health-check",
                new BigDecimal("0.50"), now);
        tracker.record(record);

        BigDecimal sum = costStore.sumCostByUser("user-A", 0, now + 10000);
        assertThat(sum).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    void shouldReturnTrueWhenWithinBudget() {
        long now = System.currentTimeMillis();
        tracker.record(newRecord("rec-1", "user-A", "health-check",
                new BigDecimal("5.00"), now));

        assertThat(tracker.isWithinBudget("user-A", "health-check")).isTrue();
    }

    @Test
    void shouldReturnFalseWhenOverBudget() {
        long now = System.currentTimeMillis();
        tracker.record(newRecord("rec-1", "user-A", "health-check",
                new BigDecimal("10.00"), now));

        assertThat(tracker.isWithinBudget("user-A", "health-check")).isFalse();
    }

    @Test
    void shouldGetUserSummary() {
        long now = System.currentTimeMillis();
        tracker.record(newRecord("rec-1", "user-A", "health-check",
                new BigDecimal("0.50"), now));
        tracker.record(newRecord("rec-2", "user-A", "database-query",
                new BigDecimal("1.00"), now + 1000));
        tracker.record(newRecord("rec-3", "user-B", "health-check",
                new BigDecimal("0.75"), now + 2000));

        CostSummary summary = tracker.getSummary("user", "user-A", 0, now + 10000);
        assertThat(summary.getDimension()).isEqualTo("user");
        assertThat(summary.getDimensionValue()).isEqualTo("user-A");
        assertThat(summary.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(summary.getRequestCount()).isEqualTo(2);
        assertThat(summary.getTotalInputTokens()).isEqualTo(200);
        assertThat(summary.getTotalOutputTokens()).isEqualTo(100);
        assertThat(summary.getBudget()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(summary.getUtilization()).isGreaterThan(0.0);
    }

    @Test
    void shouldGetGlobalSummary() {
        long now = System.currentTimeMillis();
        tracker.record(newRecord("rec-1", "user-A", "health-check",
                new BigDecimal("0.50"), now));
        tracker.record(newRecord("rec-2", "user-B", "health-check",
                new BigDecimal("1.00"), now + 1000));

        CostSummary summary = tracker.getSummary("global", "global", 0, now + 10000);
        assertThat(summary.getDimension()).isEqualTo("global");
        assertThat(summary.getTotalCost()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertThat(summary.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldReturnTypeIsDefault() {
        assertThat(tracker.type()).isEqualTo("default");
    }
}
