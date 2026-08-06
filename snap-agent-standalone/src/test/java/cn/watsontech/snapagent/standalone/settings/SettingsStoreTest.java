package cn.watsontech.snapagent.standalone.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsStoreTest {

    @TempDir
    Path tempDir;

    private SettingsStore store;

    @BeforeEach
    void setUp() {
        store = new SettingsStore(new File(tempDir.toFile(), "config.yml"));
    }

    @Test
    void shouldReturnDefaultsWhenNoFileExists() {
        Settings settings = store.load();

        assertThat(settings).isNotNull();
        assertThat(settings.getLlm().getApiType()).isEqualTo("anthropic");
        assertThat(settings.getLlm().getBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(settings.getLlm().getModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(settings.getLlm().getMaxTokens()).isEqualTo(8192);
        assertThat(settings.getLlm().getTimeoutSeconds()).isEqualTo(120);
        assertThat(settings.getJdbc().isEnabled()).isFalse();
        assertThat(settings.getSecurity().getJwtSecret()).isEmpty();
    }

    @Test
    void shouldSaveAndLoadSettings() {
        Settings settings = store.load();
        settings.getLlm().setApiKey("sk-test-123");
        settings.getLlm().setModel("claude-3-opus-20240229");
        settings.getJdbc().setEnabled(true);
        settings.getJdbc().setUrl("jdbc:mysql://db:3306/mydb");
        settings.getJdbc().setUsername("root");
        settings.getJdbc().setPassword("secret");

        store.save(settings);

        // Load again from file
        SettingsStore store2 = new SettingsStore(new File(tempDir.toFile(), "config.yml"));
        Settings loaded = store2.load();

        assertThat(loaded.getLlm().getApiKey()).isEqualTo("sk-test-123");
        assertThat(loaded.getLlm().getModel()).isEqualTo("claude-3-opus-20240229");
        assertThat(loaded.getJdbc().isEnabled()).isTrue();
        assertThat(loaded.getJdbc().getUrl()).isEqualTo("jdbc:mysql://db:3306/mydb");
        assertThat(loaded.getJdbc().getUsername()).isEqualTo("root");
        assertThat(loaded.getJdbc().getPassword()).isEqualTo("secret");
    }

    @Test
    void shouldPreserveUnchangedFieldsOnPartialUpdate() {
        Settings settings = store.load();
        settings.getLlm().setApiKey("sk-first");
        settings.getLlm().setBaseUrl("https://proxy.example.com");
        store.save(settings);

        // Partial update: only change apiKey
        Settings loaded = store.load();
        loaded.getLlm().setApiKey("sk-second");
        store.save(loaded);

        Settings reloaded = store.load();
        assertThat(reloaded.getLlm().getApiKey()).isEqualTo("sk-second");
        assertThat(reloaded.getLlm().getBaseUrl()).isEqualTo("https://proxy.example.com");
        assertThat(reloaded.getLlm().getModel()).isEqualTo("claude-sonnet-4-20250514");
    }

    @Test
    void shouldCreateConfigFileIfNotExists() {
        File configFile = new File(tempDir.toFile(), "new-config.yml");
        assertThat(configFile.exists()).isFalse();

        SettingsStore newStore = new SettingsStore(configFile);
        newStore.save(newStore.load());

        assertThat(configFile.exists()).isTrue();
    }

    @Test
    void shouldSaveFeatureFlags() {
        Settings settings = store.load();
        settings.getFeatures().setAnchorEnabled(true);
        settings.getFeatures().setPatrolEnabled(true);
        settings.getFeatures().setCodeGraphEnabled(false);
        store.save(settings);

        Settings loaded = store.load();
        assertThat(loaded.getFeatures().isAnchorEnabled()).isTrue();
        assertThat(loaded.getFeatures().isPatrolEnabled()).isTrue();
        assertThat(loaded.getFeatures().isCodeGraphEnabled()).isFalse();
    }

    @Test
    void shouldSaveSecuritySettings() {
        Settings settings = store.load();
        settings.getSecurity().setJwtSecret("my-super-secret-key-32chars!!");
        settings.getSecurity().setCorsAllowedOrigins("http://localhost:3000,https://app.example.com");
        store.save(settings);

        Settings loaded = store.load();
        assertThat(loaded.getSecurity().getJwtSecret()).isEqualTo("my-super-secret-key-32chars!!");
        assertThat(loaded.getSecurity().getCorsAllowedOrigins()).isEqualTo("http://localhost:3000,https://app.example.com");
    }
}
