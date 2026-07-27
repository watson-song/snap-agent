package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GitHubIssueTracker}.
 */
class GitHubIssueTrackerTest {

    private GitHubIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.GitHubTracker cfg = new SnapAgentProperties.IssueClosure.GitHubTracker();
        cfg.setApiBaseUrl("http://localhost:39999");
        cfg.setToken("ghp_test_token");
        cfg.setOwner("my-org");
        cfg.setRepo("my-repo");
        return new GitHubIssueTracker(cfg);
    }

    @Test
    void shouldReturnTypeGithub() {
        assertThat(createTracker().type()).isEqualTo("github");
    }

    @Test
    void shouldReturnCorrectIssueUrlForPublicGithub() {
        SnapAgentProperties.IssueClosure.GitHubTracker cfg = new SnapAgentProperties.IssueClosure.GitHubTracker();
        cfg.setToken("tok");
        cfg.setOwner("my-org");
        cfg.setRepo("my-repo");
        GitHubIssueTracker tracker = new GitHubIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("42"))
                .isEqualTo("https://github.com/my-org/my-repo/issues/42");
    }

    @Test
    void shouldReturnCorrectIssueUrlForEnterpriseGithub() {
        SnapAgentProperties.IssueClosure.GitHubTracker cfg = new SnapAgentProperties.IssueClosure.GitHubTracker();
        cfg.setApiBaseUrl("https://github.example.com/api/v3");
        cfg.setToken("tok");
        cfg.setOwner("my-org");
        cfg.setRepo("my-repo");
        GitHubIssueTracker tracker = new GitHubIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("42"))
                .isEqualTo("https://github.example.com/my-org/my-repo/issues/42");
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
        GitHubIssueTracker tracker = createTracker();
        tracker.updateStatus(null, "closed");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        GitHubIssueTracker tracker = createTracker();
        tracker.updateStatus("", "closed");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        GitHubIssueTracker tracker = createTracker();
        assertThatThrownBy(() -> tracker.createIssue("title", "desc", null))
                .isInstanceOf(AbstractHttpIssueTracker.TrackerException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleApiBaseUrlWithTrailingSlash() {
        SnapAgentProperties.IssueClosure.GitHubTracker cfg = new SnapAgentProperties.IssueClosure.GitHubTracker();
        cfg.setApiBaseUrl("https://api.github.com/");
        cfg.setToken("tok");
        cfg.setOwner("org");
        cfg.setRepo("repo");
        GitHubIssueTracker tracker = new GitHubIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("1"))
                .isEqualTo("https://github.com/org/repo/issues/1");
    }
}
