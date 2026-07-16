package cn.watsontech.snapagent.core.issue;

import java.util.List;

/**
 * 问题闭环存储 SPI。
 *
 * <p>Host applications can implement this interface to store issue closures
 * in a database or external system. The starter module provides a default
 * {@code FileIssueStore} that saves issue closures as JSON files under the
 * upload-skills directory.</p>
 */
public interface IssueStore {

    /**
     * Saves (creates or updates) an issue closure (upsert).
     *
     * @param issue the issue closure to save
     */
    void save(IssueClosure issue);

    /**
     * Loads an issue closure by its internal issue ID.
     *
     * @param issueId the internal issue ID
     * @return the issue closure, or {@code null} if not found
     */
    IssueClosure load(String issueId);

    /**
     * Finds an issue closure by its associated diagnostic task ID.
     *
     * @param taskId the diagnostic task ID
     * @return the issue closure, or {@code null} if not found
     */
    IssueClosure findByTaskId(String taskId);

    /**
     * Lists all issue closures sorted by {@code updatedAt} descending
     * (newest first).
     *
     * @return list of issue closures (never null, empty if none)
     */
    List<IssueClosure> list();

    /**
     * Lists issue closures filtered by status, sorted by {@code updatedAt}
     * descending (newest first).
     *
     * @param status the status filter
     * @return list of matching issue closures (never null, empty if none)
     */
    List<IssueClosure> listByStatus(IssueStatus status);

    /**
     * Deletes an issue closure by its internal issue ID.
     *
     * @param issueId the internal issue ID
     */
    void delete(String issueId);
}
