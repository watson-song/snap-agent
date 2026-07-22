package cn.watsontech.snapagent.core.tool;

import java.nio.file.Path;

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

    void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    void setEnabled(boolean enabled) { this.enabled = enabled; }
}
