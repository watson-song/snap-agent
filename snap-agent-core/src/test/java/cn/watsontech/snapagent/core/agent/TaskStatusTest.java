package cn.watsontech.snapagent.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TaskStatus 状态机")
class TaskStatusTest {

    @Test
    @DisplayName("初始 PENDING")
    void initialPending() {
        TaskStatus status = TaskStatus.PENDING;
        assertThat(status).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    @DisplayName("PENDING → RUNNING")
    void pendingToRunning() {
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.RUNNING)).isTrue();
    }

    @Test
    @DisplayName("RUNNING → SUCCEEDED")
    void runningToSucceeded() {
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("RUNNING → PAUSED")
    void runningToPaused() {
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PAUSED)).isTrue();
    }

    @Test
    @DisplayName("PAUSED → RUNNING (resume)")
    void pausedToRunning() {
        assertThat(TaskStatus.PAUSED.canTransitionTo(TaskStatus.RUNNING)).isTrue();
    }

    @Test
    @DisplayName("终态不可逆 — FAILED → RUNNING 抛异常")
    void terminalIsIrreversible() {
        assertThatThrownBy(() -> TaskStatus.FAILED.transitionTo(TaskStatus.RUNNING))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot transition from terminal state: FAILED");
    }

    @Test
    @DisplayName("所有终态均不可逆")
    void allTerminalsIrreversible() {
        for (TaskStatus terminal : new TaskStatus[]{TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.TIMEOUT, TaskStatus.CANCELLED}) {
            assertThatThrownBy(() -> terminal.transitionTo(TaskStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot transition from terminal state");
        }
    }
}
