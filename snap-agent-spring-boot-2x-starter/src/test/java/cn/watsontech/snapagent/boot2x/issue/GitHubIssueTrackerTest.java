package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GitHubIssueTracker}.
 *
 * <p>Pure-logic methods (type, getIssueUrl, updateStatus guards) are tested
 * directly. HTTP-dependent methods are tested two ways:</p>
 * <ul>
 *   <li>Error path: unreachable port → TrackerException</li>
 *   <li>Success path: mock HTTP server → verify request URL, headers, body, response parsing</li>
 * </ul>
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

    private GitHubIssueTracker createTracker(String baseUrl, String token) {
        SnapAgentProperties.IssueClosure.GitHubTracker cfg = new SnapAgentProperties.IssueClosure.GitHubTracker();
        cfg.setApiBaseUrl(baseUrl);
        cfg.setToken(token);
        cfg.setOwner("my-org");
        cfg.setRepo("my-repo");
        return new GitHubIssueTracker(cfg);
    }

    // ---- Pure-logic tests ----

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
        createTracker().updateStatus(null, "closed");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        createTracker().updateStatus("", "closed");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        assertThatThrownBy(() -> createTracker().createIssue("title", "desc", null))
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

    // ---- Mock-server success path tests ----

    @Test
    void createIssue_returnsNumberOnSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/repos/my-org/my-repo/issues", "POST", 201,
                    "{\"number\":42,\"html_url\":\"https://github.com/my-org/my-repo/issues/42\"}")
                    .start();

            GitHubIssueTracker tracker = createTracker(server.getBaseUrl(), "ghp_token");
            String issueId = tracker.createIssue("Test bug", "Description here", "devuser");

            assertThat(issueId).isEqualTo("42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/repos/my-org/my-repo/issues");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Bearer ghp_token");
            assertThat(req.body).contains("\"title\":\"Test bug\"");
            assertThat(req.body).contains("\"body\":\"Description here\"");
            assertThat(req.body).contains("\"assignees\":[\"devuser\"]");
            assertThat(req.body).contains("\"labels\":[\"snap-agent\"]");
        }
    }

    @Test
    void createIssue_returnsNullWhenNoNumberInResponse() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/repos/my-org/my-repo/issues", "POST", 201,
                    "{\"message\":\"Validation failed\"}")
                    .start();

            GitHubIssueTracker tracker = createTracker(server.getBaseUrl(), "ghp_token");
            String issueId = tracker.createIssue("title", "desc", null);

            assertThat(issueId).isNull();
        }
    }

    @Test
    void createIssue_omitsBodyWhenDescriptionEmpty() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/repos/my-org/my-repo/issues", "POST", 201,
                    "{\"number\":1}")
                    .start();

            GitHubIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.createIssue("title", "", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/issues");
            assertThat(req.body).doesNotContain("\"body\"");
        }
    }

    @Test
    void createIssue_omitsAssigneesWhenNull() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/repos/my-org/my-repo/issues", "POST", 201,
                    "{\"number\":1}")
                    .start();

            GitHubIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.createIssue("title", "desc", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/issues");
            assertThat(req.body).doesNotContain("\"assignees\"");
        }
    }

    // Note: updateStatus uses PATCH method, which Java 8's HttpURLConnection
    // does not support (throws ProtocolException). The guard conditions (null/empty
    // id) are tested above. The actual PATCH request would need a different HTTP
    // client or the production code to use POST with X-HTTP-Method-Override.
}
