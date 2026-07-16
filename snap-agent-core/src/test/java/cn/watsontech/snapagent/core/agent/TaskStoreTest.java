package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStoreTest {

    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore();
    }

    @Test
    void shouldSaveAndGetTask() {
        AgentTask task = AgentTask.create("u1", "skill-1", null, "m");

        store.save(task);

        assertThat(store.get(task.getTaskId())).isSameAs(task);
    }

    @Test
    void shouldReturnNullWhenTaskNotFound() {
        assertThat(store.get("nonexistent")).isNull();
    }

    @Test
    void shouldReturnNullWhenTaskIdNull() {
        assertThat(store.get(null)).isNull();
    }

    @Test
    void shouldNotSaveWhenTaskNullOrIdNull() {
        store.save(null);
        store.save(new AgentTask(null, "u", "s", null, "m"));

        assertThat(store.all()).isEmpty();
    }

    @Test
    void shouldUpdateTask() {
        AgentTask task = AgentTask.create("u", "s", null, "m");
        store.save(task);
        task.setStatus(TaskStatus.RUNNING);
        store.update(task);

        assertThat(store.get(task.getTaskId()).getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void shouldRemoveTask() {
        AgentTask task = AgentTask.create("u", "s", null, "m");
        store.save(task);

        store.remove(task.getTaskId());

        assertThat(store.get(task.getTaskId())).isNull();
    }

    @Test
    void shouldReturnAllTasks() {
        AgentTask t1 = AgentTask.create("u1", "s", null, "m");
        AgentTask t2 = AgentTask.create("u2", "s", null, "m");
        store.save(t1);
        store.save(t2);

        assertThat(store.all()).hasSize(2);
    }

    @Test
    void shouldCountByUserAndStatus() {
        AgentTask t1 = AgentTask.create("u1", "s", null, "m");
        t1.setStatus(TaskStatus.RUNNING);
        AgentTask t2 = AgentTask.create("u1", "s", null, "m");
        t2.setStatus(TaskStatus.RUNNING);
        AgentTask t3 = AgentTask.create("u2", "s", null, "m");
        t3.setStatus(TaskStatus.RUNNING);
        AgentTask t4 = AgentTask.create("u1", "s", null, "m");
        t4.setStatus(TaskStatus.SUCCEEDED);

        store.save(t1);
        store.save(t2);
        store.save(t3);
        store.save(t4);

        assertThat(store.countByUserAndStatus("u1", TaskStatus.RUNNING)).isEqualTo(2);
        assertThat(store.countByUserAndStatus("u1", TaskStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.countByUserAndStatus("u2", TaskStatus.RUNNING)).isEqualTo(1);
        assertThat(store.countByUserAndStatus("u1", TaskStatus.PENDING)).isEqualTo(0);
    }

    @Test
    void shouldClearAllTasks() {
        store.save(AgentTask.create("u", "s", null, "m"));
        store.save(AgentTask.create("u", "s", null, "m"));

        store.clear();

        assertThat(store.all()).isEmpty();
    }

    @Test
    void shouldQueryByUserIdWithPagination() {
        for (int i = 0; i < 15; i++) {
            AgentTask task = AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "model");
            store.save(task);
        }
        for (int i = 0; i < 5; i++) {
            AgentTask task = AgentTask.create("user2", "skill-a", new HashMap<String, String>(), "model");
            store.save(task);
        }

        List<AgentTask> page1 = store.query("user1", null, null, 10, 0);
        assertThat(page1).hasSize(10);

        List<AgentTask> page2 = store.query("user1", null, null, 10, 10);
        assertThat(page2).hasSize(5);
    }

    @Test
    void shouldFilterBySkillId() {
        store.save(AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "model"));
        store.save(AgentTask.create("user1", "skill-b", new HashMap<String, String>(), "model"));
        store.save(AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "model"));

        List<AgentTask> results = store.query("user1", "skill-a", null, 100, 0);
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(t -> "skill-a".equals(t.getSkillId()));
    }

    @Test
    void shouldFilterByStatus() {
        AgentTask t1 = AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "model");
        t1.setStatus(TaskStatus.SUCCEEDED);
        store.save(t1);
        AgentTask t2 = AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "model");
        t2.setStatus(TaskStatus.FAILED);
        store.save(t2);

        List<AgentTask> results = store.query("user1", null, TaskStatus.SUCCEEDED, 100, 0);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void shouldReturnResultsSortedByCreatedAtDesc() {
        AgentTask t1 = AgentTask.create("user1", "s", new HashMap<String, String>(), "m");
        store.save(t1);
        AgentTask t2 = AgentTask.create("user1", "s", new HashMap<String, String>(), "m");
        store.save(t2);

        List<AgentTask> results = store.query("user1", null, null, 100, 0);
        assertThat(results.get(0).getCreatedAt()).isGreaterThanOrEqualTo(results.get(results.size() - 1).getCreatedAt());
    }

    @Test
    void shouldReturnTotalCount() {
        for (int i = 0; i < 7; i++) {
            store.save(AgentTask.create("user1", "s", new HashMap<String, String>(), "m"));
        }

        int total = store.countByUser("user1");
        assertThat(total).isEqualTo(7);

        assertThat(store.countByUser("user2")).isEqualTo(0);
    }

    @Test
    void shouldCountWithFilters() {
        AgentTask t1 = AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "m");
        t1.setStatus(TaskStatus.SUCCEEDED);
        store.save(t1);
        AgentTask t2 = AgentTask.create("user1", "skill-a", new HashMap<String, String>(), "m");
        t2.setStatus(TaskStatus.FAILED);
        store.save(t2);
        AgentTask t3 = AgentTask.create("user1", "skill-b", new HashMap<String, String>(), "m");
        t3.setStatus(TaskStatus.SUCCEEDED);
        store.save(t3);

        assertThat(store.count("user1", "skill-a", null)).isEqualTo(2);
        assertThat(store.count("user1", null, TaskStatus.SUCCEEDED)).isEqualTo(2);
        assertThat(store.count("user1", "skill-a", TaskStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(store.count("user1", "skill-b", TaskStatus.FAILED)).isEqualTo(0);
    }
}
