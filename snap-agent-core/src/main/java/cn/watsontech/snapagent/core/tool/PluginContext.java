package cn.watsontech.snapagent.core.tool;

import java.util.Map;

/**
 * SPI for providing configuration to a registered plugin.
 *
 * <p>Implementations expose a key-value configuration map that plugins can read
 * at runtime (e.g. credentials, endpoint URLs, tunable thresholds).</p>
 */
public interface PluginContext {
    Map<String, Object> getConfiguration();
}
