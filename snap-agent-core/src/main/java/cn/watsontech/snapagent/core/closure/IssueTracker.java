package cn.watsontech.snapagent.core.closure;

import java.util.List;

/**
 * SPI for issue tracking within the Q&A closed loop.
 *
 * <p>Default implementation ({@code InMemoryIssueStore}) uses in-memory storage.
 * Custom implementations can integrate with Jira, GitHub Issues, etc.</p>
 */
public interface IssueTracker {

    /**
     * Create a new issue from a diagnostic result.
     *
     * @param issue the issue closure record (id may be null → auto-generated)
     * @return the generated issue ID
     */
    String createIssue(IssueClosure issue);

    /**
     * Get an issue by its ID.
     *
     * @param issueId the issue ID
     * @return the issue, or null if not found
     */
    IssueClosure getIssue(String issueId);

    /**
     * Update the status of an issue.
     *
     * @param issueId the issue ID
     * @param status  the new status
     */
    void updateStatus(String issueId, IssueStatus status);

    /**
     * List issues with pagination (sorted by createdAt descending).
     *
     * @param limit  maximum number of issues to return
     * @param offset number of issues to skip
     * @return list of issues
     */
    List<IssueClosure> listIssues(int limit, int offset);

    /**
     * Save/update an issue record (e.g., after adding solution or verification).
     *
     * @param issue the issue to save
     */
    void save(IssueClosure issue);
}
