package cn.watsontech.snapagent.boot2x.tool;

/**
 * POJO representing the contents of {@code META-INF/snap-agent/plugin-info.yml}.
 * Fields are populated by {@link PluginInfoYmlParser} via manual map extraction.
 */
public class PluginInfoYml {

    private String id;
    private String toolType;
    private String displayName;
    private String description;
    private String version;
    private boolean isDefault;
    private String providerClass;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public String getProviderClass() {
        return providerClass;
    }

    public void setProviderClass(String providerClass) {
        this.providerClass = providerClass;
    }
}
