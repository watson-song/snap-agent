package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects all {@link ToolPlugin} beans on the classpath and exposes their
 * metadata for operational visibility.
 *
 * <p>This registry is a metadata layer only — it does not affect tool
 * discovery. Since v0.1, the {@link cn.watsontech.snapagent.core.tool.ToolProvider}
 * interface combined with {@code @Component} is the automatic discovery
 * mechanism: any {@code ToolProvider} bean is collected by the
 * {@link cn.watsontech.snapagent.core.tool.ToolDispatcher} auto-configuration.
 * {@code ToolPlugin} lets hosts additionally advertise plugin
 * name/version/description and the list of tool names a plugin contributes,
 * which is exposed via the {@code GET /tools/plugins} endpoint.</p>
 *
 * <p>When no {@link ToolPlugin} beans exist, the plugin list is simply empty
 * — the registry bean is always created unconditionally.</p>
 */
public class ToolPluginRegistry {

    private final List<ToolPlugin> plugins;

    /**
     * Construct the registry with the list of plugins discovered by Spring.
     *
     * @param plugins the list of {@link ToolPlugin} beans (may be empty,
     *                never {@code null})
     */
    public ToolPluginRegistry(List<ToolPlugin> plugins) {
        this.plugins = plugins != null
                ? new ArrayList<ToolPlugin>(plugins)
                : new ArrayList<ToolPlugin>();
    }

    /**
     * Returns an unmodifiable view of all registered plugins.
     *
     * @return unmodifiable list of plugins (never {@code null})
     */
    public List<ToolPlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }
}
