package cn.watsontech.snapagent.core.cost;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成本记录存储 SPI。
 *
 * <p>Host applications can implement this interface to persist cost records
 * in a database or external system. The starter module provides a default
 * {@code FileCostStore} that saves cost records as JSON files partitioned by
 * date under the upload-skills directory.</p>
 *
 * <p>All timestamp arguments are epoch milliseconds. Ranges are inclusive on
 * both ends: a record whose {@code timestamp} equals {@code from} or
 * {@code to} is included.</p>
 */
public interface CostStore {

    /**
     * Saves a cost record (create only; records are append-only).
     *
     * @param record the cost record to save
     */
    void save(CostRecord record);

    /**
     * Lists all cost records whose timestamp falls in
     * {@code [fromTimestamp, toTimestamp]}.
     *
     * @param fromTimestamp inclusive lower bound (epoch millis)
     * @param toTimestamp   inclusive upper bound (epoch millis)
     * @return list of matching records (never null, empty if none)
     */
    List<CostRecord> list(long fromTimestamp, long toTimestamp);

    /**
     * Lists cost records for a specific user within the time range.
     *
     * @param userId the user ID
     * @param from   inclusive lower bound (epoch millis)
     * @param to     inclusive upper bound (epoch millis)
     * @return list of matching records (never null, empty if none)
     */
    List<CostRecord> listByUser(String userId, long from, long to);

    /**
     * Lists cost records for a specific skill within the time range.
     *
     * @param skillName the skill name
     * @param from      inclusive lower bound (epoch millis)
     * @param to        inclusive upper bound (epoch millis)
     * @return list of matching records (never null, empty if none)
     */
    List<CostRecord> listBySkill(String skillName, long from, long to);

    /**
     * Sums the total cost for a specific user within the time range.
     *
     * @param userId the user ID
     * @param from   inclusive lower bound (epoch millis)
     * @param to     inclusive upper bound (epoch millis)
     * @return the total cost (never null, {@code BigDecimal.ZERO} if no records)
     */
    BigDecimal sumCostByUser(String userId, long from, long to);

    /**
     * Sums the total cost for a specific skill within the time range.
     *
     * @param skillName the skill name
     * @param from      inclusive lower bound (epoch millis)
     * @param to        inclusive upper bound (epoch millis)
     * @return the total cost (never null, {@code BigDecimal.ZERO} if no records)
     */
    BigDecimal sumCostBySkill(String skillName, long from, long to);

    /**
     * Sums the total cost across all users and skills within the time range.
     *
     * @param from inclusive lower bound (epoch millis)
     * @param to   inclusive upper bound (epoch millis)
     * @return the total cost (never null, {@code BigDecimal.ZERO} if no records)
     */
    BigDecimal sumCost(long from, long to);

    /**
     * Counts the number of cost records for a specific user within the
     * time range.
     *
     * @param userId the user ID
     * @param from   inclusive lower bound (epoch millis)
     * @param to     inclusive upper bound (epoch millis)
     * @return the record count (0 if none)
     */
    int countByUser(String userId, long from, long to);

    /**
     * Counts the number of cost records for a specific skill within the
     * time range.
     *
     * @param skillName the skill name
     * @param from      inclusive lower bound (epoch millis)
     * @param to        inclusive upper bound (epoch millis)
     * @return the record count (0 if none)
     */
    int countBySkill(String skillName, long from, long to);

    /**
     * Deletes all cost records whose timestamp is strictly less than the
     * given threshold. Used for retention/cleanup of stale records.
     *
     * @param timestamp exclusive lower bound (records with timestamp
     *                  &lt; this value are deleted)
     */
    void deleteBefore(long timestamp);
}
