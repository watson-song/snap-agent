package cn.watsontech.snapagent.core.patrol;

import java.util.List;

/**
 * Storage SPI for {@link PatrolReport} instances.
 *
 * <p>Host applications that need persistence across Pod restarts (e.g. database-backed
 * storage) can implement this interface and register it as a Spring bean — the default
 * {@code InMemoryPatrolReportStore} will be skipped via {@code @ConditionalOnMissingBean}.</p>
 *
 * <p>Default implementation: in-memory ring buffer (lost on Pod restart).</p>
 *
 * @see cn.watsontech.snapagent.boot2x.patrol.InMemoryPatrolReportStore
 */
public interface PatrolReportStore {

    /**
     * Persists a patrol report. Implementations should auto-generate an ID if
     * {@code report.getId()} is null.
     */
    void save(PatrolReport report);

    /**
     * Returns reports for a given user sorted by triggeredAt descending, with pagination.
     * Reports with null userId are system-generated and visible to all users.
     *
     * @param userId the user ID for filtering (null returns all reports)
     * @param limit  maximum number of reports to return
     * @param offset number of reports to skip
     */
    List<PatrolReport> getReports(String userId, int limit, int offset);

    /**
     * Returns the total number of reports for a given user.
     * Reports with null userId are system-generated and counted for all users.
     */
    long count(String userId);

    /**
     * Retrieves a single report by its ID.
     *
     * @param reportId the report ID
     * @return the report, or null if not found
     */
    PatrolReport get(String reportId);
}
