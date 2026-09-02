package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.issue.BridgeHttpExecutor;
import cn.watsontech.snapagent.boot2x.issue.BridgeHttpExecutorPostProcessor;
import cn.watsontech.snapagent.boot2x.issue.DirectHttpExecutor;
import cn.watsontech.snapagent.boot2x.issue.IssueBridgeService;
import cn.watsontech.snapagent.boot2x.web.BridgeController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the browser network bridge.
 *
 * <p>Activated only when BOTH {@code snap-agent.enabled=true} AND
 * {@code snap-agent.bridge.enabled=true}. When the bridge is disabled
 * (the default), no beans are created and behavior is identical to
 * a deployment without the bridge feature.</p>
 *
 * <p>Beans assembled:
 * <ul>
 *   <li>{@link IssueBridgeService} — SSE emitter management, pending request
 *       tracking, extension status tracking.</li>
 *   <li>{@link BridgeHttpExecutor} (issue-tracker) — wraps
 *       {@link DirectHttpExecutor} with bridge routing for issue trackers.</li>
 *   <li>{@link BridgeHttpExecutor} (vcs) — same for VCS clients.</li>
 *   <li>{@link BridgeHttpExecutorPostProcessor} — transparently injects
 *       the bridge executors into {@code AbstractHttpIssueTracker} and
 *       {@code AbstractHttpVcsClient} beans.</li>
 *   <li>{@link BridgeController} — REST + SSE endpoints for the frontend.</li>
 * </ul>
 */
@Configuration
@ConditionalOnExpression("${snap-agent.enabled:false} and ${snap-agent.bridge.enabled:false}")
public class BridgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BridgeAutoConfiguration.class);

    @Bean
    public IssueBridgeService issueBridgeService(SnapAgentProperties props) {
        SnapAgentProperties.Bridge bridge = props.getBridge();
        long timeoutMs = bridge.getRequestTimeoutMs();
        java.util.List<String> allowedHosts = bridge.getAllowedHostPatterns();
        log.info("IssueBridgeService assembled (timeout={}ms, allowed-hosts={})",
                timeoutMs, allowedHosts.size());
        return new IssueBridgeService(timeoutMs, allowedHosts);
    }

    @Bean
    public BridgeHttpExecutor issueTrackerBridgeExecutor(IssueBridgeService bridgeService) {
        DirectHttpExecutor direct = new DirectHttpExecutor();
        log.info("BridgeHttpExecutor [issue-tracker] assembled");
        return new BridgeHttpExecutor(direct, bridgeService, "issue-tracker");
    }

    @Bean
    public BridgeHttpExecutor vcsBridgeExecutor(IssueBridgeService bridgeService) {
        DirectHttpExecutor direct = new DirectHttpExecutor();
        log.info("BridgeHttpExecutor [vcs] assembled");
        return new BridgeHttpExecutor(direct, bridgeService, "vcs");
    }

    @Bean
    public BridgeHttpExecutorPostProcessor bridgeHttpExecutorPostProcessor(
            BridgeHttpExecutor issueTrackerBridgeExecutor,
            BridgeHttpExecutor vcsBridgeExecutor) {
        log.info("BridgeHttpExecutorPostProcessor assembled");
        return new BridgeHttpExecutorPostProcessor(
                issueTrackerBridgeExecutor, vcsBridgeExecutor);
    }

    @Bean
    public BridgeController bridgeController(IssueBridgeService bridgeService) {
        log.info("BridgeController assembled");
        return new BridgeController(bridgeService);
    }
}
