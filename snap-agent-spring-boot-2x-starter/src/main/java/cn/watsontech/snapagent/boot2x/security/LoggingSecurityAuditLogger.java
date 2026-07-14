package cn.watsontech.snapagent.boot2x.security;

import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Default {@link SecurityAuditLogger} that writes audit events to SLF4J.
 *
 * <p>Host applications can override by registering a
 * {@code @Bean SecurityAuditLogger} in their context.</p>
 */
public class LoggingSecurityAuditLogger implements SecurityAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(LoggingSecurityAuditLogger.class);

    @Override
    public void onApiAccess(String userId, String method, String path, String action,
                            Map<String, Object> details) {
        if (log.isInfoEnabled()) {
            log.info("[AUDIT] user={} {} {} action={} details={}", userId, method, path, action, details);
        }
    }
}
