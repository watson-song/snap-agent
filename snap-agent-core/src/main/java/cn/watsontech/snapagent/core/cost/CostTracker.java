package cn.watsontech.snapagent.core.cost;

import java.util.List;

/**
 * SPI for tracking LLM cost per call and aggregating summaries.
 *
 * <p>Host applications can provide a custom implementation (e.g., DB-backed)
 * by declaring a bean that overrides the default in-memory tracker.</p>
 */
public interface CostTracker {

    /**
     * Record a single cost event.
     */
    void record(CostRecord record);

    /**
     * Get a summary for the given dimension and time range.
     *
     * @param userId     filter by user (null = all users)
     * @param skillName  filter by skill (null = all skills)
     * @param from       start timestamp (epoch millis, 0 = no lower bound)
     * @param to         end timestamp (epoch millis, 0 = no upper bound)
     * @return aggregated summary
     */
    CostSummary getSummary(String userId, String skillName, long from, long to);

    /**
     * Get individual cost records for the given user and time range.
     *
     * @param userId filter by user (null = all users)
     * @param from   start timestamp (epoch millis)
     * @param to     end timestamp (epoch millis)
     * @return list of cost records
     */
    List<CostRecord> getRecords(String userId, long from, long to);
}
