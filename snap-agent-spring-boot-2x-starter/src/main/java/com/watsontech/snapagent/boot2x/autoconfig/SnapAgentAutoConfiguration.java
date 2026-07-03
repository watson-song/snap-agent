package com.watsontech.snapagent.boot2x.autoconfig;

import com.watsontech.snapagent.boot2x.llm.AnthropicLlmClient;
import com.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter;
import com.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter;
import com.watsontech.snapagent.boot2x.routing.NoopPeerRouter;
import com.watsontech.snapagent.boot2x.routing.PeerRouter;
import com.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import com.watsontech.snapagent.boot2x.routing.StaticPeerRouter;
import com.watsontech.snapagent.boot2x.security.DefaultPrincipalResolver;
import com.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import com.watsontech.snapagent.boot2x.skill.ClasspathSkillScanner;
import com.watsontech.snapagent.boot2x.tool.JdbcQueryToolProvider;
import com.watsontech.snapagent.boot2x.tool.LogPathGuard;
import com.watsontech.snapagent.boot2x.tool.LogReadToolProvider;
import com.watsontech.snapagent.boot2x.tool.RedisReadToolProvider;
import com.watsontech.snapagent.boot2x.tool.SqlGuard;
import com.watsontech.snapagent.boot2x.web.InternalTaskController;
import com.watsontech.snapagent.boot2x.web.SnapAgentController;
import com.watsontech.snapagent.boot2x.web.SnapAgentFilter;
import com.watsontech.snapagent.core.agent.AgentExecutor;
import com.watsontech.snapagent.core.agent.RateLimiter;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.llm.LlmClient;
import com.watsontech.snapagent.core.llm.LlmClient;
import com.watsontech.snapagent.core.security.PrincipalResolver;
import com.watsontech.snapagent.core.security.SecurityGateway;
import com.watsontech.snapagent.core.skill.SkillRegistry;
import com.watsontech.snapagent.core.tool.ToolDispatcher;
import com.watsontech.snapagent.core.tool.ToolProvider;
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

import javax.servlet.Filter;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

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
        return new com.watsontech.snapagent.boot2x.security.ShiroAdapter(principalResolver);
    }

    // ---- LlmClient ----
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${snap-agent.llm.api-key:}' != '' or '${snap-agent.llm.auth-token:}' != ''")
    public LlmClient llmClient(SnapAgentProperties props) {
        return new AnthropicLlmClient(
                props.getLlm().getBaseUrl(),
                props.getLlm().getApiKey(),
                props.getLlm().getAuthToken(),
                props.getLlm().getTimeoutSeconds());
    }

    // ---- JdbcQueryToolProvider ----
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.jdbc", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    public JdbcQueryToolProvider jdbcQueryToolProvider(
            ObjectProvider<DataSource> dataSourceProvider,
            SnapAgentProperties props,
            SqlGuard sqlGuard) {
        DataSource ds = dataSourceProvider.getIfAvailable();
        log.info("JdbcQueryToolProvider assembled with DataSource: {}",
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

    // ---- SkillRegistry ----
    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(SnapAgentProperties props, ToolDispatcher toolDispatcher,
                                       ClasspathSkillScanner classpathSkillScanner) {
        // Scan classpath for builtin skills
        List<com.watsontech.snapagent.core.skill.SkillMeta> builtinSkills =
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
            SnapAgentProperties props) {
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        if (llmClient == null) {
            log.warn("LlmClient not available; AgentExecutor will not function");
        }
        return new AgentExecutor(llmClient, toolDispatcher, taskStore,
                props.getAgent().getMaxTurns(), props.getLlm().getMaxTokens());
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
            ObjectProvider<LlmClient> llmClientProvider) {
        SecurityGateway gateway = securityGatewayProvider.getIfAvailable();
        PeerSseRelay relay = peerSseRelayProvider.getIfAvailable();
        LlmClient llmClient = llmClientProvider.getIfAvailable();
        return new SnapAgentController(
                skillRegistry, agentExecutor, taskStore, toolDispatcher,
                properties, gateway, rateLimiter, taskExecutor, relay, llmClient);
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
