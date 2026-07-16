package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
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
 * {@link ToolProvider} implementation for reading application configuration.
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
public class ConfigReadToolProvider extends ObservabilityHttpClient implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfigReadToolProvider.class);

    private static final String SCHEMA = "{\"name\":\"config_read\","
            + "\"description\":\"Read application configuration. Supports local Spring properties (no network) and remote Nacos configs. Use for verifying current config, comparing across environments, and diagnosing config-related issues.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"source\":{\"type\":\"string\",\"enum\":[\"local\",\"nacos\"],\"description\":\"Config source. 'local' reads Spring Environment; 'nacos' reads from Nacos.\",\"default\":\"local\"},"
            + "\"key_prefix\":{\"type\":\"string\",\"description\":\"Property key prefix to filter local config (e.g. 'spring.datasource'). Empty = all properties. Only for source=local.\"},"
            + "\"nacos_data_id\":{\"type\":\"string\",\"description\":\"Nacos config data ID (when source=nacos). Required for nacos.\"},"
            + "\"nacos_group\":{\"type\":\"string\",\"description\":\"Nacos config group. Default 'DEFAULT_GROUP'.\",\"default\":\"DEFAULT_GROUP\"},"
            + "\"nacos_namespace\":{\"type\":\"string\",\"description\":\"Nacos namespace ID. Optional, uses config default if omitted.\"}"
            + "},"
            + "\"required\":[\"source\"]}}";

    private final SnapAgentProperties.ConfigRead config;
    private final Environment environment;

    public ConfigReadToolProvider(SnapAgentProperties.ConfigRead config, Environment environment) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        this.config = config;
        this.environment = environment;
    }

    @Override
    public String name() {
        return "config_read";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        String source = extractString(args, "source");
        if (source == null || source.isEmpty()) {
            source = "local";
        }

        if ("local".equalsIgnoreCase(source)) {
            return executeLocal(args, start);
        } else if ("nacos".equalsIgnoreCase(source)) {
            return executeNacos(args, start);
        } else {
            return ToolResult.error("invalid source: " + source + " (expected 'local' or 'nacos')",
                    elapsed(start));
        }
    }

    // ---- local source ----

    private ToolResult executeLocal(Map<String, Object> args, long start) {
        String keyPrefix = extractString(args, "key_prefix");
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
            return ToolResult.error(
                    "Environment is not configurable; local config read not supported",
                    elapsed(start));
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

        long duration = elapsed(start);
        if (truncated) {
            return ToolResult.truncated(sb.toString(), totalMatching, duration);
        }
        return ToolResult.success(sb.toString(), totalMatching, duration);
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

    private ToolResult executeNacos(Map<String, Object> args, long start) {
        String dataId = extractString(args, "nacos_data_id");
        if (dataId == null || dataId.isEmpty()) {
            return ToolResult.error("missing required parameter: nacos_data_id", elapsed(start));
        }

        String group = extractString(args, "nacos_group");
        if (group == null || group.isEmpty()) {
            group = "DEFAULT_GROUP";
        }
        String namespace = extractString(args, "nacos_namespace");
        if (namespace == null || namespace.isEmpty()) {
            namespace = config.getNacosNamespace();
            if (namespace == null) {
                namespace = "";
            }
        }

        String baseUrl = config.getNacosBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ToolResult.error("nacos-base-url is not configured", elapsed(start));
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
            return ToolResult.error("failed to encode parameter: " + e.getMessage(), elapsed(start));
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

            int lineCount = body.isEmpty() ? 0 : body.split("\n").length;
            return ToolResult.success(sb.toString(), lineCount, elapsed(start));
        } catch (IOException e) {
            log.warn("Nacos config read failed: {}", e.getMessage());
            return ToolResult.error("Nacos config read failed: " + e.getMessage(), elapsed(start));
        }
    }

    // ---- arg extraction helpers (same pattern as LogReadToolProvider) ----

    private String extractString(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? String.valueOf(value) : null;
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
