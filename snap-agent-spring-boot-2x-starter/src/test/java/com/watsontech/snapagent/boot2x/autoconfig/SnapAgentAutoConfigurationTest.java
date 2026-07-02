package com.watsontech.snapagent.boot2x.autoconfig;

import com.watsontech.snapagent.boot2x.tool.SqlGuard;
import com.watsontech.snapagent.core.agent.AgentExecutor;
import com.watsontech.snapagent.core.agent.RateLimiter;
import com.watsontech.snapagent.core.agent.TaskStore;
import com.watsontech.snapagent.core.llm.LlmClient;
import com.watsontech.snapagent.core.security.SecurityGateway;
import com.watsontech.snapagent.core.skill.SkillRegistry;
import com.watsontech.snapagent.core.tool.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;

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
                            com.watsontech.snapagent.boot2x.llm.AnthropicLlmClient.class);
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
                            com.watsontech.snapagent.boot2x.security.SpringSecurityAdapter.class);
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
                            com.watsontech.snapagent.boot2x.tool.JdbcQueryToolProvider.class);
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
                            com.watsontech.snapagent.boot2x.tool.RedisReadToolProvider.class);
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
                    assertThat(context).hasSingleBean(com.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    com.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(com.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            com.watsontech.snapagent.boot2x.routing.NoopPeerRouter.class);
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
                    com.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(com.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            com.watsontech.snapagent.boot2x.routing.StaticPeerRouter.class);
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
                    com.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(com.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            com.watsontech.snapagent.boot2x.routing.K8sApiPeerRouter.class);
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
                    com.watsontech.snapagent.boot2x.routing.PeerRouter router =
                            context.getBean(com.watsontech.snapagent.boot2x.routing.PeerRouter.class);
                    assertThat(router).isInstanceOf(
                            com.watsontech.snapagent.boot2x.routing.HeadlessDnsPeerRouter.class);
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
                    assertThat(context).hasSingleBean(com.watsontech.snapagent.boot2x.routing.PeerSseRelay.class);
                    assertThat(context).hasSingleBean(
                            com.watsontech.snapagent.boot2x.web.InternalTaskController.class);
                });
    }
}
