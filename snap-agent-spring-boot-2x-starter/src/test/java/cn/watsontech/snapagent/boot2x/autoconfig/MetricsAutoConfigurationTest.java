package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.metrics.MetricsCollector;
import cn.watsontech.snapagent.core.metrics.MicrometerObservationAdvisor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetricsAutoConfiguration")
class MetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class));

    @Test
    @DisplayName("no MeterRegistry → advisor registered in noop mode")
    void shouldRegisterAdvisorNoopWithoutMeterRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("micrometerObservationAdvisor");
            Advisor advisor = context.getBean("micrometerObservationAdvisor", Advisor.class);
            assertThat(advisor).isInstanceOf(MicrometerObservationAdvisor.class);
            assertThat(advisor.getOrder()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("MeterRegistry present → advisor registered with MicrometerMetricsCollector")
    void shouldCreateAdvisorWithCollectorWhenMeterRegistryPresent() {
        contextRunner
                .withUserConfiguration(TestMeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("micrometerObservationAdvisor");
                    Advisor advisor = context.getBean("micrometerObservationAdvisor", Advisor.class);
                    assertThat(advisor).isInstanceOf(MicrometerObservationAdvisor.class);
                    assertThat(advisor.getOrder()).isEqualTo(10);
                });
    }

    @Test
    @DisplayName("custom MetricsCollector bean → advisor uses it instead of auto-creating")
    void shouldUseCustomCollectorWhenPresent() {
        contextRunner
                .withUserConfiguration(CustomCollectorConfig.class, TestMeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasBean("micrometerObservationAdvisor");
                    // Custom MetricsCollector bean exists
                    assertThat(context.getBean("customCollector")).isInstanceOf(MetricsCollector.class);
                });
    }

    @Configuration
    static class TestMeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class CustomCollectorConfig {
        @Bean("customCollector")
        MetricsCollector customCollector() {
            return new MetricsCollector() {
                @Override public void recordTimer(String name, long durationMs, java.util.Map<String, String> tags) {}
                @Override public void incrementCounter(String name, java.util.Map<String, String> tags) {}
                @Override public void incrementCounter(String name, long amount, java.util.Map<String, String> tags) {}
                @Override public void recordError(String errorType, java.util.Map<String, String> tags) {}
            };
        }
    }
}
