package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JiraIssueTracker}.
 *
 * <p>Pure-logic methods (type, getIssueUrl, updateStatus guards) are tested
 * directly. HTTP-dependent methods are tested two ways:</p>
 * <ul>
 *   <li>Error path: unreachable port → TrackerException</li>
 *   <li>Success path: mock HTTP server → verify request URL, headers, body, response parsing</li>
 * </ul>
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

    private JiraIssueTracker createTracker(String baseUrl, String username, String token) {
        SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
        cfg.setBaseUrl(baseUrl);
        cfg.setUsername(username);
        cfg.setApiToken(token);
        cfg.setProjectKey("TEST");
        cfg.setIssueType("Bug");
        return new JiraIssueTracker(cfg);
    }

    // ---- Pure-logic tests ----

    @Test
    void shouldReturnTypeJira() {
        assertThat(createTracker().type()).isEqualTo("jira");
    }

    @Test
    void shouldReturnCorrectIssueUrl() {
        assertThat(createTracker().getIssueUrl("TEST-123"))
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
        createTracker().updateStatus(null, "Resolved");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        createTracker().updateStatus("", "Closed");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        assertThatThrownBy(() -> createTracker().createIssue("title", "desc", null))
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

    // ---- Fix: verify default issue type "Bug" is sent in request body (was fake — asserted type() only) ----

    @Test
    void shouldUseDefaultIssueTypeWhenEmpty() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201,
                    "{\"id\":\"10001\",\"key\":\"TEST-42\",\"self\":\"...\"}")
                    .start();

            SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
            cfg.setBaseUrl(server.getBaseUrl());
            cfg.setUsername("user@example.com");
            cfg.setApiToken("tok");
            cfg.setProjectKey("TEST");
            cfg.setIssueType("");  // empty — should default to "Bug"
            JiraIssueTracker tracker = new JiraIssueTracker(cfg);

            String issueKey = tracker.createIssue("test bug", "description", null);

            assertThat(issueKey).isEqualTo("TEST-42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/rest/api/2/issue");
            assertThat(req).isNotNull();
            assertThat(req.body).contains("\"issuetype\":{\"name\":\"Bug\"}");
        }
    }

    // ---- Fix: verify Bearer token is used when username is empty (was fake — only verified HTTP failure) ----

    @Test
    void shouldUseBearerAuthWhenUsernameEmpty() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201,
                    "{\"key\":\"TEST-99\"}")
                    .start();

            SnapAgentProperties.IssueClosure.JiraTracker cfg = new SnapAgentProperties.IssueClosure.JiraTracker();
            cfg.setBaseUrl(server.getBaseUrl());
            cfg.setUsername("");  // empty — should fall back to Bearer
            cfg.setApiToken("server-pat");
            cfg.setProjectKey("TEST");
            JiraIssueTracker tracker = new JiraIssueTracker(cfg);

            tracker.createIssue("title", "desc", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/rest/api/2/issue");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Bearer server-pat");
        }
    }

    // ---- Mock-server success path tests ----

    @Test
    void createIssue_returnsKeyOnSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201,
                    "{\"id\":\"10001\",\"key\":\"TEST-42\",\"self\":\"...\"}")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(),
                    "user@example.com", "api-token");
            String key = tracker.createIssue("test bug", "description", "devuser");

            assertThat(key).isEqualTo("TEST-42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/rest/api/2/issue");
            assertThat(req).isNotNull();

            // Verify Basic auth
            String expectedCreds = "user@example.com:api-token";
            String expectedEncoded = Base64.getEncoder()
                    .encodeToString(expectedCreds.getBytes(StandardCharsets.UTF_8));
            assertThat(req.header("Authorization")).isEqualTo("Basic " + expectedEncoded);

            // Verify request body structure
            assertThat(req.body).contains("\"project\":{\"key\":\"TEST\"}");
            assertThat(req.body).contains("\"summary\":\"test bug\"");
            assertThat(req.body).contains("\"description\":\"description\"");
            assertThat(req.body).contains("\"issuetype\":{\"name\":\"Bug\"}");
            assertThat(req.body).contains("\"assignee\":{\"name\":\"devuser\"}");
            assertThat(req.body).contains("\"labels\":[\"snap-agent\"]");
        }
    }

    @Test
    void createIssue_returnsNullWhenNoKeyInResponse() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201,
                    "{\"errorMessages\":[\"validation failed\"]}")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(),
                    "u", "t");
            String key = tracker.createIssue("title", "desc", null);

            assertThat(key).isNull();
        }
    }

    @Test
    void createIssue_omitsDescriptionWhenEmpty() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201, "{\"key\":\"TEST-1\"}")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(), "u", "t");
            tracker.createIssue("title", "", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/rest/api/2/issue");
            assertThat(req.body).doesNotContain("\"description\"");
        }
    }

    @Test
    void createIssue_omitsAssigneeWhenNull() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue", "POST", 201, "{\"key\":\"TEST-1\"}")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(), "u", "t");
            tracker.createIssue("title", "desc", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/rest/api/2/issue");
            assertThat(req.body).doesNotContain("\"assignee\"");
        }
    }

    @Test
    void updateStatus_findsTransitionAndPostsTransitionId() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            // GET returns transitions list, POST for the transition
            server.when("/rest/api/2/issue/TEST-123/transitions", "GET", 200,
                    "{\"transitions\":[{\"id\":\"5\",\"name\":\"Resolved\"},"
                            + "{\"id\":\"31\",\"name\":\"Closed\"}]}")
                    .when("/rest/api/2/issue/TEST-123/transitions", "POST", 204, "")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(), "u", "t");
            tracker.updateStatus("TEST-123", "Resolved");

            // Verify the POST request had the right transition id
            List<MockHttpServer.RecordedRequest> posts = new ArrayList<>();
            for (MockHttpServer.RecordedRequest req : server.getRecordedRequests()) {
                if ("POST".equals(req.method)) {
                    posts.add(req);
                }
            }
            assertThat(posts).hasSize(1);
            assertThat(posts.get(0).body).contains("\"id\":\"5\"");
        }
    }

    @Test
    void updateStatus_closesWhenStatusIsClosed() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue/TEST-123/transitions", "GET", 200,
                    "{\"transitions\":[{\"id\":\"5\",\"name\":\"Resolved\"},"
                            + "{\"id\":\"31\",\"name\":\"Closed\"}]}")
                    .when("/rest/api/2/issue/TEST-123/transitions", "POST", 204, "")
                    .start();

            JiraIssueTracker tracker = createTracker(server.getBaseUrl(), "u", "t");
            tracker.updateStatus("TEST-123", "Closed");

            // Should use transition id "31" (Closed)
            List<MockHttpServer.RecordedRequest> posts = new ArrayList<>();
            for (MockHttpServer.RecordedRequest req : server.getRecordedRequests()) {
                if ("POST".equals(req.method)) {
                    posts.add(req);
                }
            }
            assertThat(posts).hasSize(1);
            assertThat(posts.get(0).body).contains("\"id\":\"31\"");
        }
    }
}
