package cn.watsontech.snapagent.core.tool;

/**
 * Immutable result of a tool execution.
 *
 * <p>Java 8 compatible — no record, final class with explicit getters.</p>
 */
public final class ToolResult {

    private final String content;
    private final int rowCount;
    private final boolean truncated;
    private final long durationMs;
    private final String error;
    private final int originalLength;
    private final String toolUseId;

    public ToolResult(String content, int rowCount, boolean truncated, long durationMs, String error) {
        this(content, rowCount, truncated, durationMs, error, 0, null);
    }

    public ToolResult(String content, int rowCount, boolean truncated, long durationMs,
                      String error, int originalLength, String toolUseId) {
        this.content = content;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.durationMs = durationMs;
        this.error = error;
        this.originalLength = originalLength;
        this.toolUseId = toolUseId;
    }

    /** Successful non-truncated result. */
    public static ToolResult success(String content, int rowCount, long durationMs) {
        return new ToolResult(content, rowCount, false, durationMs, null, 0, null);
    }

    /** Successful result with toolUseId. */
    public static ToolResult success(String content, int rowCount, long durationMs, String toolUseId) {
        return new ToolResult(content, rowCount, false, durationMs, null, 0, toolUseId);
    }

    /** Successful but truncated result (content exceeds limits). */
    public static ToolResult truncated(String content, int rowCount, long durationMs) {
        return new ToolResult(content, rowCount, true, durationMs, null, content.length(), null);
    }

    /** Truncated result with explicit originalLength. */
    public static ToolResult truncated(String content, int rowCount, long durationMs, int originalLength) {
        return new ToolResult(content, rowCount, true, durationMs, null, originalLength, null);
    }

    /** Error result — content is null, error message is set. */
    public static ToolResult error(String message, long durationMs) {
        String msg = message != null ? message : "unknown error";
        return new ToolResult(null, 0, false, durationMs, msg, 0, null);
    }

    public String getContent() {
        return content;
    }

    public int getRowCount() {
        return rowCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getError() {
        return error;
    }

    public int getOriginalLength() {
        return originalLength;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    /** True when there is no error. */
    public boolean isSuccess() {
        return error == null;
    }

    /** True when an error message is present. */
    public boolean isError() {
        return error != null;
    }

    @Override
    public String toString() {
        return "ToolResult{content='" + content + "', rowCount=" + rowCount
                + ", truncated=" + truncated + ", durationMs=" + durationMs
                + ", error='" + error + "', originalLength=" + originalLength
                + ", toolUseId='" + toolUseId + "'}";
    }
}
