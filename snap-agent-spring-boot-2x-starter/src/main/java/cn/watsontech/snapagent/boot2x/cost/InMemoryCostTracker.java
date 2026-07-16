package cn.watsontech.snapagent.boot2x.cost;

import cn.watsontech.snapagent.core.cost.CostRecord;
import cn.watsontech.snapagent.core.cost.CostSummary;
import cn.watsontech.snapagent.core.cost.CostTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default in-memory {@link CostTracker}.
 *
 * <p>Stores cost records in a synchronized list. Summaries are computed
 * on demand by filtering and aggregating. Suitable for single-instance
 * deployments; host applications can provide a DB-backed implementation
 * for persistence and multi-instance aggregation.</p>
 */
public class InMemoryCostTracker implements CostTracker {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCostTracker.class);

    private final List<CostRecord> records;
    private final double perUserDailyBudget;
    private final double perSkillDailyBudget;
    private final double globalDailyBudget;

    public InMemoryCostTracker(double perUserDailyBudget, double perSkillDailyBudget,
                                double globalDailyBudget) {
        this.records = Collections.synchronizedList(new ArrayList<CostRecord>());
        this.perUserDailyBudget = perUserDailyBudget;
        this.perSkillDailyBudget = perSkillDailyBudget;
        this.globalDailyBudget = globalDailyBudget;
        log.info("InMemoryCostTracker assembled (budgets: user={}, skill={}, global={})",
                perUserDailyBudget, perSkillDailyBudget, globalDailyBudget);
    }

    @Override
    public void record(CostRecord record) {
        if (record != null) {
            records.add(record);
            log.debug("Recorded cost: user={}, skill={}, task={}, cost={}",
                    record.getUserId(), record.getSkillName(),
                    record.getTaskId(), record.getCost());
        }
    }

    @Override
    public CostSummary getSummary(String userId, String skillName, long from, long to) {
        double totalCost = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalCacheReadTokens = 0;
        int count = 0;

        List<CostRecord> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<CostRecord>(records);
        }

        for (CostRecord r : snapshot) {
            if (!inTimeRange(r.getTimestamp(), from, to)) continue;
            if (userId != null && !userId.equals(r.getUserId())) continue;
            if (skillName != null && !skillName.equals(r.getSkillName())) continue;
            totalCost += r.getCost();
            totalInputTokens += r.getInputTokens();
            totalOutputTokens += r.getOutputTokens();
            totalCacheReadTokens += r.getCacheReadTokens();
            count++;
        }

        // Determine dimension and budget
        String dimension;
        String dimensionValue;
        double budget;
        if (userId != null && skillName != null) {
            dimension = "user-skill";
            dimensionValue = userId + "/" + skillName;
            budget = 0;
        } else if (userId != null) {
            dimension = "user";
            dimensionValue = userId;
            budget = perUserDailyBudget;
        } else if (skillName != null) {
            dimension = "skill";
            dimensionValue = skillName;
            budget = perSkillDailyBudget;
        } else {
            dimension = "global";
            dimensionValue = "all";
            budget = globalDailyBudget;
        }

        double utilization = budget > 0 ? totalCost / budget : 0;

        return new CostSummary(dimension, dimensionValue,
                CostCalculator.round(totalCost, 6),
                totalInputTokens, totalOutputTokens, totalCacheReadTokens,
                count, budget, utilization);
    }

    @Override
    public List<CostRecord> getRecords(String userId, long from, long to) {
        List<CostRecord> filtered = new ArrayList<CostRecord>();
        List<CostRecord> snapshot;
        synchronized (records) {
            snapshot = new ArrayList<CostRecord>(records);
        }
        for (CostRecord r : snapshot) {
            if (!inTimeRange(r.getTimestamp(), from, to)) continue;
            if (userId != null && !userId.equals(r.getUserId())) continue;
            filtered.add(r);
        }
        return filtered;
    }

    public int size() {
        return records.size();
    }

    private boolean inTimeRange(long ts, long from, long to) {
        if (from > 0 && ts < from) return false;
        if (to > 0 && ts > to) return false;
        return true;
    }
}
