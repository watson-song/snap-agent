package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

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
}
