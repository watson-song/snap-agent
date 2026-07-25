package cn.watsontech.snapagent.core.tool;

/**
 * Minimal stub for Phase 1 compilation. Real implementation (with ToolCallback
 * registration, lookup, etc.) comes in Phase 2 (Tool System).
 */
public interface ToolCallbackRegistry {
    ToolCallback find(String name);
}
