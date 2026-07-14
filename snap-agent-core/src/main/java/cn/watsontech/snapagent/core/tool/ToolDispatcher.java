package cn.watsontech.snapagent.core.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes {@code tool_use} calls to the matching {@link ToolProvider} by name.
 *
 * <p>Holds an immutable map of registered providers keyed by {@link ToolProvider#name()}.
 * Unknown tool names return a {@link ToolResult#error(String, long)} so the LLM
 * can self-correct without crashing the agent loop.</p>
 *
 * <p>Successful (non-error) results whose content exceeds {@code maxToolResultChars}
 * are truncated with a {@code [truncated, total N rows]} suffix before being
 * returned to the caller.</p>
 */
public class ToolDispatcher {

    private final Map<String, ToolProvider> providers;
    private final int maxToolResultChars;

    public ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
        Map<String, ToolProvider> map = new LinkedHashMap<>();
        if (providerList != null) {
            for (ToolProvider p : providerList) {
                if (p != null && p.name() != null) {
                    map.put(p.name(), p);
                }
            }
        }
        this.providers = Collections.unmodifiableMap(map);
    }

    /** Returns the set of registered tool names. */
    public Set<String> availableToolNames() {
        return providers.keySet();
    }

    /** Returns the registered providers (for schema extraction by the executor). */
    public Collection<ToolProvider> providers() {
        return providers.values();
    }

    /**
     * Dispatch a tool call to the registered provider.
     *
     * @param name the tool name from the LLM tool_use block
     * @param args the arguments parsed from the tool_use input
     * @param ctx  request-scoped context (may carry an audit callback)
     * @return the tool result (possibly truncated); never {@code null}
     */
    public ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx) {
        if (name == null) {
            ToolResult err = ToolResult.error("tool not found: null", 0L);
            invokeAudit(ctx, name, args, err);
            return err;
        }
        ToolProvider provider = providers.get(name);
        if (provider == null) {
            ToolResult err = ToolResult.error("tool not found: " + name, 0L);
            invokeAudit(ctx, name, args, err);
            return err;
        }
        long start = System.currentTimeMillis();
        ToolResult result;
        try {
            result = provider.execute(args, ctx);
            if (result == null) {
                result = ToolResult.error("tool returned null result", System.currentTimeMillis() - start);
            }
        } catch (RuntimeException e) {
            result = ToolResult.error("tool execution failed: " + e.getMessage(), System.currentTimeMillis() - start);
        }
        ToolResult finalResult = truncateIfNeeded(result);
        invokeAudit(ctx, name, args, finalResult);
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

    /** Builds a human-readable tool definition listing for system prompt injection. */
    public String buildToolDefinitions() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n");
        for (ToolProvider p : providers.values()) {
            sb.append("- ").append(p.name()).append(": ").append(p.schema()).append("\n");
        }
        return sb.toString();
    }
}
