package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.HttpExecutor;
import cn.watsontech.snapagent.core.issue.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridge-aware {@link HttpExecutor} with status-first routing.
 *
 * <p>Checks {@link IssueBridgeService#isBridgeActive(String)} before each request:
 * <ul>
 *   <li>If the extension is installed AND master switch is ON AND the service
 *       proxy is enabled → routes directly through the SSE bridge (no direct
 *       connection attempt).</li>
 *   <li>Otherwise → delegates to {@link DirectHttpExecutor} (behavior identical
 *       to {@code bridge.enabled=false}).</li>
 * </ul>
 *
 * <p>Each instance is tagged with a {@code serviceType} (e.g. {@code "issue-tracker"},
 * {@code "vcs"}) so the extension can decide whether to proxy based on its
 * per-service configuration.</p>
 */
public class BridgeHttpExecutor implements HttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(BridgeHttpExecutor.class);

    private final HttpExecutor directExecutor;
    private final IssueBridgeService bridgeService;
    private final String serviceType;

    public BridgeHttpExecutor(HttpExecutor directExecutor,
                              IssueBridgeService bridgeService,
                              String serviceType) {
        this.directExecutor = directExecutor;
        this.bridgeService = bridgeService;
        this.serviceType = serviceType;
    }

    @Override
    public HttpResponse execute(String url, String method,
                                Map<String, String> headers, Object body) {
        if (bridgeService.isBridgeActive(serviceType)) {
            log.debug("Bridge active for [{}], proxying {} [{}]",
                      serviceType, method, url);
            return bridgeService.proxyRequest(serviceType, url, method, headers, body);
        }
        return directExecutor.execute(url, method, headers, body);
    }

    public String getServiceType() {
        return serviceType;
    }
}
