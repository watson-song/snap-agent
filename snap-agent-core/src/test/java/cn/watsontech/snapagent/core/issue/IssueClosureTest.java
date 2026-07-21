package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IssueClosure}.
 */
class IssueClosureTest {

    private static final long CREATED_AT = 1_700_000_000_000L;
    private static final long UPDATED_AT = 1_700_000_001_000L;

    private SolutionOption opt(String id) {
        return new SolutionOption(id, "title-" + id, "desc-" + id, "low", false);
    }

    private SolutionSuggestion sampleSuggestion() {
        List<SolutionOption> options = Arrays.asList(
                new SolutionOption("opt-1", "重启服务", "通过滚动重启释放连接", "low", true),
                new SolutionOption("opt-2", "扩容连接池", "永久修复, 提升容量", "high", false));
        return new SolutionSuggestion(options, "opt-2", "永久方案优先", "com.example.OrderService");
    }

    private IssueClosure newSample() {
        return new IssueClosure(
                "issue-001", null, "task-100",
                "conv-100", "user1", "为什么订单服务超时?", "连接池打满",
                sampleSuggestion(), null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                CREATED_AT, UPDATED_AT);
    }

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        IssueClosure issue = newSample();

        assertThat(issue.getIssueId()).isEqualTo("issue-001");
        assertThat(issue.getExternalIssueId()).isNull();
        assertThat(issue.getTaskId()).isEqualTo("task-100");
        assertThat(issue.getConversationId()).isEqualTo("conv-100");
        assertThat(issue.getUserId()).isEqualTo("user1");
        assertThat(issue.getUserQuery()).isEqualTo("为什么订单服务超时?");
        assertThat(issue.getRootCause()).isEqualTo("连接池打满");
        // New: SolutionSuggestion instead of List<String>
        assertThat(issue.getSolution()).isNotNull();
        assertThat(issue.getSolution().getOptions()).hasSize(2);
        assertThat(issue.getSolution().getOptions().get(0).getId()).isEqualTo("opt-1");
        assertThat(issue.getSolution().getOptions().get(1).getId()).isEqualTo("opt-2");
        assertThat(issue.getSolution().getRecommendedOptionId()).isEqualTo("opt-2");
        assertThat(issue.getSelectedSolution()).isNull();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(issue.getFixCommitId()).isNull();
        // New: VerificationResult instead of String
        assertThat(issue.getVerificationResult()).isNull();
        assertThat(issue.getKnowledgeEntryId()).isNull();
        assertThat(issue.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(issue.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void shouldReturnNullSolutionWhenNullPassed() {
        // New behavior: null SolutionSuggestion → getter returns null, does not crash
        IssueClosure issue = new IssueClosure(
                "id", null, "task", null, null, "q", "rc",
                null, null, IssueStatus.DIAGNOSED, null, null, null,
                CREATED_AT, UPDATED_AT);

        assertThat(issue.getSolution()).isNull();
        assertThat(issue.getVerificationResult()).isNull();
    }

    @Test
    void shouldPreserveProvidedSolutionSuggestionReference() {
        // SolutionSuggestion is itself immutable, so IssueClosure may share the
        // reference (no defensive copy of its internals is required). Verify
        // that the exact same immutable suggestion is returned.
        SolutionSuggestion suggestion = sampleSuggestion();
        IssueClosure issue = new IssueClosure(
                "id", null, "task", null, null, "q", "rc",
                suggestion, null, IssueStatus.DIAGNOSED, null, null, null,
                CREATED_AT, UPDATED_AT);

        assertThat(issue.getSolution()).isSameAs(suggestion);
        // Mutating the source options list after construction is safe because
        // SolutionSuggestion defensively copied at its own construction time.
        assertThat(issue.getSolution().getOptions()).hasSize(2);
    }

    @Test
    void shouldHaveMeaningfulToString() {
        IssueClosure issue = newSample();

        String str = issue.toString();
        assertThat(str).contains("IssueClosure");
        assertThat(str).contains("issue-001");
        assertThat(str).contains("task-100");
        assertThat(str).contains("DIAGNOSED");
    }

    @Test
    void withStatusShouldReturnNewInstanceWithUpdatedStatusAndUpdatedAt() {
        IssueClosure original = newSample();
        long newUpdatedAt = UPDATED_AT + 5_000L;

        IssueClosure updated = original.withStatus(IssueStatus.SOLUTION_PROPOSED, newUpdatedAt);

        // New instance returned
        assertThat(updated).isNotSameAs(original);
        // Updated fields
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.SOLUTION_PROPOSED);
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Original unchanged (immutability)
        assertThat(original.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(original.getUpdatedAt()).isEqualTo(UPDATED_AT);
        // Other fields preserved
        assertThat(updated.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(updated.getTaskId()).isEqualTo(original.getTaskId());
        // SolutionSuggestion preserved through chain (immutable, may share reference)
        assertThat(updated.getSolution()).isNotNull();
        assertThat(updated.getSolution().getOptions()).hasSize(2);
        assertThat(updated.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    void withExternalIssueShouldReturnNewInstanceWithExternalIdAndStatus() {
        IssueClosure original = newSample();
        long newUpdatedAt = UPDATED_AT + 10_000L;

        IssueClosure updated = original.withExternalIssue(
                "JIRA-1234", IssueStatus.ISSUE_CREATED, newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getExternalIssueId()).isEqualTo("JIRA-1234");
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.ISSUE_CREATED);
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Original unchanged
        assertThat(original.getExternalIssueId()).isNull();
        assertThat(original.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        // Other fields preserved
        assertThat(updated.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(updated.getTaskId()).isEqualTo(original.getTaskId());
        assertThat(updated.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    void withExternalIssueShouldAcceptSelectedSolutionId() {
        IssueClosure original = newSample();
        long newUpdatedAt = UPDATED_AT + 11_000L;

        IssueClosure updated = original.withExternalIssue(
                "JIRA-1234", "opt-2", IssueStatus.FIX_IN_PROGRESS, newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getExternalIssueId()).isEqualTo("JIRA-1234");
        // selectedSolution stays String (user-chosen option ID), not SolutionSuggestion
        assertThat(updated.getSelectedSolution()).isEqualTo("opt-2");
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Original unchanged
        assertThat(original.getSelectedSolution()).isNull();
    }

    @Test
    void withSolutionShouldReturnNewInstanceWithSolutionSuggestion() {
        IssueClosure original = new IssueClosure(
                "id", null, "task", null, null, "q", "rc",
                null, null, IssueStatus.DIAGNOSED, null, null, null,
                CREATED_AT, UPDATED_AT);
        long newUpdatedAt = UPDATED_AT + 6_000L;
        SolutionSuggestion suggestion = sampleSuggestion();

        IssueClosure updated = original.withSolution(suggestion, newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getSolution()).isSameAs(suggestion);
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Status preserved
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        // Original unchanged (still null solution)
        assertThat(original.getSolution()).isNull();
        // Other fields preserved
        assertThat(updated.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(updated.getTaskId()).isEqualTo(original.getTaskId());
    }

    @Test
    void withVerificationShouldReturnNewInstanceWithVerificationResult() {
        IssueClosure original = newSample().withStatus(
                IssueStatus.FIX_IN_PROGRESS, UPDATED_AT);
        long newUpdatedAt = UPDATED_AT + 20_000L;
        VerificationResult verification = new VerificationResult(
                true, "修复后连接池使用率正常, 超时消失",
                "ERROR_RATE=0.05", "ERROR_RATE=0.001", newUpdatedAt);

        IssueClosure updated = original.withVerification(verification, newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getVerificationResult()).isSameAs(verification);
        assertThat(updated.getVerificationResult().isPassed()).isTrue();
        assertThat(updated.getVerificationResult().getSummary())
                .isEqualTo("修复后连接池使用率正常, 超时消失");
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Status preserved from original (withVerification does not touch status)
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        // Original unchanged
        assertThat(original.getVerificationResult()).isNull();
        // Other fields preserved
        assertThat(updated.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(updated.getTaskId()).isEqualTo(original.getTaskId());
    }

    @Test
    void withKnowledgeEntryShouldReturnNewInstanceWithKnowledgeEntryId() {
        IssueClosure original = newSample().withStatus(
                IssueStatus.VERIFIED, UPDATED_AT);
        long newUpdatedAt = UPDATED_AT + 30_000L;

        IssueClosure updated = original.withKnowledgeEntry(
                "kb-sed-001", newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getKnowledgeEntryId()).isEqualTo("kb-sed-001");
        assertThat(updated.getUpdatedAt()).isEqualTo(newUpdatedAt);
        // Original unchanged
        assertThat(original.getKnowledgeEntryId()).isNull();
        // Other fields preserved (status should remain VERIFIED, not CLOSED)
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.VERIFIED);
        assertThat(updated.getIssueId()).isEqualTo(original.getIssueId());
        assertThat(updated.getTaskId()).isEqualTo(original.getTaskId());
    }

    @Test
    void chainedWithMethodsShouldComposeWithoutLosingData() {
        IssueClosure base = newSample();
        VerificationResult verification = new VerificationResult(
                true, "verified ok", "before", "after", UPDATED_AT + 3L);

        IssueClosure chained = base
                .withStatus(IssueStatus.SOLUTION_PROPOSED, UPDATED_AT + 1L)
                .withExternalIssue("EXT-9", IssueStatus.ISSUE_CREATED, UPDATED_AT + 2L)
                .withSolution(sampleSuggestion(), UPDATED_AT + 2L)
                .withVerification(verification, UPDATED_AT + 3L)
                .withKnowledgeEntry("kb-1", UPDATED_AT + 4L);

        assertThat(chained.getStatus()).isEqualTo(IssueStatus.ISSUE_CREATED);
        assertThat(chained.getExternalIssueId()).isEqualTo("EXT-9");
        assertThat(chained.getSolution()).isNotNull();
        assertThat(chained.getSolution().getOptions()).hasSize(2);
        assertThat(chained.getVerificationResult()).isSameAs(verification);
        assertThat(chained.getVerificationResult().isPassed()).isTrue();
        assertThat(chained.getKnowledgeEntryId()).isEqualTo("kb-1");
        assertThat(chained.getUpdatedAt()).isEqualTo(UPDATED_AT + 4L);
        // Original base unchanged
        assertThat(base.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(base.getExternalIssueId()).isNull();
        assertThat(base.getVerificationResult()).isNull();
        assertThat(base.getKnowledgeEntryId()).isNull();
        // Solution preserved through chain
        assertThat(chained.getSolution().getOptions().get(0).getId()).isEqualTo("opt-1");
        assertThat(chained.getSolution().getOptions().get(1).getId()).isEqualTo("opt-2");
    }

    @Test
    void solutionSuggestionShouldBeSharedAcrossWithInstances() {
        // SolutionSuggestion is immutable, so derived instances may share the
        // same reference. Verify that the suggestion's data is preserved across
        // a chain of with* calls.
        IssueClosure base = newSample();

        IssueClosure updated = base.withStatus(
                IssueStatus.SOLUTION_PROPOSED, UPDATED_AT);

        assertThat(updated.getSolution()).isNotNull();
        assertThat(updated.getSolution().getOptions()).hasSize(2);
        assertThat(updated.getSolution().getOptions().get(0).getId()).isEqualTo("opt-1");
    }

    @Test
    void enumShouldHaveExpectedStatusesInOrder() {
        IssueStatus[] values = IssueStatus.values();
        assertThat(values).containsExactly(
                IssueStatus.DIAGNOSED,
                IssueStatus.SOLUTION_PROPOSED,
                IssueStatus.ISSUE_CREATED,
                IssueStatus.FIX_IN_PROGRESS,
                IssueStatus.VERIFIED,
                IssueStatus.CLOSED,
                IssueStatus.FAILED);
    }

    @Test
    void enumValueOfShouldResolveByName() {
        assertThat(IssueStatus.valueOf("DIAGNOSED")).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(IssueStatus.valueOf("CLOSED")).isEqualTo(IssueStatus.CLOSED);
        assertThat(IssueStatus.valueOf("FIX_IN_PROGRESS")).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        assertThat(IssueStatus.valueOf("ISSUE_CREATED")).isEqualTo(IssueStatus.ISSUE_CREATED);
        assertThat(IssueStatus.valueOf("FAILED")).isEqualTo(IssueStatus.FAILED);
    }
}
