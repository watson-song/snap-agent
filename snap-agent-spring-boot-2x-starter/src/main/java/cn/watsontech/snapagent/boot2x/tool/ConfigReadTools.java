package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2.x config-read tool exposed via the {@code @Tool} annotation pattern.
 *
 * <p>Refactored from the 1.x {@code ConfigReadToolProvider} (which implemented
 * the now-removed {@code ToolProvider} SPI) to an {@code @Tool}-annotated
 * method discovered by {@link cn.watsontech.snapagent.core.tool.ToolCallbacks#from(Object)}.
 * The method returns a {@link String} directly (success text or {@code "Error: ..."}
 * on failure) instead of {@code ToolResult}.</p>
 *
 * <p>Still extends {@link ObservabilityHttpClient} so the Nacos HTTP call can
 * reuse the shared {@code httpGet} helper (and remain overridable in tests).</p>
 *
 * <p>Tool name: {@code config_read}. Supports two sources:</p>
 * <ul>
 *   <li><b>local</b> — reads the Spring {@link Environment} property sources (no
 *   network call). Filters by key prefix, masks sensitive values, clamps to
 *   {@code max-keys}.</li>
 *   <li><b>nacos</b> — fetches a config file from the Nacos config server via
 *   {@code GET /nacos/v1/cs/configs}.</li>
 * </ul>
 *
 * <p>See design doc §4.4 for the contract.</p>
 */
public class ConfigReadTools extends ObservabilityHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigReadTools.class);

    private final SnapAgentProperties.ConfigRead config;
    private final Environment environment;

    public ConfigReadTools(SnapAgentProperties.ConfigRead config, Environment environment) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        this.config = config;
        this.environment = environment;
    }

    @Tool(name = "config_read", description = "Read application configuration. Supports local Spring properties (no network) and remote Nacos configs. Use for verifying current config, comparing across environments, and diagnosing config-related issues.")
    public String configRead(
            @ToolParam(description = "Config source. 'local' reads Spring Environment; 'nacos' reads from Nacos.", required = false) String source,
            @ToolParam(description = "Property key prefix to filter local config (e.g. 'spring.datasource'). Empty = all properties. Only for source=local.", required = false) String key_prefix,
            @ToolParam(description = "Nacos config data ID (when source=nacos). Required for nacos.", required = false) String nacos_data_id,
            @ToolParam(description = "Nacos config group. Default 'DEFAULT_GROUP'.", required = false) String nacos_group,
            @ToolParam(description = "Nacos namespace ID. Optional, uses config default if omitted.", required = false) String nacos_namespace) {

        String src = (source == null || source.isEmpty()) ? "local" : source;
        if ("local".equalsIgnoreCase(src)) {
            return executeLocal(key_prefix);
        } else if ("nacos".equalsIgnoreCase(src)) {
            return executeNacos(nacos_data_id, nacos_group, nacos_namespace);
        }
        return "Error: invalid source: " + src + " (expected 'local' or 'nacos')";
    }

    // ---- local source ----

    private String executeLocal(String keyPrefix) {
        if (keyPrefix == null) {
            keyPrefix = "";
        }
        int maxKeys = config.getMaxKeys();

        List<String> sensitivePatterns = config.getSensitiveKeyPatterns();
        if (sensitivePatterns == null) {
            sensitivePatterns = Arrays.asList("password", "secret", "token", "credential", "key");
        }

        // Collect property names, deduplicated (first occurrence wins)
        Map<String, String> collected = new LinkedHashMap<String, String>();

        if (!(environment instanceof ConfigurableEnvironment)) {
            return "Error: Environment is not configurable; local config read not supported";
        }

        ConfigurableEnvironment ce = (ConfigurableEnvironment) environment;
        for (PropertySource<?> ps : ce.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource) {
                EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) ps;
                for (String name : eps.getPropertyNames()) {
                    if (collected.size() >= maxKeys) {
                        break;
                    }
                    // Skip duplicates (higher-precedence source already captured the key)
                    if (collected.containsKey(name)) {
                        continue;
                    }
                    // Apply key_prefix filter
                    if (!keyPrefix.isEmpty() && !name.startsWith(keyPrefix)) {
                        continue;
                    }
                    String resolved = environment.getProperty(name);
                    if (resolved == null) {
                        continue;
                    }
                    // Mask sensitive values
                    String value = maskIfSensitive(name, resolved, sensitivePatterns);
                    collected.put(name, value);
                }
            }
            if (collected.size() >= maxKeys) {
                break;
            }
        }

        boolean truncated = false;
        int totalMatching = collected.size();
        // We can't easily count total matches without iterating all sources fully;
        // if we hit maxKeys, assume there might be more.
        if (collected.size() >= maxKeys) {
            truncated = true;
        }

        String profiles = "";
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles != null && activeProfiles.length > 0) {
            profiles = String.join(",", activeProfiles);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Config Source: local (Spring Environment)\n");
        sb.append("# Profiles: ").append(profiles.isEmpty() ? "(none)" : profiles).append("\n");
        sb.append("# Properties: ").append(totalMatching).append(" (filtered by prefix '")
                .append(keyPrefix.isEmpty() ? "(all)" : keyPrefix)
                .append("', max ").append(maxKeys).append(")\n\n");

        for (Map.Entry<String, String> entry : collected.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }

        if (truncated) {
            sb.append("\n# (truncated: result hit max-keys limit)\n");
        }
        return sb.toString();
    }

    private String maskIfSensitive(String key, String value, List<String> sensitivePatterns) {
        String keyLower = key.toLowerCase(Locale.ROOT);
        for (String pattern : sensitivePatterns) {
            if (pattern != null && !pattern.isEmpty() && keyLower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return "****";
            }
        }
        return value;
    }

    // ---- nacos source ----

    private String executeNacos(String dataId, String group, String namespace) {
        if (dataId == null || dataId.isEmpty()) {
            return "Error: missing required parameter: nacos_data_id";
        }

        if (group == null || group.isEmpty()) {
            group = "DEFAULT_GROUP";
        }
        if (namespace == null || namespace.isEmpty()) {
            namespace = config.getNacosNamespace();
            if (namespace == null) {
                namespace = "";
            }
        }

        String baseUrl = config.getNacosBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "Error: nacos-base-url is not configured";
        }

        String url;
        try {
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
                    .append("/nacos/v1/cs/configs?dataId=")
                    .append(URLEncoder.encode(dataId, "UTF-8"))
                    .append("&group=").append(URLEncoder.encode(group, "UTF-8"));
            if (namespace != null && !namespace.isEmpty()) {
                urlBuilder.append("&tenant=").append(URLEncoder.encode(namespace, "UTF-8"));
            }
            url = urlBuilder.toString();
        } catch (UnsupportedEncodingException e) {
            return "Error: failed to encode parameter: " + e.getMessage();
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        String authToken = config.getNacosAuthToken();
        if (authToken != null && !authToken.isEmpty()) {
            headers.put("accessToken", authToken);
        }

        int timeoutMs = 15 * 1000; // Nacos uses a fixed default timeout

        log.info("Nacos config read: dataId={}, group={}, namespace={}", dataId, group, namespace);

        try {
            String body = httpGet(url, headers, timeoutMs, timeoutMs);

            StringBuilder sb = new StringBuilder();
            sb.append("# Config Source: nacos\n");
            sb.append("# Data ID: ").append(dataId).append("\n");
            sb.append("# Group: ").append(group).append("\n");
            if (namespace != null && !namespace.isEmpty()) {
                sb.append("# Namespace: ").append(namespace).append("\n");
            }
            sb.append("\n").append(body);

            return sb.toString();
        } catch (IOException e) {
            log.warn("Nacos config read failed: {}", e.getMessage());
            return "Error: Nacos config read failed: " + e.getMessage();
        }
    }
}
