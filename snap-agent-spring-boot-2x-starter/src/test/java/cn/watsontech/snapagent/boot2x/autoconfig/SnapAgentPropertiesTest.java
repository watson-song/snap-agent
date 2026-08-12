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
        assertThat(security.getRequiredPermission()).isEmpty();  // Default: no permission check
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

    // ---- 2.x new property groups ----

    @Test
    void shouldDefaultCheckpointToSqlite() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Checkpoint checkpoint = props.getCheckpoint();

        assertThat(checkpoint.getType()).isEqualTo("sqlite");
        assertThat(checkpoint.getSqlite().getPath()).isEqualTo("snap-agent-checkpoints.db");
        assertThat(checkpoint.getRedis().getTtlSeconds()).isEqualTo(604800);
    }

    @Test
    void shouldSetCheckpointProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Checkpoint checkpoint = props.getCheckpoint();

        checkpoint.setType("redis");
        checkpoint.getRedis().setTtlSeconds(86400);
        checkpoint.getSqlite().setPath("/custom/checkpoint.db");

        assertThat(checkpoint.getType()).isEqualTo("redis");
        assertThat(checkpoint.getRedis().getTtlSeconds()).isEqualTo(86400);
        assertThat(checkpoint.getSqlite().getPath()).isEqualTo("/custom/checkpoint.db");
    }

    @Test
    void shouldDefaultVectorStoreToDisabledRedis() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.VectorStore vs = props.getVectorStore();

        assertThat(vs.isEnabled()).isFalse();
        assertThat(vs.getType()).isEqualTo("redis");
        assertThat(vs.getRedis().getIndexKey()).isEqualTo("snap-agent-vectors");
        assertThat(vs.getJdbc().getTableName()).isEqualTo("snap_agent_vectors");
    }

    @Test
    void shouldSetVectorStoreProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.VectorStore vs = props.getVectorStore();

        vs.setEnabled(true);
        vs.setType("jdbc");
        vs.getJdbc().setTableName("custom_vectors");
        vs.getRedis().setIndexKey("custom-key");

        assertThat(vs.isEnabled()).isTrue();
        assertThat(vs.getType()).isEqualTo("jdbc");
        assertThat(vs.getJdbc().getTableName()).isEqualTo("custom_vectors");
        assertThat(vs.getRedis().getIndexKey()).isEqualTo("custom-key");
    }

    @Test
    void shouldDefaultEmbeddingToOpenAi() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Embedding emb = props.getEmbedding();

        assertThat(emb.getProvider()).isEqualTo("openai");
        assertThat(emb.getModel()).isEqualTo("text-embedding-3-small");
        assertThat(emb.getOllama().getBaseUrl()).isEqualTo("http://ollama:11434");
        assertThat(emb.getOllama().getModel()).isEqualTo("nomic-embed-text");
    }

    @Test
    void shouldSetEmbeddingProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Embedding emb = props.getEmbedding();

        emb.setProvider("ollama");
        emb.setModel("custom-model");
        emb.getOpenai().setApiKey("sk-test");
        emb.getOllama().setBaseUrl("http://custom:11434");

        assertThat(emb.getProvider()).isEqualTo("ollama");
        assertThat(emb.getModel()).isEqualTo("custom-model");
        assertThat(emb.getOpenai().getApiKey()).isEqualTo("sk-test");
        assertThat(emb.getOllama().getBaseUrl()).isEqualTo("http://custom:11434");
    }

    @Test
    void shouldDefaultRagToDisabled() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Rag rag = props.getRag();

        assertThat(rag.isEnabled()).isFalse();
        assertThat(rag.getTopK()).isEqualTo(4);
        assertThat(rag.getSimilarityThreshold()).isEqualTo(0.75);
        assertThat(rag.getFilterExpression()).isEmpty();
    }

    @Test
    void shouldSetRagProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Rag rag = props.getRag();

        rag.setEnabled(true);
        rag.setTopK(10);
        rag.setSimilarityThreshold(0.9);
        rag.setFilterExpression("type=doc");

        assertThat(rag.isEnabled()).isTrue();
        assertThat(rag.getTopK()).isEqualTo(10);
        assertThat(rag.getSimilarityThreshold()).isEqualTo(0.9);
        assertThat(rag.getFilterExpression()).isEqualTo("type=doc");
    }

    @Test
    void shouldDefaultMemoryToInMemory() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Memory memory = props.getMemory();

        assertThat(memory.getType()).isEqualTo("in-memory");
        assertThat(memory.getMaxMessages()).isEqualTo(50);
        assertThat(memory.getSummarizeThreshold()).isEqualTo(10);
        assertThat(memory.getRepositoryType()).isEqualTo("memory");
        assertThat(memory.getJdbc().getTableName()).isEqualTo("snap_agent_chat_memory");
    }

    @Test
    void shouldSetMemoryProperties() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Memory memory = props.getMemory();

        memory.setType("jdbc");
        memory.setMaxMessages(50);
        memory.getJdbc().setTableName("custom_memory");

        assertThat(memory.getType()).isEqualTo("jdbc");
        assertThat(memory.getMaxMessages()).isEqualTo(50);
        assertThat(memory.getJdbc().getTableName()).isEqualTo("custom_memory");
    }

    @Test
    void shouldDefaultCostMaxTokensPerRun() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Cost cost = props.getCost();

        assertThat(cost.isEnabled()).isFalse();
        assertThat(cost.getMaxTokensPerRun()).isEqualTo(100000);
    }

    @Test
    void shouldSetCostMaxTokensPerRun() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Cost cost = props.getCost();

        cost.setEnabled(true);
        cost.setMaxTokensPerRun(50000);

        assertThat(cost.isEnabled()).isTrue();
        assertThat(cost.getMaxTokensPerRun()).isEqualTo(50000);
    }

    // ---- Vcs config (v1.1 auto-fix) ----

    @Test
    void shouldDefaultVcsSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Vcs vcs = props.getVcs();

        assertThat(vcs.isEnabled()).isFalse();
        assertThat(vcs.getType()).isEqualTo("gitlab");
        assertThat(vcs.getDefaultBranch()).isEqualTo("main");
        assertThat(vcs.getGitlab().getBaseUrl()).isEmpty();
        assertThat(vcs.getGitlab().getToken()).isEmpty();
        assertThat(vcs.getGitlab().getProjectId()).isZero();
        assertThat(vcs.getBitbucket().getBaseUrl()).isEmpty();
        assertThat(vcs.getBitbucket().getProjectKey()).isEmpty();
        assertThat(vcs.getBitbucket().getRepoSlug()).isEmpty();
    }

    // ---- Fix config (v1.1 auto-fix) ----

    @Test
    void shouldDefaultFixSettings() {
        SnapAgentProperties props = new SnapAgentProperties();
        SnapAgentProperties.Fix fix = props.getFix();

        assertThat(fix.isEnabled()).isFalse();
        assertThat(fix.getMaxTurns()).isEqualTo(20);
        assertThat(fix.getTimeoutMinutes()).isEqualTo(10);
        assertThat(fix.getProjectRoot()).isEmpty();

        SnapAgentProperties.Fix.Guard guard = fix.getGuard();
        assertThat(guard.getIncludePaths()).hasSize(3);
        assertThat(guard.getExcludePaths()).hasSize(5);
        assertThat(guard.getAllowedExtensions()).contains(".java", ".xml", ".yml");
        assertThat(guard.getMaxFileCount()).isEqualTo(20);
        assertThat(guard.getMaxFileSize()).isEqualTo(512000L);

        SnapAgentProperties.Fix.Webhook webhook = fix.getWebhook();
        assertThat(webhook.getSecret()).isEmpty();
    }

    @Test
    void shouldSetVcsEnabled() {
        SnapAgentProperties props = new SnapAgentProperties();
        props.getVcs().setEnabled(true);
        props.getVcs().setType("bitbucket");

        assertThat(props.getVcs().isEnabled()).isTrue();
        assertThat(props.getVcs().getType()).isEqualTo("bitbucket");
    }

    @Test
    void shouldSetFixEnabled() {
        SnapAgentProperties props = new SnapAgentProperties();
        props.getFix().setEnabled(true);

        assertThat(props.getFix().isEnabled()).isTrue();
    }

    @Test
    void bridgeShouldBeInitializedByDefault() {
        SnapAgentProperties props = new SnapAgentProperties();

        assertThat(props.getBridge()).isNotNull();
        assertThat(props.getBridge().getRequestTimeoutMs()).isGreaterThan(0);
    }
}
