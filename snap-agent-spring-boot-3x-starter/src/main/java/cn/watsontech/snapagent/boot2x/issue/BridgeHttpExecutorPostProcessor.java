package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.vcs.AbstractHttpVcsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Injects {@link BridgeHttpExecutor} into {@link AbstractHttpIssueTracker}
 * and {@link AbstractHttpVcsClient} beans when the bridge is enabled.
 *
 * <p>Each instance gets a {@code BridgeHttpExecutor} tagged with the
 * appropriate {@code serviceType} ({@code "issue-tracker"} or
 * {@code "vcs"}). {@code NoopIssueTracker} is skipped because it does
 * not make HTTP calls.</p>
 */
public class BridgeHttpExecutorPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(BridgeHttpExecutorPostProcessor.class);

    private final BridgeHttpExecutor issueTrackerBridge;
    private final BridgeHttpExecutor vcsBridge;

    public BridgeHttpExecutorPostProcessor(BridgeHttpExecutor issueTrackerBridge,
                                           BridgeHttpExecutor vcsBridge) {
        this.issueTrackerBridge = issueTrackerBridge;
        this.vcsBridge = vcsBridge;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AbstractHttpIssueTracker) {
            AbstractHttpIssueTracker tracker = (AbstractHttpIssueTracker) bean;
            tracker.httpExecutor = issueTrackerBridge;
            log.info("Injected BridgeHttpExecutor [issue-tracker] into {} ({})",
                     bean.getClass().getSimpleName(), beanName);
        } else if (bean instanceof AbstractHttpVcsClient) {
            AbstractHttpVcsClient client = (AbstractHttpVcsClient) bean;
            client.httpExecutor = vcsBridge;
            log.info("Injected BridgeHttpExecutor [vcs] into {} ({})",
                     bean.getClass().getSimpleName(), beanName);
        }
        return bean;
    }
}
