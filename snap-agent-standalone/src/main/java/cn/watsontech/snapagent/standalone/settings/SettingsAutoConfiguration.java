package cn.watsontech.snapagent.standalone.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Auto-configuration for Settings store.
 * Config file location defaults to /app/data/config.yml (Docker) or ./data/config.yml (local).
 */
@Configuration
public class SettingsAutoConfiguration {

    @Value("${snap-agent.standalone.config-file:./data/config.yml}")
    private String configFile;

    @Bean
    public SettingsStore settingsStore() {
        return new SettingsStore(new File(configFile));
    }
}
