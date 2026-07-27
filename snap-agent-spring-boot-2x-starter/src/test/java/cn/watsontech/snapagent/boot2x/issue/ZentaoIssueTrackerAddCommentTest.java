package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.test.MockHttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZentaoIssueTrackerAddCommentTest {

    private ZentaoIssueTracker createTracker() {
        SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("test-token");
        cfg.setProductId(1);
        return new ZentaoIssueTracker(cfg);
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
        assertThatThrownBy(() -> createTracker().addComment("123", "test comment"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void addComment_successPostsCommentBodyWithTokenHeader() throws Exception {
        try (MockHttpServer server = new MockHttpServer()) {
            server.when("/api.php/v1/bugs/456/comments", "POST", 200, "")
                    .start();

            SnapAgentProperties.IssueClosure.ZentaoTracker cfg = new SnapAgentProperties.IssueClosure.ZentaoTracker();
            cfg.setBaseUrl(server.getBaseUrl());
            cfg.setToken("my-token");
            cfg.setProductId(1);
            ZentaoIssueTracker tracker = new ZentaoIssueTracker(cfg);

            tracker.addComment("456", "已修复，请验证");

            MockHttpServer.RecordedRequest req = server.findRequest("POST", "/bugs/456/comments");
            assertThat(req).isNotNull();
            assertThat(req.header("Authorization")).isEqualTo("Token my-token");
            assertThat(req.body).contains("\"comment\":\"已修复，请验证\"");
        }
    }
}
