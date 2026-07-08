package com.watsontech.snapagent.core.conversation;

/**
 * A single message in a conversation (user or assistant turn).
 *
 * <p>Immutable; created via static factory methods.</p>
 */
public final class ConversationMessage {

    private final String role;
    private final String content;
    private final long timestamp;

    public ConversationMessage(String role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    /** Creates a user message with the current timestamp. */
    public static ConversationMessage user(String content) {
        return new ConversationMessage("user", content, System.currentTimeMillis());
    }

    /** Creates an assistant message with the current timestamp. */
    public static ConversationMessage assistant(String content) {
        return new ConversationMessage("assistant", content, System.currentTimeMillis());
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

    @Override
    public String toString() {
        return "ConversationMessage{role='" + role + "', content.length="
                + (content != null ? content.length() : 0) + "'}";
    }
}
