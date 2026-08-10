package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.bridge.FileBridgeService;
import cn.watsontech.snapagent.boot2x.web.FileBridgeController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for file bridge components.
 *
 * <p>Activated when {@code snap-agent.bridge.enabled=true}.</p>
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>{@link FileBridgeService} — manages SSE emitters and pending file reads</li>
 *   <li>{@link FileBridgeController} — REST endpoints for file bridge</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent.bridge", name = "enabled", havingValue = "true")
public class FileBridgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FileBridgeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public FileBridgeService fileBridgeService() {
        long timeout = 30000L; // 30s default
        log.info("FileBridgeService initialized (timeout={}ms)", timeout);
        return new FileBridgeService(timeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileBridgeController fileBridgeController(FileBridgeService fileBridgeService) {
        return new FileBridgeController(fileBridgeService);
    }
}
