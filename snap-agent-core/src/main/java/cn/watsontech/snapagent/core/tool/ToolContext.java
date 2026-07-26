package cn.watsontech.snapagent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context object passed to {@link ToolCallback#execute} and
 * {@link ToolProvider#execute} carrying request-scoped info.
 *
 * <p>Immutable — all fields are final. The 2.x constructors ({@code (taskId, userId)}
 * and {@code (taskId, userId, pluginOverrides)}) are the primary path. The
 * 1.x-compatible constructors that take {@link AuditCallback} are retained for
 * starter module tool providers that have not yet been migrated to 2.x.</p>
 */
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;
    private final Map<String, String> pluginOverrides;
    private final Object pluginContext;

    /** 2.x primary constructor. */
    public ToolContext(String taskId, String userId) {
        this(taskId, userId, null, Collections.<String, String>emptyMap(), null);
    }

    /** 2.x constructor with plugin overrides. */
    public ToolContext(String taskId, String userId, Map<String, String> pluginOverrides) {
        this(taskId, userId, null, pluginOverrides, null);
    }

    /** 1.x-compatible constructor with audit callback. */
    public ToolContext(String taskId, String userId, AuditCallback auditCallback) {
        this(taskId, userId, auditCallback, Collections.<String, String>emptyMap(), null);
    }

    /** 1.x-compatible constructor with audit callback and plugin overrides. */
    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides) {
        this(taskId, userId, auditCallback, pluginOverrides, null);
    }

    /** Full constructor. */
    public ToolContext(String taskId, String userId, AuditCallback auditCallback,
                       Map<String, String> pluginOverrides, Object pluginContext) {
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
    public Object getPluginContext() { return pluginContext; }

    /**
     * Returns a new ToolContext with the same fields except pluginContext
     * is replaced with the given value.
     */
    public ToolContext withPluginContext(Object pluginContext) {
        return new ToolContext(taskId, userId, auditCallback, pluginOverrides, pluginContext);
    }
}
