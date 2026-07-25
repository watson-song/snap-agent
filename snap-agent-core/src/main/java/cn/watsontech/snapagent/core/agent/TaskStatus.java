package cn.watsontech.snapagent.core.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Task lifecycle state machine.
 * PENDING → RUNNING → {SUCCEEDED | FAILED | TIMEOUT | CANCELLED | PAUSED}
 * PAUSED → RUNNING (resume)
 * Terminal states (SUCCEEDED/FAILED/TIMEOUT/CANCELLED) are irreversible.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    CANCELLED,
    PAUSED;

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, TIMEOUT, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(TaskStatus target) {
        if (this.isTerminal()) return false;
        if (this == PENDING && target == RUNNING) return true;
        if (this == RUNNING && (target == SUCCEEDED || target == FAILED || target == TIMEOUT || target == CANCELLED || target == PAUSED)) return true;
        if (this == PAUSED && target == RUNNING) return true;
        return false;
    }

    public TaskStatus transitionTo(TaskStatus target) {
        if (this.isTerminal()) {
            throw new IllegalStateException("cannot transition from terminal state: " + this);
        }
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition from " + this + " to " + target);
        }
        return target;
    }
}
