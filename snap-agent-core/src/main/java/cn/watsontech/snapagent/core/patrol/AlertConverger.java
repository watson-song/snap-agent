package cn.watsontech.snapagent.core.patrol;

import java.util.List;

/**
 * SPI for recording and querying converged alerts.
 *
 * <p>Implementations deduplicate repeated anomaly events of the same type and
 * source into a single convergence record with a running count.</p>
 */
public interface AlertConverger {

    /**
     * Records an anomaly event, creating or updating a convergence record.
     *
     * @param event the anomaly event to record
     * @return the updated or newly created convergence record
     */
    AlertConvergence record(AnomalyEvent event);

    /**
     * Queries convergence records with optional filtering.
     *
     * @param userId the user ID (may be null for no filter)
     * @param type   the alert type (may be null for no filter)
     * @param limit  maximum number of records to return
     * @param offset number of records to skip
     */
    List<AlertConvergence> query(String userId, String type, int limit, int offset);

    /**
     * Returns the total number of convergence records matching the given filters.
     */
    long count(String userId, String type);

    /**
     * Marks a convergence record as resolved.
     *
     * @param alertId the ID of the convergence record to resolve
     */
    void resolve(String alertId);
}
