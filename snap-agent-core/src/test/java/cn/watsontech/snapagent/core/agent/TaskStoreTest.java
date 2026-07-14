package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
}
