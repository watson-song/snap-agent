package cn.watsontech.snapagent.core.conversation;

/**
 * A single message in a conversation (user or assistant turn).
 *
 * <p>Immutable; created via static factory methods.</p>
 */
public final class ConversationMessage {

    private final String role;
    private final String content;
    private final long timestamp;
    /** Optional: the task ID that produced this assistant message, so the UI can attach per-message
     *  action buttons (建议方案 / 创建 Issue) after a page refresh. Null for user messages or legacy conversations. */
    private final String taskId;

    public ConversationMessage(String role, String content, long timestamp) {
        this(role, content, timestamp, null);
    }

    public ConversationMessage(String role, String content, long timestamp, String taskId) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.taskId = taskId;
    }

    /** Creates a user message with the current timestamp. */
    public static ConversationMessage user(String content) {
        return new ConversationMessage("user", content, System.currentTimeMillis(), null);
    }

    /** Creates an assistant message with the current timestamp. */
    public static ConversationMessage assistant(String content) {
        return new ConversationMessage("assistant", content, System.currentTimeMillis(), null);
    }

    /** Creates an assistant message tied to a specific task ID (for issue-closure button persistence). */
    public static ConversationMessage assistantWithTask(String content, String taskId) {
        return new ConversationMessage("assistant", content, System.currentTimeMillis(), taskId);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getTaskId() {
        return taskId;
    }

    @Override
    public String toString() {
        return "ConversationMessage{role='" + role + "', content.length="
                + (content != null ? content.length() : 0) + ", taskId="
                + (taskId != null ? taskId : "null") + "}";
    }
}
