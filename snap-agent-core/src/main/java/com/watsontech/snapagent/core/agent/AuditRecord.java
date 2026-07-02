package com.watsontech.snapagent.core.agent;

import java.util.Collections;
import java.util.Map;

/**
 * Audit record for a single tool invocation.
 *
 * <p>Created by the agent layer after each {@code ToolDispatcher.dispatch} call.</p>
 */
public final class AuditRecord {

    private final String taskId;
    private final String userId;
    private final String toolName;
    private final Map<String, Object> args;
    private final int rowCount;
    private final boolean truncated;
    private final long timestamp;
    private final long durationMs;

    public AuditRecord(String taskId, String userId, String toolName,
                       Map<String, Object> args, int rowCount, boolean truncated,
                       long timestamp, long durationMs) {
        this.taskId = taskId;
        this.userId = userId;
        this.toolName = toolName;
        this.args = args == null ? Collections.<String, Object>emptyMap() : args;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
    public int getRowCount() { return rowCount; }
    public boolean isTruncated() { return truncated; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }

    @Override
    public String toString() {
        return "AuditRecord{taskId='" + taskId + "', toolName='" + toolName
                + "', rowCount=" + rowCount + ", durationMs=" + durationMs + "}";
    }
}
