package cn.watsontech.snapagent.core.issue;

/**
 * 外部 Issue 系统对接 SPI。
 *
 * <p>Host applications can implement this interface to integrate with
 * external issue tracking systems such as Jira, GitHub Issues, or
 * internal ticket systems. The starter module provides a default
 * {@code NoopIssueTracker} that does not interact with any external
 * system.</p>
 */
public interface IssueTracker {

    /**
     * Creates an external issue and returns its external ID.
     *
     * @param title       issue title
     * @param description issue description / body
     * @param assignee    assignee identifier (username or email, may be null)
     * @return the external issue ID, or {@code null} if the tracker does not
     *         create issues (e.g. NoopIssueTracker)
     */
    String createIssue(String title, String description, String assignee);

    /**
     * Updates the status of an external issue.
     *
     * @param externalIssueId the external issue ID
     * @param status          the new status (tracker-specific string)
     */
    void updateStatus(String externalIssueId, String status);

    /**
     * Returns the URL of the external issue's detail page.
     *
     * @param externalIssueId the external issue ID
     * @return the issue URL, or {@code null} if not applicable
     */
    String getIssueUrl(String externalIssueId);

    /**
     * Returns the source type identifier of this tracker (e.g.
     * {@code "jira"}, {@code "github"}, {@code "noop"}).
     *
     * @return the tracker type identifier
     */
    String type();
}
