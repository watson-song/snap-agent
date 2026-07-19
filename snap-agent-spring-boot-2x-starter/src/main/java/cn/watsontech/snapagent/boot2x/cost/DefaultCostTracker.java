package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostSummary;
import cn.watsontech.snapagent.core.cost.CostTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Default {@link CostTracker} implementation that combines a {@link CostStore}
 * for persistence with a {@link BudgetEnforcer} for budget checking.
 *
 * <p>Host applications can replace this by declaring their own
 * {@code CostTracker} bean when {@code snap-agent.cost.enabled=true}.</p>
 */
public class DefaultCostTracker implements CostTracker {

    private static final Logger log = LoggerFactory.getLogger(DefaultCostTracker.class);

    private final CostStore costStore;
    private final BudgetEnforcer budgetEnforcer;

    public DefaultCostTracker(CostStore costStore, BudgetEnforcer budgetEnforcer) {
        this.costStore = costStore;
        this.budgetEnforcer = budgetEnforcer;
    }

    @Override
    public void record(CostRecord record) {
        if (record == null) {
            return;
        }
        costStore.save(record);
        log.debug("Recorded cost: {} (model={}, input={}, output={}, cost={})",
                record.getId(), record.getModel(),
                record.getInputTokens(), record.getOutputTokens(),
                record.getCost());
    }

    @Override
    public boolean isWithinBudget(String userId, String skillName) {
        return budgetEnforcer.isWithinBudget(userId, skillName);
    }

    @Override
    public CostSummary getSummary(String dimension, String dimensionValue, long from, long to) {
        BigDecimal totalCost;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        int requestCount;
        BigDecimal budget = null;

        if ("user".equals(dimension)) {
            List<CostRecord> records = costStore.listByUser(dimensionValue, from, to);
            totalCost = sumCosts(records);
            totalInputTokens = sumInputTokens(records);
            totalOutputTokens = sumOutputTokens(records);
            requestCount = records.size();
            budget = budgetEnforcer.getPerUserDaily();
        } else if ("skill".equals(dimension)) {
            List<CostRecord> records = costStore.listBySkill(dimensionValue, from, to);
            totalCost = sumCosts(records);
            totalInputTokens = sumInputTokens(records);
            totalOutputTokens = sumOutputTokens(records);
            requestCount = records.size();
            budget = budgetEnforcer.getPerSkillDaily();
        } else {
            // global
            List<CostRecord> records = costStore.list(from, to);
            totalCost = sumCosts(records);
            totalInputTokens = sumInputTokens(records);
            totalOutputTokens = sumOutputTokens(records);
            requestCount = records.size();
            budget = budgetEnforcer.getGlobalDaily();
        }

        double utilization = computeUtilization(totalCost, budget);

        return new CostSummary(dimension, dimensionValue, totalCost,
                totalInputTokens, totalOutputTokens, requestCount,
                budget, utilization);
    }

    @Override
    public String type() {
        return "default";
    }

    // ---- helpers ----

    private BigDecimal sumCosts(List<CostRecord> records) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CostRecord record : records) {
            if (record.getCost() != null) {
                sum = sum.add(record.getCost());
            }
        }
        return sum;
    }

    private long sumInputTokens(List<CostRecord> records) {
        long sum = 0;
        for (CostRecord record : records) {
            sum += record.getInputTokens();
        }
        return sum;
    }

    private long sumOutputTokens(List<CostRecord> records) {
        long sum = 0;
        for (CostRecord record : records) {
            sum += record.getOutputTokens();
        }
        return sum;
    }

    private double computeUtilization(BigDecimal totalCost, BigDecimal budget) {
        if (budget == null || budget.compareTo(BigDecimal.ZERO) == 0) {
            return -1.0;
        }
        return totalCost.divide(budget, 4, RoundingMode.HALF_UP).doubleValue();
    }
}
