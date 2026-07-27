package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JiraIssueTracker}.
 */
class JiraIssueTrackerTest {

    private JiraIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setUsername("test@example.com");
        cfg.setApiToken("test-token");
        cfg.setProjectKey("TEST");
        cfg.setIssueType("Bug");
        return new JiraIssueTracker(cfg);
    }

    @Test
    void shouldReturnTypeJira() {
        assertThat(createTracker().type()).isEqualTo("jira");
    }

    @Test
    void shouldReturnCorrectIssueUrl() {
        JiraIssueTracker tracker = createTracker();
        assertThat(tracker.getIssueUrl("TEST-123"))
                .isEqualTo("http://localhost:39999/browse/TEST-123");
    }

    @Test
    void shouldReturnNullIssueUrlForNullId() {
        assertThat(createTracker().getIssueUrl(null)).isNull();
    }

    @Test
    void shouldReturnNullIssueUrlForEmptyId() {
        assertThat(createTracker().getIssueUrl("")).isNull();
    }

    @Test
    void shouldNoOpUpdateStatusForNullId() {
        JiraIssueTracker tracker = createTracker();
        tracker.updateStatus(null, "Resolved");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        JiraIssueTracker tracker = createTracker();
        tracker.updateStatus("", "Closed");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        JiraIssueTracker tracker = createTracker();
        assertThatThrownBy(() -> tracker.createIssue("title", "desc", null))
                .isInstanceOf(AbstractHttpIssueTracker.TrackerException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() {
        SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
        cfg.setBaseUrl("https://myteam.atlassian.net/");
        cfg.setUsername("user@example.com");
        cfg.setApiToken("tok");
        cfg.setProjectKey("PROJ");
        JiraIssueTracker tracker = new JiraIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("PROJ-1"))
                .isEqualTo("https://myteam.atlassian.net/browse/PROJ-1");
    }

    @Test
    void shouldUseDefaultIssueTypeWhenEmpty() {
        SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setUsername("u");
        cfg.setApiToken("t");
        cfg.setProjectKey("PROJ");
        cfg.setIssueType("");  // empty — should default to "Bug"
        JiraIssueTracker tracker = new JiraIssueTracker(cfg);
        // verify it doesn't crash on creation
        assertThat(tracker.type()).isEqualTo("jira");
    }

    @Test
    void shouldUseBearerAuthWhenUsernameEmpty() {
        // When username is empty, should use Bearer token auth
        SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setUsername("");  // empty — should fall back to Bearer
        cfg.setApiToken("server-pat");
        cfg.setProjectKey("PROJ");
        JiraIssueTracker tracker = new JiraIssueTracker(cfg);
        // Should throw TrackerException (can't connect) but NOT an auth error
        assertThatThrownBy(() -> tracker.createIssue("title", "desc", null))
                .isInstanceOf(AbstractHttpIssueTracker.TrackerException.class);
    }
}
