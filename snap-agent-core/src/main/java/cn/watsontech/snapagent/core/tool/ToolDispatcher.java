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
 * <p>Dispatch routing logic:
 * <ol>
 *   <li>If {@code ctx.pluginOverrides[toolType]} is set, use that plugin</li>
 *   <li>Otherwise use {@code registry.getDefault(toolType)}</li>
 *   <li>If the selected plugin is disabled or not found, return a
 *       {@link ToolResult#error} so the LLM can self-correct</li>
 * </ol></p>
 *
 * <p>The old {@code ToolDispatcher(Collection<ToolProvider>, int)} constructor is
 * retained {@code @Deprecated} for backward compatibility — it creates an
 * internal {@link InMemoryPluginRegistry} and registers each provider as a
 * system plugin.</p>
 */
public class ToolDispatcher {

    private final PluginRegistry registry;
    private final int maxToolResultChars;

    /**
     * New constructor — routes via PluginRegistry.
     */
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
                    PluginDescriptor desc = new PluginDescriptor(
                            p.name(), p.name(), p.name(), "", "built-in",
                            true, true, true, p, null, null, null);
                    reg.register(desc);
                }
            }
        }
        this.registry = reg;
        this.maxToolResultChars = maxToolResultChars;
    }

    /** Returns the set of registered tool types. */
    public Set<String> availableToolTypes() {
        Set<String> types = new HashSet<>();
        for (PluginDescriptor p : registry.list()) {
            types.add(p.getToolType());
        }
        return types;
    }

    /** @deprecated Use {@link #availableToolTypes()}. */
    @Deprecated
    public Set<String> availableToolNames() {
        return availableToolTypes();
    }

    /**
     * Returns the active plugins for this request.
     * Each toolType is represented once — by the override plugin (if specified)
     * or the default plugin (if enabled).
     */
    public Collection<PluginDescriptor> activePlugins(Map<String, String> overrides) {
        Map<String, PluginDescriptor> active = new LinkedHashMap<>();
        // First pass: default plugins win for their toolType
        for (PluginDescriptor p : registry.list()) {
            if (!p.isEnabled() || !p.isDefault()) continue;
            active.put(p.getToolType(), p);
        }
        // Second pass: fill in types that have no default yet
        for (PluginDescriptor p : registry.list()) {
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

    /** Convenience overload — no overrides. */
    public Collection<PluginDescriptor> activePlugins() {
        return activePlugins(null);
    }

    /** @deprecated Use {@link #activePlugins()}. */
    @Deprecated
    public Collection<ToolProvider> providers() {
        List<ToolProvider> result = new ArrayList<>();
        for (PluginDescriptor desc : activePlugins()) {
            result.add(desc.getProvider());
        }
        return result;
    }

    /**
     * Dispatch a tool call via PluginRegistry routing.
     */
    public ToolResult dispatch(String toolType, Map<String, Object> args, ToolContext ctx) {
        if (toolType == null) {
            ToolResult err = ToolResult.error("no plugin registered for: null", 0L);
            invokeAudit(ctx, toolType, args, err);
            return err;
        }
        String override = ctx != null ? ctx.getPluginOverrides().get(toolType) : null;
        PluginDescriptor plugin = (override != null)
                ? registry.getPlugin(override)
                : registry.getDefault(toolType);

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

        if (plugin.getPluginContext() != null && ctx != null) {
            ctx = ctx.withPluginContext(plugin.getPluginContext());
        }

        long start = System.currentTimeMillis();
        ToolResult result;
        try {
            result = plugin.getProvider().execute(args, ctx);
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
                // audit failure must not break the agent loop
            }
        }
    }

    /** @deprecated Use activePlugins() + provider schemas instead. */
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
}
