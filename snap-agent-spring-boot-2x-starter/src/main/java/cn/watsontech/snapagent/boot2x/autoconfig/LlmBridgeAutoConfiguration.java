package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.bridge.LlmBridgeService;
import cn.watsontech.snapagent.boot2x.llm.BridgeLlmClient;
import cn.watsontech.snapagent.boot2x.llm.FallbackLlmClient;
import cn.watsontech.snapagent.boot2x.web.LlmBridgeController;
import cn.watsontech.snapagent.core.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for LLM bridge components.
 *
 * <p>Always enabled when snap-agent is active. The bridge provides optional
 * LLM routing through the browser extension — if the extension is connected
 * and active, requests are routed through it; otherwise falls back to direct API.</p>
 *
 * <p>Activation flow:
 * <ul>
 *   <li>If browser extension is connected with LLM bridge enabled → use bridge</li>
 *   <li>Otherwise → fall back to configured LLM client (Anthropic/OpenAI)</li>
 *   <li>If neither available → error at request time</li>
 * </ul>
 */
@Configuration
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
    public BridgeLlmClient bridgeLlmClient(LlmBridgeService llmBridgeService, SnapAgentProperties props) {
        long timeout = props.getLlm().getTimeoutSeconds() * 1000L;
        log.info("BridgeLlmClient initialized (timeout={}ms)", timeout);
        return new BridgeLlmClient(llmBridgeService, timeout);
    }

    /**
     * The main LlmClient bean — wraps primary client (Anthropic/OpenAI) with bridge fallback.
     * Tries browser bridge first if active, otherwise uses direct API.
     */
    @Bean
    @Primary
    public LlmClient llmClient(ObjectProvider<LlmClient> primaryClientProvider,
                                BridgeLlmClient bridgeLlmClient,
                                LlmBridgeService llmBridgeService) {
        // Find the primary client (not the bridge or this bean itself)
        LlmClient primary = null;
        int count = 0;
        for (LlmClient client : primaryClientProvider) {
            count++;
            log.debug("Checking LlmClient bean #{}: {} (class={})", count, client, client.getClass().getName());
            if (!(client instanceof BridgeLlmClient) && !(client instanceof FallbackLlmClient)) {
                primary = client;
                log.info("Found primary LlmClient: {}", primary.getClass().getSimpleName());
                break;
            } else {
                log.debug("Skipping {} (bridge/fallback type)", client.getClass().getSimpleName());
            }
        }
        log.info("Total LlmClient beans found: {}", count);

        if (primary != null) {
            log.info("FallbackLlmClient assembled: bridge-first with {} fallback",
                    primary.getClass().getSimpleName());
            return new FallbackLlmClient(primary, bridgeLlmClient, llmBridgeService);
        }
        // No primary client — bridge-only mode
        log.info("No primary LlmClient configured, using bridge-only mode");
        return bridgeLlmClient;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmBridgeController llmBridgeController(LlmBridgeService llmBridgeService) {
        return new LlmBridgeController(llmBridgeService);
    }
}
