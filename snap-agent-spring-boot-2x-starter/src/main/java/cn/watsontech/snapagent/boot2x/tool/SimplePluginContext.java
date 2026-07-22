package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.PluginContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple immutable implementation of {@link PluginContext}.
 * The configuration map is defensively copied on construction and
 * wrapped in an unmodifiable view.
 */
public class SimplePluginContext implements PluginContext {

    private final Map<String, Object> configuration;

    public SimplePluginContext(Map<String, Object> configuration) {
        if (configuration != null) {
            this.configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
        } else {
            this.configuration = Collections.emptyMap();
        }
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return configuration;
    }
}
