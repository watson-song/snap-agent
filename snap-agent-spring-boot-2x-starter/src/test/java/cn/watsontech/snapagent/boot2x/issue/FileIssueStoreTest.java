package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileIssueStore}.
 */
class FileIssueStoreTest {

    @TempDir
    Path tempDir;

    private IssueStore store;

    @BeforeEach
    void setUp() {
        store = new FileIssueStore(tempDir.toString());
    }

    private IssueClosure newSample(String issueId, String taskId, IssueStatus status,
                                   long createdAt, long updatedAt) {
        return new IssueClosure(
                issueId, null, taskId,
                "conv-100", "为什么订单服务超时?", "连接池打满",
                sampleSuggestion(), null,
                status, null,
                null, null,
                createdAt, updatedAt);
    }

    private SolutionSuggestion sampleSuggestion() {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        options.add(new SolutionOption("opt-1", "方案1: 重启服务", "方案1: 重启服务", "medium", false));
        options.add(new SolutionOption("opt-2", "方案2: 扩容连接池", "方案2: 扩容连接池", "medium", false));
        return new SolutionSuggestion(options, "opt-1", null, null);
    }

    private SolutionSuggestion suggestionOf(String... titles) {
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        int index = 1;
        for (String title : titles) {
            options.add(new SolutionOption("opt-" + index, title, title, "medium", false));
            index++;
        }
        String recommended = options.isEmpty() ? null : "opt-1";
        return new SolutionSuggestion(options, recommended, null, null);
    }

    private VerificationResult verificationOf(boolean passed, String summary) {
        return new VerificationResult(passed, summary, "FIX_IN_PROGRESS",
                passed ? "SUCCEEDED" : "FAILED", 1_500L);
    }

    @Test
    void shouldSaveAndLoadIssue() {
        IssueClosure issue = newSample("issue-001", "task-100",
                IssueStatus.DIAGNOSED, 1_700_000_000_000L, 1_700_000_001_000L);

        store.save(issue);

        IssueClosure loaded = store.load("issue-001");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getIssueId()).isEqualTo("issue-001");
        assertThat(loaded.getTaskId()).isEqualTo("task-100");
        assertThat(loaded.getUserQuery()).isEqualTo("为什么订单服务超时?");
        assertThat(loaded.getRootCause()).isEqualTo("连接池打满");
        assertThat(loaded.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(loaded.getSolution()).isNotNull();
        assertThat(loaded.getSolution().getOptions()).hasSize(2);
        assertThat(loaded.getSolution().getOptions().get(0).getTitle()).isEqualTo("方案1: 重启服务");
        assertThat(loaded.getSolution().getRecommendedOptionId()).isEqualTo("opt-1");
        assertThat(loaded.getCreatedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(loaded.getUpdatedAt()).isEqualTo(1_700_000_001_000L);
    }

    @Test
    void shouldReturnNullForNonexistentIssue() {
        IssueClosure loaded = store.load("nonexistent");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldOverwriteOnResave() {
        IssueClosure issue = newSample("issue-002", "task-200",
                IssueStatus.DIAGNOSED, 1_000L, 2_000L);
        store.save(issue);

        IssueClosure updated = new IssueClosure(
                "issue-002", "EXT-1", "task-200",
                null, "updated query", "updated root cause",
                suggestionOf("new solution"), "selected",
                IssueStatus.FIX_IN_PROGRESS, "commit-abc",
                verificationOf(true, "verified ok"), "kb-1",
                1_000L, 3_000L);
        store.save(updated);

        IssueClosure loaded = store.load("issue-002");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getExternalIssueId()).isEqualTo("EXT-1");
        assertThat(loaded.getStatus()).isEqualTo(IssueStatus.FIX_IN_PROGRESS);
        assertThat(loaded.getUserQuery()).isEqualTo("updated query");
        assertThat(loaded.getRootCause()).isEqualTo("updated root cause");
        assertThat(loaded.getSelectedSolution()).isEqualTo("selected");
        assertThat(loaded.getFixCommitId()).isEqualTo("commit-abc");
        assertThat(loaded.getVerificationResult()).isNotNull();
        assertThat(loaded.getVerificationResult().isPassed()).isTrue();
        assertThat(loaded.getVerificationResult().getSummary()).isEqualTo("verified ok");
        assertThat(loaded.getKnowledgeEntryId()).isEqualTo("kb-1");
        assertThat(loaded.getUpdatedAt()).isEqualTo(3_000L);
        assertThat(loaded.getCreatedAt()).isEqualTo(1_000L);
    }

    @Test
    void shouldFindByTaskId() {
        IssueClosure issue1 = newSample("issue-a", "task-A",
                IssueStatus.DIAGNOSED, 1_000L, 2_000L);
        IssueClosure issue2 = newSample("issue-b", "task-B",
                IssueStatus.SOLUTION_PROPOSED, 3_000L, 4_000L);
        store.save(issue1);
        store.save(issue2);

        IssueClosure found = store.findByTaskId("task-A");
        assertThat(found).isNotNull();
        assertThat(found.getIssueId()).isEqualTo("issue-a");
        assertThat(found.getTaskId()).isEqualTo("task-A");
    }

    @Test
    void shouldReturnNullForFindByTaskIdWhenNoMatch() {
        store.save(newSample("issue-x", "task-X",
                IssueStatus.DIAGNOSED, 1_000L, 2_000L));

        IssueClosure found = store.findByTaskId("nonexistent-task");
        assertThat(found).isNull();
    }

    @Test
    void shouldListAllIssuesSortedByUpdatedAtDescending() {
        store.save(newSample("issue-old", "task-1",
                IssueStatus.DIAGNOSED, 1_000L, 1_000L));
        store.save(newSample("issue-new", "task-2",
                IssueStatus.DIAGNOSED, 2_000L, 5_000L));
        store.save(newSample("issue-mid", "task-3",
                IssueStatus.DIAGNOSED, 3_000L, 3_000L));

        List<IssueClosure> list = store.list();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).getIssueId()).isEqualTo("issue-new");
        assertThat(list.get(1).getIssueId()).isEqualTo("issue-mid");
        assertThat(list.get(2).getIssueId()).isEqualTo("issue-old");
    }

    @Test
    void shouldReturnEmptyListWhenNoIssues() {
        List<IssueClosure> list = store.list();
        assertThat(list).isEmpty();
    }

    @Test
    void shouldListByStatus() {
        store.save(newSample("issue-1", "task-1",
                IssueStatus.DIAGNOSED, 1_000L, 1_000L));
        store.save(newSample("issue-2", "task-2",
                IssueStatus.SOLUTION_PROPOSED, 2_000L, 2_000L));
        store.save(newSample("issue-3", "task-3",
                IssueStatus.DIAGNOSED, 3_000L, 3_000L));
        store.save(newSample("issue-4", "task-4",
                IssueStatus.CLOSED, 4_000L, 4_000L));

        List<IssueClosure> diagnosed = store.listByStatus(IssueStatus.DIAGNOSED);
        assertThat(diagnosed).hasSize(2);
        for (IssueClosure issue : diagnosed) {
            assertThat(issue.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        }

        List<IssueClosure> closed = store.listByStatus(IssueStatus.CLOSED);
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getIssueId()).isEqualTo("issue-4");
    }

    @Test
    void shouldReturnEmptyListForStatusWithNoMatches() {
        store.save(newSample("issue-1", "task-1",
                IssueStatus.DIAGNOSED, 1_000L, 1_000L));

        List<IssueClosure> closed = store.listByStatus(IssueStatus.CLOSED);
        assertThat(closed).isEmpty();
    }

    @Test
    void shouldDeleteIssue() {
        store.save(newSample("issue-del", "task-del",
                IssueStatus.DIAGNOSED, 1_000L, 2_000L));

        store.delete("issue-del");

        IssueClosure loaded = store.load("issue-del");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldNoOpWhenDeletingNonexistent() {
        store.delete("nonexistent-issue");
        // Should not throw
        assertThat(store.list()).isEmpty();
    }

    @Test
    void shouldHandleNullSolutionsOnSaveAndLoad() {
        IssueClosure issue = new IssueClosure(
                "issue-null-sols", null, "task-null",
                null, "query", "root cause",
                null, null,
                IssueStatus.DIAGNOSED, null,
                null, null,
                1_000L, 2_000L);
        store.save(issue);

        IssueClosure loaded = store.load("issue-null-sols");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSolution()).isNull();
    }

    @Test
    void shouldPreserveAllFieldsWithExternalIssue() {
        IssueClosure issue = new IssueClosure(
                "issue-full", "JIRA-42", "task-full",
                "conv-42", "full query", "full root cause",
                suggestionOf("sol1", "sol2"), "sol1",
                IssueStatus.FIX_IN_PROGRESS, "commit-xyz",
                verificationOf(true, "verification summary"), "kb-entry-1",
                1_000L, 5_000L);
        store.save(issue);

        IssueClosure loaded = store.load("issue-full");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getExternalIssueId()).isEqualTo("JIRA-42");
        assertThat(loaded.getConversationId()).isEqualTo("conv-42");
        assertThat(loaded.getSelectedSolution()).isEqualTo("sol1");
        assertThat(loaded.getFixCommitId()).isEqualTo("commit-xyz");
        assertThat(loaded.getVerificationResult()).isNotNull();
        assertThat(loaded.getVerificationResult().getSummary()).isEqualTo("verification summary");
        assertThat(loaded.getVerificationResult().isPassed()).isTrue();
        assertThat(loaded.getKnowledgeEntryId()).isEqualTo("kb-entry-1");
        assertThat(loaded.getSolution()).isNotNull();
        assertThat(loaded.getSolution().getOptions()).hasSize(2);
        assertThat(loaded.getSolution().getOptions().get(0).getTitle()).isEqualTo("sol1");
        assertThat(loaded.getSolution().getOptions().get(1).getTitle()).isEqualTo("sol2");
    }

    @Test
    void shouldFindByTaskIdAfterMultipleSaves() {
        store.save(newSample("issue-1", "task-A",
                IssueStatus.DIAGNOSED, 1_000L, 1_000L));
        store.save(newSample("issue-2", "task-B",
                IssueStatus.DIAGNOSED, 2_000L, 2_000L));
        store.save(newSample("issue-3", "task-C",
                IssueStatus.DIAGNOSED, 3_000L, 3_000L));

        IssueClosure found = store.findByTaskId("task-B");
        assertThat(found).isNotNull();
        assertThat(found.getIssueId()).isEqualTo("issue-2");
    }
}
