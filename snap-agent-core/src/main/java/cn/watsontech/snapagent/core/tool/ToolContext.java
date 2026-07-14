package cn.watsontech.snapagent.core.tool;

/**
 * Context object passed to {@link ToolProvider#execute} carrying request-scoped
 * information: task id, user id and an audit callback.
 */
public final class ToolContext {

    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) {
        this.taskId = taskId;
        this.userId = userId;
        this.auditCallback = auditCallback;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public AuditCallback getAuditCallback() {
        return auditCallback;
    }
}
