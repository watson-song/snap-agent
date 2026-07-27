package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

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
}
