package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStoreTest {

    private AgentTask newTask(String id, String userId) {
        return new AgentTask(id, userId, "skill1", null, null);
    }

    // ---- Basic CRUD ----

    @Test
    void shouldSaveAndGetTask() {
        TaskStore store = new TaskStore();
        AgentTask task = newTask("t1", "u1");
        store.save(task);

        assertThat(store.get("t1")).isSameAs(task);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldReturnNullForMissingTask() {
        TaskStore store = new TaskStore();
        assertThat(store.get("nonexistent")).isNull();
    }

    @Test
    void shouldOverwriteTaskWithSameId() {
        TaskStore store = new TaskStore();
        AgentTask t1 = newTask("t1", "u1");
        AgentTask t2 = newTask("t1", "u2");
        store.save(t1);
        store.save(t2);

        assertThat(store.get("t1")).isSameAs(t2);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldRemoveTask() {
        TaskStore store = new TaskStore();
        store.save(newTask("t1", "u1"));
        store.remove("t1");

        assertThat(store.get("t1")).isNull();
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void shouldIgnoreNullTask() {
        TaskStore store = new TaskStore();
        store.save(null);
        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void shouldReturnAllTasks() {
        TaskStore store = new TaskStore();
        store.save(newTask("t1", "u1"));
        store.save(newTask("t2", "u1"));

        assertThat(store.all()).hasSize(2);
    }

    @Test
    void shouldClearAllTasks() {
        TaskStore store = new TaskStore();
        store.save(newTask("t1", "u1"));
        store.save(newTask("t2", "u1"));
        store.clear();

        assertThat(store.size()).isEqualTo(0);
    }

    // ---- Query ----

    @Test
    void shouldQueryByUser() {
        TaskStore store = new TaskStore();
        store.save(newTask("t1", "u1"));
        store.save(newTask("t2", "u2"));
        store.save(newTask("t3", "u1"));

        assertThat(store.query("u1", null, null, 10, 0)).hasSize(2);
        assertThat(store.query("u2", null, null, 10, 0)).hasSize(1);
    }

    @Test
    void shouldQueryBySkill() {
        TaskStore store = new TaskStore();
        store.save(new AgentTask("t1", "u1", "s1", null, null));
        store.save(new AgentTask("t2", "u1", "s2", null, null));

        assertThat(store.query("u1", "s1", null, 10, 0)).hasSize(1);
    }

    // ---- Eviction: maxSize ----

    @Test
    void shouldEvictTerminalTasksWhenOverMaxSize() {
        TaskStore store = new TaskStore(3, Long.MAX_VALUE);

        AgentTask t1 = newTask("t1", "u1");
        t1.setStatus(TaskStatus.SUCCEEDED);
        AgentTask t2 = newTask("t2", "u1");
        t2.setStatus(TaskStatus.FAILED);
        AgentTask t3 = newTask("t3", "u1");
        t3.setStatus(TaskStatus.CANCELLED);

        store.save(t1);
        store.save(t2);
        store.save(t3);
        assertThat(store.size()).isEqualTo(3);

        // Adding 4th triggers eviction; t1 (oldest terminal) should go
        AgentTask t4 = newTask("t4", "u1");
        t4.setStatus(TaskStatus.RUNNING);
        store.save(t4);

        assertThat(store.size()).isLessThanOrEqualTo(3);
        assertThat(store.get("t1")).isNull();
        assertThat(store.get("t4")).isNotNull();
    }

    @Test
    void shouldPreferEvictingTerminalOverRunning() {
        TaskStore store = new TaskStore(2, Long.MAX_VALUE);

        AgentTask running = newTask("running", "u1");
        running.setStatus(TaskStatus.RUNNING);
        AgentTask terminal = newTask("terminal", "u1");
        terminal.setStatus(TaskStatus.SUCCEEDED);

        store.save(running);
        store.save(terminal);

        // Adding 3rd triggers eviction
        store.save(newTask("t3", "u1"));

        assertThat(store.size()).isLessThanOrEqualTo(2);
        assertThat(store.get("terminal")).isNull();
        assertThat(store.get("running")).isNotNull();
    }

    // ---- Eviction: TTL ----

    @Test
    void shouldEvictExpiredTasks() {
        TaskStore store = new TaskStore(100, 1); // TTL=1ms

        store.save(newTask("t1", "u1"));
        assertThat(store.size()).isEqualTo(1);

        try { Thread.sleep(10); } catch (InterruptedException e) { /* ignore */ }

        // Trigger eviction via save
        store.save(newTask("t2", "u1"));

        assertThat(store.get("t1")).isNull();
        assertThat(store.get("t2")).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldNotEvictNonExpiredTasks() {
        TaskStore store = new TaskStore(100, 60000); // 60s TTL

        store.save(newTask("t1", "u1"));
        assertThat(store.query("u1", null, null, 10, 0)).hasSize(1);
    }

    // ---- Default constructor ----

    @Test
    void shouldAcceptManyTasksWithDefaults() {
        TaskStore store = new TaskStore();
        for (int i = 0; i < 100; i++) {
            store.save(newTask("t" + i, "u1"));
        }
        assertThat(store.size()).isEqualTo(100);
    }

    // ---- Count methods ----

    @Test
    void shouldCountByUserAndStatus() {
        TaskStore store = new TaskStore();
        AgentTask t1 = newTask("t1", "u1");
        t1.setStatus(TaskStatus.SUCCEEDED);
        AgentTask t2 = newTask("t2", "u1");
        t2.setStatus(TaskStatus.RUNNING);
        AgentTask t3 = newTask("t3", "u2");
        t3.setStatus(TaskStatus.SUCCEEDED);

        store.save(t1);
        store.save(t2);
        store.save(t3);

        assertThat(store.countByUserAndStatus("u1", TaskStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.countByUserAndStatus("u1", TaskStatus.RUNNING)).isEqualTo(1);
    }

    @Test
    void shouldCountByUser() {
        TaskStore store = new TaskStore();
        store.save(newTask("t1", "u1"));
        store.save(newTask("t2", "u1"));
        store.save(newTask("t3", "u2"));

        assertThat(store.countByUser("u1")).isEqualTo(2);
        assertThat(store.countByUser("u2")).isEqualTo(1);
    }
}
