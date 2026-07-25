package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registry for tool callbacks.
 * <p>Phase 2: added register/unregister/getAll/toToolDefinitionsJson/subset.</p>
 */
public interface ToolCallbackRegistry {
    ToolCallback find(String name);

    default void register(ToolCallback callback) { throw new UnsupportedOperationException("register not implemented"); }
    default void unregister(String toolName) { throw new UnsupportedOperationException("unregister not implemented"); }
    default List<ToolCallback> getAll() { return Collections.emptyList(); }
    default String toToolDefinitionsJson() { return "[]"; }
    default ToolCallbackRegistry subset(Map<String, String> pluginOverrides) { return this; }
}
