package com.watsontech.snapagent.core.agent;

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
}
