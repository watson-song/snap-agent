package cn.watsontech.snapagent.core.security;

import java.util.Map;

/**
 * Immutable record of an API-level audit event.
 */
public final class AuditEntry {
    private final String userId;
    private final String method;
    private final String path;
    private final String action;
    private final Map<String, Object> details;
    private final long timestamp;

    public AuditEntry(String userId, String method, String path, String action,
                      Map<String, Object> details, long timestamp) {
        this.userId = userId;
        this.method = method;
        this.path = path;
        this.action = action;
        this.details = details != null ? java.util.Collections.unmodifiableMap(new java.util.HashMap<>(details)) : null;
        this.timestamp = timestamp;
    }

    public String getUserId() { return userId; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getAction() { return action; }
    public Map<String, Object> getDetails() { return details; }
    public long getTimestamp() { return timestamp; }
}
