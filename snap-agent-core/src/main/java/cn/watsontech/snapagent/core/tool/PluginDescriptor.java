package cn.watsontech.snapagent.core.tool;

import java.nio.file.Path;

/**
 * Immutable metadata model describing a registered tool plugin.
 *
 * <p>A descriptor is created when a {@link ToolProvider} is registered (either
 * discovered on the host classpath or loaded from an external JAR) and is
 * consumed by the plugin registry and tool dispatcher to resolve which
 * providers are active for a given request.</p>
 *
 * <p>Key field semantics:</p>
 * <ul>
 *   <li><b>pluginId</b> — stable, unique identifier of the plugin instance
 *       (e.g. {@code "remote-log"}). Never {@code null}.</li>
 *   <li><b>toolType</b> — the {@link ToolProvider#name()} this plugin serves
 *       (e.g. {@code "log_read"}). Multiple plugins may target the same
 *       {@code toolType}; only the one marked {@code isDefault} wins.
 *       Never {@code null}.</li>
 *   <li><b>system</b> — {@code true} for built-in plugins that ship with the
 *       agent and cannot be uninstalled; {@code false} for user-supplied
 *       (e.g. external-JAR) plugins.</li>
 * </ul>
 *
 * <p>The {@code classLoader}, {@code jarPath} and {@code pluginContext} fields
 * are {@code null} for classpath-resident plugins (loaded by the host
 * classloader, no JAR, configuration read directly from Spring Environment).
 * They are non-null only for plugins loaded from an external JAR, where the
 * descriptor records the isolated classloader, the JAR origin path, and a
 * bridging {@link PluginContext} respectively.</p>
 *
 * <p>The {@code isDefault} and {@code enabled} flags are the only mutable
 * fields; they are {@code volatile} to allow safe runtime toggling by the
 * plugin registry (e.g. disabling a plugin or promoting an alternative to
 * default) without external synchronisation. All other fields are immutable
 * after construction.</p>
 */
public final class PluginDescriptor {

    private final String pluginId;
    private final String toolType;
    private final String displayName;
    private final String description;
    private final String version;
    private volatile boolean isDefault;
    private volatile boolean enabled;
    private final boolean system;
    private final ToolProvider provider;
    private final ClassLoader classLoader;
    private final Path jarPath;
    private final PluginContext pluginContext;

    public PluginDescriptor(String pluginId, String toolType, String displayName,
                            String description, String version,
                            boolean isDefault, boolean enabled, boolean system,
                            ToolProvider provider, ClassLoader classLoader,
                            Path jarPath, PluginContext pluginContext) {
        if (pluginId == null) throw new IllegalArgumentException("pluginId must not be null");
        if (toolType == null) throw new IllegalArgumentException("toolType must not be null");
        this.pluginId = pluginId;
        this.toolType = toolType;
        this.displayName = displayName;
        this.description = description;
        this.version = version;
        this.isDefault = isDefault;
        this.enabled = enabled;
        this.system = system;
        this.provider = provider;
        this.classLoader = classLoader;
        this.jarPath = jarPath;
        this.pluginContext = pluginContext;
    }

    public String getPluginId() { return pluginId; }
    public String getToolType() { return toolType; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefault() { return isDefault; }
    public boolean isEnabled() { return enabled; }
    public boolean isSystem() { return system; }
    public ToolProvider getProvider() { return provider; }
    public ClassLoader getClassLoader() { return classLoader; }
    public Path getJarPath() { return jarPath; }
    public PluginContext getPluginContext() { return pluginContext; }

    @Override
    public String toString() {
        return "PluginDescriptor{pluginId='" + pluginId + "', toolType='" + toolType
                + "', version='" + version + "', isDefault=" + isDefault
                + ", enabled=" + enabled + ", system=" + system + "}";
    }

    void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    void setEnabled(boolean enabled) { this.enabled = enabled; }
}
