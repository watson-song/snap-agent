package cn.watsontech.snapagent.core.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link AgentTask} instances, backed by a
 * {@link ConcurrentHashMap}.
 *
 * <p>Eviction policy (lazy, triggered on {@link #save} and {@link #query}):</p>
 * <ol>
 *   <li>Remove tasks older than {@code ttlMillis}.</li>
 *   <li>If still over {@code maxSize}, remove terminal tasks first (oldest first),
 *       then any task (oldest first).</li>
 * </ol>
 *
 * <p>Default limits: maxSize=5000, ttl=24h. Configurable via
 * {@code snap-agent.agent.task-store-max-size} and
 * {@code snap-agent.agent.task-store-ttl-minutes}.</p>
 */
public class TaskStore {

    private static final int DEFAULT_MAX_SIZE = 5000;
    private static final long DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<String, AgentTask>();
    private final int maxSize;
    private final long ttlMillis;

    public TaskStore() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS);
    }

    public TaskStore(int maxSize, long ttlMillis) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
    }

    public void save(AgentTask task) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        tasks.put(task.getTaskId(), task);
        evictIfNeeded();
    }

    public AgentTask get(String taskId) {
        if (taskId == null) {
            return null;
        }
        return tasks.get(taskId);
    }

    public void update(AgentTask task) {
        save(task);
    }

    public void remove(String taskId) {
        if (taskId != null) {
            tasks.remove(taskId);
        }
    }

    public Collection<AgentTask> all() {
        return Collections.unmodifiableCollection(new ArrayList<AgentTask>(tasks.values()));
    }

    public int size() {
        return tasks.size();
    }

    public int countByUserAndStatus(String userId, TaskStatus status) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (userId.equals(task.getUserId()) && task.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    public void clear() {
        tasks.clear();
    }

    public List<AgentTask> query(String userId, String skillId, TaskStatus status, int limit, int offset) {
        evictIfNeeded();
        List<AgentTask> matched = new ArrayList<AgentTask>();
        for (AgentTask task : tasks.values()) {
            if (!userId.equals(task.getUserId())) continue;
            if (skillId != null && !skillId.equals(task.getSkillId())) continue;
            if (status != null && task.getStatus() == status) continue;
            matched.add(task);
        }
        matched.sort(new Comparator<AgentTask>() {
            @Override
            public int compare(AgentTask a, AgentTask b) {
                int cmp = Long.compare(b.getCreatedAt(), a.getCreatedAt());
                if (cmp != 0) return cmp;
                return b.getTaskId().compareTo(a.getTaskId());
            }
        });
        if (offset >= matched.size()) return Collections.emptyList();
        int end = Math.min(offset + limit, matched.size());
        return new ArrayList<AgentTask>(matched.subList(offset, end));
    }

    public int countByUser(String userId) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (userId.equals(task.getUserId())) count++;
        }
        return count;
    }

    public int count(String userId, String skillId, TaskStatus status) {
        int count = 0;
        for (AgentTask task : tasks.values()) {
            if (!userId.equals(task.getUserId())) continue;
            if (skillId != null && !skillId.equals(task.getSkillId())) continue;
            if (status != null && task.getStatus() == status) continue;
            count++;
        }
        return count;
    }

    // ---- Eviction ----

    private void evictIfNeeded() {
        long now = System.currentTimeMillis();

        // Phase 1: remove expired tasks
        tasks.entrySet().removeIf(new java.util.function.Predicate<java.util.Map.Entry<String, AgentTask>>() {
            @Override
            public boolean test(java.util.Map.Entry<String, AgentTask> e) {
                return (now - e.getValue().getCreatedAt()) > ttlMillis;
            }
        });

        if (tasks.size() <= maxSize) return;

        // Phase 2: remove terminal tasks first (oldest first)
        List<AgentTask> terminal = new ArrayList<AgentTask>();
        for (AgentTask task : tasks.values()) {
            if (task.getStatus().isTerminal()) {
                terminal.add(task);
            }
        }
        terminal.sort(new Comparator<AgentTask>() {
            @Override
            public int compare(AgentTask a, AgentTask b) {
                return Long.compare(a.getCreatedAt(), b.getCreatedAt());
            }
        });

        int toRemove = tasks.size() - maxSize;
        for (int i = 0; i < toRemove && i < terminal.size(); i++) {
            tasks.remove(terminal.get(i).getTaskId());
        }

        if (tasks.size() <= maxSize) return;

        // Phase 3: remove any task (oldest first), including running
        List<AgentTask> remaining = new ArrayList<AgentTask>(tasks.values());
        remaining.sort(new Comparator<AgentTask>() {
            @Override
            public int compare(AgentTask a, AgentTask b) {
                return Long.compare(a.getCreatedAt(), b.getCreatedAt());
            }
        });
        int stillToRemove = tasks.size() - maxSize;
        for (int i = 0; i < stillToRemove && i < remaining.size(); i++) {
            tasks.remove(remaining.get(i).getTaskId());
        }
    }
}
// test
