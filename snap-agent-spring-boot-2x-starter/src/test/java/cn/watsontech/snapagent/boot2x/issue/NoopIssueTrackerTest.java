package cn.watsontech.snapagent.boot2x.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoopIssueTracker}.
 */
class NoopIssueTrackerTest {

    @Test
    void shouldReturnNullFromCreateIssue() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        String result = tracker.createIssue("title", "description", "assignee");
        assertThat(result).isNull();
    }

    @Test
    void shouldNoOpUpdateStatus() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        // Should not throw
        tracker.updateStatus("EXT-1", "in_progress");
    }

    @Test
    void shouldReturnNullFromGetIssueUrl() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        String url = tracker.getIssueUrl("EXT-1");
        assertThat(url).isNull();
    }

    @Test
    void shouldReturnTypeNoop() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        assertThat(tracker.type()).isEqualTo("noop");
    }
}
