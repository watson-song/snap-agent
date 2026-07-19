package cn.watsontech.snapagent.core.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link AgentTask} instances, backed by a
 * {@link ConcurrentHashMap}. Process-level; restart loses all state.
 */
public class TaskStore {

    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<String, AgentTask>();

    /** Saves (puts) a task; replaces if same id exists. */
    public void save(AgentTask task) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        tasks.put(task.getTaskId(), task);
    }

    /** Returns the task with the given id, or {@code null}. */
    public AgentTask get(String taskId) {
        if (taskId == null) {
            return null;
        }
        return tasks.get(taskId);
    }

    /** Updates an existing task (same as save for in-memory store). */
    public void update(AgentTask task) {
        save(task);
    }

    /** Removes a task. */
    public void remove(String taskId) {
        if (taskId != null) {
            tasks.remove(taskId);
        }
    }

    /** Returns all tasks (defensive copy). */
    public Collection<AgentTask> all() {
        return Collections.unmodifiableCollection(new ArrayList<AgentTask>(tasks.values()));
    }

    /** Returns the count of tasks with the given status for a specific user. */
    public int countByUserAndStatus(String userId, TaskStatus status) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (userId.equals(task.getUserId()) && task.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /** Clears all tasks (for testing). */
    public void clear() {
        tasks.clear();
    }

    /**
     * Query tasks with optional filters and pagination.
     * Results are sorted by createdAt descending (newest first).
     *
     * @param userId  required user filter (must not be null)
     * @param skillId optional skill filter, null = all skills
     * @param status  optional status filter, null = all statuses
     * @param limit   max results per page
     * @param offset  zero-based offset
     * @return list of matching tasks, sorted newest first
     */
    public List<AgentTask> query(String userId, String skillId, TaskStatus status, int limit, int offset) {
        List<AgentTask> matched = new ArrayList<AgentTask>();
        for (AgentTask task : tasks.values()) {
            if (!userId.equals(task.getUserId())) continue;
            if (skillId != null && !skillId.equals(task.getSkillId())) continue;
            if (status != null && task.getStatus() != status) continue;
            matched.add(task);
        }
        matched.sort((a, b) -> {
            int cmp = Long.compare(b.getCreatedAt(), a.getCreatedAt());
            if (cmp != 0) return cmp;
            return b.getTaskId().compareTo(a.getTaskId());
        });
        if (offset >= matched.size()) return Collections.emptyList();
        int end = Math.min(offset + limit, matched.size());
        return new ArrayList<AgentTask>(matched.subList(offset, end));
    }

    /** Count total tasks for a user (all skills, all statuses). */
    public int countByUser(String userId) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (userId.equals(task.getUserId())) count++;
        }
        return count;
    }

    /**
     * Count tasks matching the given filters (same semantics as {@link #query}).
     *
     * @param userId  required user filter (must not be null)
     * @param skillId optional skill filter, null = all skills
     * @param status  optional status filter, null = all statuses
     * @return count of matching tasks
     */
    public int count(String userId, String skillId, TaskStatus status) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (!userId.equals(task.getUserId())) continue;
            if (skillId != null && !skillId.equals(task.getSkillId())) continue;
            if (status != null && task.getStatus() != status) continue;
            count++;
        }
        return count;
    }
}
