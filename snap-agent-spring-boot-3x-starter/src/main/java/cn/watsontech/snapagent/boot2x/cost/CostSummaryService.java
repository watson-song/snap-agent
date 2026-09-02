package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Aggregation service that builds {@link CostSummary} objects from
 * {@link CostStore} data, suitable for the cost reporting REST endpoints.
 *
 * <p>Unlike {@link DefaultCostTracker}, this service does not check budgets
 * or enforce limits — it only reads and aggregates cost data for reporting.</p>
 */
public class CostSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CostSummaryService.class);

    private final CostStore costStore;
    private final BigDecimal perUserDaily;
    private final BigDecimal perSkillDaily;
    private final BigDecimal globalDaily;

    public CostSummaryService(CostStore costStore, BigDecimal perUserDaily,
                              BigDecimal perSkillDaily, BigDecimal globalDaily) {
        this.costStore = costStore;
        this.perUserDaily = perUserDaily;
        this.perSkillDaily = perSkillDaily;
        this.globalDaily = globalDaily;
    }

    /** Returns the underlying cost store (for record-level queries). */
    public CostStore getStore() {
        return costStore;
    }

    /**
     * Builds a cost summary for a specific user within the time range.
     *
     * @param userId the user ID
     * @param from   inclusive lower bound (epoch millis)
     * @param to     inclusive upper bound (epoch millis)
     * @return the cost summary (never null)
     */
    public CostSummary getUserSummary(String userId, long from, long to) {
        List<CostRecord> records = costStore.listByUser(userId, from, to);
        return buildSummary("user", userId, records, perUserDaily);
    }

    /**
     * Builds a cost summary for a specific skill within the time range.
     *
     * @param skillName the skill name
     * @param from      inclusive lower bound (epoch millis)
     * @param to        inclusive upper bound (epoch millis)
     * @return the cost summary (never null)
     */
    public CostSummary getSkillSummary(String skillName, long from, long to) {
        List<CostRecord> records = costStore.listBySkill(skillName, from, to);
        return buildSummary("skill", skillName, records, perSkillDaily);
    }

    /**
     * Builds a global cost summary across all users and skills.
     *
     * @param from inclusive lower bound (epoch millis)
     * @param to   inclusive upper bound (epoch millis)
     * @return the cost summary (never null)
     */
    public CostSummary getGlobalSummary(long from, long to) {
        List<CostRecord> records = costStore.list(from, to);
        return buildSummary("global", "global", records, globalDaily);
    }

    /**
     * Lists individual cost records within the time range (newest first).
     *
     * @param from inclusive lower bound (epoch millis)
     * @param to   inclusive upper bound (epoch millis)
     * @return list of records (never null, empty if none); sorted by timestamp desc
     */
    public List<CostRecord> listRecords(long from, long to) {
        List<CostRecord> records = costStore.list(from, to);
        // Defensive copy sorted by timestamp descending (newest first)
        List<CostRecord> sorted = new java.util.ArrayList<CostRecord>(records);
        java.util.Collections.sort(sorted, new java.util.Comparator<CostRecord>() {
            @Override
            public int compare(CostRecord a, CostRecord b) {
                return Long.compare(b.getTimestamp(), a.getTimestamp());
            }
        });
        return sorted;
    }

    // ---- helpers ----

    private CostSummary buildSummary(String dimension, String dimensionValue,
                                     List<CostRecord> records, BigDecimal budget) {
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        for (CostRecord record : records) {
            if (record.getCost() != null) {
                totalCost = totalCost.add(record.getCost());
            }
            totalInputTokens += record.getInputTokens();
            totalOutputTokens += record.getOutputTokens();
        }

        double utilization = computeUtilization(totalCost, budget);

        return new CostSummary(dimension, dimensionValue, totalCost,
                totalInputTokens, totalOutputTokens, records.size(),
                budget, utilization);
    }

    private double computeUtilization(BigDecimal totalCost, BigDecimal budget) {
        if (budget == null || budget.compareTo(BigDecimal.ZERO) == 0) {
            return -1.0;
        }
        return totalCost.divide(budget, 4, RoundingMode.HALF_UP).doubleValue();
    }
}
