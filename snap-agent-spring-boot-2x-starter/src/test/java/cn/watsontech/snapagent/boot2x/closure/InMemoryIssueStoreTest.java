package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.IssueStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIssueStoreTest {

    @Test
    void createIssue_generatesId() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        issue.setRootCauseSummary("test root cause");

        String id = store.createIssue(issue);

        assertThat(id).isNotNull().startsWith("issue_");
        assertThat(issue.getId()).isEqualTo(id);
        assertThat(issue.getStatus()).isEqualTo(IssueStatus.DIAGNOSED);
        assertThat(issue.getCreatedAt()).isGreaterThan(0);
    }

    @Test
    void createIssue_preservesExistingId() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setId("custom-id-123");

        String id = store.createIssue(issue);

        assertThat(id).isEqualTo("custom-id-123");
    }

    @Test
    void getIssue_returnsById() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        String id = store.createIssue(issue);

        IssueClosure retrieved = store.getIssue(id);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getTaskId()).isEqualTo("task-1");
    }

    @Test
    void getIssue_unknownId_returnsNull() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        assertThat(store.getIssue("nonexistent")).isNull();
    }

    @Test
    void updateStatus_changesStatus() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        String id = store.createIssue(issue);

        store.updateStatus(id, IssueStatus.SOLUTION_PROPOSED);

        assertThat(store.getIssue(id).getStatus()).isEqualTo(IssueStatus.SOLUTION_PROPOSED);
    }

    @Test
    void listIssues_sortedByCreatedAtDescending() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue1 = new IssueClosure();
        issue1.setTaskId("task-1");
        issue1.setCreatedAt(1000);
        store.createIssue(issue1);

        IssueClosure issue2 = new IssueClosure();
        issue2.setTaskId("task-2");
        issue2.setCreatedAt(2000);
        store.createIssue(issue2);

        List<IssueClosure> list = store.listIssues(10, 0);
        assertThat(list).hasSize(2);
        // Newer first
        assertThat(list.get(0).getTaskId()).isEqualTo("task-2");
        assertThat(list.get(1).getTaskId()).isEqualTo("task-1");
    }

    @Test
    void listIssues_respectsLimitAndOffset() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        for (int i = 0; i < 5; i++) {
            IssueClosure issue = new IssueClosure();
            issue.setTaskId("task-" + i);
            issue.setCreatedAt(i * 1000);
            store.createIssue(issue);
        }

        List<IssueClosure> page1 = store.listIssues(2, 0);
        assertThat(page1).hasSize(2);

        List<IssueClosure> page2 = store.listIssues(2, 2);
        assertThat(page2).hasSize(2);

        List<IssueClosure> page3 = store.listIssues(2, 4);
        assertThat(page3).hasSize(1);
    }

    @Test
    void save_updatesExistingIssue() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setTaskId("task-1");
        String id = store.createIssue(issue);

        IssueClosure saved = store.getIssue(id);
        saved.setRootCauseSummary("updated root cause");
        store.save(saved);

        assertThat(store.getIssue(id).getRootCauseSummary()).isEqualTo("updated root cause");
    }

    @Test
    void save_withNewId_createsIssue() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        IssueClosure issue = new IssueClosure();
        issue.setId("new-id");
        issue.setTaskId("task-1");
        store.save(issue);

        assertThat(store.getIssue("new-id")).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void size_returnsCount() {
        InMemoryIssueStore store = new InMemoryIssueStore();
        assertThat(store.size()).isZero();
        store.createIssue(new IssueClosure());
        store.createIssue(new IssueClosure());
        assertThat(store.size()).isEqualTo(2);
    }
}
