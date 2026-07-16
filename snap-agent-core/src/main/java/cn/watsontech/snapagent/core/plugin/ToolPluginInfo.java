package cn.watsontech.snapagent.core.plugin;

import java.util.Collections;
import java.util.List;

/**
 * Immutable DTO describing a registered tool plugin.
 */
public class ToolPluginInfo {

    private final String pluginId;
    private final String description;
    private final List<String> toolNames;

    public ToolPluginInfo(String pluginId, String description, List<String> toolNames) {
        this.pluginId = pluginId;
        this.description = description;
        this.toolNames = toolNames == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(toolNames);
    }

    public String getPluginId() { return pluginId; }
    public String getDescription() { return description; }
    public List<String> getToolNames() { return toolNames; }
}
