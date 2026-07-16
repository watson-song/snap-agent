package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.conversation.FileConversationStore;
import cn.watsontech.snapagent.boot2x.context.ProjectContextExtender;
import cn.watsontech.snapagent.boot2x.llm.AnthropicLlmClient;
import cn.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.NoopPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import cn.watsontech.snapagent.boot2x.routing.StaticPeerRouter;
import cn.watsontech.snapagent.boot2x.security.DefaultPrincipalResolver;
import cn.watsontech.snapagent.boot2x.security.LoggingSecurityAuditLogger;
import cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import cn.watsontech.snapagent.boot2x.skill.ClasspathSkillScanner;
import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.boot2x.tool.CodeReaderToolProvider;
import cn.watsontech.snapagent.boot2x.tool.ConfigReadToolProvider;
import cn.watsontech.snapagent.boot2x.tool.DataSourceRegistry;
import cn.watsontech.snapagent.boot2x.tool.GitLogToolProvider;
import cn.watsontech.snapagent.boot2x.tool.JdbcQueryToolProvider;
import cn.watsontech.snapagent.boot2x.tool.LogPathGuard;
import cn.watsontech.snapagent.boot2x.tool.LogReadToolProvider;
import cn.watsontech.snapagent.boot2x.tool.LogSearchToolProvider;
import cn.watsontech.snapagent.boot2x.tool.MetricsToolProvider;
import cn.watsontech.snapagent.boot2x.tool.ProjectStructureToolProvider;
import cn.watsontech.snapagent.boot2x.tool.RedisReadToolProvider;
import cn.watsontech.snapagent.boot2x.tool.SqlGuard;
import cn.watsontech.snapagent.boot2x.tool.TraceSearchToolProvider;
import cn.watsontech.snapagent.boot2x.web.InternalTaskController;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.boot2x.web.SnapAgentFilter;
import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.SystemPromptExtender;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.conversation.ConversationStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.PrincipalResolver;
import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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

import javax.servlet.Filter;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ---- SecurityAuditLogger ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.security", name = "audit-log", havingValue = "true", matchIfMissing = true)
    public SecurityAuditLogger securityAuditLogger() {
        log.info("Using LoggingSecurityAuditLogger (default SLF4J audit logger)");
        return new LoggingSecurityAuditLogger();
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
                props.getLlm().getTimeoutSeconds());
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

    // ---- JdbcQueryToolProvider ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.jdbc", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public JdbcQueryToolProvider jdbcQueryToolProvider(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DataSourceRegistry> registryProvider,
            SnapAgentProperties props,
            SqlGuard sqlGuard) {
        DataSourceRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            log.info("JdbcQueryToolProvider assembled with DataSourceRegistry ({} envs)", registry.size());
            return new JdbcQueryToolProvider(registry, sqlGuard);
        }
        DataSource ds = dataSourceProvider.getIfAvailable();
        log.info("JdbcQueryToolProvider assembled with single DataSource: {}",
                props.getJdbc().getDatasourceBeanName());
        return new JdbcQueryToolProvider(ds, sqlGuard);
    }

    // ---- RedisReadToolProvider ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.redis", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.data.redis.core.RedisTemplate")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            type = "org.springframework.data.redis.core.RedisTemplate")
    @ConditionalOnMissingBean
    public RedisReadToolProvider redisReadToolProvider(
            org.springframework.beans.factory.BeanFactory beanFactory,
            SnapAgentProperties props) {
        String beanName = props.getRedis().getRedisTemplateBeanName();
        org.springframework.data.redis.core.RedisTemplate template =
                beanFactory.getBean(beanName, org.springframework.data.redis.core.RedisTemplate.class);
        log.info("RedisReadToolProvider assembled with RedisTemplate bean '{}'", beanName);
        return new RedisReadToolProvider(template);
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

    // ---- LogReadToolProvider ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.logs", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LogReadToolProvider logReadToolProvider(LogPathGuard logPathGuard) {
        log.info("LogReadToolProvider assembled");
        return new LogReadToolProvider(logPathGuard);
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

    // ---- ProjectContextExtender (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.code", name = "context-injection",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public SystemPromptExtender projectContextExtender(CodePathGuard codePathGuard,
                                                        SnapAgentProperties props) {
        log.info("ProjectContextExtender assembled (structure-depth={})",
                props.getCode().getStructureDepth());
        return new ProjectContextExtender(codePathGuard, props.getCode().getStructureDepth());
    }

    // ---- CodeReaderToolProvider (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public CodeReaderToolProvider codeReaderToolProvider(CodePathGuard codePathGuard) {
        log.info("CodeReaderToolProvider assembled");
        return new CodeReaderToolProvider(codePathGuard);
    }

    // ---- ProjectStructureToolProvider (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public ProjectStructureToolProvider projectStructureToolProvider(CodePathGuard codePathGuard) {
        log.info("ProjectStructureToolProvider assembled");
        return new ProjectStructureToolProvider(codePathGuard);
    }

    // ---- GitLogToolProvider (v0.3) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public GitLogToolProvider gitLogToolProvider(CodePathGuard codePathGuard) {
        log.info("GitLogToolProvider assembled");
        return new GitLogToolProvider(codePathGuard);
    }

    // ---- MetricsToolProvider (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.metrics", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public MetricsToolProvider metricsToolProvider(SnapAgentProperties props) {
        log.info("MetricsToolProvider assembled (base-url={})", props.getMetrics().getBaseUrl());
        return new MetricsToolProvider(props.getMetrics());
    }

    // ---- LogSearchToolProvider (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.log-search", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public LogSearchToolProvider logSearchToolProvider(SnapAgentProperties props) {
        log.info("LogSearchToolProvider assembled (base-url={})", props.getLogSearch().getBaseUrl());
        return new LogSearchToolProvider(props.getLogSearch());
    }

    // ---- TraceSearchToolProvider (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.trace", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public TraceSearchToolProvider traceSearchToolProvider(SnapAgentProperties props) {
        log.info("TraceSearchToolProvider assembled (base-url={})", props.getTrace().getBaseUrl());
        return new TraceSearchToolProvider(props.getTrace());
    }

    // ---- ConfigReadToolProvider (v0.4) ----
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.config-read", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ConfigReadToolProvider configReadToolProvider(
            SnapAgentProperties props,
            org.springframework.core.env.Environment environment) {
        log.info("ConfigReadToolProvider assembled");
        return new ConfigReadToolProvider(props.getConfigRead(), environment);
    }

    // ---- ToolDispatcher ----
    @Bean
    @ConditionalOnMissingBean
    public ToolDispatcher toolDispatcher(
            ObjectProvider<ToolProvider> toolProviders,
            SnapAgentProperties props) {
        // Collect every ToolProvider bean in the context (Jdbc, Redis, custom).
        List<ToolProvider> providers = new ArrayList<ToolProvider>(
                toolProviders.orderedStream().collect(java.util.stream.Collectors.toList()));
        log.info("ToolDispatcher assembled with {} provider(s)", providers.size());
        return new ToolDispatcher(providers, props.getAgent().getMaxToolResultChars());
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
    public SkillRegistry skillRegistry(SnapAgentProperties props, ToolDispatcher toolDispatcher,
                                       ClasspathSkillScanner classpathSkillScanner) {
        // Scan classpath for builtin skills
        List<cn.watsontech.snapagent.core.skill.SkillMeta> builtinSkills =
                classpathSkillScanner.scan(props.getBuiltinSkillsDir());

        // Resolve upload dir (filesystem, read-write)
        Path uploadDir = resolveUploadDir(props.getUploadSkillsDir());

        return new SkillRegistry(uploadDir, builtinSkills, toolDispatcher);
    }

    // ---- AgentExecutor ----
    @Bean
    @ConditionalOnMissingBean
    public AgentExecutor agentExecutor(
            ObjectProvider<LlmClient> llmClientProvider,
            ToolDispatcher toolDispatcher,
            TaskStore taskStore,
            SnapAgentProperties props,
            ObjectProvider<SystemPromptExtender> extenderProvider) {
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            log.warn("LlmClient not available; AgentExecutor will not function");
        }
        List<SystemPromptExtender> extenders = extenderProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList());
        if (!extenders.isEmpty()) {
            log.info("AgentExecutor assembled with {} SystemPromptExtender(s): {}",
                    extenders.size(),
                    extenders.stream()
                            .map(e -> e.getClass().getSimpleName())
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        return new AgentExecutor(llmClient, toolDispatcher, taskStore,
                props.getAgent().getMaxTurns(), props.getLlm().getMaxTokens(),
                extenders);
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
            AgentExecutor agentExecutor,
            TaskStore taskStore,
            ToolDispatcher toolDispatcher,
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
            org.springframework.core.env.Environment environment) {
        SecurityGateway gateway = securityGatewayProvider.getIfAvailable();
        PeerSseRelay relay = peerSseRelayProvider.getIfAvailable();
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        SecurityAuditLogger auditLogger = auditLoggerProvider.getIfAvailable();
        ConversationStore conversationStore = conversationStoreProvider.getIfAvailable();
        cn.watsontech.snapagent.core.patrol.PatrolScheduler patrolScheduler = patrolSchedulerProvider.getIfAvailable();
        cn.watsontech.snapagent.core.patrol.AlertConverger alertConverger = alertConvergerProvider.getIfAvailable();
        cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester bugfixSuggester = bugfixSuggesterProvider.getIfAvailable();
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
        return new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, gateway, rateLimiter, taskExecutor, relay, llmClient,
                auditLogger, conversationStore,
                patrolScheduler, alertConverger, bugfixSuggester);
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

    // ---- PatrolReportStore (v0.5) ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.patrol", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore(SnapAgentProperties props) {
        log.info("PatrolReportStore assembled (buffer-size={})", props.getPatrol().getReportBufferSize());
        return new cn.watsontech.snapagent.core.patrol.PatrolReportStore(
                props.getPatrol().getReportBufferSize());
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
            AgentExecutor agentExecutor,
            SkillRegistry skillRegistry,
            cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore) {
        log.info("ScheduledPatrolScheduler assembled");
        return new cn.watsontech.snapagent.boot2x.patrol.ScheduledPatrolScheduler(
                patrolTaskScheduler, agentExecutor, skillRegistry, patrolReportStore);
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
            AgentExecutor agentExecutor,
            SkillRegistry skillRegistry,
            ObjectProvider<cn.watsontech.snapagent.core.patrol.AlertConverger> alertConvergerProvider,
            cn.watsontech.snapagent.core.patrol.PatrolReportStore patrolReportStore) {
        log.info("DefaultAnomalyEventListener assembled");
        return new cn.watsontech.snapagent.boot2x.patrol.DefaultAnomalyEventListener(
                agentExecutor, skillRegistry, alertConvergerProvider.getIfAvailable(), patrolReportStore);
    }

    // ---- TemplateBugfixSuggester (v0.5) ----
    @Bean
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester templateBugfixSuggester() {
        log.info("TemplateBugfixSuggester assembled");
        return new cn.watsontech.snapagent.boot2x.patrol.TemplateBugfixSuggester();
    }

    // ---- Knowledge Base (v0.7) ----

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.knowledge.KnowledgeSearcher simpleKeywordSearcher() {
        log.info("SimpleKeywordSearcher assembled");
        return new cn.watsontech.snapagent.boot2x.knowledge.SimpleKeywordSearcher();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "snap-agent.knowledge", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public cn.watsontech.snapagent.core.knowledge.KnowledgeBase knowledgeBase(
            SnapAgentProperties props,
            cn.watsontech.snapagent.core.knowledge.KnowledgeSearcher searcher,
            ObjectProvider<cn.watsontech.snapagent.core.knowledge.KnowledgeSource> sourceProvider) {
        List<cn.watsontech.snapagent.core.knowledge.KnowledgeSource> sources =
                new ArrayList<cn.watsontech.snapagent.core.knowledge.KnowledgeSource>();
        // Collect any custom KnowledgeSource beans declared by the host application.
        sources.addAll(sourceProvider.orderedStream()
                .collect(java.util.stream.Collectors.toList()));
        // Add configured markdown sources; default to the builtin knowledge directory
        // when the host did not configure any sources.
        List<SnapAgentProperties.KnowledgeSourceConfig> configs =
                props.getKnowledge().getSources();
        if (configs == null || configs.isEmpty()) {
            log.info("No knowledge sources configured; using default classpath:/docs/knowledge/");
            sources.add(new cn.watsontech.snapagent.boot2x.knowledge.MarkdownKnowledgeSource(
                    "classpath:/docs/knowledge/"));
        } else {
            for (SnapAgentProperties.KnowledgeSourceConfig srcCfg : configs) {
                if ("markdown".equalsIgnoreCase(srcCfg.getType()) && !srcCfg.getDir().isEmpty()) {
                    log.info("MarkdownKnowledgeSource assembled (dir={})", srcCfg.getDir());
                    sources.add(new cn.watsontech.snapagent.boot2x.knowledge.MarkdownKnowledgeSource(
                            srcCfg.getDir()));
                } else {
                    log.warn("Skipping knowledge source with unknown type or empty dir: type={}, dir={}",
                            srcCfg.getType(), srcCfg.getDir());
                }
            }
        }
        cn.watsontech.snapagent.core.knowledge.KnowledgeBase kb =
                new cn.watsontech.snapagent.core.knowledge.KnowledgeBase(sources, searcher);
        log.info("KnowledgeBase assembled ({} fragments)", kb.size());
        return kb;
    }

    // KnowledgeInjector coexists with ProjectContextExtender as separate beans —
    // do NOT use @ConditionalOnMissingBean(SystemPromptExtender.class) here, since
    // that would suppress whichever extender is evaluated second (spec §5).
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            cn.watsontech.snapagent.core.knowledge.KnowledgeBase.class)
    public cn.watsontech.snapagent.core.agent.SystemPromptExtender knowledgeInjector(
            SnapAgentProperties props,
            cn.watsontech.snapagent.core.knowledge.KnowledgeBase knowledgeBase) {
        int maxFrag = props.getKnowledge().getMaxFragments();
        double minScore = props.getKnowledge().getMinScore();
        log.info("KnowledgeInjector assembled (maxFragments={}, minScore={})", maxFrag, minScore);
        return new cn.watsontech.snapagent.boot2x.knowledge.KnowledgeInjector(
                knowledgeBase, maxFrag, minScore);
    }

    // ---- helpers ----

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
