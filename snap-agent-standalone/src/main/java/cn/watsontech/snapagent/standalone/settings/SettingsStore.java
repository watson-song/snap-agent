package cn.watsontech.snapagent.standalone.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Persists Settings to a YAML file on disk.
 * Thread-safe via synchronized methods.
 */
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final File configFile;
    private final ObjectMapper yamlMapper;

    public SettingsStore(File configFile) {
        this.configFile = configFile;
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        this.yamlMapper = new ObjectMapper(factory);
        this.yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Loads settings from disk. Returns defaults if file doesn't exist.
     */
    public synchronized Settings load() {
        if (!configFile.exists()) {
            log.info("No config file found at {}, returning defaults", configFile.getAbsolutePath());
            return new Settings();
        }
        try {
            Settings settings = yamlMapper.readValue(configFile, Settings.class);
            log.debug("Loaded settings from {}", configFile.getAbsolutePath());
            return settings;
        } catch (IOException e) {
            log.warn("Failed to read config file {}, returning defaults: {}",
                    configFile.getAbsolutePath(), e.getMessage());
            return new Settings();
        }
    }

    /**
     * Saves settings to disk (YAML format).
     */
    public synchronized void save(Settings settings) {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yamlMapper.writeValue(configFile, settings);
            log.info("Settings saved to {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save settings to {}: {}", configFile.getAbsolutePath(), e.getMessage());
            throw new RuntimeException("Failed to save settings", e);
        }
    }
}
