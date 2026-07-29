package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.issue.BridgeHttpExecutor;
import cn.watsontech.snapagent.boot2x.issue.BridgeHttpExecutorPostProcessor;
import cn.watsontech.snapagent.boot2x.issue.BridgeStatus;
import cn.watsontech.snapagent.boot2x.issue.IssueBridgeService;
import cn.watsontech.snapagent.boot2x.web.BridgeController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BridgeAutoConfiguration} conditional assembly.
 *
 * <p>Tests that bridge beans are only created when both
 * {@code snap-agent.enabled=true} AND {@code snap-agent.bridge.enabled=true}.</p>
 */
class BridgeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SnapAgentAutoConfiguration.class));

    @Test
    void shouldNotCreateBridgeBeansWhenBridgeDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.bridge.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IssueBridgeService.class);
                    assertThat(context).doesNotHaveBean(BridgeHttpExecutor.class);
                    assertThat(context).doesNotHaveBean(BridgeController.class);
                });
    }

    @Test
    void shouldNotCreateBridgeBeansWhenBridgeNotConfigured() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IssueBridgeService.class);
                    assertThat(context).doesNotHaveBean(BridgeController.class);
                });
    }

    @Test
    void shouldNotCreateBridgeBeansWhenAgentDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=false",
                        "snap-agent.bridge.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IssueBridgeService.class);
                    assertThat(context).doesNotHaveBean(BridgeController.class);
                });
    }

    @Test
    void shouldCreateBridgeBeansWhenBothEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.bridge.enabled=true",
                        "snap-agent.bridge.request-timeout-ms=15000",
                        "snap-agent.bridge.allowed-host-patterns[0]=*.example.com",
                        "snap-agent.bridge.allowed-host-patterns[1]=localhost")
                .run(context -> {
                    assertThat(context).hasSingleBean(IssueBridgeService.class);
                    assertThat(context).hasSingleBean(BridgeController.class);
                    // Two BridgeHttpExecutor beans: issue-tracker + vcs
                    assertThat(context.getBeansOfType(BridgeHttpExecutor.class)).hasSize(2);
                    assertThat(context).hasSingleBean(BridgeHttpExecutorPostProcessor.class);
                });
    }

    @Test
    void shouldWireBridgePropertiesIntoService() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.llm.api-key=sk-test",
                        "snap-agent.bridge.enabled=true",
                        "snap-agent.bridge.request-timeout-ms=5000")
                .run(context -> {
                    IssueBridgeService svc = context.getBean(IssueBridgeService.class);
                    BridgeStatus status = svc.getStatus();
                    assertThat(status).isNotNull();
                    assertThat(status.isConnected()).isFalse();
                });
    }
}
