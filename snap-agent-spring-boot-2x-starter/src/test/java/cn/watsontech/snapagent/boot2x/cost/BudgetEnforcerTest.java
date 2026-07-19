package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BudgetEnforcer}.
 */
class BudgetEnforcerTest {

    @TempDir
    Path tempDir;

    private CostStore costStore;

    @BeforeEach
    void setUp() {
        costStore = new FileCostStore(tempDir.toString());
    }

    private void recordCost(String userId, String skillName, BigDecimal cost, long timestamp) {
        costStore.save(new cn.watsontech.snapagent.core.cost.CostRecord(
                null, userId, skillName, null, "claude-sonnet-4-6",
                100, 50, 0, cost, timestamp));
    }

    @Test
    void shouldReturnTrueWhenNoBudgetsConfigured() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore, null, null, null);
        assertThat(enforcer.isWithinBudget("user-A", "health-check")).isTrue();
    }

    @Test
    void shouldReturnTrueWhenWithinBudget() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                new BigDecimal("10.00"), null, null);
        recordCost("user-A", "health-check", new BigDecimal("5.00"),
                System.currentTimeMillis());
        assertThat(enforcer.isWithinBudget("user-A", "health-check")).isTrue();
    }

    @Test
    void shouldReturnFalseWhenUserBudgetExceeded() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                new BigDecimal("10.00"), null, null);
        recordCost("user-A", "health-check", new BigDecimal("10.00"),
                System.currentTimeMillis());
        assertThat(enforcer.isWithinBudget("user-A", "health-check")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenSkillBudgetExceeded() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                null, new BigDecimal("50.00"), null);
        recordCost("user-A", "health-check", new BigDecimal("50.00"),
                System.currentTimeMillis());
        assertThat(enforcer.isWithinBudget("user-A", "health-check")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenGlobalBudgetExceeded() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                null, null, new BigDecimal("500.00"));
        recordCost("user-A", "health-check", new BigDecimal("500.00"),
                System.currentTimeMillis());
        assertThat(enforcer.isWithinBudget("user-A", "health-check")).isFalse();
    }

    @Test
    void shouldStartOfTodayBeBeforeNow() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore, null, null, null);
        long start = enforcer.startOfTodayMillis();
        long now = System.currentTimeMillis();
        assertThat(start).isLessThanOrEqualTo(now);
        // Start of today should be within 24 hours of now
        assertThat(now - start).isLessThan(24L * 60 * 60 * 1000);
    }
}
