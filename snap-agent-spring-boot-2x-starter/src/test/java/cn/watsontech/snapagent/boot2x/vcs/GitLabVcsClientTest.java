package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabVcsClientTest {

    private GitLabVcsClient createClient() {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("glpat-test");
        cfg.setProjectId(123);
        return new GitLabVcsClient(cfg);
    }

    private GitLabVcsClient createClient(String baseUrl, String token) {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl(baseUrl);
        cfg.setToken(token);
        cfg.setProjectId(123);
        return new GitLabVcsClient(cfg);
    }

    @Test
    void shouldReturnTypeGitlab() {
        assertThat(createClient().type()).isEqualTo("gitlab");
    }

    @Test
    void shouldThrowWhenServerUnreachable() {
        assertThatThrownBy(() -> createClient().createBranch("fix/test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed POST");
    }

    // ---- Fix: trailing slash should be trimmed (was fake — asserted type() only) ----

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/repository/branches", "POST", 201, "{}")
                    .start();

            // Set base URL with trailing slash — must be trimmed so request path
            // doesn't contain double slashes
            GitLabVcsClient client = createClient(server.getBaseUrl() + "/", "glpat-test");
            client.createBranch("fix/issue-1");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/repository/branches");
            assertThat(req).isNotNull();
            // Path should not contain "//" after the host
            assertThat(req.path).isEqualTo("/api/v4/projects/123/repository/branches");
        }
    }

    // ---- Fix: empty changes list throws before any HTTP call (was fake — passed due to HTTP failure) ----

    @Test
    void shouldHandleCommitFilesEmpty() {
        GitLabVcsClient client = createClient();
        assertThatThrownBy(() -> client.commitFiles("fix/test",
                new ArrayList<FileChange>(), "msg"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("No file changes to commit");
    }

    @Test
    void shouldHandleCommitFilesNull() {
        GitLabVcsClient client = createClient();
        assertThatThrownBy(() -> client.commitFiles("fix/test", null, "msg"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("No file changes to commit");
    }

    // ---- Mock-server success path tests ----

    @Test
    void createBranch_postsToBranchesEndpointWithToken() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/repository/branches", "POST", 201, "{}")
                    .start();

            GitLabVcsClient client = createClient(server.getBaseUrl(), "glpat-test");
            client.createBranch("fix/issue-42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/repository/branches");
            assertThat(req).isNotNull();
            assertThat(req.header("PRIVATE-TOKEN")).isEqualTo("glpat-test");
            assertThat(req.body).contains("\"branch\":\"fix/issue-42\"");
            assertThat(req.body).contains("\"ref\":\"main\"");
        }
    }

    @Test
    void commitFiles_returnsCommitIdOnSuccess() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/repository/commits", "POST", 201,
                    "{\"id\":\"abc123sha\",\"short_id\":\"abc123\"}")
                    .start();

            GitLabVcsClient client = createClient(server.getBaseUrl(), "glpat-test");
            List<FileChange> changes = Arrays.asList(
                    FileChange.create("src/Main.java", "public class Main {}"),
                    FileChange.update("src/Utils.java", "// updated"));
            String commitSha = client.commitFiles("fix/test", changes, "fix: resolve NPE");

            assertThat(commitSha).isEqualTo("abc123sha");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/repository/commits");
            assertThat(req).isNotNull();
            assertThat(req.header("PRIVATE-TOKEN")).isEqualTo("glpat-test");
            assertThat(req.body).contains("\"branch\":\"fix/test\"");
            assertThat(req.body).contains("\"commit_message\":\"fix: resolve NPE\"");
            assertThat(req.body).contains("\"action\":\"create\"");
            assertThat(req.body).contains("\"file_path\":\"src/Main.java\"");
            assertThat(req.body).contains("\"content\":\"public class Main {}\"");
        }
    }

    @Test
    void createPullRequest_returnsMergeRequestInfo() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/merge_requests", "POST", 201,
                    "{\"iid\":42,\"web_url\":\"https://gitlab.com/user/repo/-/merge_requests/42\","
                            + "\"sha\":\"commit-sha-789\"}")
                    .start();

            GitLabVcsClient client = createClient(server.getBaseUrl(), "glpat-test");
            MergeRequestInfo info = client.createPullRequest(
                    "fix/issue-1", "main", "Fix NPE", "Resolves #1");

            assertThat(info.getPrUrl()).isEqualTo("https://gitlab.com/user/repo/-/merge_requests/42");
            assertThat(info.getPrNumber()).isEqualTo("42");
            assertThat(info.getCommitSha()).isEqualTo("commit-sha-789");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/merge_requests");
            assertThat(req).isNotNull();
            assertThat(req.body).contains("\"source_branch\":\"fix/issue-1\"");
            assertThat(req.body).contains("\"target_branch\":\"main\"");
            assertThat(req.body).contains("\"title\":\"Fix NPE\"");
        }
    }

    @Test
    void getMergeStatus_returnsStateFromResponse() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/merge_requests/42", "GET", 200,
                    "{\"state\":\"merged\"}")
                    .start();

            GitLabVcsClient client = createClient(server.getBaseUrl(), "glpat-test");
            String status = client.getMergeStatus("42");

            assertThat(status).isEqualTo("MERGED");
        }
    }

    @Test
    void getMergeStatus_returnsUnknownWhenNoState() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api/v4/projects/123/merge_requests/42", "GET", 200, "{}")
                    .start();

            GitLabVcsClient client = createClient(server.getBaseUrl(), "glpat-test");
            String status = client.getMergeStatus("42");

            assertThat(status).isEqualTo("UNKNOWN");
        }
    }
}
