package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.anchor.AnchorContextSummarizer;
import cn.watsontech.snapagent.boot2x.anchor.AnchorInjectionCache;
import cn.watsontech.snapagent.boot2x.anchor.AnchorInjectionOrchestrator;
import cn.watsontech.snapagent.boot2x.anchor.AnchorOrchestrator;
import cn.watsontech.snapagent.boot2x.anchor.AnchorSkillClassifier;
import cn.watsontech.snapagent.boot2x.anchor.AnchorSummaryCache;
import cn.watsontech.snapagent.boot2x.conversation.ConversationStore;
import cn.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.NoopPeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerRouter;
import cn.watsontech.snapagent.boot2x.routing.PeerSseRelay;
import cn.watsontech.snapagent.boot2x.routing.StaticPeerRouter;
import cn.watsontech.snapagent.boot2x.tool.PluginUploader;
import cn.watsontech.snapagent.boot2x.tool.ToolPluginRegistry;
import cn.watsontech.snapagent.boot2x.experiment.DefaultExperimentRunner;
import cn.watsontech.snapagent.boot2x.skill.SkillTemplateCatalog;
import cn.watsontech.snapagent.boot2x.web.ExperimentController;
import cn.watsontech.snapagent.boot2x.web.WorkflowDesignerController;
import cn.watsontech.snapagent.boot2x.web.InternalTaskController;
import cn.watsontech.snapagent.boot2x.web.SkillTemplateController;
import cn.watsontech.snapagent.boot2x.web.SnapAgentController;
import cn.watsontech.snapagent.core.experiment.ExperimentRunner;
import cn.watsontech.snapagent.core.experiment.ExperimentStore;
import cn.watsontech.snapagent.boot2x.web.SnapAgentFilter;
import cn.watsontech.snapagent.boot2x.workflow.WorkflowEngine;
import cn.watsontech.snapagent.boot2x.workflow.YamlWorkflowLoader;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.AuditStore;
import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.file.Path;
import javax.servlet.Filter;

/**
 * Web/routing auto-configuration extracted from {@link SnapAgentAutoConfiguration}.
 *
 * <p>Holds beans for peer routing, SSE relay, the internal task controller,
 * the main {@link SnapAgentController} (including anchor orchestrator wiring),
 * and the {@link SnapAgentFilter} registration. Activated only when
 * {@code snap-agent.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class WebAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WebAutoConfiguration.class);

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

    // ---- SkillTemplateCatalog + SkillTemplateController (Marketplace) ----
    @Bean
    @ConditionalOnMissingBean
    public SkillTemplateCatalog skillTemplateCatalog(SnapAgentProperties props, SkillRegistry skillRegistry) {
        java.nio.file.Path uploadDir = java.nio.file.Paths.get(props.getUploadSkillsDir());
        return new SkillTemplateCatalog(uploadDir, skillRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillTemplateController skillTemplateController(
            SkillTemplateCatalog catalog) {
        return new SkillTemplateController(catalog);
    }

    // ---- A/B Experiment Framework ----
    @Bean
    @ConditionalOnMissingBean
    public ExperimentStore experimentStore() {
        return new ExperimentStore();
    }

    @Bean
    @ConditionalOnMissingBean(ExperimentRunner.class)
    public ExperimentRunner experimentRunner(AgentService agentService, SkillRegistry skillRegistry) {
        return new DefaultExperimentRunner(agentService, skillRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExperimentController experimentController(
            ExperimentStore experimentStore,
            ObjectProvider<ExperimentRunner> experimentRunnerProvider) {
        return new ExperimentController(experimentStore, experimentRunnerProvider.getIfAvailable());
    }

    // ---- Visual Workflow Designer ----
    @Bean
    @ConditionalOnMissingBean
    public WorkflowDesignerController workflowDesignerController(
            SkillRegistry skillRegistry,
            ObjectProvider<YamlWorkflowLoader> workflowLoaderProvider,
            ObjectProvider<WorkflowEngine> workflowEngineProvider,
            SnapAgentProperties props) {
        Path workflowsDir = null;
        String dir = props.getWorkflows().getDir();
        if (dir != null && !dir.isEmpty()) {
            String path = dir;
            if (path.startsWith("file:")) path = path.substring(5);
            workflowsDir = java.nio.file.Paths.get(path);
        } else {
            workflowsDir = java.nio.file.Paths.get(props.getUploadSkillsDir()).resolve("workflows");
        }
        return new WorkflowDesignerController(
                skillRegistry,
                workflowLoaderProvider.getIfAvailable(),
                workflowEngineProvider.getIfAvailable(),
                workflowsDir);
    }
}
