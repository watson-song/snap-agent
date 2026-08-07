package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.boot2x.llm.BridgeLlmClient;
import cn.watsontech.snapagent.boot2x.web.LlmBridgeController;
import cn.watsontech.snapagent.core.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for LLM bridge components.
 * 
 * <p>Activated when {@code snap-agent.llm.api-type=bridge}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent.llm", name = "api-type", havingValue = "bridge")
public class LlmBridgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmBridgeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public LlmBridgeService llmBridgeService(SnapAgentProperties props) {
        long timeout = props.getBridge().getRequestTimeoutMs();
        log.info("LlmBridgeService initialized (timeout={}ms)", timeout);
        return new LlmBridgeService(timeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmClient llmClient(LlmBridgeService llmBridgeService, SnapAgentProperties props) {
        long timeout = props.getLlm().getTimeoutSeconds() * 1000L;
        log.info("BridgeLlmClient initialized (timeout={}ms)", timeout);
        return new BridgeLlmClient(llmBridgeService, timeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmBridgeController llmBridgeController(LlmBridgeService llmBridgeService) {
        return new LlmBridgeController(llmBridgeService);
    }
}
