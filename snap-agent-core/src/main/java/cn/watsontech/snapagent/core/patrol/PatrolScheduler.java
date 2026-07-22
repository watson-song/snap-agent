package cn.watsontech.snapagent.core.patrol;

import java.util.List;

/**
 * SPI for scheduling and managing patrol tasks.
 *
 * <p>Implementations are responsible for cron-based scheduling, cancellation,
 * and retrieval of patrol reports.</p>
 */
public interface PatrolScheduler {

    /**
     * Schedules a patrol task for periodic execution.
     *
     * @param task the patrol task to schedule
     */
    void schedule(PatrolTask task);

    /**
     * Cancels a scheduled patrol task.
     *
     * @param patrolId the ID of the patrol task to cancel
     */
    void cancel(String patrolId);

    /**
     * Returns all registered patrol tasks.
     */
    List<PatrolTask> listTasks();

    /**
     * Returns patrol reports for a given user, sorted by triggeredAt descending.
     *
     * @param userId the user ID
     * @param limit  maximum number of reports to return
     * @param offset number of reports to skip
     */
    List<PatrolReport> getReports(String userId, int limit, int offset);

    /**
     * Returns the total number of patrol reports for a given user.
     */
    long countReports(String userId);

    /**
     * Toggles the enabled state of a patrol task.
     *
     * @param patrolId the patrol task ID
     * @return the new enabled state, or {@code null} if the task was not found
     */
    default Boolean toggleEnabled(String patrolId) { return null; }
}
