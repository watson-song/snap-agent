package cn.watsontech.snapagent.standalone.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.io.File;
import java.nio.file.Path;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
class SettingsControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Configuration
    @Import(SettingsController.class)
    static class TestConfig {
        @Bean
        public SettingsStore settingsStore() {
            return new SettingsStore(new File(tempDir.toFile(), "test-config.yml"));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Reset: delete config file so defaults are returned
        File configFile = new File(tempDir.toFile(), "test-config.yml");
        configFile.delete();
    }

    @Test
    void shouldGetDefaultSettings() throws Exception {
        mockMvc.perform(get("/snap-agent/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llm.apiType").value("anthropic"))
                .andExpect(jsonPath("$.llm.baseUrl").value("https://api.anthropic.com"))
                .andExpect(jsonPath("$.llm.model").value("claude-sonnet-4-20250514"))
                .andExpect(jsonPath("$.jdbc.enabled").value(false))
                .andExpect(jsonPath("$.features").exists())
                .andExpect(jsonPath("$.security").exists());
    }

    @Test
    void shouldUpdateSettings() throws Exception {
        Settings update = new Settings();
        update.getLlm().setApiKey("sk-new-key");
        update.getLlm().setModel("claude-3-opus-20240229");
        update.getJdbc().setEnabled(true);
        update.getJdbc().setUrl("jdbc:mysql://localhost:3306/testdb");

        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llm.apiKey").value("sk****ey"))
                .andExpect(jsonPath("$.llm.model").value("claude-3-opus-20240229"))
                .andExpect(jsonPath("$.jdbc.enabled").value(true));
    }

    @Test
    void shouldPersistUpdatedSettings() throws Exception {
        // Update
        Settings update = new Settings();
        update.getLlm().setApiKey("sk-persisted");
        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        // Get again - should reflect persisted value
        mockMvc.perform(get("/snap-agent/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llm.apiKey").value("sk****ed"));
    }

    @Test
    void shouldUpdateFeatureFlags() throws Exception {
        Settings update = new Settings();
        update.getFeatures().setAnchorEnabled(true);
        update.getFeatures().setPatrolEnabled(true);

        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.anchorEnabled").value(true))
                .andExpect(jsonPath("$.features.patrolEnabled").value(true));
    }

    @Test
    void shouldMaskApiKeyInGetResponse() throws Exception {
        // Save a settings with API key
        Settings settings = new Settings();
        settings.getLlm().setApiKey("sk-secret-key-12345");
        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isOk());

        // GET should mask the API key
        mockMvc.perform(get("/snap-agent/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llm.apiKey").value(not("sk-secret-key-12345")));
    }

    @Test
    void shouldMaskPasswordInGetResponse() throws Exception {
        Settings settings = new Settings();
        settings.getJdbc().setPassword("db-secret-password");
        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/snap-agent/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jdbc.password").value(not("db-secret-password")));
    }

    @Test
    void shouldCheckIfSetupComplete() throws Exception {
        // Default: no API key → setup not complete
        mockMvc.perform(get("/snap-agent/settings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupComplete").value(false));

        // Set API key
        Settings settings = new Settings();
        settings.getLlm().setApiKey("sk-has-key");
        mockMvc.perform(put("/snap-agent/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settings)))
                .andExpect(status().isOk());

        // Now setup complete
        mockMvc.perform(get("/snap-agent/settings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupComplete").value(true));
    }
}
