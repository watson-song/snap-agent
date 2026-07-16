package cn.watsontech.snapagent.core.plugin;

import cn.watsontech.snapagent.core.tool.ToolProvider;

import java.util.List;

/**
 * SPI for tool plugins — groups multiple {@link ToolProvider} instances
 * as a logical, self-describing unit.
 *
 * <p>Implementations are typically Spring beans discovered via component
 * scanning. The plugin's {@link ToolProvider} instances are auto-registered
 * in the {@link cn.watsontech.snapagent.core.tool.ToolDispatcher} by virtue
 * of being beans; this interface provides metadata and introspection only.</p>
 */
public interface ToolPlugin {

    /** Unique plugin identifier (e.g., "mysql", "redis", "ops"). */
    String pluginId();

    /** Human-readable description. */
    String description();

    /** Tools provided by this plugin. */
    List<ToolProvider> toolProviders();
}
