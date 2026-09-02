package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.metrics.MicrometerMetricsCollector;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.metrics.MetricsCollector;
import cn.watsontech.snapagent.core.metrics.MicrometerObservationAdvisor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for SnapAgent metrics and observability.
 *
 * <p>Activated when Micrometer is on the classpath (typically via
 * {@code spring-boot-starter-actuator}). The {@link MicrometerObservationAdvisor}
 * is always registered; when no {@link MeterRegistry} bean is present, the advisor
 * receives null and noops without throwing.</p>
 *
 * <p>When a custom {@link MetricsCollector} bean is declared by the host application,
 * it takes precedence and is wired into the advisor instead.</p>
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class MetricsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MetricsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "micrometerObservationAdvisor")
    public Advisor micrometerObservationAdvisor(
            ObjectProvider<MetricsCollector> metricsCollectorProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MetricsCollector collector = metricsCollectorProvider.getIfAvailable();
        if (collector == null) {
            MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
            if (meterRegistry != null) {
                log.info("MicrometerMetricsCollector assembled (registry={})",
                        meterRegistry.getClass().getSimpleName());
                collector = new MicrometerMetricsCollector(meterRegistry);
            } else {
                log.info("MicrometerObservationAdvisor assembled in noop mode (no MeterRegistry)");
            }
        } else {
            log.info("MicrometerObservationAdvisor assembled with custom MetricsCollector");
        }
        return new MicrometerObservationAdvisor(collector);
    }
}
