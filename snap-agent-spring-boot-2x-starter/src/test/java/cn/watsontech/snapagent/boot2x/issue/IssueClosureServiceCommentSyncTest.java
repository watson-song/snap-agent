package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueClosureServiceCommentSyncTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", null);
    }

    @Test
    void close_addsCommentToExternalIssue() {
        IssueClosure issue = new IssueClosure(
                "i1", "EXT-1", "t1", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                "sha", null, null,
                new VerificationResult(true, "ok", null, null, 1L),
                null, 1L, 1L);
        when(issueStore.load("i1")).thenReturn(issue);

        service.close("i1");

        verify(issueTracker).updateStatus("EXT-1", "resolved");
        verify(issueTracker).addComment(eq("EXT-1"), contains("关闭"));
    }

    @Test
    void close_commentFailureDoesNotBlock() {
        IssueClosure issue = new IssueClosure(
                "i2", "EXT-2", "t2", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("i2")).thenReturn(issue);
        doThrow(new RuntimeException("timeout"))
                .when(issueTracker).addComment(eq("EXT-2"), any(String.class));

        IssueClosure result = service.close("i2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);
    }
}
