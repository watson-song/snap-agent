package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitbucketVcsClientTest {

    private BitbucketVcsClient createClient() {
        SnapAgentProperties.Vcs.Bitbucket cfg = new SnapAgentProperties.Vcs.Bitbucket();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("bb-test");
        cfg.setProjectKey("PROJ");
        cfg.setRepoSlug("myrepo");
        return new BitbucketVcsClient(cfg);
    }

    private BitbucketVcsClient createClient(String baseUrl, String token) {
        SnapAgentProperties.Vcs.Bitbucket cfg = new SnapAgentProperties.Vcs.Bitbucket();
        cfg.setBaseUrl(baseUrl);
        cfg.setToken(token);
        cfg.setProjectKey("PROJ");
        cfg.setRepoSlug("myrepo");
        return new BitbucketVcsClient(cfg);
    }

    @Test
    void shouldReturnTypeBitbucket() {
        assertThat(createClient().type()).isEqualTo("bitbucket");
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
            server.when("/rest/branch-utils/1.0/projects/PROJ/repos/myrepo/branches",
                    "POST", 201, "{}")
                    .start();

            // Set base URL with trailing slash — must be trimmed
            BitbucketVcsClient client = createClient(server.getBaseUrl() + "/", "bb-test");
            client.createBranch("fix/issue-1");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/branches");
            assertThat(req).isNotNull();
            // Path should not contain "//" after the host
            assertThat(req.path).isEqualTo(
                    "/rest/branch-utils/1.0/projects/PROJ/repos/myrepo/branches");
        }
    }

    // ---- Mock-server success path tests ----

    @Test
    void createBranch_postsToBranchesEndpointWithBearerToken() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/branch-utils/1.0/projects/PROJ/repos/myrepo/branches",
                    "POST", 201, "{}")
                    .start();

            BitbucketVcsClient client = createClient(server.getBaseUrl(), "bb-token");
            client.createBranch("fix/issue-99");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/branches");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Bearer bb-token");
            assertThat(req.body).contains("\"name\":\"fix/issue-99\"");
            assertThat(req.body).contains("\"startPoint\":\"refs/heads/main\"");
        }
    }

    @Test
    void createPullRequest_returnsMergeRequestInfo() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/1.0/projects/PROJ/repos/myrepo/pull-requests",
                    "POST", 201,
                    "{\"id\":7,\"fromRef\":{\"latestCommit\":\"sha-abc\"}}")
                    .start();

            BitbucketVcsClient client = createClient(server.getBaseUrl(), "bb-token");
            MergeRequestInfo info = client.createPullRequest(
                    "fix/issue-1", "main", "Fix bug", "Resolves #1");

            assertThat(info.getPrNumber()).isEqualTo("7");
            assertThat(info.getCommitSha()).isEqualTo("sha-abc");
            assertThat(info.getPrUrl()).contains("/pull-requests/7");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/pull-requests");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Bearer bb-token");
            assertThat(req.body).contains("\"title\":\"Fix bug\"");
            assertThat(req.body).contains("\"fromRef\"");
            assertThat(req.body).contains("\"toRef\"");
        }
    }

    @Test
    void getMergeStatus_returnsStateFromResponse() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/1.0/projects/PROJ/repos/myrepo/pull-requests/7",
                    "GET", 200, "{\"state\":\"MERGED\"}")
                    .start();

            BitbucketVcsClient client = createClient(server.getBaseUrl(), "bb-token");
            String status = client.getMergeStatus("7");

            assertThat(status).isEqualTo("MERGED");
        }
    }

    @Test
    void getMergeStatus_returnsUnknownWhenNoState() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/1.0/projects/PROJ/repos/myrepo/pull-requests/7",
                    "GET", 200, "{}")
                    .start();

            BitbucketVcsClient client = createClient(server.getBaseUrl(), "bb-token");
            String status = client.getMergeStatus("7");

            assertThat(status).isEqualTo("UNKNOWN");
        }
    }
}
