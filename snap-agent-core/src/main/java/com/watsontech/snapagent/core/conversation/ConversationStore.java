package com.watsontech.snapagent.core.conversation;

import java.util.List;

/**
 * SPI for persisting and retrieving conversations.
 *
 * <p>Host applications can implement this interface to store conversations
 * in a database or external system. The starter module provides a default
 * {@code FileConversationStore} that saves conversations as JSON files
 * under the upload-skills directory.</p>
 *
 * <p>All methods receive a {@code userId} parameter so that conversations
 * are isolated per user. Implementations must verify that the conversation
 * belongs to the given user before returning or modifying data.</p>
 */
public interface ConversationStore {

    /**
     * Saves (creates or updates) a conversation.
     *
     * <p>If {@code conversation.getId()} is null or empty, a new ID is generated.
     * If the ID already exists for this user, the conversation is updated.</p>
     *
     * @param conversation the conversation to save (userId must be set)
     * @return the saved conversation (with generated ID and timestamps if new)
     */
    Conversation save(Conversation conversation);

    /**
     * Loads a conversation by ID.
     *
     * @param conversationId the conversation ID
     * @param userId         the requesting user (for ownership check)
     * @return the conversation, or {@code null} if not found or not owned by the user
     */
    Conversation load(String conversationId, String userId);

    /**
     * Lists conversation summaries for a user, optionally filtered by skill.
     *
     * @param userId  the requesting user
     * @param skillId optional skill filter (null or empty = all skills)
     * @return list of summaries sorted by updatedAt descending (newest first)
     */
    List<ConversationSummary> list(String userId, String skillId);

    /**
     * Deletes a conversation.
     *
     * @param conversationId the conversation ID
     * @param userId         the requesting user (for ownership check)
     * @return {@code true} if deleted, {@code false} if not found or not owned
     */
    boolean delete(String conversationId, String userId);

    /**
     * Exports a conversation as markdown text.
     *
     * @param conversationId the conversation ID
     * @param userId         the requesting user (for ownership check)
     * @return markdown string, or {@code null} if not found or not owned
     */
    String exportMarkdown(String conversationId, String userId);
}
