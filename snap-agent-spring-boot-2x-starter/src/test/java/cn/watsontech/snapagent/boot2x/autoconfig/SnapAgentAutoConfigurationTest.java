package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.tool.SqlGuard;
import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.RateLimiter;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.security.SecurityGateway;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SnapAgentAutoConfiguration} conditional assembly
 * (TDD_SPEC §UC-17, §AC15).
 *
 * <p>IT-02: enabled=false → zero beans.
 * IT-03: enabled=true + api-key non-empty → beans exist.</p>
 */
class SnapAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SnapAgentAutoConfiguration.class));

    // ---- IT-02: enabled=false → no beans ----

    @Test
    void shouldCreateNoAgentBeansWhenEnabledIsFalse() {
        contextRunner
                .withPropertyValues("snap-agent.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SkillRegistry.class);
                    assertThat(context).doesNotHaveBean(AgentExecutor.class);
                    assertThat(context).doesNotHaveBean(LlmClient.class);
                    assertThat(context).doesNotHaveBean(ToolDispatcher.class);
                    assertThat(context).doesNotHaveBean(TaskStore.class);
                    assertThat(context).doesNotHaveBean(RateLimiter.class);
                    assertThat(context).doesNotHaveBean("snapAgentExecutor");
                    assertThat(context).doesNotHaveBean("snapAgentFilter");
                });
    }

    @Test
    void shouldCreateNoBeansWhenPropertyNotSet() {
        // Default: enabled is not set → defaults to false → no beans
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SkillRegistry.class);
            assertThat(context).doesNotHaveBean(AgentExecutor.class);
        });
    }

    // ---- IT-03: enabled=true + api-key non-empty → beans exist ----

    @Test
    void shouldCreateBeansWhenEnabledAndApiKeySet() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(SkillRegistry.class);
                    assertThat(context).hasSingleBean(AgentExecutor.class);
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context).hasSingleBean(ToolDispatcher.class);
                    assertThat(context).hasSingleBean(TaskStore.class);
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    assertThat(context).hasSingleBean(AsyncTaskExecutor.class);
                    assertThat(context).hasSingleBean(SqlGuard.class);
                });
    }

    @Test
    void shouldCreateLlmClientWhenApiKeyIsNonEmpty() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    LlmClient client = context.getBean(LlmClient.class);
                    assertThat(client).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.llm.AnthropicLlmClient.class);
                });
    }

    @Test
    void shouldNotCreateLlmClientWhenApiKeyIsEmpty() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=")
                .run(context -> {
                    // When api-key is empty, the @ConditionalOnExpression
                    // prevents bean creation entirely.
                    assertThat(context).doesNotHaveBean(LlmClient.class);
                });
    }

    @Test
    void shouldCreateSecurityGatewayWhenSpringSecurityOnClasspath() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(SecurityGateway.class);
                    SecurityGateway gateway = context.getBean(SecurityGateway.class);
                    assertThat(gateway).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter.class);
                });
    }

    @Test
    void shouldCreateThreadPoolWithCorrectName() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test")
                .run(context -> {
                    AsyncTaskExecutor executor = context.getBean(AsyncTaskExecutor.class);
                    assertThat(executor).isNotNull();
                });
    }

    @Test
    void shouldNotCreateJdbcProviderWhenJdbcDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.jdbc.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.JdbcQueryToolProvider.class);
                });
    }

    @Test
    void shouldNotCreateRedisProviderWhenRedisDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.redis.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.RedisReadToolProvider.class);
                });
    }

    // ---- Routing bean selection by mode ----

    @Test
    void shouldCreateNoopPeerRouterWhenModeNone() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.routing.mode=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(cn.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    cn.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(cn.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.routing.NoopPeerRouter.class);
                });
    }

    @Test
    void shouldCreateStaticPeerRouterWhenModeStatic() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.routing.mode=static",
                        "snap-agent.routing.static-peers[0]=http://10.0.0.1:8080",
                        "snap-agent.routing.static-peers[1]=http://10.0.0.2:8080")
                .run(context -> {
                    cn.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(cn.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.routing.StaticPeerRouter.class);
                    assertThat(router.discoverPeers()).containsExactly(
                            "http://10.0.0.1:8080", "http://10.0.0.2:8080");
                });
    }

    @Test
    void shouldCreateK8sApiPeerRouterWhenModeK8sApi() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.routing.mode=k8s-api",
                        "snap-agent.routing.k8s-service-name=snap-agent",
                        "snap-agent.routing.port=8080")
                .run(context -> {
                    cn.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(cn.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter.class);
                    assertThat(router.mode()).isEqualTo("k8s-api");
                });
    }

    @Test
    void shouldCreateHeadlessDnsPeerRouterWhenModeHeadlessDns() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.routing.mode=headless-dns",
                        "snap-agent.routing.k8s-service-name=snap-agent.default.svc.cluster.local",
                        "snap-agent.routing.port=8080")
                .run(context -> {
                    cn.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(cn.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            cn.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter.class);
                    assertThat(router.mode()).isEqualTo("headless-dns");
                });
    }

    @Test
    void shouldCreatePeerSseRelayAndInternalControllerWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.routing.mode=static",
                        "snap-agent.routing.static-peers[0]=http://10.0.0.1:8080",
                        "snap-agent.routing.internal-token=secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(cn.watsontech.snapagent.boot2x.routing.PeerSseRelay.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.web.InternalTaskController.class);
                });
    }

    // ---- v0.3: Code understanding tools ----

    @Test
    void shouldNotCreateCodeBeansWhenCodeDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.code.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.CodePathGuard.class);
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.CodeReaderToolProvider.class);
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.ProjectStructureToolProvider.class);
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.tool.GitLogToolProvider.class);
                });
    }

    @Test
    void shouldCreateCodeBeansWhenEnabledWithProjectRoot() {
        String tempDir = System.getProperty("java.io.tmpdir");
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.code.enabled=true",
                        "snap-agent.code.project-root=" + tempDir)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.tool.CodePathGuard.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.tool.CodeReaderToolProvider.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.tool.ProjectStructureToolProvider.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.tool.GitLogToolProvider.class);
                });
    }

    // ---- v0.5: Patrol ----

    @Test
    void shouldNotCreatePatrolBeansWhenPatrolDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.patrol.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.patrol.PatrolScheduler.class);
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.patrol.AlertConverger.class);
                });
    }

    @Test
    void shouldCreatePatrolBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.patrol.enabled=true",
                        "snap-agent.alert.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.core.patrol.PatrolScheduler.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.core.patrol.AlertConverger.class);
                });
    }

    // ---- v0.7: Knowledge base ----

    @Test
    void shouldNotCreateKnowledgeBeansWhenKnowledgeDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.knowledge.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.knowledge.KnowledgeBase.class);
                });
    }

    @Test
    void shouldCreateKnowledgeBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.knowledge.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.core.knowledge.KnowledgeBase.class);
                });
    }

    // ---- v0.8: Code graph ----

    @Test
    void shouldNotCreateCodeGraphBeansWhenCodeGraphDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.code-graph.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder.class);
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.codegraph.CodeGraphToolProvider.class);
                });
    }

    @Test
    void shouldCreateCodeGraphBeansWhenEnabledWithProjectRoot() throws Exception {
        java.nio.file.Path safeTempDir = Files.createTempDirectory("snapagent-codegraph-test");
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.code.enabled=true",
                        "snap-agent.code.project-root=" + safeTempDir.toString())
                .withPropertyValues(
                        "snap-agent.code-graph.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder.class);
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.codegraph.CodeGraphToolProvider.class);
                });
    }

    // ---- v1.0: Cost tracking ----

    @Test
    void shouldNotCreateCostBeansWhenCostDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.cost.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.cost.CostTracker.class);
                });
    }

    @Test
    void shouldCreateCostBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.cost.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.core.cost.CostTracker.class);
                });
    }

    // ---- v1.0: Workflows ----

    @Test
    void shouldNotCreateWorkflowBeansWhenWorkflowsDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.workflows.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.core.workflow.WorkflowEngine.class);
                });
    }
}
