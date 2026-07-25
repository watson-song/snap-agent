package cn.watsontech.snapagent.core.issue;

/**
 * SPI for closing an issue after verification.
 *
 * <p>Called by {@link IssueClosureNode} when the graph routes to the
 * {@code issue_closure} terminal node after agent {@code end_turn}.
 * Host applications implement this (typically via {@code IssueClosureService})
 * to perform the actual close + knowledge sedimentation.</p>
 *
 * <p>Implementations should be idempotent — closing an already-closed issue
 * is a no-op, not an exception.</p>
 */
@FunctionalInterface
public interface IssueClosureHandler {

    /**
     * Closes the issue associated with the given task ID.
     *
     * @param taskId the diagnostic task ID whose issue should be closed
     */
    void close(String taskId);
}
