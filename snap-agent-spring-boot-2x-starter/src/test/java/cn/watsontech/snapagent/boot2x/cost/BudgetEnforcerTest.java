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

    // ---- GAP (P2): startOfTodayMillis timezone semantics ----

    @Test
    void shouldNotCountCostsFromYesterdayTowardTodayBudget() {
        // Per TDD_SPEC: "窗口从本地午夜零点（startOfTodayMillis）"
        // A cost recorded before today's midnight must NOT count toward
        // today's per-user budget, even if it would exceed the limit.
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                new BigDecimal("10.00"), null, null);

        long startOfToday = enforcer.startOfTodayMillis();
        // Record a cost 1ms BEFORE today's midnight — belongs to yesterday
        recordCost("user-Y", "skill-Y", new BigDecimal("100.00"),
                startOfToday - 1);

        // Today's budget is $10; yesterday's $100 cost must not trigger a
        // budget-exceeded condition for today.
        assertThat(enforcer.isWithinBudget("user-Y", "skill-Y")).isTrue();
    }

    @Test
    void shouldCountCostsFromTodayTowardBudget() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                new BigDecimal("10.00"), null, null);

        long startOfToday = enforcer.startOfTodayMillis();
        // Record a cost exactly at today's midnight (inclusive)
        recordCost("user-T", "skill-T", new BigDecimal("10.00"),
                startOfToday);

        // Exactly at the limit → budget exceeded (>= comparison)
        assertThat(enforcer.isWithinBudget("user-T", "skill-T")).isFalse();
    }

    @Test
    void shouldStartOfTodayAlignToMidnightLocalTime() {
        // startOfTodayMillis() should be exactly 00:00:00.000 of today
        // in the local time zone — i.e., all time-of-day fields zeroed out.
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore, null, null, null);
        long start = enforcer.startOfTodayMillis();

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(start);
        assertThat(cal.get(java.util.Calendar.HOUR_OF_DAY)).isZero();
        assertThat(cal.get(java.util.Calendar.MINUTE)).isZero();
        assertThat(cal.get(java.util.Calendar.SECOND)).isZero();
        assertThat(cal.get(java.util.Calendar.MILLISECOND)).isZero();
    }

    @Test
    void shouldNotCountCostsFromYesterdayForSkillBudget() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                null, new BigDecimal("50.00"), null);

        long startOfToday = enforcer.startOfTodayMillis();
        recordCost("user-S", "skill-S", new BigDecimal("500.00"),
                startOfToday - 1);

        assertThat(enforcer.isWithinBudget("user-S", "skill-S")).isTrue();
    }

    @Test
    void shouldNotCountCostsFromYesterdayForGlobalBudget() {
        BudgetEnforcer enforcer = new BudgetEnforcer(costStore,
                null, null, new BigDecimal("500.00"));

        long startOfToday = enforcer.startOfTodayMillis();
        recordCost("user-G", "skill-G", new BigDecimal("5000.00"),
                startOfToday - 1);

        assertThat(enforcer.isWithinBudget("user-G", "skill-G")).isTrue();
    }
}
