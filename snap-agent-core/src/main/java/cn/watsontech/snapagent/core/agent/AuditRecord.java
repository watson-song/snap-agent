package cn.watsontech.snapagent.core.agent;

import java.util.Collections;
import java.util.Map;

/**
 * Audit record for a single LLM or tool invocation.
 *
 * <p>Created by AuditAdvisor after each node execution.</p>
 */
public final class AuditRecord {

    private final String taskId;
    private final String userId;
    private final String nodeName;
    private final String llmModel;
    private final int inputTokens;
    private final int outputTokens;
    private final String toolName;
    private final Map<String, Object> args;
    private final String result;
    private final int rowCount;
    private final boolean truncated;
    private final long timestamp;
    private final long durationMs;

    public AuditRecord(String taskId, String userId, String toolName,
                       Map<String, Object> args, int rowCount, boolean truncated,
                       long timestamp, long durationMs) {
        this(taskId, userId, null, null, 0, 0, toolName, args, null, rowCount, truncated, timestamp, durationMs);
    }

    public AuditRecord(String taskId, String userId, String nodeName, String llmModel,
                       int inputTokens, int outputTokens, String toolName,
                       Map<String, Object> args, String result,
                       int rowCount, boolean truncated, long timestamp, long durationMs) {
        this.taskId = taskId;
        this.userId = userId;
        this.nodeName = nodeName;
        this.llmModel = llmModel;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.toolName = toolName;
        this.args = args == null ? Collections.<String, Object>emptyMap() : args;
        this.result = result;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public String getNodeName() { return nodeName; }
    public String getLlmModel() { return llmModel; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
    public String getResult() { return result; }
    public int getRowCount() { return rowCount; }
    public boolean isTruncated() { return truncated; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }

    @Override
    public String toString() {
        return "AuditRecord{taskId='" + taskId + "', nodeName='" + nodeName
                + "', toolName='" + toolName + "', inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens + ", durationMs=" + durationMs + "}";
    }
}
