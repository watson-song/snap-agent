package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context object passed to {@link ToolProvider#execute} carrying request-scoped
 * information: task id, user id, audit callback, plugin overrides, and plugin context.
 *
 * <p>Immutable — all fields are final. {@link #withPluginContext(PluginContext)}
 * returns a new instance with an updated plugin context for use by
 * {@link ToolDispatcher} when injecting the selected plugin's configuration.</p>
 */
public final class ToolContext {

    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;
    private final Map<String, String> pluginOverrides;
    private final PluginContext pluginContext;

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) {
        this(taskId, userId, auditCallback, Collections.<String, String>emptyMap(), null);
    }

    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides) {
        this(taskId, userId, auditCallback, pluginOverrides, null);
    }

    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides, PluginContext pluginContext) {
        this.taskId = taskId;
        this.userId = userId;
        this.auditCallback = auditCallback;
        this.pluginOverrides = pluginOverrides != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverrides))
                : Collections.<String, String>emptyMap();
        this.pluginContext = pluginContext;
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public AuditCallback getAuditCallback() { return auditCallback; }
    public Map<String, String> getPluginOverrides() { return pluginOverrides; }
    public PluginContext getPluginContext() { return pluginContext; }

    /**
     * Returns a new ToolContext with the same fields except pluginContext
     * is replaced with the given value.
     */
    public ToolContext withPluginContext(PluginContext pluginContext) {
        return new ToolContext(taskId, userId, auditCallback, pluginOverrides, pluginContext);
    }
}
