package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueClosureServiceOnPrMergedTest {

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
    void onPrMerged_returnsNullWhenIssueNotFound() {
        when(issueStore.findByPrNumber("99")).thenReturn(null);
        assertThat(service.onPrMerged("99")).isNull();
    }

    @Test
    void onPrMerged_idempotentWhenWrongStatus() {
        IssueClosure issue = new IssueClosure(
                "issue_2", null, null, "t", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, null, null, 1L, 1L);
        when(issueStore.findByPrNumber("2")).thenReturn(issue);
        assertThat(service.onPrMerged("2")).isNull();
    }

    @Test
    void onPrMerged_verifiesAndClosesIssue() {
        // 1. Stub findByPrNumber to return a FIX_SUBMITTED issue with an external ID
        IssueClosure issue = new IssueClosure(
                "issue_42", "EXT-1", null, "t", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, "42",
                null, null, 1L, 1L);
        when(issueStore.findByPrNumber("42")).thenReturn(issue);
        when(issueStore.load("issue_42")).thenReturn(issue);

        // 2. Spy the service and stub verifyViaSkill to return a passing result
        IssueClosureService spy = spy(service);
        VerificationResult vr = new VerificationResult(true, "all passed",
                "FIX_SUBMITTED", "VERIFIED", 2000L);
        doReturn(vr).when(spy).verifyViaSkill(eq("issue_42"), any(IssueClosure.class));

        // 3. Call onPrMerged
        IssueClosure result = spy.onPrMerged("42");

        // 4. Assert the returned issue has status CLOSED
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);

        // 5. Verify external tracker update and persistence occurred
        verify(issueTracker).updateStatus(eq("EXT-1"), any(String.class));
        verify(issueStore, atLeast(1)).save(any(IssueClosure.class));
    }
}
