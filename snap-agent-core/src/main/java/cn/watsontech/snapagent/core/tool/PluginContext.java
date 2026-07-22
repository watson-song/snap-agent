package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for providing configuration to a registered plugin.
 *
 * <p>Implementations expose a key-value configuration map that plugins can read
 * at runtime (e.g. credentials, endpoint URLs, tunable thresholds). When a plugin
 * is loaded from the host application's own classpath (not from an external JAR),
 * the {@link PluginContext} is typically {@code null} because the plugin can
 * resolve its configuration directly from the Spring Environment. When a plugin
 * is loaded from an external JAR, a non-null context bridges configuration from
 * the host into the isolated plugin.</p>
 */
public interface PluginContext {
    Map<String, Object> getConfiguration();
}
