package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;

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

    /**
     * P2.2: Refills conversation messages from a ChatMemoryRepository.
     *
     * <p>This method synchronizes the conversation's message list with the
     * messages stored in the ChatMemoryRepository. It's useful for:</p>
     * <ul>
     *   <li>Restoring conversation history after application restart</li>
     *   <li>Merging two storage systems (ConversationStore + ChatMemoryRepository)</li>
     *   <li>Ensuring message consistency across different storage layers</li>
     * </ul>
     *
     * <p>The implementation should:</p>
     * <ol>
     *   <li>Load messages from the ChatMemoryRepository for the given conversationId</li>
     *   <li>Convert core Message objects to ConversationMessage objects</li>
     *   <li>Update the conversation's message list</li>
     *   <li>Save the updated conversation</li>
     * </ol>
     *
     * <p>Default implementation is a no-op for backward compatibility.</p>
     *
     * @param conversationId the conversation ID to refill
     * @param userId         the conversation owner (for ownership check)
     * @param chatMemoryRepo the ChatMemoryRepository to load messages from
     * @return the updated conversation with refilled messages, or {@code null} if not found
     */
    default Conversation refillFromChatMemory(String conversationId, String userId,
                                              ChatMemoryRepository chatMemoryRepo) {
        // Default no-op implementation for backward compatibility
        return load(conversationId, userId);
    }

    /**
     * P2.2: Refills conversation messages from a ChatMemoryRepository without userId check.
     *
     * <p>This is a convenience method for startup refills where userId is not available
     * in the ChatMemoryRepository. The implementation should skip ownership verification.</p>
     *
     * <p>Default implementation delegates to {@link #refillFromChatMemory(String, String, ChatMemoryRepository)}
     * with userId=null.</p>
     *
     * @param conversationId the conversation ID to refill
     * @param chatMemoryRepo the ChatMemoryRepository to load messages from
     * @return the updated conversation with refilled messages, or {@code null} if not found
     */
    default Conversation refillFromChatMemory(String conversationId,
                                              ChatMemoryRepository chatMemoryRepo) {
        return refillFromChatMemory(conversationId, null, chatMemoryRepo);
    }
}
