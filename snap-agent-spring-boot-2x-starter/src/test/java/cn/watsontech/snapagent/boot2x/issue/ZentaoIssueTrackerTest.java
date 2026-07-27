package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ZentaoIssueTracker}.
 *
 * <p>HTTP-dependent methods are tested with unreachable URLs to verify
 * error handling. Pure-logic methods (type, getIssueUrl, updateStatus
 * guards) are tested directly.</p>
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

    @Test
    void shouldReturnTypeZentao() {
        assertThat(createTracker().type()).isEqualTo("zentao");
    }

    @Test
    void shouldReturnCorrectIssueUrl() {
        ZentaoIssueTracker tracker = createTracker();
        assertThat(tracker.getIssueUrl("123"))
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
        ZentaoIssueTracker tracker = createTracker();
        // Should not throw
        tracker.updateStatus(null, "resolved");
    }

    @Test
    void shouldNoOpUpdateStatusForEmptyId() {
        ZentaoIssueTracker tracker = createTracker();
        tracker.updateStatus("", "resolved");
    }

    @Test
    void shouldThrowTrackerExceptionWhenServerUnreachable() {
        ZentaoIssueTracker tracker = createTracker();
        assertThatThrownBy(() -> tracker.createIssue("title", "desc", null))
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
        // Should still produce a URL (with empty base)
        assertThat(tracker.getIssueUrl("1"))
                .isEqualTo("/bug-view-1.html");
    }
}
