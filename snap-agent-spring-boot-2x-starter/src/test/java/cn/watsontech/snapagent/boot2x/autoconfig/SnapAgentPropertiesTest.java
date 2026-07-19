package cn.watsontech.snapagent.boot2x.autoconfig;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapAgentProperties} default values and nested bindings.
 */
class SnapAgentPropertiesTest {

    @Test
    void shouldDefaultEnabledToFalse() {
        SnapAgentProperties props = new SnapAgentProperties();

        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void shouldDefaultBasePathToSnapAgent() {
        SnapAgentProperties props = new SnapAgentProperties();

        assertThat(props.getBasePath()).isEqualTo("/snap-agent");
    }

    @Test
    void shouldDefaultBuiltinSkillsDirToClasspathDocsSkills() {
        SnapAgentProperties props = new SnapAgentProperties();

        assertThat(props.getBuiltinSkillsDir()).isEqualTo("classpath*:/docs/skills/");
    }

    @Test
    void shouldDefaultUploadSkillsDirToTmpSnapAgentSkills() {
        SnapAgentProperties props = new SnapAgentProperties();

        assertThat(props.getUploadSkillsDir()).isEqualTo("/tmp/snap-agent-skills");
    }

    @Test
    void shouldDefaultLlmSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Llm llm = props.getLlm();

        assertThat(llm.getApiType()).isEqualTo("anthropic");
        assertThat(llm.getBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(llm.getApiKey()).isEmpty();
        assertThat(llm.getProxyUrl()).isEmpty();
        assertThat(llm.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(llm.getAllowedModels()).isEmpty();
        assertThat(llm.getMaxTokens()).isEqualTo(8192);
        assertThat(llm.getTimeoutSeconds()).isEqualTo(120);
        assertThat(llm.isStreaming()).isTrue();
    }

    @Test
    void shouldDefaultAgentSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Agent agent = props.getAgent();

        assertThat(agent.getMaxTurns()).isEqualTo(20);
        assertThat(agent.getTaskTimeoutMinutes()).isEqualTo(30);
        assertThat(agent.getExecutor()).isEqualTo("snapAgentExecutor");
        assertThat(agent.getMaxConcurrentRunsPerUser()).isEqualTo(1);
        assertThat(agent.getMaxRunsPerHour()).isEqualTo(20);
        assertThat(agent.getMaxResultRows()).isEqualTo(1000);
        assertThat(agent.getMaxToolResultChars()).isEqualTo(50000);
        assertThat(agent.getTranscriptEventLimit()).isEqualTo(500);
    }

    @Test
    void shouldDefaultJdbcSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Jdbc jdbc = props.getJdbc();

        assertThat(jdbc.isEnabled()).isTrue();
        assertThat(jdbc.getDatasourceBeanName()).isEqualTo("snapAgentReadOnlyDataSource");
    }

    @Test
    void shouldDefaultRedisSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Redis redis = props.getRedis();

        assertThat(redis.isEnabled()).isTrue();
        assertThat(redis.getRedisTemplateBeanName()).isEqualTo("redisTemplate");
        assertThat(redis.getMaxKeyCount()).isEqualTo(100);
    }

    @Test
    void shouldDefaultMcpSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Mcp mcp = props.getMcp();

        assertThat(mcp.isEnabled()).isFalse();
        assertThat(mcp.getServers()).isEmpty();
    }

    @Test
    void shouldDefaultLogsSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Logs logs = props.getLogs();

        assertThat(logs.isEnabled()).isTrue();
        assertThat(logs.getAllowedPaths()).isEmpty();
        assertThat(logs.getMaxLines()).isEqualTo(500);
        assertThat(logs.getMaxFileBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void shouldDefaultSecuritySettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Security security = props.getSecurity();

        assertThat(security.getFramework()).isEqualTo("auto");
        assertThat(security.getRequiredPermission()).isEqualTo("snap-agent:access");
        assertThat(security.getFilterOrder()).isEqualTo(Integer.MAX_VALUE - 10);
        assertThat(security.getPrincipalResolverClass()).isEmpty();
        assertThat(security.isAuditLog()).isTrue();
    }

    @Test
    void shouldAllowSettingValues() {
        SnapAgentProperties props = new SnapAgentProperties();
        props.setEnabled(true);
        props.setBasePath("/custom-agent");
        props.getLlm().setApiKey("sk-test");
        props.getAgent().setMaxResultRows(500);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getBasePath()).isEqualTo("/custom-agent");
        assertThat(props.getLlm().getApiKey()).isEqualTo("sk-test");
        assertThat(props.getAgent().getMaxResultRows()).isEqualTo(500);
    }

    @Test
    void shouldSetAndVerifyAllLlmProperties() {
        SnapAgentProperties.Llm llm = new SnapAgentProperties.Llm();
        llm.setApiType("openai");
        llm.setBaseUrl("https://custom.api.com");
        llm.setApiKey("sk-custom");
        llm.setProxyUrl("http://proxy.example.com:3128");
        llm.setModel("custom-model");
        llm.setAllowedModels(Arrays.asList("custom-model"));
        llm.setMaxTokens(4096);
        llm.setTimeoutSeconds(60);
        llm.setStreaming(false);

        assertThat(llm.getApiType()).isEqualTo("openai");
        assertThat(llm.getBaseUrl()).isEqualTo("https://custom.api.com");
        assertThat(llm.getApiKey()).isEqualTo("sk-custom");
        assertThat(llm.getProxyUrl()).isEqualTo("http://proxy.example.com:3128");
        assertThat(llm.getModel()).isEqualTo("custom-model");
        assertThat(llm.getAllowedModels()).containsExactly("custom-model");
        assertThat(llm.getMaxTokens()).isEqualTo(4096);
        assertThat(llm.getTimeoutSeconds()).isEqualTo(60);
        assertThat(llm.isStreaming()).isFalse();
    }

    @Test
    void shouldSetAndVerifyAllAgentProperties() {
        SnapAgentProperties.Agent agent = new SnapAgentProperties.Agent();
        agent.setMaxTurns(10);
        agent.setTaskTimeoutMinutes(15);
        agent.setExecutor("customExecutor");
        agent.setMaxConcurrentRunsPerUser(3);
        agent.setMaxRunsPerHour(50);
        agent.setMaxResultRows(500);
        agent.setMaxToolResultChars(10000);
        agent.setTranscriptEventLimit(100);

        assertThat(agent.getMaxTurns()).isEqualTo(10);
        assertThat(agent.getTaskTimeoutMinutes()).isEqualTo(15);
        assertThat(agent.getExecutor()).isEqualTo("customExecutor");
        assertThat(agent.getMaxConcurrentRunsPerUser()).isEqualTo(3);
        assertThat(agent.getMaxRunsPerHour()).isEqualTo(50);
        assertThat(agent.getMaxResultRows()).isEqualTo(500);
        assertThat(agent.getMaxToolResultChars()).isEqualTo(10000);
        assertThat(agent.getTranscriptEventLimit()).isEqualTo(100);
    }

    @Test
    void shouldSetAndVerifyAllJdbcProperties() {
        SnapAgentProperties.Jdbc jdbc = new SnapAgentProperties.Jdbc();
        jdbc.setEnabled(false);
        jdbc.setDatasourceBeanName("customDataSource");

        assertThat(jdbc.isEnabled()).isFalse();
        assertThat(jdbc.getDatasourceBeanName()).isEqualTo("customDataSource");
    }

    @Test
    void shouldSetAndVerifyAllRedisProperties() {
        SnapAgentProperties.Redis redis = new SnapAgentProperties.Redis();
        redis.setEnabled(false);
        redis.setRedisTemplateBeanName("customTemplate");
        redis.setMaxKeyCount(50);

        assertThat(redis.isEnabled()).isFalse();
        assertThat(redis.getRedisTemplateBeanName()).isEqualTo("customTemplate");
        assertThat(redis.getMaxKeyCount()).isEqualTo(50);
    }

    @Test
    void shouldSetAndVerifyAllLogsProperties() {
        SnapAgentProperties.Logs logs = new SnapAgentProperties.Logs();
        logs.setEnabled(false);
        logs.setAllowedPaths(Arrays.asList("/app/logs", "/var/log"));
        logs.setMaxLines(200);
        logs.setMaxFileBytes(5L * 1024 * 1024);

        assertThat(logs.isEnabled()).isFalse();
        assertThat(logs.getAllowedPaths()).containsExactly("/app/logs", "/var/log");
        assertThat(logs.getMaxLines()).isEqualTo(200);
        assertThat(logs.getMaxFileBytes()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void shouldSetAndVerifyAllMcpProperties() {
        SnapAgentProperties.Mcp mcp = new SnapAgentProperties.Mcp();
        mcp.setEnabled(true);

        Map<String, SnapAgentProperties.McpServer> servers = new LinkedHashMap<String, SnapAgentProperties.McpServer>();
        SnapAgentProperties.McpServer server = new SnapAgentProperties.McpServer();
        server.setTransport("http");
        server.setUrl("http://localhost:3000");
        server.setAuthHeader("X-API-Key");
        server.setAuthHeaderValue("secret");
        servers.put("local", server);
        mcp.setServers(servers);

        assertThat(mcp.isEnabled()).isTrue();
        assertThat(mcp.getServers()).hasSize(1);
        SnapAgentProperties.McpServer retrieved = mcp.getServers().get("local");
        assertThat(retrieved.getTransport()).isEqualTo("http");
        assertThat(retrieved.getUrl()).isEqualTo("http://localhost:3000");
        assertThat(retrieved.getAuthHeader()).isEqualTo("X-API-Key");
        assertThat(retrieved.getAuthHeaderValue()).isEqualTo("secret");
    }

    @Test
    void shouldSetAndVerifyAllSecurityProperties() {
        SnapAgentProperties.Security security = new SnapAgentProperties.Security();
        security.setFramework("shiro");
        security.setRequiredPermission("skills:run");
        security.setFilterOrder(100);
        security.setPrincipalResolverClass("com.example.MyResolver");
        security.setAuditLog(false);

        assertThat(security.getFramework()).isEqualTo("shiro");
        assertThat(security.getRequiredPermission()).isEqualTo("skills:run");
        assertThat(security.getFilterOrder()).isEqualTo(100);
        assertThat(security.getPrincipalResolverClass()).isEqualTo("com.example.MyResolver");
        assertThat(security.isAuditLog()).isFalse();
    }

    @Test
    void shouldSetAndVerifyTopLevelProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        props.setBuiltinSkillsDir("/custom/builtin");
        props.setUploadSkillsDir("/custom/upload");
        props.setLlm(new SnapAgentProperties.Llm());
        props.setAgent(new SnapAgentProperties.Agent());
        props.setJdbc(new SnapAgentProperties.Jdbc());
        props.setRedis(new SnapAgentProperties.Redis());
        props.setLogs(new SnapAgentProperties.Logs());
        props.setMcp(new SnapAgentProperties.Mcp());
        props.setSecurity(new SnapAgentProperties.Security());

        assertThat(props.getBuiltinSkillsDir()).isEqualTo("/custom/builtin");
        assertThat(props.getUploadSkillsDir()).isEqualTo("/custom/upload");
        assertThat(props.getLlm()).isNotNull();
        assertThat(props.getAgent()).isNotNull();
        assertThat(props.getJdbc()).isNotNull();
        assertThat(props.getRedis()).isNotNull();
        assertThat(props.getLogs()).isNotNull();
        assertThat(props.getMcp()).isNotNull();
        assertThat(props.getSecurity()).isNotNull();
    }
}
