package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.conversation.FileConversationStore;
import cn.watsontech.snapagent.boot2x.conversation.ConversationStore;
import cn.watsontech.snapagent.boot2x.cost.CostCalculator;
import cn.watsontech.snapagent.boot2x.cost.CostTrackingLlmClient;
import cn.watsontech.snapagent.boot2x.llm.AnthropicLlmClient;
import cn.watsontech.snapagent.boot2x.skill.ClasspathSkillScanner;
import cn.watsontech.snapagent.boot2x.skill.SkillHotReloader;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.cost.CostTracker;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.memory.ChatMemory;
import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import cn.watsontech.snapagent.core.memory.InMemoryChatMemoryRepository;
import cn.watsontech.snapagent.core.memory.MessageChatMemoryAdvisor;
import cn.watsontech.snapagent.core.memory.MessageWindowChatMemory;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-configuration for the embedded SnapAgent.
 *
 * <p>Activated only when {@code snap-agent.enabled=true} (default false).
 * When disabled, zero beans are created — no filter, no thread pool, no routes
 * (TDD_SPEC §AC15, design doc 01 §4).</p>
 *
 * <p>Domain-specific beans are decomposed into focused {@code @Configuration}
 * classes imported via {@link Import}:</p>
 * <ul>
 *   <li>{@link SecurityAutoConfiguration} — security, audit, SqlGuard</li>
 *   <li>{@link ToolAutoConfiguration} — all diagnostic tools, plugins, MCP</li>
 *   <li>{@link WebAutoConfiguration} — controllers, filter, peer routing</li>
 *   <li>{@link PatrolAutoConfiguration} — patrol scheduling, alert convergence</li>
 *   <li>{@link KnowledgeAutoConfiguration} — vector store, RAG, code graph</li>
 *   <li>{@link IssueAutoConfiguration} — issue tracking, VCS, auto-fix</li>
 *   <li>{@link CostAutoConfiguration} — cost tracking, budgets, pricing</li>
 *   <li>{@link WorkflowAutoConfiguration} — workflow engine, plugin registry</li>
 *   <li>{@link BridgeAutoConfiguration} — browser network bridge (when enabled)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
@Import({
        SecurityAutoConfiguration.class,
        ToolAutoConfiguration.class,
        WebAutoConfiguration.class,
        PatrolAutoConfiguration.class,
        KnowledgeAutoConfiguration.class,
        IssueAutoConfiguration.class,
        CostAutoConfiguration.class,
        WorkflowAutoConfiguration.class,
        BridgeAutoConfiguration.class
})
public class SnapAgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SnapAgentAutoConfiguration.class);

    // ════════════════════════════════════════════════════════════════
    // Core agent infrastructure
    // ════════════════════════════════════════════════════════════════

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

    // ---- ChatMemory (ReAct loop conversation history) ----
    // In-memory chat memory repository for single-node deployments.
    // Hosts can replace this with a durable implementation (Redis, DB) by
    // declaring their own ChatMemoryRepository bean.
    @Bean
    @ConditionalOnMissingBean
    public ChatMemoryRepository chatMemoryRepository() {
        log.info("Using InMemoryChatMemoryRepository for ReAct loop memory");
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        log.info("Using MessageWindowChatMemory (maxMessages={})",
                MessageWindowChatMemory.DEFAULT_MAX_MESSAGES);
        return new MessageWindowChatMemory(chatMemoryRepository);
    }

    // ---- MessageChatMemoryAdvisor (Layer 5 — Short-term Notes) ----
    // Persists user message (after entry), assistant turns with tool_use
    // blocks (after agent), and tool_result messages (after tools) to
    // ChatMemory. On the next ReAct turn, beforeNode loads the full
    // history into state["memory.messages"] for AgentNode to use directly.
    @Bean
    @ConditionalOnMissingBean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        log.info("MessageChatMemoryAdvisor assembled (order=100)");
        return new MessageChatMemoryAdvisor(chatMemory);
    }

    // ---- SkillRegistry ----
    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(SnapAgentProperties props, ToolCallbackRegistry toolCallbackRegistry,
                                       ClasspathSkillScanner classpathSkillScanner) {
        // Scan classpath for builtin skills
        java.util.List<cn.watsontech.snapagent.core.skill.SkillMeta> builtinSkills =
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
        java.util.List<Advisor> advisors = advisorProvider.orderedStream()
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

    // ════════════════════════════════════════════════════════════════
    // File system helpers
    // ════════════════════════════════════════════════════════════════

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
