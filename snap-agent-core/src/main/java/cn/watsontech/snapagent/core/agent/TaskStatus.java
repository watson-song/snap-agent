package cn.watsontech.snapagent.core.agent;

/**
 * Lifecycle status of an {@link AgentTask}.
 *
 * <pre>
 * PENDING ──(thread pool accepts)──> RUNNING ──┬──> SUCCEEDED
 *                                              ├──> FAILED
 *                                              ├──> TIMEOUT
 *                                              └──> CANCELLED
 * </pre>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    CANCELLED
}
