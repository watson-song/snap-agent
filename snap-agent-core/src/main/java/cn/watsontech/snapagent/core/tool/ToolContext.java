package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context object passed to ToolCallback.execute carrying request-scoped info.
 * Immutable — all fields are final.
 */
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final Map<String, String> pluginOverrides;

    public ToolContext(String taskId, String userId) {
        this(taskId, userId, null);
    }

    public ToolContext(String taskId, String userId, Map<String, String> pluginOverrides) {
        this.taskId = taskId;
        this.userId = userId;
        this.pluginOverrides = pluginOverrides != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverrides))
                : Collections.<String, String>emptyMap();
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public Map<String, String> getPluginOverrides() { return pluginOverrides; }
}
