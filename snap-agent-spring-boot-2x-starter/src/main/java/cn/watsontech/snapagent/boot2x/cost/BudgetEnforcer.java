package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Calendar;

/**
 * Budget enforcement for per-user, per-skill, and global daily cost limits.
 *
 * <p>A {@code null} budget value means "no limit" for that dimension — the
 * check is skipped. When all three are {@code null}, {@link #isWithinBudget}
 * always returns {@code true}.</p>
 *
 * <p>The budget window resets at midnight local time (start of today).</p>
 */
public class BudgetEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BudgetEnforcer.class);

    private final CostStore costStore;
    private final BigDecimal perUserDaily;
    private final BigDecimal perSkillDaily;
    private final BigDecimal globalDaily;

    public BudgetEnforcer(CostStore costStore, BigDecimal perUserDaily,
                          BigDecimal perSkillDaily, BigDecimal globalDaily) {
        this.costStore = costStore;
        this.perUserDaily = perUserDaily;
        this.perSkillDaily = perSkillDaily;
        this.globalDaily = globalDaily;
    }

    /**
     * Checks all applicable budgets. Returns {@code true} when the
     * user/skill combination is within all configured daily budgets.
     *
     * @param userId    the user ID
     * @param skillName the skill name
     * @return {@code true} if within budget, {@code false} if any budget exceeded
     */
    public boolean isWithinBudget(String userId, String skillName) {
        long todayStart = startOfTodayMillis();
        long now = System.currentTimeMillis();

        if (perUserDaily != null) {
            BigDecimal userCost = costStore.sumCostByUser(userId, todayStart, now);
            if (userCost.compareTo(perUserDaily) >= 0) {
                log.info("Budget exceeded for user {}: {} >= {}",
                        userId, userCost, perUserDaily);
                return false;
            }
        }

        if (perSkillDaily != null) {
            BigDecimal skillCost = costStore.sumCostBySkill(skillName, todayStart, now);
            if (skillCost.compareTo(perSkillDaily) >= 0) {
                log.info("Budget exceeded for skill {}: {} >= {}",
                        skillName, skillCost, perSkillDaily);
                return false;
            }
        }

        if (globalDaily != null) {
            BigDecimal total = costStore.sumCost(todayStart, now);
            if (total.compareTo(globalDaily) >= 0) {
                log.info("Global budget exceeded: {} >= {}", total, globalDaily);
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the epoch-millis timestamp of midnight (00:00:00) at the
     * start of today in the local time zone.
     *
     * @return start-of-today epoch millis
     */
    public long startOfTodayMillis() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** Per-user daily budget (null = no limit). */
    public BigDecimal getPerUserDaily() {
        return perUserDaily;
    }

    /** Per-skill daily budget (null = no limit). */
    public BigDecimal getPerSkillDaily() {
        return perSkillDaily;
    }

    /** Global daily budget (null = no limit). */
    public BigDecimal getGlobalDaily() {
        return globalDaily;
    }
}
