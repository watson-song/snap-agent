package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for tool execution.
 * <p>Phase 2: added getDescription() and getJsonSchema() defaults for LLM tool definitions.</p>
 */
public interface ToolCallback {
    ToolResult execute(Map<String, Object> input, Object context);
    String getName();
    default String getDescription() { return ""; }
    default String getJsonSchema() { return "{}"; }
    default boolean isReturnDirect() { return false; }
    default boolean isSystem() { return false; }
    default boolean isApprovalRequired() { return false; }
    /**
     * Maximum execution time in seconds. 0 = no timeout.
     * ToolsNode wraps execution in a CompletableFuture when > 0.
     */
    default long getTimeoutSeconds() { return 30; }
}
