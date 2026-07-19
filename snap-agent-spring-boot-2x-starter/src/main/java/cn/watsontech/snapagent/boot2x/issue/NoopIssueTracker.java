package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueTracker;

/**
 * Default {@link IssueTracker} that does not interact with any external
 * issue tracking system.
 *
 * <p>Used when {@code snap-agent.issue-closure.tracker-type=noop} (default).
 * The {@code createIssue} method returns {@code null}, signalling that no
 * external issue was created. Host applications can replace this bean by
 * declaring a custom {@link IssueTracker} bean (e.g. Jira, GitHub Issues).</p>
 */
public class NoopIssueTracker implements IssueTracker {

    @Override
    public String createIssue(String title, String description, String assignee) {
        return null;
    }

    @Override
    public void updateStatus(String externalIssueId, String status) {
        // no-op
    }

    @Override
    public String getIssueUrl(String externalIssueId) {
        return null;
    }

    @Override
    public String type() {
        return "noop";
    }
}
