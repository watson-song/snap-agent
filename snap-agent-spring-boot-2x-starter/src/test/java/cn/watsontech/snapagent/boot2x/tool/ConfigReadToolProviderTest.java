package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfigReadToolProvider}.
 *
 * <p>Uses the subclass-and-override-httpGet pattern (design doc §8.1) for Nacos
 * mode, and a {@link MockEnvironment} for local mode.</p>
 *
 * <p>See design doc §8.2 for the test matrix.</p>
 */
class ConfigReadToolProviderTest {

    private SnapAgentProperties.ConfigRead config;
    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        config = new SnapAgentProperties.ConfigRead();
        config.setMaxKeys(100);

        env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/orders");
        env.setProperty("spring.datasource.username", "app_user");
        env.setProperty("spring.datasource.password", "s3cr3t");
        env.setProperty("spring.jpa.database", "MYSQL");
        env.setProperty("server.port", "8080");
        env.setProperty("app.api.key", "abc-123-def");
        env.setProperty("app.auth.token", "xyz-token");
        env.setActiveProfiles("sit");
    }

    // ---- identity ----

    @Test
    @DisplayName("name() returns 'config_read'")
    void shouldReturnNameConfigRead() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);
        assertThat(provider.name()).isEqualTo("config_read");
    }

    @Test
    @DisplayName("schema() contains source and key_prefix properties")
    void shouldReturnSchemaWithSourceAndKeyPrefix() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);
        String schema = provider.schema();

        assertThat(schema).contains("config_read");
        assertThat(schema).contains("\"source\"");
        assertThat(schema).contains("\"key_prefix\"");
    }

    // ---- local source ----

    @Test
    @DisplayName("local source reads all properties from Spring Environment")
    void shouldReadLocalConfigSuccessfully() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("spring.datasource.url");
        assertThat(result.getContent()).contains("jdbc:mysql://localhost:3306/orders");
        assertThat(result.getContent()).contains("spring.datasource.username");
        assertThat(result.getContent()).contains("app_user");
        assertThat(result.getContent()).contains("server.port");
        assertThat(result.getContent()).contains("8080");
    }

    // ---- key_prefix filtering ----

    @Test
    @DisplayName("key_prefix filters properties to matching prefix only")
    void shouldFilterByKeyPrefix() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        args.put("key_prefix", "spring.datasource");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("spring.datasource.url");
        assertThat(result.getContent()).contains("spring.datasource.username");
        assertThat(result.getContent()).contains("spring.datasource.password");
        // Non-matching properties should be excluded
        assertThat(result.getContent()).doesNotContain("server.port");
        assertThat(result.getContent()).doesNotContain("spring.jpa");
    }

    // ---- sensitive value masking ----

    @Test
    @DisplayName("sensitive values (password, token, key) are masked to ****")
    void shouldMaskSensitiveValues() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        // password should be masked
        assertThat(result.getContent()).contains("spring.datasource.password = ****");
        assertThat(result.getContent()).doesNotContain("s3cr3t");
        // token should be masked
        assertThat(result.getContent()).contains("app.auth.token = ****");
        assertThat(result.getContent()).doesNotContain("xyz-token");
        // key should be masked
        assertThat(result.getContent()).contains("app.api.key = ****");
        assertThat(result.getContent()).doesNotContain("abc-123-def");
        // username and url should NOT be masked
        assertThat(result.getContent()).contains("app_user");
        assertThat(result.getContent()).contains("jdbc:mysql://localhost:3306/orders");
    }

    @Test
    @DisplayName("custom sensitive-key-patterns are respected for masking")
    void shouldRespectCustomSensitiveKeyPatterns() {
        java.util.List<String> customPatterns = new java.util.ArrayList<String>();
        customPatterns.add("connection");
        customPatterns.add("database");
        config.setSensitiveKeyPatterns(customPatterns);

        env.setProperty("app.connection.string", "amqp://broker:5672");
        env.setProperty("app.database.host", "db-host");

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        args.put("key_prefix", "app.");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("app.connection.string = ****");
        assertThat(result.getContent()).contains("app.database.host = ****");
        // Default patterns should NOT mask (since we overrode them)
        assertThat(result.getContent()).contains("app.api.key");
        // "key" is no longer in the custom patterns, so it's NOT masked
        assertThat(result.getContent()).contains("abc-123-def");
    }

    // ---- max-keys truncation ----

    @Test
    @DisplayName("properties exceeding max-keys are truncated and marked truncated")
    void shouldTruncateToMaxKeys() {
        config.setMaxKeys(2);

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    // ---- default source ----

    @Test
    @DisplayName("omitted source defaults to 'local'")
    void shouldDefaultToLocalSource() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("local");
        assertThat(result.getContent()).contains("spring.datasource.url");
    }

    // ---- active profiles ----

    @Test
    @DisplayName("active Spring profiles are shown in the output header")
    void shouldShowActiveProfiles() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "local");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Profiles:");
        assertThat(result.getContent()).contains("sit");
    }

    // ---- nacos source ----

    @Test
    @DisplayName("nacos source fetches config text via httpGet")
    void shouldReadNacosConfigSuccessfully() {
        config.setNacosBaseUrl("http://nacos:8848");

        final String mockConfig = "spring:\n  datasource:\n    url: jdbc:mysql://nacos-db:3306/orders\n";
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) {
                assertThat(url).contains("/nacos/v1/cs/configs");
                assertThat(url).contains("dataId=order-service.yml");
                assertThat(url).contains("group=DEFAULT_GROUP");
                return mockConfig;
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        args.put("nacos_data_id", "order-service.yml");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("nacos");
        assertThat(result.getContent()).contains("order-service.yml");
        assertThat(result.getContent()).contains("jdbc:mysql://nacos-db");
    }

    @Test
    @DisplayName("nacos source with custom group and namespace passes them in URL")
    void shouldReadNacosConfigWithCustomGroupAndNamespace() {
        config.setNacosBaseUrl("http://nacos:8848");
        config.setNacosNamespace("prod-ns");

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) {
                assertThat(url).contains("group=PROD_GROUP");
                assertThat(url).contains("tenant=prod-ns");
                return "key: value\n";
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        args.put("nacos_data_id", "app.yml");
        args.put("nacos_group", "PROD_GROUP");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
    }

    // ---- nacos missing data_id ----

    @Test
    @DisplayName("nacos source without data_id returns error")
    void shouldReturnErrorWhenNacosDataIdMissing() {
        config.setNacosBaseUrl("http://nacos:8848");

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("nacos_data_id");
    }

    // ---- nacos not configured ----

    @Test
    @DisplayName("nacos source without nacos-base-url configured returns error")
    void shouldReturnErrorWhenNacosBaseUrlNotConfigured() {
        // nacos-base-url is empty by default
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        args.put("nacos_data_id", "test.yml");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("nacos-base-url");
    }

    // ---- nacos auth token ----

    @Test
    @DisplayName("nacos auth token is injected as accessToken header")
    void shouldInjectAuthTokenForNacos() {
        config.setNacosBaseUrl("http://nacos:8848");
        config.setNacosAuthToken("nacos-secret-token");

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) {
                assertThat(headers).containsEntry("accessToken", "nacos-secret-token");
                return "key: value\n";
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        args.put("nacos_data_id", "test.yml");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isSuccess()).isTrue();
    }

    // ---- nacos IOException ----

    @Test
    @DisplayName("nacos source IOException returns error result")
    void shouldHandleNacosIOException() {
        config.setNacosBaseUrl("http://nacos:8848");

        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env) {
            @Override
            protected String httpGet(String url, Map<String, String> headers,
                                     int connectMs, int readMs) throws IOException {
                throw new IOException("HTTP 401: unauthorized");
            }
        };

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "nacos");
        args.put("nacos_data_id", "test.yml");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("401");
    }

    // ---- invalid source ----

    @Test
    @DisplayName("invalid source value returns error")
    void shouldReturnErrorForInvalidSource() {
        ConfigReadToolProvider provider = new ConfigReadToolProvider(config, env);

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source", "redis");
        ToolResult result = provider.execute(args, ctx());

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("invalid source");
    }

    // ---- helpers ----

    private ToolContext ctx() {
        return new ToolContext("task-1", "user-1", null);
    }
}
