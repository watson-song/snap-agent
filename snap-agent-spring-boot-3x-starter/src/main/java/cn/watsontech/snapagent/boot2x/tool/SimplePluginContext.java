package cn.watsontech.snapagent.boot2x.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple immutable holder for plugin configuration.
 */
public class SimplePluginContext {

    private final Map<String, Object> configuration;

    public SimplePluginContext(Map<String, Object> configuration) {
        if (configuration != null) {
            this.configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
        } else {
            this.configuration = Collections.emptyMap();
        }
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }
}
