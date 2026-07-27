package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ZentaoIssueTracker}.
 *
 * <p>Pure-logic methods (type, getIssueUrl, updateStatus guards) are tested
 * directly. HTTP-dependent methods are tested two ways:</p>
 * <ul>
 *   <li>Error path: unreachable port → TrackerException</li>
 *   <li>Success path: mock HTTP server → verify request URL, headers, body, response parsing</li>
 * </ul>
 */
class ZentaoIssueTrackerTest {

    private ZentaoIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
        cfg.setBaseUrl("http://localhost:39999");  // unreachable port
        cfg.setToken("test-token");
        cfg.setProductId(1);
        cfg.setProjectId(2);
        return new ZentaoIssueTracker(cfg);
    }

    private ZentaoIssueTracker createTracker(String baseUrl, String token) {
        SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
        cfg.setBaseUrl(baseUrl);
        cfg.setToken(token);
        cfg.setProductId(1);
        cfg.setProjectId(2);
        return new ZentaoIssueTracker(cfg);
    }

    // ---- Pure-logic tests ----

    @Test
    void shouldReturnTypeZentao() {
        assertThat(createTracker().type()).isEqualTo("zentao");
    }

    @Test
    void shouldReturnCorrectIssueUrl() {
        assertThat(createTracker().getIssueUrl("123"))
                .isEqualTo("http://localhost:39999/bug-view-123.html");
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
        createTracker().updateStatus(null, "resolved");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        createTracker().updateStatus("", "resolved");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        assertThatThrownBy(() -> createTracker().createIssue("title", "desc", null))
                .isInstanceOf(AbstractHttpIssueTracker.TrackerException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() {
        SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
        cfg.setBaseUrl("https://zentao.example.com/");
        cfg.setToken("tok");
        cfg.setProductId(5);
        ZentaoIssueTracker tracker = new ZentaoIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("42"))
                .isEqualTo("https://zentao.example.com/bug-view-42.html");
    }

    @Test
    void shouldHandleNullBaseUrl() {
        SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
        ZentaoIssueTracker tracker = new ZentaoIssueTracker(cfg);
        assertThat(tracker.getIssueUrl("1"))
                .isEqualTo("/bug-view-1.html");
    }

    // ---- Mock-server success path tests ----

    @Test
    void createIssue_returnsIdOnSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/products/1/bugs", "POST", 200, "{\"id\":123}")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "zentao-token");
            String issueId = tracker.createIssue("测试Bug", "描述内容", "dev1");

            assertThat(issueId).isEqualTo("123");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/products/1/bugs");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Token zentao-token");
            assertThat(req.body).contains("\"title\":\"测试Bug\"");
            assertThat(req.body).contains("\"desc\":\"描述内容\"");
            assertThat(req.body).contains("\"severity\":3");
            assertThat(req.body).contains("\"pri\":3");
            assertThat(req.body).contains("\"type\":\"codeerror\"");
            assertThat(req.body).contains("\"project\":2");
            assertThat(req.body).contains("\"assignedTo\":\"dev1\"");
        }
    }

    @Test
    void createIssue_returnsNullWhenResponseHasNoId() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/products/1/bugs", "POST", 200,
                    "{\"error\":\"validation failed\"}")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            String issueId = tracker.createIssue("title", "desc", null);

            assertThat(issueId).isNull();
        }
    }

    @Test
    void createIssue_omitsProjectWhenProjectIdZero() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/products/5/bugs", "POST", 200, "{\"id\":99}")
                    .start();

            SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
            cfg.setBaseUrl(server.getBaseUrl());
            cfg.setToken("tok");
            cfg.setProductId(5);
            // projectId defaults to 0 — should not appear in body
            ZentaoIssueTracker tracker = new ZentaoIssueTracker(cfg);

            tracker.createIssue("bug", "desc", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/products/5/bugs");
            assertThat(req.body).doesNotContain("\"project\"");
        }
    }

    @Test
    void createIssue_omitsAssigneeWhenNull() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/products/1/bugs", "POST", 200, "{\"id\":1}")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.createIssue("bug", "desc", null);

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/products/1/bugs");
            assertThat(req.body).doesNotContain("\"assignedTo\"");
        }
    }

    @Test
    void updateStatus_postsResolveForResolvedStatus() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/bugs/123/resolve", "POST", 200, "")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.updateStatus("123", "resolved");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/resolve");
            assertThat(req).isNotNull();
            assertThat(req.body).contains("\"resolution\":\"fixed\"");
        }
    }

    @Test
    void updateStatus_postsCloseForClosedStatus() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/bugs/123/close", "POST", 200, "")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.updateStatus("123", "closed");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/close");
            assertThat(req).isNotNull();
        }
    }

    @Test
    void updateStatus_postsActivateForReopenStatus() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/bugs/123/activate", "POST", 200, "")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "tok");
            tracker.updateStatus("123", "reopened");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/activate");
            assertThat(req).isNotNull();
        }
    }

    @Test
    void addComment_postsToCommentsEndpoint() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/bugs/123/comments", "POST", 200, "")
                    .start();

            ZentaoIssueTracker tracker = createTracker(server.getBaseUrl(), "zentao-token");
            tracker.addComment("123", "修复完成");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/comments");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Token zentao-token");
            assertThat(req.body).contains("\"comment\":\"修复完成\"");
        }
    }
}
