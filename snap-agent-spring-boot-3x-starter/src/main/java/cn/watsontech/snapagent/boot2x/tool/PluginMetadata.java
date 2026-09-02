package cn.watsontech.snapagent.boot2x.tool;

/**
 * Immutable result POJO carrying plugin metadata extracted from either
 * {@link cn.watsontech.snapagent.core.tool.ToolPluginAnnotation} or
 * {@code plugin-info.yml} by {@link PluginMetadataScanner}.
 */
public class PluginMetadata {

    private final String pluginId;
    private final String toolType;
    private final String displayName;
    private final String description;
    private final String version;
    private final boolean isDefault;
    private final String providerClassName;

    public PluginMetadata(String pluginId, String toolType, String displayName,
                          String description, String version, boolean isDefault,
                          String providerClassName) {
        this.pluginId = pluginId;
        this.toolType = toolType;
        this.displayName = displayName;
        this.description = description;
        this.version = version;
        this.isDefault = isDefault;
        this.providerClassName = providerClassName;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getToolType() {
        return toolType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public String getProviderClassName() {
        return providerClassName;
    }
}
