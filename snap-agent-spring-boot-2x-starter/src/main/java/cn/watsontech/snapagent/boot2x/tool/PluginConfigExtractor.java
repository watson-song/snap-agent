package cn.watsontech.snapagent.boot2x.tool;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.Map;

/**
 * Extracts a plugin's configuration properties from the Spring {@link Environment}
 * using the prefix {@code snap-agent.tools.<pluginId>}.
 * <p>
 * Uses Spring Boot's {@link Binder} to bind the subtree into a Map.
 */
public class PluginConfigExtractor {

    /**
     * Extracts configuration for the given plugin from the Spring Environment.
     *
     * @param env     the Spring Environment
     * @param pluginId the plugin ID (used as the config key segment)
     * @return an unmodifiable Map of config key→value, or empty map if none found or pluginId is null
     */
    public Map<String, Object> extract(Environment env, String pluginId) {
        if (pluginId == null || pluginId.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            String prefix = "snap-agent.tools." + pluginId;
            return Binder.get(env)
                    .bind(prefix, Bindable.mapOf(String.class, Object.class))
                    .orElseGet(Collections::emptyMap);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
