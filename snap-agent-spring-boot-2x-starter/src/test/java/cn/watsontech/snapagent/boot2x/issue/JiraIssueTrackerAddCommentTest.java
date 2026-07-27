package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiraIssueTrackerAddCommentTest {

    private JiraIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.JiraTracker config =
                new SnapAgentProperties.IssueClosure.JiraTracker();
        config.setBaseUrl("http://localhost:39999");
        config.setUsername("user@example.com");
        config.setApiToken("api-token");
        config.setProjectKey("PROJ");
        return new JiraIssueTracker(config);
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
        assertThatThrownBy(() -> createTracker().addComment("PROJ-123", "test comment"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addComment_successPostsCommentToCorrectEndpoint() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/rest/api/2/issue/PROJ-123/comment", "POST", 201,
                    "{\"id\":\"100\",\"body\":\"test\"}")
                    .start();

            SnapAgentProperties.IssueClosure.JiraTracker config =
                    new SnapAgentProperties.IssueClosure.JiraTracker();
            config.setBaseUrl(server.getBaseUrl());
            config.setUsername("user@example.com");
            config.setApiToken("api-token");
            config.setProjectKey("PROJ");
            JiraIssueTracker tracker = new JiraIssueTracker(config);

            tracker.addComment("PROJ-123", "Fix submitted in PR #42");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/comment");
            assertThat(req).isNotNull();
            assertThat(req.path).isEqualTo("/rest/api/2/issue/PROJ-123/comment");
            assertThat(req.body).contains("\"body\":\"Fix submitted in PR #42\"");
            // Verify Basic auth is used when username is set
            assertThat(req.header("Authorization")).startsWith("Basic ");
        }
    }
}
