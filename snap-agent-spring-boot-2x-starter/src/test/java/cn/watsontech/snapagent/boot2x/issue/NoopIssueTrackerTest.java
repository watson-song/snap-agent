package cn.watsontech.snapagent.boot2x.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        assertThatCode(() -> tracker.updateStatus("EXT-1", "in_progress"))
                .doesNotThrowAnyException();
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

    // ── deeper coverage (GAP-8) ───────────────────────────────────

    @Test
    void shouldReturnNullFromCreateIssueWithAllNullArgs() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        assertThat(tracker.createIssue(null, null, null)).isNull();
    }

    @Test
    void shouldNoOpUpdateStatusWithNullArgs() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        // Should not throw for any combination of null/empty arguments.
        assertThatCode(() -> {
            tracker.updateStatus(null, null);
            tracker.updateStatus(null, "");
            tracker.updateStatus("", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldReturnNullFromGetIssueUrlWithNull() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        assertThat(tracker.getIssueUrl(null)).isNull();
        assertThat(tracker.getIssueUrl("")).isNull();
    }

    @Test
    void shouldBeIdempotentUpdateStatus() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        // Updating the same issue multiple times should not throw.
        assertThatCode(() -> {
            tracker.updateStatus("EXT-1", "open");
            tracker.updateStatus("EXT-1", "in_progress");
            tracker.updateStatus("EXT-1", "closed");
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldReturnConsistentNullFromCreateIssue() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        // Multiple calls should consistently return null.
        assertThat(tracker.createIssue("t1", "d1", "a1")).isNull();
        assertThat(tracker.createIssue("t2", "d2", null)).isNull();
    }

    @Test
    void addComment_doesNotThrow() {
        NoopIssueTracker tracker = new NoopIssueTracker();
        // The noop tracker should silently accept addComment calls
        assertThatCode(() -> tracker.addComment("EXT-1", "test comment"))
                .doesNotThrowAnyException();
    }
}
