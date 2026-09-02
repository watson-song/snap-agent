package cn.watsontech.snapagent.boot2x.security;

import cn.watsontech.snapagent.core.security.AuditEntry;
import cn.watsontech.snapagent.core.security.AuditStore;
import cn.watsontech.snapagent.core.security.SecurityAuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Bridges {@link SecurityAuditLogger} to {@link AuditStore} — records every
 * API audit event into the store AND logs to SLF4J (backward compatible with
 * the previous {@code LoggingSecurityAuditLogger} behavior).
 *
 * <p>This is the default {@code SecurityAuditLogger} wired by
 * {@code SnapAgentAutoConfiguration} when {@code snap-agent.security.audit-log=true}
 * (the default). Host applications can still override by registering their own
 * {@code @Bean SecurityAuditLogger}.</p>
 */
public class AuditStoreAuditLogger implements SecurityAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditStoreAuditLogger.class);
    private final AuditStore auditStore;

    public AuditStoreAuditLogger(AuditStore auditStore) {
        this.auditStore = auditStore;
    }

    @Override
    public void onApiAccess(String userId, String method, String path, String action,
                            Map<String, Object> details) {
        log.info("[AUDIT] user={} {} {} action={} details={}", userId, method, path, action, details);
        auditStore.record(new AuditEntry(userId, method, path, action, details, System.currentTimeMillis()));
    }
}
