package com.watsontech.snapagent.core.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A full conversation: metadata + ordered list of messages.
 *
 * <p>Immutable; modifications return new instances.</p>
 */
public final class Conversation {

    private final String id;
    private final String userId;
    private final String skillId;
    private final String title;
    private final long createdAt;
    private final long updatedAt;
    private final List<ConversationMessage> messages;

    public Conversation(String id, String userId, String skillId, String title,
                        long createdAt, long updatedAt,
                        List<ConversationMessage> messages) {
        this.id = id;
        this.userId = userId;
        this.skillId = skillId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages = messages != null
                ? Collections.unmodifiableList(new ArrayList<ConversationMessage>(messages))
                : Collections.<ConversationMessage>emptyList();
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

    public List<ConversationMessage> getMessages() {
        return messages;
    }

    public int getMessageCount() {
        return messages.size();
    }

    @Override
    public String toString() {
        return "Conversation{id='" + id + "', skillId='" + skillId
                + "', messages=" + messages.size() + "'}";
    }
}
