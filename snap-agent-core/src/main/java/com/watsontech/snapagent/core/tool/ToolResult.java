package com.watsontech.snapagent.core.tool;

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

    public ToolResult(String content, int rowCount, boolean truncated, long durationMs, String error) {
        this.content = content;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.durationMs = durationMs;
        this.error = error;
    }

    /** Successful non-truncated result. */
    public static ToolResult success(String content, int rowCount, long durationMs) {
        return new ToolResult(content, rowCount, false, durationMs, null);
    }

    /** Successful but truncated result (content exceeds limits). */
    public static ToolResult truncated(String content, int rowCount, long durationMs) {
        return new ToolResult(content, rowCount, true, durationMs, null);
    }

    /** Error result — content is null, error message is set. */
    public static ToolResult error(String message, long durationMs) {
        String msg = message != null ? message : "unknown error";
        return new ToolResult(null, 0, false, durationMs, msg);
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
                + ", error='" + error + "'}";
    }
}
