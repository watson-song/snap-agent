package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IssueClosure}.
 */
class IssueClosureTest {

    private static final long CREATED_AT = 1_700_000_000_000L;
    private static final long UPDATED_AT = 1_700_000_001_000L;

    private IssueClosure newSample() {
        List<String> solutions = new ArrayList<>(Arrays.asList(
                "方案1: 重启服务", "方案2: 扩容连接池"));
        return new IssueClosure(
                "issue-001", null, "task-100",
                "conv-100", "为什么订单服务超时?", "连接池打满",
                solutions, null,
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
        assertThat(issue.getUserQuery()).isEqualTo("为什么订单服务超时?");
        assertThat(issue.getRootCause()).isEqualTo("连接池打满");
        assertThat(issue.getSolutions()).containsExactly(
                "方案1: 重启服务", "方案2: 扩容连接池");
        assertThat(issue.getSelectedSolution()).isNull();
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(issue.getFixCommitId()).isNull();
        assertThat(issue.getVerificationResult()).isNull();
        assertThat(issue.getKnowledgeEntryId()).isNull();
        assertThat(issue.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(issue.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void shouldDefensivelyCopySolutionsOnConstruction() {
        List<String> original = new ArrayList<>();
        original.add("方案1");
        original.add("方案2");

        IssueClosure issue = new IssueClosure(
                "id", null, "task", null, "q", "rc",
                original, null, IssueStatus.DIAGNOSED, null, null, null,
                CREATED_AT, UPDATED_AT);

        // Mutate the original list — issue's copy should be unaffected
        original.add("方案3");
        original.set(0, "hacked");

        assertThat(issue.getSolutions()).doesNotContain("方案3");
        assertThat(issue.getSolutions().get(0)).isEqualTo("方案1");
        assertThat(issue.getSolutions()).hasSize(2);
    }

    @Test
    void shouldReturnUnmodifiableSolutionsFromGetter() {
        IssueClosure issue = newSample();

        assertThatThrownBy(() -> issue.getSolutions().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> issue.getSolutions().set(0, "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveEmptySolutionsWhenNullPassed() {
        IssueClosure issue = new IssueClosure(
                "id", null, "task", null, "q", "rc",
                null, null, IssueStatus.DIAGNOSED, null, null, null,
                CREATED_AT, UPDATED_AT);

        assertThat(issue.getSolutions()).isNotNull();
        assertThat(issue.getSolutions()).isEmpty();
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
        assertThat(updated.getSolutions()).isEqualTo(original.getSolutions());
        assertThat(updated.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    void withExternalIssueShouldReturnNewInstanceWithExternalIdAndStatus() {
        IssueClosure original = newSample();
        long newUpdatedAt = UPDATED_AT + 10_000L;

        IssueClosure updated = original.withExternalIssue(
                "JIRA-1234", IssueStatus.FIX_IN_PROGRESS, newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getExternalIssueId()).isEqualTo("JIRA-1234");
        assertThat(updated.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
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
    void withVerificationShouldReturnNewInstanceWithVerificationResult() {
        IssueClosure original = newSample().withStatus(
                IssueStatus.FIX_IN_PROGRESS, UPDATED_AT);
        long newUpdatedAt = UPDATED_AT + 20_000L;

        IssueClosure updated = original.withVerification(
                "修复后连接池使用率正常, 超时消失", newUpdatedAt);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getVerificationResult()).isEqualTo("修复后连接池使用率正常, 超时消失");
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

        IssueClosure chained = base
                .withStatus(IssueStatus.SOLUTION_PROPOSED, UPDATED_AT + 1L)
                .withExternalIssue("EXT-9", IssueStatus.FIX_IN_PROGRESS, UPDATED_AT + 2L)
                .withVerification("verified ok", UPDATED_AT + 3L)
                .withKnowledgeEntry("kb-1", UPDATED_AT + 4L);

        assertThat(chained.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        assertThat(chained.getExternalIssueId()).isEqualTo("EXT-9");
        assertThat(chained.getVerificationResult()).isEqualTo("verified ok");
        assertThat(chained.getKnowledgeEntryId()).isEqualTo("kb-1");
        assertThat(chained.getUpdatedAt()).isEqualTo(UPDATED_AT + 4L);
        // Original base unchanged
        assertThat(base.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(base.getExternalIssueId()).isNull();
        assertThat(base.getVerificationResult()).isNull();
        assertThat(base.getKnowledgeEntryId()).isNull();
        // Solutions preserved through chain
        assertThat(chained.getSolutions()).containsExactly(
                "方案1: 重启服务", "方案2: 扩容连接池");
    }

    @Test
    void solutionsShouldBeIndependentAcrossWithInstances() {
        IssueClosure base = newSample();

        IssueClosure updated = base.withStatus(
                IssueStatus.SOLUTION_PROPOSED, UPDATED_AT);

        // Modifying the base instance's solutions list should not affect the
        // derived instance's solutions list (defensive copy on construction)
        base.getSolutions();
        // Both lists are unmodifiable, so we verify they hold independent data
        assertThat(updated.getSolutions()).containsExactly(
                "方案1: 重启服务", "方案2: 扩容连接池");
        assertThat(updated.getSolutions()).isNotSameAs(base.getSolutions());
    }

    @Test
    void enumShouldHaveExpectedStatusesInOrder() {
        IssueStatus[] values = IssueStatus.values();
        assertThat(values).containsExactly(
                IssueStatus.DIAGNOSED,
                IssueStatus.SOLUTION_PROPOSED,
                IssueStatus.FIX_IN_PROGRESS,
                IssueStatus.VERIFIED,
                IssueStatus.CLOSED);
    }

    @Test
    void enumValueOfShouldResolveByName() {
        assertThat(IssueStatus.valueOf("DIAGNOSED")).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(IssueStatus.valueOf("CLOSED")).isEqualTo(IssueStatus.CLOSED);
        assertThat(IssueStatus.valueOf("FIX_IN_PROGRESS")).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
    }
}
