package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.core.issue.*;
import cn.watsontech.snapagent.core.vcs.FixResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueClosureServiceAutoFixTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private FixExecutionService fixExecutionService;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        fixExecutionService = mock(FixExecutionService.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", fixExecutionService);
    }

    @Test
    void autoFix_transitionsToFixSubmitted() {
        IssueClosure issue = new IssueClosure(
                "issue_1", "EXT-1", null, "task_1", null, "user1", "query",
                "root cause", null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, null, null, 1000L, 1000L);
        when(issueStore.load("issue_1")).thenReturn(issue);

        FixResult fixResult = FixResult.success(
                "abc123", "https://gitlab/mr/1", "1",
                Arrays.asList("src/Main.java"));
        when(fixExecutionService.autoFix("issue_1")).thenReturn(fixResult);

        IssueClosure result = service.autoFix("issue_1");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
        assertThat(result.getFixCommitId()).isEqualTo("abc123");
        verify(issueStore).save(any(IssueClosure.class));
        verify(issueTracker).updateStatus("EXT-1", "in_progress");
        verify(issueTracker).addComment(eq("EXT-1"), any(String.class));
    }

    @Test
    void autoFix_returnsNullWhenIssueNotFound() {
        when(issueStore.load("nope")).thenReturn(null);
        assertThat(service.autoFix("nope")).isNull();
    }

    @Test
    void autoFix_returnsNullWhenWrongStatus() {
        IssueClosure issue = new IssueClosure(
                "issue_2", null, null, "task_2", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("issue_2")).thenReturn(issue);
        assertThat(service.autoFix("issue_2")).isNull();
    }

    @Test
    void autoFix_addCommentFailureDoesNotBlock() {
        IssueClosure issue = new IssueClosure(
                "issue_3", "EXT-3", null, "task_3", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.load("issue_3")).thenReturn(issue);
        when(fixExecutionService.autoFix("issue_3"))
                .thenReturn(FixResult.success("sha", "url", "1", Arrays.asList("f")));
        doThrow(new RuntimeException("network error"))
                .when(issueTracker).addComment(eq("EXT-3"), any(String.class));

        IssueClosure result = service.autoFix("issue_3");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
    }
}
