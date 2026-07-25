package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for tool execution. Phase 1 stub — real implementation
 * (with @Tool/@ToolParam, ToolCallbacks.from() reflection factory)
 * comes in Phase 2 (Tool System).
 */
public interface ToolCallback {
    ToolResult execute(Map<String, Object> input, Object context);
    String getName();
    default boolean isReturnDirect() { return false; }
    default boolean isSystem() { return false; }
    default boolean isApprovalRequired() { return false; }
}
