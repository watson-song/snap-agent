package cn.watsontech.snapagent.boot2x.closure;

import cn.watsontech.snapagent.core.closure.IssueClosure;
import cn.watsontech.snapagent.core.closure.IssueStatus;
import cn.watsontech.snapagent.core.closure.IssueTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link IssueTracker} implementation.
 *
 * <p>Stores issues in a {@link LinkedHashMap} keyed by ID. Issues are lost on
 * restart — for persistence, implement a custom {@code IssueTracker} backed
 * by a database.</p>
 */
public class InMemoryIssueStore implements IssueTracker {

    private final Map<String, IssueClosure> store = new LinkedHashMap<String, IssueClosure>();

    @Override
    public String createIssue(IssueClosure issue) {
        if (issue.getId() == null || issue.getId().isEmpty()) {
            issue.setId("issue_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        if (issue.getStatus() == null) {
            issue.setStatus(IssueStatus.DIAGNOSED);
        }
        if (issue.getCreatedAt() == 0) {
            issue.setCreatedAt(System.currentTimeMillis());
        }
        issue.setUpdatedAt(System.currentTimeMillis());
        store.put(issue.getId(), issue);
        return issue.getId();
    }

    @Override
    public IssueClosure getIssue(String issueId) {
        return store.get(issueId);
    }

    @Override
    public void updateStatus(String issueId, IssueStatus status) {
        IssueClosure issue = store.get(issueId);
        if (issue != null) {
            issue.setStatus(status);
        }
    }

    @Override
    public List<IssueClosure> listIssues(int limit, int offset) {
        List<IssueClosure> all = new ArrayList<IssueClosure>(store.values());
        Collections.sort(all, new Comparator<IssueClosure>() {
            @Override
            public int compare(IssueClosure a, IssueClosure b) {
                return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            }
        });
        int fromIndex = Math.min(offset, all.size());
        int toIndex = Math.min(fromIndex + limit, all.size());
        return all.subList(fromIndex, toIndex);
    }

    @Override
    public void save(IssueClosure issue) {
        if (issue.getId() == null || issue.getId().isEmpty()) {
            createIssue(issue);
        } else {
            issue.setUpdatedAt(System.currentTimeMillis());
            store.put(issue.getId(), issue);
        }
    }

    public int size() {
        return store.size();
    }
}
