package cn.watsontech.snapagent.boot2x.tool;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * Parses {@code plugin-info.yml} into a {@link PluginInfoYml} object.
 * Uses SnakeYAML to load the YAML into a raw Map, then manually maps
 * fields to the POJO so that missing keys are handled gracefully.
 */
public class PluginInfoYmlParser {

    @SuppressWarnings("unchecked")
    public PluginInfoYml parse(InputStream yamlStream) {
        Yaml yaml = new Yaml();
        Map<String, Object> raw = yaml.load(yamlStream);
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        PluginInfoYml info = new PluginInfoYml();
        info.setId(getString(raw, "id"));
        info.setToolType(getString(raw, "toolType"));
        info.setDisplayName(getString(raw, "displayName"));
        info.setDescription(getString(raw, "description"));
        info.setVersion(getString(raw, "version"));
        info.setDefault(getBoolean(raw, "isDefault"));
        info.setProviderClass(getString(raw, "providerClass"));
        return info;
    }

    private String getString(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getBoolean(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
