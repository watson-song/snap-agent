package cn.watsontech.snapagent.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes {@code tool_use} calls to the matching plugin via {@link PluginRegistry}.
 *
 * <p>Compatibility class retained from 1.x for starter module code that has not
 * yet been migrated to the 2.x {@code GraphExecutor} pattern. The 2.x primary
 * path uses {@code ToolCallbackRegistry} directly.</p>
 */
public class ToolDispatcher implements ToolCallbackRegistry {

    private final PluginRegistry registry;
    private final int maxToolResultChars;

    public ToolDispatcher(PluginRegistry registry, int maxToolResultChars) {
        this.registry = registry != null ? registry : new InMemoryPluginRegistry();
        this.maxToolResultChars = maxToolResultChars;
    }

    /**
     * @deprecated Use {@link #ToolDispatcher(PluginRegistry, int)}.
     * Creates an internal registry and registers each provider as a system plugin.
     */
    @Deprecated
    public ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars) {
        InMemoryPluginRegistry reg = new InMemoryPluginRegistry();
        if (providerList != null) {
            for (ToolProvider p : providerList) {
                if (p != null && p.name() != null) {
                    ToolCallback[] callbacks = ToolCallbacks.from(p);
                    PluginDescriptor desc = new PluginDescriptor(
                            p.name(), p.name(), p.name(), "", "built-in",
                            true, true, true, callbacks, null, null, null);
                    reg.register(desc);
                }
            }
        }
        this.registry = reg;
        this.maxToolResultChars = maxToolResultChars;
    }

    public Set<String> availableToolTypes() {
        Set<String> types = new HashSet<>();
        for (PluginDescriptor p : registry.listPlugins()) {
            types.add(p.getToolType());
        }
        return types;
    }

    @Deprecated
    public Set<String> availableToolNames() {
        return availableToolTypes();
    }

    public Collection<PluginDescriptor> activePlugins(Map<String, String> overrides) {
        Map<String, PluginDescriptor> active = new LinkedHashMap<>();
        for (PluginDescriptor p : registry.listPlugins()) {
            if (!p.isEnabled() || !p.isDefault()) continue;
            active.put(p.getToolType(), p);
        }
        for (PluginDescriptor p : registry.listPlugins()) {
            if (!p.isEnabled()) continue;
            String toolType = p.getToolType();
            if (!active.containsKey(toolType)) {
                active.put(toolType, p);
            }
        }
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                PluginDescriptor p = registry.getPlugin(e.getValue());
                if (p != null && p.isEnabled()) {
                    active.put(e.getKey(), p);
                }
            }
        }
        return active.values();
    }

    public Collection<PluginDescriptor> activePlugins() {
        return activePlugins(null);
    }

    @Deprecated
    public Collection<ToolProvider> providers() {
        List<ToolProvider> result = new ArrayList<>();
        for (PluginDescriptor desc : activePlugins()) {
            ToolProvider tp = desc.getProvider();
            if (tp != null) result.add(tp);
        }
        return result;
    }

    public ToolResult dispatch(String toolType, Map<String, Object> args, ToolContext ctx) {
        if (toolType == null) {
            ToolResult err = ToolResult.error("no plugin registered for: null", 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        String override = ctx != null ? ctx.getPluginOverrides().get(toolType) : null;
        PluginDescriptor plugin = (override != null)
                ? registry.getPlugin(override)
                : findDefault(toolType);

        if (plugin == null) {
            ToolResult err = ToolResult.error("no plugin registered for: " + toolType, 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        if (!plugin.isEnabled()) {
            ToolResult err = ToolResult.error("plugin disabled: " + plugin.getPluginId(), 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }

        ToolContext effectiveCtx = ctx;
        if (plugin.getPluginContext() != null && ctx != null) {
            effectiveCtx = ctx.withPluginContext(plugin.getPluginContext());
        }

        long start = System.currentTimeMillis();
        ToolResult result;
        try {
            ToolProvider provider = plugin.getProvider();
            if (provider == null) {
                result = ToolResult.error("plugin has no tool callbacks", 0L);
            } else {
                result = provider.execute(args, effectiveCtx);
            }
            if (result == null) {
                result = ToolResult.error("tool returned null result",
                        System.currentTimeMillis() - start);
            }
        } catch (RuntimeException e) {
            result = ToolResult.error("plugin execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        ToolResult finalResult = truncateIfNeeded(result);
        invokeAudit(ctx, toolType, args, finalResult);
        return finalResult;
    }

    /** Finds the default plugin for a tool type. */
    private PluginDescriptor findDefault(String toolType) {
        PluginDescriptor fallback = null;
        for (PluginDescriptor p : registry.listPlugins()) {
            if (toolType.equals(p.getToolType())) {
                if (p.isDefault()) return p;
                if (fallback == null) fallback = p;
            }
        }
        return fallback;
    }

    private ToolResult truncateIfNeeded(ToolResult result) {
        if (result == null || result.isError() || result.getContent() == null) {
            return result;
        }
        String content = result.getContent();
        if (content.length() <= maxToolResultChars) {
            return result;
        }
        String suffix = "\n...[truncated, total " + result.getRowCount() + " rows]";
        int keep = Math.max(0, maxToolResultChars - suffix.length());
        String truncated = content.substring(0, keep) + suffix;
        return new ToolResult(truncated, result.getRowCount(), true, result.getDurationMs(), null);
    }

    private void invokeAudit(ToolContext ctx, String toolName, Map<String, Object> args, ToolResult result) {
        if (ctx != null && ctx.getAuditCallback() != null) {
            try {
                ctx.getAuditCallback().onToolExecuted(toolName, args, result);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Deprecated
    public String buildToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n");
        for (PluginDescriptor desc : activePlugins()) {
            sb.append("- ").append(desc.getToolType())
              .append(": ").append(desc.getProvider().schema()).append("\n");
        }
        return sb.toString();
    }

    // ---- ToolCallbackRegistry implementation ----

    @Override
    public ToolCallback find(String name) {
        for (PluginDescriptor desc : registry.listPlugins()) {
            if (desc.isEnabled()) {
                ToolCallback[] callbacks = desc.getToolCallbacks();
                if (callbacks != null) {
                    for (ToolCallback cb : callbacks) {
                        if (cb.getName().equals(name)) return cb;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public List<ToolCallback> getAll() {
        List<ToolCallback> all = new ArrayList<>();
        for (PluginDescriptor desc : registry.listPlugins()) {
            if (desc.isEnabled() && desc.getToolCallbacks() != null) {
                for (ToolCallback cb : desc.getToolCallbacks()) {
                    all.add(cb);
                }
            }
        }
        return all;
    }

    @Override
    public String toToolDefinitionsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ToolCallback cb : getAll()) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(cb.getName()).append("\",\"schema\":")
              .append(cb.getJsonSchema()).append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public ToolCallbackRegistry subset(Map<String, String> pluginOverrides) {
        return this;
    }
}
