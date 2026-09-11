package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.ModuleArchitectureTools;
import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeTools;
import cn.watsontech.snapagent.boot2x.context.ProjectContextAdvisor;
import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.boot2x.tool.ConfigReadTools;
import cn.watsontech.snapagent.boot2x.tool.DataSourceRegistry;
import cn.watsontech.snapagent.boot2x.tool.GitLogTools;
import cn.watsontech.snapagent.boot2x.tool.JdbcQueryTools;
import cn.watsontech.snapagent.boot2x.tool.LogPathGuard;
import cn.watsontech.snapagent.boot2x.tool.LogReadTools;
import cn.watsontech.snapagent.boot2x.tool.LogSearchTools;
import cn.watsontech.snapagent.boot2x.tool.MetricsTools;
import cn.watsontech.snapagent.boot2x.tool.ProjectStructureTools;
import cn.watsontech.snapagent.boot2x.tool.RedisReadTools;
import cn.watsontech.snapagent.boot2x.tool.SqlGuard;
import cn.watsontech.snapagent.boot2x.tool.PluginUploader;
import cn.watsontech.snapagent.boot2x.tool.PluginMetadataScanner;
import cn.watsontech.snapagent.boot2x.tool.PluginConfigExtractor;
import cn.watsontech.snapagent.boot2x.tool.TraceSearchTools;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpBootstrap;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpSseClient;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpToolInfo;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpTools;
import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.boot2x.tool.CodeReadTool;
import cn.watsontech.snapagent.boot2x.bridge.FileBridgeService;

import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import okhttp3.OkHttpClient;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Auto-configuration for SnapAgent tool / plugin / MCP beans.
 *
 * <p>Activated only when {@code snap-agent.enabled=true} (default false).
 * Extracted from {@link SnapAgentAutoConfiguration} to keep tool wiring
 * (JdbcQueryTools, RedisReadTools, LogReadTools, CodeReadTool, etc.)
 * separate from the core agent / security / LLM beans.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class ToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolAutoConfiguration.class);

    // ---- DataSourceRegistry (multi-env, v0.6) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.jdbc", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DataSourceRegistry dataSourceRegistry(SnapAgentProperties props) {
        Map<String, SnapAgentProperties.Jdbc.Datasource> dsConfig = props.getJdbc().getDatasources();
        if (dsConfig == null || dsConfig.isEmpty()) {
            return null;
        }
        Map<String, DataSource> registry = new LinkedHashMap<String, DataSource>();
        for (Map.Entry<String, SnapAgentProperties.Jdbc.Datasource> entry : dsConfig.entrySet()) {
            SnapAgentProperties.Jdbc.Datasource cfg = entry.getValue();
            if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
                log.warn("Skipping datasource env '{}': url is empty", entry.getKey());
                continue;
            }
            try {
                DataSource ds = createSimpleDataSource(cfg);
                registry.put(entry.getKey(), ds);
                log.info("Registered datasource env '{}': url={}", entry.getKey(), cfg.getUrl());
            } catch (RuntimeException e) {
                log.error("Failed to create datasource for env '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        if (registry.isEmpty()) {
            log.warn("No valid datasource environments configured; falling back to single DataSource mode");
            return null;
        }
        log.info("DataSourceRegistry assembled with {} env(s): {}", registry.size(), registry.keySet());
        return new DataSourceRegistry(registry, props.getJdbc().getDefaultEnv());
    }

    /**
     * Creates a simple DataSource from config properties.
     * Uses Spring's SimpleDriverDataSource (no pool overhead, suitable for read-only diagnostics).
     */
    private DataSource createSimpleDataSource(SnapAgentProperties.Jdbc.Datasource cfg) {
        String driverClassName = cfg.getDriverClassName();
        if (driverClassName == null || driverClassName.isEmpty()) {
            driverClassName = "com.mysql.cj.jdbc.Driver";
        }
        try {
            Class<?> driverClass = Class.forName(driverClassName);
            java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
            org.springframework.jdbc.datasource.SimpleDriverDataSource ds =
                    new org.springframework.jdbc.datasource.SimpleDriverDataSource();
            ds.setDriver(driver);
            ds.setUrl(cfg.getUrl());
            ds.setUsername(cfg.getUsername());
            ds.setPassword(cfg.getPassword());
            return ds;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("JDBC driver class not found: " + driverClassName
                    + " — add the driver dependency to your project", e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Failed to instantiate JDBC driver: " + driverClassName, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate JDBC driver: " + driverClassName, e);
        }
    }

    // ---- JdbcQueryTools ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.jdbc", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public JdbcQueryTools jdbcQueryTools(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DataSourceRegistry> registryProvider,
            SnapAgentProperties props,
            SqlGuard sqlGuard) {
        DataSourceRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            log.info("JdbcQueryTools assembled with DataSourceRegistry ({} envs)", registry.size());
            return new JdbcQueryTools(registry, sqlGuard);
        }
        // Use lazy constructor: defers DataSource resolution to query time
        // to avoid auto-configuration ordering issues with @ConditionalOnBean
        log.info("JdbcQueryTools assembled with lazy DataSource resolution (bean name='{}')",
                props.getJdbc().getDatasourceBeanName());
        return new JdbcQueryTools(dataSourceProvider, sqlGuard);
    }

    // ---- RedisReadTools ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.redis", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            type = "org.springframework.data.redis.core.RedisTemplate")
    @ConditionalOnMissingBean
    public RedisReadTools redisReadTools(
            org.springframework.beans.factory.BeanFactory beanFactory,
            SnapAgentProperties props) {
        String beanName = props.getRedis().getRedisTemplateBeanName();
        org.springframework.data.redis.core.RedisTemplate template =
                beanFactory.getBean(beanName, org.springframework.data.redis.core.RedisTemplate.class);
        log.info("RedisReadTools assembled with RedisTemplate bean '{}'", beanName);
        return new RedisReadTools(template);
    }

    // ---- LogPathGuard ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.logs", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LogPathGuard logPathGuard(SnapAgentProperties props) {
        log.info("LogPathGuard assembled with {} allowed path(s)",
                props.getLogs().getAllowedPaths().size());
        return new LogPathGuard(props.getLogs().getAllowedPaths(),
                props.getLogs().getMaxLines(),
                props.getLogs().getMaxFileBytes());
    }

    // ---- LogReadTools ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.logs", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LogReadTools logReadTools(LogPathGuard logPathGuard) {
        log.info("LogReadTools assembled");
        return new LogReadTools(logPathGuard);
    }

    // ---- CodePathGuard (v0.3) ----
    // Created only when code.enabled=true AND project-root is non-empty AND is a real directory.
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${snap-agent.code.enabled:false}' == 'true' "
            + "and !'${snap-agent.code.project-root:}'.trim().isEmpty()")
    @ConditionalOnMissingBean
    public CodePathGuard codePathGuard(SnapAgentProperties props) {
        String root = props.getCode().getProjectRoot();
        Path rootPath = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            log.warn("CodePathGuard not assembled: project-root {} is not a directory", rootPath);
            return null;
        }
        log.info("CodePathGuard assembled with project-root: {}", rootPath);
        return new CodePathGuard(root, props.getCode().getAllowedExtensions(),
                props.getCode().getMaxLines(), props.getCode().getMaxFileBytes());
    }

    // ---- ProjectContextAdvisor (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.code", name = "context-injection",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public ProjectContextAdvisor projectContextAdvisor(CodePathGuard codePathGuard,
                                                        SnapAgentProperties props) {
        log.info("ProjectContextAdvisor assembled (structure-depth={})",
                props.getCode().getStructureDepth());
        return new ProjectContextAdvisor(codePathGuard);
    }

    // ---- ProjectStructureTools (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public ProjectStructureTools projectStructureTools(CodePathGuard codePathGuard) {
        log.info("ProjectStructureTools assembled");
        return new ProjectStructureTools(codePathGuard);
    }

    // ---- GitLogTools (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public GitLogTools gitLogTools(CodePathGuard codePathGuard) {
        log.info("GitLogTools assembled");
        return new GitLogTools(codePathGuard);
    }

    // ---- MetricsTools (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.metrics", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public MetricsTools metricsTools(SnapAgentProperties props) {
        log.info("MetricsTools assembled (base-url={})", props.getMetrics().getBaseUrl());
        return new MetricsTools(props.getMetrics());
    }

    // ---- LogSearchTools (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.log-search", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LogSearchTools logSearchTools(SnapAgentProperties props) {
        log.info("LogSearchTools assembled (base-url={})", props.getLogSearch().getBaseUrl());
        return new LogSearchTools(props.getLogSearch());
    }

    // ---- TraceSearchTools (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.trace", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public TraceSearchTools traceSearchTools(SnapAgentProperties props) {
        log.info("TraceSearchTools assembled (base-url={})", props.getTrace().getBaseUrl());
        return new TraceSearchTools(props.getTrace());
    }

    // ---- ConfigReadTools (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.config-read", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ConfigReadTools configReadTools(
            SnapAgentProperties props,
            org.springframework.core.env.Environment environment) {
        log.info("ConfigReadTools assembled");
        return new ConfigReadTools(props.getConfigRead(), environment);
    }

    // ---- PluginRegistry (v0.5) ----
    // Wraps every built-in *Tools bean as a system plugin so the
    // ToolCallbackRegistry routes via PluginRegistry instead of a static map.
    @Bean
    @ConditionalOnMissingBean
    public PluginRegistry pluginRegistry(
            ObjectProvider<JdbcQueryTools> jdbcTools,
            ObjectProvider<RedisReadTools> redisTools,
            ObjectProvider<LogReadTools> logReadTools,
            ObjectProvider<ProjectStructureTools> projectStructureTools,
            ObjectProvider<GitLogTools> gitLogTools,
            ObjectProvider<MetricsTools> metricsTools,
            ObjectProvider<LogSearchTools> logSearchTools,
            ObjectProvider<TraceSearchTools> traceSearchTools,
            ObjectProvider<ConfigReadTools> configReadTools,
            ObjectProvider<CodeGraphTools> codeGraphTools,
            ObjectProvider<ModuleArchitectureTools> moduleArchTools,
            ObjectProvider<DomainKnowledgeTools> domainKnowledgeTools,
            ObjectProvider<CodeReadTool> codeReadTool,
            ObjectProvider<McpBootstrap> mcpBootstrapProvider) {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        // Collect all tools beans and register their ToolCallbacks
        List<Object> toolsBeans = new ArrayList<>();
        addIfAvailable(toolsBeans, jdbcTools);
        addIfAvailable(toolsBeans, redisTools);
        addIfAvailable(toolsBeans, logReadTools);
        addIfAvailable(toolsBeans, codeReadTool);
        addIfAvailable(toolsBeans, projectStructureTools);
        addIfAvailable(toolsBeans, gitLogTools);
        addIfAvailable(toolsBeans, metricsTools);
        addIfAvailable(toolsBeans, logSearchTools);
        addIfAvailable(toolsBeans, traceSearchTools);
        addIfAvailable(toolsBeans, configReadTools);
        addIfAvailable(toolsBeans, codeGraphTools);
        addIfAvailable(toolsBeans, moduleArchTools);
        addIfAvailable(toolsBeans, domainKnowledgeTools);

        McpBootstrap mcp = mcpBootstrapProvider.getIfAvailable();
        if (mcp != null) {
            for (ToolCallback cb : mcp.getCallbacks()) {
                PluginDescriptor desc = new PluginDescriptor(
                    cb.getName(), cb.getName(), cb.getName(), "", "built-in",
                    true, true, true, new ToolCallback[]{cb}, null, null, null);
                registry.register(desc);
            }
        }

        for (Object tools : toolsBeans) {
            if (tools == null) continue;
            ToolCallback[] callbacks = ToolCallbacks.from(tools);
            for (ToolCallback cb : callbacks) {
                PluginDescriptor desc = new PluginDescriptor(
                    cb.getName(), cb.getName(), cb.getName(), "", "built-in",
                    true, true, true, new ToolCallback[]{cb}, null, null, null);
                registry.register(desc);
            }
        }
        log.info("PluginRegistry assembled with {} tool(s)", registry.listPlugins().size());
        return registry;
    }

    private void addIfAvailable(List<Object> list, ObjectProvider<?> provider) {
        Object bean = provider.getIfAvailable();
        if (bean != null) list.add(bean);
    }

    // ---- ToolCallbackRegistry ----
    @Bean
    @ConditionalOnMissingBean
    public ToolCallbackRegistry toolCallbackRegistry(PluginRegistry pluginRegistry) {
        // PluginRegistry's default ToolCallbackRegistry implementation
        return new ToolCallbackRegistry() {
            @Override
            public ToolCallback find(String name) {
                for (PluginDescriptor desc : pluginRegistry.listPlugins()) {
                    if (desc.isEnabled() && desc.getToolCallbacks() != null) {
                        for (ToolCallback cb : desc.getToolCallbacks()) {
                            if (cb.getName().equals(name)) return cb;
                        }
                    }
                }
                return null;
            }
            @Override
            public List<ToolCallback> getAll() {
                List<ToolCallback> all = new ArrayList<>();
                for (PluginDescriptor desc : pluginRegistry.listPlugins()) {
                    if (desc.isEnabled() && desc.getToolCallbacks() != null) {
                        for (ToolCallback cb : desc.getToolCallbacks()) {
                            all.add(cb);
                        }
                    }
                }
                return all;
            }
            @Override
            public String toToolDefinitionsJson() {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (ToolCallback cb : getAll()) {
                    if (!first) sb.append(",");
                    sb.append("{\"name\":\"").append(cb.getName()).append("\",\"schema\":")
                      .append(cb.getJsonSchema()).append("}");
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }
        };
    }

    // ---- PluginUploader (v0.5) ----
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "snap-agent.tools", name = "plugin-upload-enabled", havingValue = "true", matchIfMissing = true)
    public PluginUploader pluginUploader(
            SnapAgentProperties props,
            PluginRegistry pluginRegistry,
            org.springframework.core.env.Environment environment) {
        java.nio.file.Path uploadDir = java.nio.file.Paths.get(
                props.getUploadSkillsDir(), "plugins");
        uploadDir.toFile().mkdirs();
        PluginMetadataScanner scanner = new PluginMetadataScanner();
        PluginConfigExtractor configExtractor = new PluginConfigExtractor();
        log.info("PluginUploader assembled, upload dir: {}", uploadDir);
        return new PluginUploader(uploadDir, pluginRegistry, scanner, configExtractor, environment);
    }

    // ---- McpBootstrap (MCP server discovery) ----
    // When snap-agent.mcp.enabled=true, connect to each configured MCP server,
    // discover tools, register each McpTools as an individual singleton on
    // the bean factory (so ObjectProvider<ToolCallback> can see them), and hold
    // them in McpBootstrap so toolCallbackRegistry can add them explicitly too. When
    // MCP is disabled (the default), this bean is not created and toolCallbackRegistry
    // behaves exactly as before.
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.mcp", name = "enabled", havingValue = "true")
    public McpBootstrap mcpBootstrap(SnapAgentProperties props,
                                     ConfigurableListableBeanFactory beanFactory) {
        McpBootstrap bootstrap = new McpBootstrap();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        for (Map.Entry<String, SnapAgentProperties.McpServer> entry
                : props.getMcp().getServers().entrySet()) {
            String serverName = entry.getKey();
            SnapAgentProperties.McpServer server = entry.getValue();
            if (server.getTransport() != null && !"sse".equals(server.getTransport())) {
                log.warn("MCP server {} uses unsupported transport '{}', skipping",
                        serverName, server.getTransport());
                continue;
            }
            try {
                McpSseClient client = new McpSseClient(
                        server.getUrl(), server.getAuthHeader(),
                        server.getAuthHeaderValue(), httpClient);
                List<McpToolInfo> tools = client.connect();
                for (McpToolInfo tool : tools) {
                    ToolCallback callback = McpTools.from(
                            serverName, tool.getName(), tool.getDescription(),
                            tool.getInputSchema(), client);
                    String beanName = "mcpTool_" + serverName + "_" + tool.getName();
                    beanFactory.registerSingleton(beanName, callback);
                    bootstrap.addCallback(callback);
                }
                log.info("Registered {} MCP tools from server '{}'", tools.size(), serverName);
            } catch (Exception e) {
                log.error("Failed to connect to MCP server '{}': {}", serverName, e.getMessage());
            }
        }
        return bootstrap;
    }

    // ---- Code Read Tool ----
    @Bean
    @ConditionalOnMissingBean
    public CodeReadTool codeReadTool(ObjectProvider<FileBridgeService> fileBridgeServiceProvider,
                                    SnapAgentProperties props) {
        String projectRoot = props.getCode().getProjectRoot();
        // Fallback to user.dir when code.project-root is empty (standalone/local dev)
        if (projectRoot == null || projectRoot.trim().isEmpty()) {
            projectRoot = System.getProperty("user.dir");
        }
        FileBridgeService fbs = fileBridgeServiceProvider.getIfAvailable();
        if (fbs != null) {
            return new CodeReadTool(fbs, projectRoot);
        }
        return new CodeReadTool(projectRoot);
    }
}

