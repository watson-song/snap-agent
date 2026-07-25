package cn.watsontech.snapagent.core.security;

import cn.watsontech.snapagent.core.agent.AuditRecord;

import java.util.List;

/**
 * SPI for storing and querying audit entries.
 * Host apps can implement this to persist to a database.
 */
public interface AuditStore {

    void record(AuditEntry entry);

    /**
     * Saves a tool/LLM invocation audit record.
     * Default is noop — host implementations override.
     */
    default void saveRecord(AuditRecord record) { }

    /**
     * Lists audit records for a specific task.
     */
    default List<AuditRecord> listByTask(String taskId) {
        return java.util.Collections.emptyList();
    }

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
