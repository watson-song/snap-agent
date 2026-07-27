package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.conversation.FileConversationStore;
import cn.watsontech.snapagent.boot2x.context.ProjectContextAdvisor;
import cn.watsontech.snapagent.boot2x.cost.BudgetEnforcer;
import cn.watsontech.snapagent.boot2x.cost.CostCalculator;
import cn.watsontech.snapagent.boot2x.cost.CostSummaryService;
import cn.watsontech.snapagent.boot2x.cost.CostTrackingLlmClient;
import cn.watsontech.snapagent.boot2x.cost.DefaultCostTracker;
import cn.watsontech.snapagent.boot2x.cost.FileCostStore;
import cn.watsontech.snapagent.boot2x.issue.FileIssueStore;
import cn.watsontech.snapagent.boot2x.issue.GitHubIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.IssueClosureService;
import cn.watsontech.snapagent.boot2x.issue.JiraIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.NoopIssueTracker;
import cn.watsontech.snapagent.boot2x.issue.SimpleVerificationRunner;
import cn.watsontech.snapagent.boot2x.issue.TemplateSolutionSuggester;
import cn.watsontech.snapagent.boot2x.issue.ZentaoIssueTracker;
import cn.watsontech.snapagent.boot2x.llm.AnthropicLlmClient;
import cn.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.NoopPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import cn.watsontech.snapagent.boot2x.routing.StaticPeerRouter;
import cn.watsontech.snapagent.boot2x.security.AuditStoreAuditLogger;
import cn.watsontech.snapagent.boot2x.security.DefaultPrincipalResolver;
import cn.watsontech.snapagent.boot2x.security.InMemoryAuditStore;
import cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import cn.watsontech.snapagent.boot2x.skill.ClasspathSkillScanner;
import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.boot2x.tool.CodeReaderTools;
import cn.watsontech.snapagent.boot2x.tool.ConfigReadTools;
import cn.watsontech.snapagent.boot2x.tool.DataSourceRegistry;
import cn.watsontech.snapagent.boot2x.tool.GitLogTools;
import cn.watsontech.snapagent.boot2x.skill.SkillHotReloader;
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
import cn.watsontech.snapagent.boot2x.tool.ToolPluginRegistry;
import cn.watsontech.snapagent.boot2x.tool.TraceSearchTools;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpBootstrap;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpSseClient;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpToolInfo;
import cn.watsontech.snapagent.boot2x.tool.mcp.McpTools;
import cn.watsontech.snapagent.boot2x.anchor.AnchorContextSummarizer;
import cn.watsontech.snapagent.boot2x.anchor.AnchorInjectionCache;
import cn.watsontech.snapagent.boot2x.anchor.AnchorInjectionOrchestrator;
import cn.watsontech.snapagent.boot2x.anchor.AnchorOrchestrator;
import cn.watsontech.snapagent.boot2x.anchor.AnchorSkillClassifier;
import cn.watsontech.snapagent.boot2x.anchor.AnchorSummaryCache;
import cn.watsontech.snapagent.boot2x.web.InternalTaskController;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.boot2x.web.SnapAgentFilter;
import cn.watsontech.snapagent.boot2x.workflow.SimpleWorkflowEngine;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.boot2x.conversation.ConversationStore;
import cn.watsontech.snapagent.core.cost.CostStore;
import cn.watsontech.snapagent.core.cost.CostTracker;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.AuditStore;
import cn.watsontech.snapagent.core.security.PrincipalResolver;
import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbacks;
import cn.watsontech.snapagent.core.tool.ToolPlugin;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import okhttp3.OkHttpClient;

import javax.servlet.Filter;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Auto-configuration for the embedded SnapAgent.
 *
 * <p>Activated only when {@code snap-agent.enabled=true} (default false).
 * When disabled, zero beans are created — no filter, no thread pool, no routes
 * (TDD_SPEC §AC15, design doc 01 §4).</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
public class SnapAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SnapAgentAutoConfiguration.class);

    // ---- SqlGuard ----
    @Bean
    @ConditionalOnMissingBean
    public SqlGuard sqlGuard(SnapAgentProperties props) {
        return new SqlGuard(props.getAgent().getMaxResultRows());
    }

    // ---- TaskStore ----
    @Bean
    @ConditionalOnMissingBean
    public TaskStore taskStore() {
        return new TaskStore();
    }

    // ---- RateLimiter ----
    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(SnapAgentProperties props) {
        return new RateLimiter(
                props.getAgent().getMaxConcurrentRunsPerUser(),
                props.getAgent().getMaxRunsPerHour());
    }

    // ---- PrincipalResolver ----
    @Bean
    @ConditionalOnMissingBean
    public PrincipalResolver principalResolver() {
        return new DefaultPrincipalResolver();
    }

    // ---- AuditStore (in-memory ring buffer) ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.security", name = "audit-log", havingValue = "true", matchIfMissing = true)
    public AuditStore auditStore() {
        log.info("Using InMemoryAuditStore (capacity=1000) for audit entries");
        return new InMemoryAuditStore(1000);
    }

    // ---- SecurityAuditLogger (bridged to AuditStore + SLF4J) ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.security", name = "audit-log", havingValue = "true", matchIfMissing = true)
    public SecurityAuditLogger securityAuditLogger(AuditStore auditStore) {
        log.info("Using AuditStoreAuditLogger (audit store + SLF4J)");
        return new AuditStoreAuditLogger(auditStore);
    }

    // ---- SecurityGateway: Spring Security adapter ----
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean(SecurityGateway.class)
    public SecurityGateway springSecurityAdapter(PrincipalResolver principalResolver) {
        log.info("Using SpringSecurityAdapter for SecurityGateway");
        return new SpringSecurityAdapter(principalResolver);
    }

    // ---- SecurityGateway: Shiro adapter (fallback) ----
    @Bean
    @ConditionalOnClass(name = "org.apache.shiro.SecurityUtils")
    @ConditionalOnMissingBean(SecurityGateway.class)
    public SecurityGateway shiroAdapter(PrincipalResolver principalResolver) {
        log.info("Using ShiroAdapter for SecurityGateway");
        return new cn.watsontech.snapagent.boot2x.security.ShiroAdapter(principalResolver);
    }

    // ---- LlmClient ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${snap-agent.llm.api-key:}' != '' or '${snap-agent.llm.auth-token:}' != ''")
    public LlmClient llmClient(SnapAgentProperties props) {
        String apiType = props.getLlm().getApiType();
        if ("openai".equalsIgnoreCase(apiType)) {
            log.info("Using OpenAiLlmClient (api-type=openai, base-url={})", props.getLlm().getBaseUrl());
            return new cn.watsontech.snapagent.boot2x.llm.OpenAiLlmClient(
                    props.getLlm().getBaseUrl(),
                    props.getLlm().getApiKey(),
                    props.getLlm().getAuthToken(),
                    props.getLlm().getProxyUrl(),
                    props.getLlm().getTimeoutSeconds());
        }
        log.info("Using AnthropicLlmClient (api-type=anthropic, base-url={})", props.getLlm().getBaseUrl());
        return new AnthropicLlmClient(
                props.getLlm().getBaseUrl(),
                props.getLlm().getApiKey(),
                props.getLlm().getAuthToken(),
                props.getLlm().getProxyUrl(),
                props.getLlm().getTimeoutSeconds(),
                props.getLlm().getModel());
    }

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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
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
        DataSource ds = dataSourceProvider.getIfAvailable();
        log.info("JdbcQueryTools assembled with single DataSource: {}",
                props.getJdbc().getDatasourceBeanName());
        return new JdbcQueryTools(ds, sqlGuard);
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

    // ---- CodeReaderTools (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public CodeReaderTools codeReaderTools(CodePathGuard codePathGuard) {
        log.info("CodeReaderTools assembled");
        return new CodeReaderTools(codePathGuard);
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
            ObjectProvider<CodeReaderTools> codeReaderTools,
            ObjectProvider<ProjectStructureTools> projectStructureTools,
            ObjectProvider<GitLogTools> gitLogTools,
            ObjectProvider<MetricsTools> metricsTools,
            ObjectProvider<LogSearchTools> logSearchTools,
            ObjectProvider<TraceSearchTools> traceSearchTools,
            ObjectProvider<ConfigReadTools> configReadTools,
            ObjectProvider<McpBootstrap> mcpBootstrapProvider) {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();
        // Collect all tools beans and register their ToolCallbacks
        List<Object> toolsBeans = new ArrayList<>();
        addIfAvailable(toolsBeans, jdbcTools);
        addIfAvailable(toolsBeans, redisTools);
        addIfAvailable(toolsBeans, logReadTools);
        addIfAvailable(toolsBeans, codeReaderTools);
        addIfAvailable(toolsBeans, projectStructureTools);
        addIfAvailable(toolsBeans, gitLogTools);
        addIfAvailable(toolsBeans, metricsTools);
        addIfAvailable(toolsBeans, logSearchTools);
        addIfAvailable(toolsBeans, traceSearchTools);
        addIfAvailable(toolsBeans, configReadTools);

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

    // ---- ClasspathSkillScanner (builtin skills) ----
    @Bean
    @ConditionalOnMissingBean
    public ClasspathSkillScanner classpathSkillScanner() {
        return new ClasspathSkillScanner();
    }

    // ---- ConversationStore (conversation history persistence) ----
    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(SnapAgentProperties props) {
        String baseDir = props.getUploadSkillsDir();
        log.info("Using FileConversationStore with base dir: {}", baseDir);
        return new FileConversationStore(baseDir);
    }

    // ---- SkillRegistry ----
    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(SnapAgentProperties props, ToolCallbackRegistry toolCallbackRegistry,
                                       ClasspathSkillScanner classpathSkillScanner) {
        // Scan classpath for builtin skills
        List<cn.watsontech.snapagent.core.skill.SkillMeta> builtinSkills =
                classpathSkillScanner.scan(props.getBuiltinSkillsDir());

        // Resolve upload dir (filesystem, read-write)
        Path uploadDir = resolveUploadDir(props.getUploadSkillsDir());

        return new SkillRegistry(uploadDir, builtinSkills, toolCallbackRegistry);
    }

    // ---- SkillHotReloader (auto-refresh on file change) ----
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "snap-agent.skill", name = "hot-reload",
            havingValue = "true", matchIfMissing = true)
    public SkillHotReloader skillHotReloader(SkillRegistry skillRegistry, SnapAgentProperties props) {
        Path uploadDir = resolveUploadDir(props.getUploadSkillsDir());
        log.info("SkillHotReloader assembled, watching upload-skills-dir: {}", uploadDir);
        return new SkillHotReloader(uploadDir, skillRegistry, 1000);
    }

    // ---- AgentService ----
    @Bean
    @ConditionalOnMissingBean
    public AgentService agentService(
            ObjectProvider<LlmClient> llmClientProvider,
            ToolCallbackRegistry toolCallbackRegistry,
            TaskStore taskStore,
            SnapAgentProperties props,
            ObjectProvider<Advisor> advisorProvider,
            ObjectProvider<CostTracker> costTrackerProvider,
            ObjectProvider<CostCalculator> costCalculatorProvider) {
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            log.warn("LlmClient not available; AgentService will not function");
        }
        // Wrap LlmClient with CostTrackingLlmClient when cost tracking is enabled
        CostTracker costTracker = costTrackerProvider.getIfAvailable();
        if (llmClient != null && costTracker != null && props.getCost().isEnabled()) {
            CostCalculator costCalculator = costCalculatorProvider.getIfAvailable();
            if (costCalculator == null) {
                // Fall back to a default calculator if the bean is missing
                SnapAgentProperties.Cost.Pricing pricing = props.getCost().getPricing();
                BigDecimal input = pricing != null ? pricing.getInput() : BigDecimal.ZERO;
                BigDecimal output = pricing != null ? pricing.getOutput() : BigDecimal.ZERO;
                BigDecimal cacheRead = pricing != null ? pricing.getCacheRead() : BigDecimal.ZERO;
                costCalculator = new CostCalculator(input, output, cacheRead);
            }
            llmClient = new CostTrackingLlmClient(llmClient, costTracker,
                    costCalculator,
                    props.getIssueClosure().getSystemUserId(), "");
            log.info("LlmClient wrapped with CostTrackingLlmClient (cost tracking enabled)");
        }
        List<Advisor> advisors = advisorProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList());
        log.info("AgentService assembled with {} Advisor(s): {}", advisors.size(),
                advisors.stream().map(Advisor::getName).collect(java.util.stream.Collectors.joining(", ")));
        return new AgentService(llmClient, toolCallbackRegistry, taskStore,
                props.getAgent().getMaxTurns(), advisors);
    }

    // ---- ThreadPoolTaskExecutor ----
    @Bean(name = "snapAgentExecutor")
    @ConditionalOnMissingBean(name = "snapAgentExecutor")
    public AsyncTaskExecutor snapAgentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("snap-agent-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("snapAgentExecutor thread pool initialized (core=2, max=4, queue=10)");
        return executor;
    }

    // ---- PeerRouter (mode selector) ----
    @Bean
    @ConditionalOnMissingBean
    public PeerRouter peerRouter(SnapAgentProperties props) {
        String mode = props.getRouting().getMode();
        int port = props.getRouting().getPort();
        int cacheTtl = props.getRouting().getDiscoveryCacheTtlSeconds();
        String svc = props.getRouting().getK8sServiceName();
        if ("k8s-api".equalsIgnoreCase(mode)) {
            log.info("PeerRouter mode=k8s-api (service={})", svc);
            return new K8sApiPeerRouter(svc, port, cacheTtl);
        }
        if ("headless-dns".equalsIgnoreCase(mode)) {
            log.info("PeerRouter mode=headless-dns (service={})", svc);
            return new HeadlessDnsPeerRouter(svc, port, cacheTtl);
        }
        if ("static".equalsIgnoreCase(mode)) {
            log.info("PeerRouter mode=static (peers={})", props.getRouting().getStaticPeers());
            return new StaticPeerRouter(props.getRouting().getStaticPeers());
        }
        log.info("PeerRouter mode=none (cross-pod relay disabled)");
        return new NoopPeerRouter();
    }

    // ---- PeerSseRelay ----
    @Bean
    @ConditionalOnMissingBean
    public PeerSseRelay peerSseRelay(PeerRouter peerRouter, SnapAgentProperties props) {
        return new PeerSseRelay(peerRouter,
                props.getRouting().getInternalToken(),
                props.getRouting().getInternalPath());
    }

    // ---- InternalTaskController ----
    @Bean
    @ConditionalOnMissingBean
    public InternalTaskController internalTaskController(
            TaskStore taskStore,
            SnapAgentProperties props,
            @Qualifier("snapAgentExecutor") AsyncTaskExecutor taskExecutor) {
        return new InternalTaskController(taskStore,
                props.getRouting().getInternalToken(),
                taskExecutor);
    }

    // ---- SnapAgentController ----
    @Bean
    @ConditionalOnMissingBean
    public SnapAgentController snapAgentController(
            SkillRegistry skillRegistry,
            AgentService agentService,
            TaskStore taskStore,
            ToolCallbackRegistry toolCallbackRegistry,
            SnapAgentProperties properties,
            ObjectProvider<SecurityGateway> securityGatewayProvider,
            RateLimiter rateLimiter,
            @Qualifier("snapAgentExecutor") AsyncTaskExecutor taskExecutor,
            ObjectProvider<PeerSseRelay> peerSseRelayProvider,
            ObjectProvider<LlmClient> llmClientProvider,
            ObjectProvider<SecurityAuditLogger> auditLoggerProvider,
            ObjectProvider<ConversationStore> conversationStoreProvider,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.PatrolScheduler> patrolSchedulerProvider,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertConverger> alertConvergerProvider,
            ObjectProvider<cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester> bugfixSuggesterProvider,
            ObjectProvider<cn.watsontech.snapagent.boot2x.issue.IssueClosureService> issueClosureServiceProvider,
            ObjectProvider<cn.watsontech.snapagent.boot2x.cost.CostSummaryService> costSummaryServiceProvider,
            ObjectProvider<YamlWorkflowLoader> workflowLoaderProvider,
            ObjectProvider<WorkflowEngine> workflowEngineProvider,
            ObjectProvider<ToolPluginRegistry> toolPluginRegistryProvider,
            ObjectProvider<AuditStore> auditStoreProvider,
            PluginRegistry pluginRegistry,
            ObjectProvider<PluginUploader> pluginUploaderProvider,
            org.springframework.core.env.Environment environment) {
        SecurityGateway gateway = securityGatewayProvider.getIfAvailable();
        PeerSseRelay relay = peerSseRelayProvider.getIfAvailable();
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        SecurityAuditLogger auditLogger = auditLoggerProvider.getIfAvailable();
        ConversationStore conversationStore = conversationStoreProvider.getIfAvailable();
        cn.watsontech.snapagent.core.patrol.PatrolScheduler patrolScheduler = patrolSchedulerProvider.getIfAvailable();
        cn.watsontech.snapagent.core.patrol.AlertConverger alertConverger = alertConvergerProvider.getIfAvailable();
        cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester bugfixSuggester = bugfixSuggesterProvider.getIfAvailable();
        cn.watsontech.snapagent.boot2x.issue.IssueClosureService issueClosureService = issueClosureServiceProvider.getIfAvailable();
        cn.watsontech.snapagent.boot2x.cost.CostSummaryService costSummaryService = costSummaryServiceProvider.getIfAvailable();
        YamlWorkflowLoader workflowLoader = workflowLoaderProvider.getIfAvailable();
        WorkflowEngine workflowEngine = workflowEngineProvider.getIfAvailable();
        ToolPluginRegistry toolPluginRegistry = toolPluginRegistryProvider.getIfAvailable();
        AuditStore auditStore = auditStoreProvider.getIfAvailable();
        PluginUploader pluginUploader = pluginUploaderProvider.getIfAvailable();
        // Auto-resolve app log file path from Spring's logging.file.name
        if (properties.getLogs().getAppLogFile() == null || properties.getLogs().getAppLogFile().isEmpty()) {
            String logFile = environment.getProperty("logging.file.name");
            if (logFile != null && !logFile.isEmpty()) {
                properties.getLogs().setAppLogFile(logFile);
                log.info("App log file path resolved from logging.file.name: {}", logFile);
            }
        }
        // Resolve host app's active Spring profiles so skills can reference {_app_profile}
        // and the web UI can surface the current environment without prompting the user.
        if (properties.getAppProfiles() == null || properties.getAppProfiles().isEmpty()) {
            String[] active = environment.getActiveProfiles();
            if (active != null && active.length > 0) {
                String joined = String.join(",", active);
                properties.setAppProfiles(joined);
                log.info("App active profiles resolved: {}", joined);
            }
        }
        SnapAgentController controller = new SnapAgentController(
                skillRegistry, agentService, taskStore, toolCallbackRegistry,
                properties, gateway, rateLimiter, taskExecutor, relay, llmClient,
                auditLogger, conversationStore,
                patrolScheduler, alertConverger, bugfixSuggester, issueClosureService,
                costSummaryService, workflowLoader, workflowEngine, toolPluginRegistry, auditStore,
                pluginRegistry, pluginUploader);

        // Wire anchor orchestrator if anchor feature is enabled and LLM is available
        if (properties.getAnchor().isEnabled() && llmClient != null) {
            AnchorSummaryCache cache = new AnchorSummaryCache(properties.getAnchor());
            AnchorContextSummarizer summarizer = new AnchorContextSummarizer(llmClient, properties.getAnchor());
            AnchorSkillClassifier classifier = new AnchorSkillClassifier(llmClient, skillRegistry, properties.getAnchor());
            AnchorOrchestrator orchestrator = new AnchorOrchestrator(llmClient, cache, summarizer,
                    classifier, skillRegistry, properties.getAnchor());
            controller.setAnchorOrchestrator(orchestrator);
            log.info("AnchorOrchestrator wired (anchor feature enabled)");

            // Wire injection orchestrator
            AnchorInjectionCache injectionCache = new AnchorInjectionCache(
                    properties.getAnchor().getInjectionCacheMaxSize());
            AnchorInjectionOrchestrator injectionOrchestrator = new AnchorInjectionOrchestrator(
                    llmClient, skillRegistry, workflowLoader, workflowEngine,
                    injectionCache, gateway, properties.getAnchor());
            controller.setInjectionOrchestrator(injectionOrchestrator);
            log.info("AnchorInjectionOrchestrator wired (anchor injection feature enabled)");
        }

        return controller;
    }

    // ---- SnapAgentFilter ----
    @Bean
    @ConditionalOnMissingBean(name = "snapAgentFilter")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(SecurityGateway.class)
    public FilterRegistrationBean<Filter> snapAgentFilter(
            ObjectProvider<SecurityGateway> securityGatewayProvider,
            SnapAgentProperties props) {
        SecurityGateway gateway = securityGatewayProvider.getIfAvailable();
        SnapAgentFilter filter = new SnapAgentFilter(gateway, props.getBasePath());
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<Filter>(filter);
        registration.addUrlPatterns(props.getBasePath() + "/*");
        registration.setOrder(props.getSecurity().getFilterOrder());
        registration.setName("snapAgentFilter");
        return registration;
    }

    // ---- InMemoryPatrolReportStore (v0.5, SPI-extracted v1.1) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore(SnapAgentProperties props) {
        log.info("InMemoryPatrolReportStore assembled (buffer-size={})", props.getPatrol().getReportBufferSize());
        return new cn.watsontech.snapagent.boot2x.patrol.InMemoryPatrolReportStore(
                props.getPatrol().getReportBufferSize());
    }

    // ---- NoopPatrolLockProvider (v1.1, multi-Pod coordination SPI) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.patrol.PatrolLockProvider patrolLockProvider() {
        log.info("NoopPatrolLockProvider assembled (single-Pod mode; implement PatrolLockProvider for multi-Pod)");
        return new cn.watsontech.snapagent.boot2x.patrol.NoopPatrolLockProvider();
    }

    // ---- Patrol TaskScheduler (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "patrolTaskScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler patrolTaskScheduler(
            SnapAgentProperties props) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(props.getPatrol().getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("patrol-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        log.info("patrolTaskScheduler initialized (pool-size={})", props.getPatrol().getSchedulerPoolSize());
        return scheduler;
    }

    // ---- ScheduledPatrolScheduler (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.ScheduledPatrolScheduler scheduledPatrolScheduler(
            org.springframework.scheduling.TaskScheduler patrolTaskScheduler,
            AgentService agentService,
            SkillRegistry skillRegistry,
            cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore,
            cn.watsontech.snapagent.core.patrol.PatrolLockProvider patrolLockProvider,
            SnapAgentProperties props,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertPushChannel> pushChannelProvider,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertConverger> alertConvergerProvider) {
        List<cn.watsontech.snapagent.core.patrol.AlertPushChannel> pushChannels =
                new ArrayList<cn.watsontech.snapagent.core.patrol.AlertPushChannel>();
        pushChannels.addAll(pushChannelProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList()));
        cn.watsontech.snapagent.core.patrol.AlertConverger alertConverger = alertConvergerProvider.getIfAvailable();
        log.info("ScheduledPatrolScheduler assembled (lockProvider={}, pushChannels={}, lockTtl={}s, alertConverger={})",
                patrolLockProvider.type(), pushChannels.size(), props.getPatrol().getLockTtlSeconds(),
                alertConverger != null ? alertConverger.getClass().getSimpleName() : "none");
        return new cn.watsontech.snapagent.boot2x.patrol.ScheduledPatrolScheduler(
                patrolTaskScheduler, agentService, skillRegistry, patrolReportStore,
                patrolLockProvider, props.getPatrol().getLockTtlSeconds(), pushChannels, alertConverger);
    }

    // ---- InMemoryAlertConverger (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.alert", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.InMemoryAlertConverger inMemoryAlertConverger(
            SnapAgentProperties props) {
        log.info("InMemoryAlertConverger assembled (buffer-size={}, auto-resolve-minutes={})",
                props.getAlert().getBufferSize(), props.getAlert().getAutoResolveMinutes());
        return new cn.watsontech.snapagent.boot2x.patrol.InMemoryAlertConverger(
                props.getAlert().getBufferSize(),
                props.getAlert().getAutoResolveMinutes());
    }

    // ---- DefaultAnomalyEventListener (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.DefaultAnomalyEventListener defaultAnomalyEventListener(
            AgentService agentService,
            SkillRegistry skillRegistry,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertConverger> alertConvergerProvider,
            cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertPushChannel> pushChannelProvider) {
        List<cn.watsontech.snapagent.core.patrol.AlertPushChannel> pushChannels =
                new ArrayList<cn.watsontech.snapagent.core.patrol.AlertPushChannel>();
        pushChannels.addAll(pushChannelProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList()));
        log.info("DefaultAnomalyEventListener assembled (pushChannels={})", pushChannels.size());
        return new cn.watsontech.snapagent.boot2x.patrol.DefaultAnomalyEventListener(
                agentService, skillRegistry, alertConvergerProvider.getIfAvailable(),
                patrolReportStore, pushChannels);
    }

    // ---- WebhookAlertPushChannel (v1.1, default push channel) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "${snap-agent.alert.push.webhook.enabled:false} and " +
            "!T(org.springframework.util.StringUtils).isEmpty('${snap-agent.alert.push.webhook.url:}')")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.WebhookAlertPushChannel webhookAlertPushChannel(
            SnapAgentProperties props) {
        SnapAgentProperties.Alert.Push.Webhook wh = props.getAlert().getPush().getWebhook();
        log.info("WebhookAlertPushChannel assembled (url={}, connectMs={}, readMs={})",
                wh.getUrl(), wh.getConnectTimeoutMs(), wh.getReadTimeoutMs());
        return new cn.watsontech.snapagent.boot2x.patrol.WebhookAlertPushChannel(
                wh.getUrl(), wh.getAuthHeader(), wh.getAuthToken(),
                wh.getConnectTimeoutMs(), wh.getReadTimeoutMs());
    }

    // ---- EmailAlertPushChannel (v1.1, default push channel; requires spring-boot-starter-mail) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.alert.push.email", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springframework.mail.javamail.JavaMailSender")
    @ConditionalOnMissingBean
    public Object emailAlertPushChannel(
            SnapAgentProperties props,
            ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenderProvider) {
        SnapAgentProperties.Alert.Push.Email emailCfg = props.getAlert().getPush().getEmail();
        org.springframework.mail.javamail.JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("snap-agent.alert.push.email.enabled=true but no JavaMailSender bean found; " +
                    "add spring-boot-starter-mail to host dependencies to enable email push");
            return new cn.watsontech.snapagent.boot2x.autoconfig.NoopMarkerBean("emailAlertPushChannel-skipped");
        }
        log.info("EmailAlertPushChannel assembled (recipients={}, from={})",
                emailCfg.getTo(), emailCfg.getFrom());
        return new cn.watsontech.snapagent.boot2x.patrol.EmailAlertPushChannel(
                mailSender, emailCfg.getFrom(), emailCfg.getTo(), emailCfg.getSubjectPrefix());
    }

    // ---- TemplateBugfixSuggester (v0.5) ----
    @Bean
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester templateBugfixSuggester() {
        log.info("TemplateBugfixSuggester assembled");
        return new cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester();
    }

    // ---- Knowledge (v2.x) ----
    // Old v0.7 KnowledgeBase/SimpleKeywordSearcher/MarkdownKnowledgeSource/KnowledgeInjector
    // deleted. New 2.x VectorStore/EmbeddingModel/RetrievalAugmentationAdvisor SPIs
    // are wired conditionally when implementations are available.

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.knowledge.KnowledgeETLPipeline knowledgeETLPipeline(
            ObjectProvider<cn.watsontech.snapagent.core.vectorstore.VectorStore> vectorStoreProvider,
            ObjectProvider<cn.watsontech.snapagent.core.embedding.EmbeddingModel> embeddingModelProvider) {
        log.info("KnowledgeETLPipeline assembled");
        return new cn.watsontech.snapagent.boot2x.knowledge.KnowledgeETLPipeline(
                vectorStoreProvider.getIfAvailable(),
                embeddingModelProvider.getIfAvailable());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({
            cn.watsontech.snapagent.core.vectorstore.VectorStore.class,
            cn.watsontech.snapagent.core.embedding.EmbeddingModel.class
    })
    public cn.watsontech.snapagent.core.rag.RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            cn.watsontech.snapagent.core.vectorstore.VectorStore vectorStore,
            cn.watsontech.snapagent.core.embedding.EmbeddingModel embeddingModel) {
        log.info("RetrievalAugmentationAdvisor assembled");
        cn.watsontech.snapagent.core.rag.DocumentRetriever retriever = (query, topK) ->
                vectorStore.similaritySearch(
                        new cn.watsontech.snapagent.core.vectorstore.SearchRequest(query, topK, 0.75, null));
        return new cn.watsontech.snapagent.core.rag.RetrievalAugmentationAdvisor(
                (q, ctx) -> q,
                retriever,
                new cn.watsontech.snapagent.core.rag.DefaultQueryAugmenter(false));
    }

    // ---- Code Graph (v0.8) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder simpleCodeGraphBuilder(
            CodePathGuard codePathGuard,
            SnapAgentProperties props) {
        log.info("SimpleCodeGraphBuilder assembled (scanPackages={})",
                props.getCodeGraph().getScanPackages());
        return new cn.watsontech.snapagent.boot2x.codegraph.SimpleCodeGraphBuilder(
                codePathGuard, props.getCodeGraph().getScanPackages());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder.class)
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.codegraph.CodeGraphIndex inMemoryCodeGraphIndex(
            cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder builder) {
        cn.watsontech.snapagent.core.codegraph.CodeGraph graph = builder.build();
        log.info("InMemoryCodeGraphIndex assembled ({} nodes, {} edges)",
                graph.nodeCount(), graph.edgeCount());
        return new cn.watsontech.snapagent.boot2x.codegraph.InMemoryCodeGraphIndex(graph);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(cn.watsontech.snapagent.core.codegraph.CodeGraphIndex.class)
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools codeGraphTools(
            cn.watsontech.snapagent.core.codegraph.CodeGraphIndex index,
            SnapAgentProperties props) {
        log.info("CodeGraphTools assembled (maxDepth={}, maxImpactDepth={})",
                props.getCodeGraph().getMaxDepth(), props.getCodeGraph().getMaxImpactDepth());
        return new cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools(
                index, props.getCodeGraph().getMaxDepth(),
                props.getCodeGraph().getMaxImpactDepth());
    }

    // ---- helpers ----

    // ---- Issue Closure (v0.9) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(IssueStore.class)
    public FileIssueStore fileIssueStore(SnapAgentProperties props) {
        String storageDir = props.getIssueClosure().getStorageDir();
        if (storageDir == null || storageDir.isEmpty()) {
            storageDir = props.getUploadSkillsDir() + "/issues";
        }
        log.info("FileIssueStore assembled with storage dir: {}", storageDir);
        return new FileIssueStore(storageDir);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(IssueTracker.class)
    public NoopIssueTracker noopIssueTracker() {
        log.info("NoopIssueTracker assembled (tracker-type=noop)");
        return new NoopIssueTracker();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "zentao")
    public ZentaoIssueTracker zentaoIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.ZentaoTracker zt = props.getIssueClosure().getZentao();
        log.info("ZentaoIssueTracker assembled (base-url={}, product-id={})",
                zt.getBaseUrl(), zt.getProductId());
        return new ZentaoIssueTracker(zt);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "github")
    public GitHubIssueTracker gitHubIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.GitHubTracker gh = props.getIssueClosure().getGithub();
        log.info("GitHubIssueTracker assembled (owner={}, repo={})",
                gh.getOwner(), gh.getRepo());
        return new GitHubIssueTracker(gh);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "tracker-type", havingValue = "jira")
    public JiraIssueTracker jiraIssueTracker(SnapAgentProperties props) {
        SnapAgentProperties.IssueClosure.JiraTracker jr = props.getIssueClosure().getJira();
        log.info("JiraIssueTracker assembled (base-url={}, project-key={})",
                jr.getBaseUrl(), jr.getProjectKey());
        return new JiraIssueTracker(jr);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService knowledgeSedimentationService(
            ObjectProvider<cn.watsontech.snapagent.core.vectorstore.VectorStore> vectorStoreProvider,
            ObjectProvider<cn.watsontech.snapagent.core.embedding.EmbeddingModel> embeddingModelProvider) {
        log.info("KnowledgeSedimentationService assembled");
        return new cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService(
                vectorStoreProvider.getIfAvailable(),
                embeddingModelProvider.getIfAvailable());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(cn.watsontech.snapagent.core.issue.SolutionSuggester.class)
    public TemplateSolutionSuggester templateSolutionSuggester() {
        log.info("TemplateSolutionSuggester assembled");
        return new TemplateSolutionSuggester();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(cn.watsontech.snapagent.core.issue.VerificationRunner.class)
    public SimpleVerificationRunner simpleVerificationRunner(
            AgentService agentService,
            TaskStore taskStore,
            SkillRegistry skillRegistry,
            SnapAgentProperties properties) {
        log.info("SimpleVerificationRunner assembled (system-user-id={})",
                properties.getIssueClosure().getSystemUserId());
        return new SimpleVerificationRunner(agentService, taskStore, skillRegistry,
                properties.getIssueClosure().getSystemUserId());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public IssueClosureService issueClosureService(
            AgentService agentService,
            TaskStore taskStore,
            SkillRegistry skillRegistry,
            IssueStore issueStore,
            IssueTracker issueTracker,
            ObjectProvider<cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService> sedimentationServiceProvider,
            ObjectProvider<cn.watsontech.snapagent.core.issue.SolutionSuggester> solutionSuggesterProvider,
            ObjectProvider<cn.watsontech.snapagent.core.issue.VerificationRunner> verificationRunnerProvider,
            ObjectProvider<cn.watsontech.snapagent.boot2x.fix.FixExecutionService> fixExecutionServiceProvider,
            SnapAgentProperties properties) {
        log.info("IssueClosureService assembled (system-user-id={})",
                properties.getIssueClosure().getSystemUserId());
        return new IssueClosureService(agentService, taskStore, skillRegistry,
                issueStore, issueTracker,
                sedimentationServiceProvider.getIfAvailable(),
                solutionSuggesterProvider.getIfAvailable(),
                verificationRunnerProvider.getIfAvailable(),
                properties.getIssueClosure().getSystemUserId(),
                fixExecutionServiceProvider.getIfAvailable());
    }

    // ---- Cost Accounting (v1.0) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CostStore.class)
    public FileCostStore fileCostStore(SnapAgentProperties props) {
        String storageDir = props.getCost().getStorageDir();
        if (storageDir == null || storageDir.isEmpty()) {
            storageDir = props.getUploadSkillsDir() + "/cost";
        }
        log.info("FileCostStore assembled with storage dir: {}", storageDir);
        return new FileCostStore(storageDir);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public BudgetEnforcer budgetEnforcer(CostStore costStore, SnapAgentProperties props) {
        SnapAgentProperties.Cost.Budgets budgets = props.getCost().getBudgets();
        BigDecimal perUser = budgets != null ? budgets.getPerUserDaily() : null;
        BigDecimal perSkill = budgets != null ? budgets.getPerSkillDaily() : null;
        BigDecimal global = budgets != null ? budgets.getGlobalDaily() : null;
        log.info("BudgetEnforcer assembled (perUserDaily={}, perSkillDaily={}, globalDaily={})",
                perUser, perSkill, global);
        return new BudgetEnforcer(costStore, perUser, perSkill, global);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CostTracker.class)
    public DefaultCostTracker defaultCostTracker(CostStore costStore, BudgetEnforcer budgetEnforcer) {
        log.info("DefaultCostTracker assembled");
        return new DefaultCostTracker(costStore, budgetEnforcer);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public CostSummaryService costSummaryService(CostStore costStore, SnapAgentProperties props) {
        SnapAgentProperties.Cost.Budgets budgets = props.getCost().getBudgets();
        BigDecimal perUser = budgets != null ? budgets.getPerUserDaily() : null;
        BigDecimal perSkill = budgets != null ? budgets.getPerSkillDaily() : null;
        BigDecimal global = budgets != null ? budgets.getGlobalDaily() : null;
        log.info("CostSummaryService assembled");
        return new CostSummaryService(costStore, perUser, perSkill, global);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.cost", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CostCalculator.class)
    public CostCalculator costCalculator(SnapAgentProperties props) {
        SnapAgentProperties.Cost.Pricing pricing = props.getCost().getPricing();
        BigDecimal input = pricing != null ? pricing.getInput() : BigDecimal.ZERO;
        BigDecimal output = pricing != null ? pricing.getOutput() : BigDecimal.ZERO;
        BigDecimal cacheRead = pricing != null ? pricing.getCacheRead() : BigDecimal.ZERO;
        log.info("CostCalculator assembled (input={}, output={}, cacheRead={})",
                input, output, cacheRead);
        return new CostCalculator(input, output, cacheRead);
    }

    // ---- Tool Plugin Registry (v1.0) ----

    @Bean
    @ConditionalOnMissingBean
    public ToolPluginRegistry toolPluginRegistry(ObjectProvider<ToolPlugin> toolPluginProvider) {
        java.util.List<ToolPlugin> plugins = toolPluginProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList());
        log.info("ToolPluginRegistry assembled with {} plugin(s)", plugins.size());
        return new ToolPluginRegistry(plugins);
    }

    // ---- Workflow Engine (v1.0) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public YamlWorkflowLoader yamlWorkflowLoader(SnapAgentProperties props) {
        String dir = props.getWorkflows().getDir();
        Path workflowsDir;
        if (dir == null || dir.isEmpty()) {
            workflowsDir = Paths.get(props.getUploadSkillsDir()).resolve("workflows");
        } else {
            String path = dir;
            if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            workflowsDir = Paths.get(path);
        }
        try {
            if (!Files.isDirectory(workflowsDir)) {
                Files.createDirectories(workflowsDir);
                log.info("Created workflows dir: {}", workflowsDir);
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to create workflows dir {}: {}", workflowsDir, e.getMessage());
        }
        log.info("YamlWorkflowLoader assembled with dir: {}", workflowsDir);
        return new YamlWorkflowLoader(workflowsDir);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.workflows", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(WorkflowEngine.class)
    public SimpleWorkflowEngine simpleWorkflowEngine(
            AgentService agentService,
            SkillRegistry skillRegistry,
            SnapAgentProperties props) {
        String systemUserId = props.getIssueClosure().getSystemUserId();
        log.info("SimpleWorkflowEngine assembled (systemUserId={})", systemUserId);
        return new SimpleWorkflowEngine(agentService, skillRegistry, systemUserId);
    }

    // ---- file system helpers ----

    private Path resolveUploadDir(String uploadSkillsDir) {
        if (uploadSkillsDir == null || uploadSkillsDir.isEmpty()) {
            log.warn("upload-skills-dir is not configured; only builtin skills will be loaded");
            return null;
        }
        String path = uploadSkillsDir;
        if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        Path dir = Paths.get(path);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                log.info("Created upload-skills-dir: {}", dir);
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to create upload-skills-dir {}: {}", dir, e.getMessage());
        }
        return dir;
    }
}
