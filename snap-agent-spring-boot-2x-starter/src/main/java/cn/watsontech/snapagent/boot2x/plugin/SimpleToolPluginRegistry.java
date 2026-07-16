package cn.watsontech.snapagent.boot2x.plugin;

import cn.watsontech.snapagent.core.plugin.ToolPlugin;
import cn.watsontech.snapagent.core.plugin.ToolPluginInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects all {@link ToolPlugin} beans and provides introspection.
 *
 * <p>This registry is metadata-only — the actual tool providers are
 * already wired into the {@link cn.watsontech.snapagent.core.tool.ToolDispatcher}
 * via Spring's bean collection.</p>
 */
public class SimpleToolPluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(SimpleToolPluginRegistry.class);

    private final List<ToolPlugin> plugins;

    public SimpleToolPluginRegistry(List<ToolPlugin> plugins) {
        this.plugins = plugins != null
                ? new ArrayList<ToolPlugin>(plugins)
                : new ArrayList<ToolPlugin>();
        log.info("SimpleToolPluginRegistry assembled with {} plugin(s)", this.plugins.size());
    }

    public List<ToolPluginInfo> listPlugins() {
        List<ToolPluginInfo> infos = new ArrayList<ToolPluginInfo>();
        for (ToolPlugin plugin : plugins) {
            List<String> toolNames = new ArrayList<String>();
            if (plugin.toolProviders() != null) {
                for (cn.watsontech.snapagent.core.tool.ToolProvider tp : plugin.toolProviders()) {
                    toolNames.add(tp.name());
                }
            }
            infos.add(new ToolPluginInfo(
                    plugin.pluginId(),
                    plugin.description(),
                    toolNames));
        }
        return Collections.unmodifiableList(infos);
    }

    public int size() {
        return plugins.size();
    }
}
