package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.core.issue.AcceptanceCriterion;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.vcs.FixResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for the auto-fix workflow:
 * autoFix → PR submitted → onPrMerged → verify → close.
 *
 * <p>Uses mocks for IssueStore, IssueTracker, and FixExecutionService
 * to verify the full orchestration flow without external dependencies.</p>
 */
@ExtendWith(MockitoExtension.class)
class AutoFixEndToEndTest {

    @Mock private IssueStore issueStore;
    @Mock private IssueTracker issueTracker;
    @Mock private FixExecutionService fixExecutionService;

    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", fixExecutionService);
    }

    @Test
    void fullFlow_autoFix_thenOnPrMerged_thenClose() {
        // 1. Start with an issue in FIX_IN_PROGRESS
        IssueClosure issue = new IssueClosure(
                "issue_1", "EXT-1", null, "task_1", null, "u", "q", "rc",
                null, null,
                IssueStatus.FIX_IN_PROGRESS,
                null, null, null,
                null, null,
                1000L, 1000L);
        when(issueStore.load("issue_1")).thenReturn(issue);
        when(issueStore.findByPrNumber("42")).thenReturn(
                issue.withFix("sha123", "https://gitlab/mr/42", "42",
                        IssueStatus.FIX_SUBMITTED, 2000L));

        // 2. autoFix — FixExecutionService returns success
        FixResult fixResult = FixResult.success(
                "sha123", "https://gitlab/mr/42", "42",
                Arrays.asList("src/Main.java"));
        when(fixExecutionService.autoFix("issue_1")).thenReturn(fixResult);

        IssueClosure fixed = service.autoFix("issue_1");
        assertThat(fixed).isNotNull();
        assertThat(fixed.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
        assertThat(fixed.getFixCommitId()).isEqualTo("sha123");
        assertThat(fixed.getFixPrNumber()).isEqualTo("42");
        verify(issueTracker).addComment(eq("EXT-1"), contains("修复"));

        // 3. onPrMerged — spy and stub verifyViaSkill to return passing result
        IssueClosureService spy = spy(service);
        VerificationResult vr = new VerificationResult(true, "all passed",
                "FIX_SUBMITTED", "VERIFIED", 3000L);
        doReturn(vr).when(spy).verifyViaSkill(eq("issue_1"), any(IssueClosure.class));

        IssueClosure merged = spy.onPrMerged("42");
        assertThat(merged).isNotNull();
        assertThat(merged.getStatus()).isEqualTo(IssueStatus.CLOSED);
        verify(issueTracker, atLeast(1)).updateStatus(eq("EXT-1"), any(String.class));
    }

    @Test
    void autoFix_failureTransitionsToFailed() {
        IssueClosure issue = new IssueClosure(
                "issue_2", "EXT-2", null, "task_2", null, "u", "q", "rc",
                null, null,
                IssueStatus.FIX_IN_PROGRESS,
                null, null, null,
                null, null,
                1L, 1L);
        when(issueStore.load("issue_2")).thenReturn(issue);
        when(fixExecutionService.autoFix("issue_2"))
                .thenReturn(FixResult.failed("AI produced no changes"));

        IssueClosure result = service.autoFix("issue_2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FAILED);
        verify(issueTracker).addComment(eq("EXT-2"), contains("失败"));
    }

    @Test
    void onPrMerged_returnsNullWhenNoMatchingIssue() {
        when(issueStore.findByPrNumber("999")).thenReturn(null);

        IssueClosure result = service.onPrMerged("999");
        assertThat(result).isNull();
    }

    @Test
    void onPrMerged_skipsWhenNotFixSubmitted() {
        IssueClosure issue = new IssueClosure(
                "issue_3", "EXT-3", null, "task_3", null, "u", "q", "rc",
                null, null,
                IssueStatus.DIAGNOSED,
                null, null, null,
                null, null,
                1L, 1L);
        when(issueStore.findByPrNumber("3")).thenReturn(issue);

        IssueClosure result = service.onPrMerged("3");
        assertThat(result).isNull();
    }
}
