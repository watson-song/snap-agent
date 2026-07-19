package cn.watsontech.snapagent.core.security;

import java.util.List;

/**
 * SPI for storing and querying audit entries.
 * Host apps can implement this to persist to a database.
 */
public interface AuditStore {

    void record(AuditEntry entry);

    /**
     * Query audit entries with optional filters.
     *
     * @param userId optional user filter, null = all users
     * @param action optional action filter, null = all actions
     * @param limit max results
     * @param offset zero-based offset
     * @return entries sorted by timestamp descending (newest first)
     */
    List<AuditEntry> query(String userId, String action, int limit, int offset);

    /** Total count for optional filters (null = no filter). */
    int count(String userId, String action);
}
