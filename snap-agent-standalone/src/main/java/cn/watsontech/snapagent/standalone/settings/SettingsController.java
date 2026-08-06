package cn.watsontech.snapagent.standalone.settings;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API for runtime settings management.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /snap-agent/settings — get current settings (secrets masked)</li>
 *   <li>PUT /snap-agent/settings — update settings (triggers hot-reload)</li>
 *   <li>GET /snap-agent/settings/status — check if setup is complete</li>
 * </ul>
 */
@RestController
@RequestMapping("/snap-agent/settings")
public class SettingsController {

    private final SettingsStore settingsStore;

    public SettingsController(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping
    public ResponseEntity<Settings> getSettings() {
        Settings settings = settingsStore.load();
        maskSecrets(settings);
        return ResponseEntity.ok(settings);
    }

    @PutMapping
    public ResponseEntity<Settings> updateSettings(@RequestBody Settings incoming) {
        Settings current = settingsStore.load();
        merge(current, incoming);
        settingsStore.save(current);

        Settings result = settingsStore.load();
        maskSecrets(result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Settings settings = settingsStore.load();
        Map<String, Object> status = new HashMap<String, Object>();

        boolean hasApiKey = settings.getLlm().getApiKey() != null
                && !settings.getLlm().getApiKey().isEmpty();
        boolean hasAuthToken = settings.getLlm().getAuthToken() != null
                && !settings.getLlm().getAuthToken().isEmpty();

        status.put("setupComplete", hasApiKey || hasAuthToken);
        status.put("hasApiKey", hasApiKey);
        status.put("hasAuthToken", hasAuthToken);
        status.put("jdbcEnabled", settings.getJdbc().isEnabled());
        status.put("model", settings.getLlm().getModel());

        return ResponseEntity.ok(status);
    }

    /**
     * Merge incoming settings into current, preserving non-empty fields.
     */
    private void merge(Settings current, Settings incoming) {
        // LLM
        if (isSet(incoming.getLlm().getApiType())) current.getLlm().setApiType(incoming.getLlm().getApiType());
        if (isSet(incoming.getLlm().getBaseUrl())) current.getLlm().setBaseUrl(incoming.getLlm().getBaseUrl());
        if (isSet(incoming.getLlm().getApiKey())) current.getLlm().setApiKey(incoming.getLlm().getApiKey());
        if (isSet(incoming.getLlm().getAuthToken())) current.getLlm().setAuthToken(incoming.getLlm().getAuthToken());
        if (isSet(incoming.getLlm().getModel())) current.getLlm().setModel(incoming.getLlm().getModel());
        if (incoming.getLlm().getMaxTokens() > 0) current.getLlm().setMaxTokens(incoming.getLlm().getMaxTokens());
        if (incoming.getLlm().getTimeoutSeconds() > 0) current.getLlm().setTimeoutSeconds(incoming.getLlm().getTimeoutSeconds());

        // JDBC
        current.getJdbc().setEnabled(incoming.getJdbc().isEnabled());
        if (isSet(incoming.getJdbc().getUrl())) current.getJdbc().setUrl(incoming.getJdbc().getUrl());
        if (isSet(incoming.getJdbc().getUsername())) current.getJdbc().setUsername(incoming.getJdbc().getUsername());
        if (isSet(incoming.getJdbc().getPassword())) current.getJdbc().setPassword(incoming.getJdbc().getPassword());

        // Security
        if (isSet(incoming.getSecurity().getJwtSecret())) current.getSecurity().setJwtSecret(incoming.getSecurity().getJwtSecret());
        if (isSet(incoming.getSecurity().getCorsAllowedOrigins())) current.getSecurity().setCorsAllowedOrigins(incoming.getSecurity().getCorsAllowedOrigins());

        // Features
        current.getFeatures().setJdbcEnabled(incoming.getFeatures().isJdbcEnabled());
        current.getFeatures().setAnchorEnabled(incoming.getFeatures().isAnchorEnabled());
        current.getFeatures().setPatrolEnabled(incoming.getFeatures().isPatrolEnabled());
        current.getFeatures().setAlertEnabled(incoming.getFeatures().isAlertEnabled());
        current.getFeatures().setCodeGraphEnabled(incoming.getFeatures().isCodeGraphEnabled());
    }

    private boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Mask sensitive fields before returning to client.
     */
    private void maskSecrets(Settings settings) {
        if (isSet(settings.getLlm().getApiKey())) {
            settings.getLlm().setApiKey(mask(settings.getLlm().getApiKey()));
        }
        if (isSet(settings.getLlm().getAuthToken())) {
            settings.getLlm().setAuthToken(mask(settings.getLlm().getAuthToken()));
        }
        if (isSet(settings.getJdbc().getPassword())) {
            settings.getJdbc().setPassword(mask(settings.getJdbc().getPassword()));
        }
        if (isSet(settings.getSecurity().getJwtSecret())) {
            settings.getSecurity().setJwtSecret(mask(settings.getSecurity().getJwtSecret()));
        }
    }

    private String mask(String value) {
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
