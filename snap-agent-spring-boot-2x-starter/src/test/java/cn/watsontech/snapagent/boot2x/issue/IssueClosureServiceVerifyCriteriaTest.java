package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueClosureServiceVerifyCriteriaTest {

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
    void verify_returnsNullWhenNoCriteriaNoRunnerAndNoSkillRegistry() {
        SolutionSuggestion sol = new SolutionSuggestion(
                java.util.Collections.emptyList(), null, null, null, null);
        IssueClosure issue = new IssueClosure(
                "i1", "EXT-1", null, "t1", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, null, null, 1L, 1L);
        when(issueStore.load("i1")).thenReturn(issue);

        // No verificationRunner and no skillRegistry → returns null
        IssueClosure result = service.verify("i1");
        // Since verifyViaSkill needs skillRegistry (null) → returns null
        assertThat(result).isNull();
    }

    @Test
    void verify_addsCommentToExternalIssue() {
        SolutionSuggestion sol = new SolutionSuggestion(
                java.util.Collections.emptyList(), null, null, null, null);
        VerificationResult vr = new VerificationResult(true, "passed",
                "FIX_SUBMITTED", "VERIFIED", 2000L);
        IssueClosure issue = new IssueClosure(
                "i2", "EXT-2", null, "t2", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, null, null, 1L, 1L);
        when(issueStore.load("i2")).thenReturn(issue);

        // Spy to override verifyViaSkill
        IssueClosureService spy = spy(service);
        doReturn(vr).when(spy).verifyViaSkill(eq("i2"), any(IssueClosure.class));

        IssueClosure result = spy.verify("i2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.VERIFIED);
        verify(issueTracker).addComment(eq("EXT-2"), any(String.class));
    }

    @Test
    void verify_transitionsToFailedWhenNotPassed() {
        SolutionSuggestion sol = new SolutionSuggestion(
                java.util.Collections.emptyList(), null, null, null, null);
        VerificationResult vr = new VerificationResult(false, "failed",
                "FIX_SUBMITTED", "FAILED", 2000L);
        IssueClosure issue = new IssueClosure(
                "i3", null, null, "t3", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, null, null, 1L, 1L);
        when(issueStore.load("i3")).thenReturn(issue);

        IssueClosureService spy = spy(service);
        doReturn(vr).when(spy).verifyViaSkill(eq("i3"), any(IssueClosure.class));

        IssueClosure result = spy.verify("i3");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FAILED);
    }
}
