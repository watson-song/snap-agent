package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.List;

/**
 * Chat memory SPI for multi-turn conversation history.
 *
 * <p>Stores messages per conversation ID, supports retrieving the last N
 * messages, and clearing a conversation. Implementations include
 * {@link MessageWindowChatMemory} (sliding window in front of a
 * {@link ChatMemoryRepository}).</p>
 *
 * <p>Replaces the old v0.7 {@code ConversationStore}.</p>
 */
public interface ChatMemory {

    /**
     * Append a message to the conversation history.
     *
     * @param conversationId the conversation identifier (e.g. thread ID or user ID)
     * @param message        the message to add
     */
    void add(String conversationId, Message message);

    /**
     * Retrieve the last N messages from the conversation, respecting the
     * sliding window. System messages are always retained.
     *
     * @param conversationId the conversation identifier
     * @param lastN          maximum number of messages to return
     * @return list of messages (oldest first), never null
     */
    List<Message> get(String conversationId, int lastN);

    /**
     * Clear all messages for the given conversation.
     *
     * @param conversationId the conversation identifier
     */
    void clear(String conversationId);
}
