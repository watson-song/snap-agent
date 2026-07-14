package cn.watsontech.snapagent.boot2x.web;

import cn.watsontech.snapagent.core.security.SecurityGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * javax.servlet Filter that injects the authenticated principal into
 * {@link AgentRequestContext} for {@code /snap-agent/**} requests.
 *
 * <p>Does NOT perform authentication — that is delegated to the host's
 * security framework. This filter only reads the already-authenticated
 * principal via {@link SecurityGateway} (design doc 07 §4).</p>
 *
 * <p>Registered with order {@code Ordered.LOWEST_PRECEDENCE - 10} so it
 * runs after the host's security filter chain.</p>
 */
public class SnapAgentFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SnapAgentFilter.class);

    private final SecurityGateway securityGateway;
    private final String basePath;

    public SnapAgentFilter(SecurityGateway securityGateway) {
        this(securityGateway, "/snap-agent");
    }

    public SnapAgentFilter(SecurityGateway securityGateway, String basePath) {
        this.securityGateway = securityGateway;
        this.basePath = basePath != null ? basePath : "/snap-agent";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestUri = httpRequest.getRequestURI();

        if (!requestUri.startsWith(basePath)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String userId = securityGateway.currentUserId();
            AgentRequestContext.setUserId(userId);
            log.debug("SnapAgentFilter: request {} -> userId={}", requestUri, userId);

            chain.doFilter(request, response);
        } finally {
            AgentRequestContext.clear();
        }
    }
}
