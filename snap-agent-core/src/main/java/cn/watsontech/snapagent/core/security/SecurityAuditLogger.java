package cn.watsontech.snapagent.core.security;

import java.util.Map;

/**
 * Security audit logger SPI — allows host applications to record audit events
 * for SnapAgent API access (model listing, skill runs, skill uploads, etc.).
 *
 * <p>The starter provides a default {@code LoggingSecurityAuditLogger} that
 * writes to SLF4J. Host applications can override it by registering a
 * {@code @Bean SecurityAuditLogger} in their context.</p>
 *
 * <p>Activated only when {@code snap-agent.security.audit-log=true} (default).</p>
 */
public interface SecurityAuditLogger {

    /**
     * Called when a user accesses a SnapAgent API endpoint.
     *
     * @param userId  the authenticated user id (never null)
     * @param method  HTTP method (GET, POST, DELETE)
     * @param path    request path (e.g. "/snap-agent/models")
     * @param action  logical action name (e.g. "LIST_MODELS", "RUN_SKILL",
     *                "UPLOAD_SKILL", "DELETE_SKILL")
     * @param details additional context (e.g. skill name, model used); may be empty
     */
    void onApiAccess(String userId, String method, String path, String action,
                     Map<String, Object> details);
}
