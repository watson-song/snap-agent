package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubIssueTrackerAddCommentTest {

    private GitHubIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.GitHubTracker config =
                new SnapAgentProperties.IssueClosure.GitHubTracker();
        config.setApiBaseUrl("http://localhost:39999");
        config.setToken("ghp_testtoken");
        config.setOwner("myorg");
        config.setRepo("myrepo");
        return new GitHubIssueTracker(config);
    }

    @Test
    void addComment_emptyIdDoesNothing() {
        assertThatCode(() -> createTracker().addComment("", "comment"))
                .doesNotThrowAnyException();
    }

    @Test
    void addComment_nullIdDoesNothing() {
        assertThatCode(() -> createTracker().addComment(null, "comment"))
                .doesNotThrowAnyException();
    }

    @Test
    void addComment_throwsWhenServerUnreachable() {
        assertThatThrownBy(() -> createTracker().addComment("42", "test comment"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addComment_successPostsCommentToCorrectEndpoint() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/repos/myorg/myrepo/issues/42/comments", "POST", 201,
                    "{\"id\":999,\"body\":\"test\"}")
                    .start();

            SnapAgentProperties.IssueClosure.GitHubTracker config =
                    new SnapAgentProperties.IssueClosure.GitHubTracker();
            config.setApiBaseUrl(server.getBaseUrl());
            config.setToken("ghp_token");
            config.setOwner("myorg");
            config.setRepo("myrepo");
            GitHubIssueTracker tracker = new GitHubIssueTracker(config);

            tracker.addComment("42", "Fix submitted in PR #42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/comments");
            assertThat(req).isNotNull();
            assertThat(req.path).isEqualTo("/repos/myorg/myrepo/issues/42/comments");
            assertThat(req.header("Authorization")).isEqualTo("Bearer ghp_token");
            assertThat(req.body).contains("\"body\":\"Fix submitted in PR #42\"");
        }
    }
}
