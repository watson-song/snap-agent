package com.watsontech.snapagent.core.conversation;

/**
 * Summary of a conversation for list views (no message bodies).
 *
 * <p>Immutable.</p>
 */
public final class ConversationSummary {

    private final String id;
    private final String userId;
    private final String skillId;
    private final String title;
    private final long createdAt;
    private final long updatedAt;
    private final int messageCount;

    public ConversationSummary(String id, String userId, String skillId, String title,
                               long createdAt, long updatedAt, int messageCount) {
        this.id = id;
        this.userId = userId;
        this.skillId = skillId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
    }

    /** Builds a summary from a full conversation. */
    public static ConversationSummary from(Conversation conv) {
        return new ConversationSummary(
                conv.getId(), conv.getUserId(), conv.getSkillId(),
                conv.getTitle(), conv.getCreatedAt(), conv.getUpdatedAt(),
                conv.getMessageCount());
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getSkillId() {
        return skillId;
    }

    public String getTitle() {
        return title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    @Override
    public String toString() {
        return "ConversationSummary{id='" + id + "', skillId='" + skillId
                + "', messageCount=" + messageCount + "'}";
    }
}
