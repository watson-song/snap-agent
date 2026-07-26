package cn.watsontech.snapagent.core.tool;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable descriptor for a registered plugin.
 *
 * <p>Holds plugin metadata, the {@link ToolCallback[]} array produced by
 * {@link ToolCallbacks#from(Object)}, the isolated {@link ClassLoader},
 * and the JAR path on disk.</p>
 *
 * <p>The {@code system} flag is immutable and prevents unregistration.
 * The {@code enabled} and {@code isDefault} fields are mutable
 * (volatile for concurrent visibility).</p>
 */
public class PluginDescriptor {

    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final String pluginId;
    private final String toolType;
    private final String displayName;
    private final String version;
    private final String description;
    private volatile boolean isDefault;
    private volatile boolean enabled;
    private final boolean system;
    private final ToolCallback[] toolCallbacks;
    private final ClassLoader classLoader;
    private final String jarPath;
    private final Object pluginContext;

    public PluginDescriptor(String pluginId, String toolType, String displayName,
                            String version, String description,
                            boolean isDefault, boolean enabled, boolean system,
                            ToolCallback[] toolCallbacks, ClassLoader classLoader,
                            String jarPath, Object pluginContext) {
        if (pluginId == null || !PLUGIN_ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("invalid pluginId: " + pluginId);
        }
        this.pluginId = pluginId;
        this.toolType = toolType;
        this.displayName = displayName;
        this.version = version;
        this.description = description;
        this.isDefault = isDefault;
        this.enabled = enabled;
        this.system = system;
        this.toolCallbacks = toolCallbacks != null ? toolCallbacks : new ToolCallback[0];
        this.classLoader = classLoader;
        this.jarPath = jarPath;
        this.pluginContext = pluginContext;
    }

    public String getPluginId() { return pluginId; }
    public String getToolType() { return toolType; }
    public String getDisplayName() { return displayName; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public boolean isDefault() { return isDefault; }
    public boolean isEnabled() { return enabled; }
    public boolean isSystem() { return system; }
    public ToolCallback[] getToolCallbacks() { return toolCallbacks; }
    public ClassLoader getClassLoader() { return classLoader; }
    public String getJarPath() { return jarPath; }
    public Object getPluginContext() { return pluginContext; }

    /**
     * Compatibility adapter: wraps the first {@link ToolCallback} as a
     * {@link ToolProvider} for 1.x code that expects the old SPI.
     * Returns {@code null} if no tool callbacks are registered.
     */
    public ToolProvider getProvider() {
        if (toolCallbacks == null || toolCallbacks.length == 0) {
            return null;
        }
        final ToolCallback cb = toolCallbacks[0];
        return new ToolProvider() {
            @Override
            public String name() { return cb.getName(); }
            @Override
            public String schema() { return cb.getJsonSchema(); }
            @Override
            public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
                return cb.execute(args, ctx);
            }
        };
    }

    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public String toString() {
        return "PluginDescriptor{pluginId='" + pluginId + "', toolType='" + toolType
                + "', version='" + version + "', enabled=" + enabled
                + ", system=" + system + "}";
    }
}
