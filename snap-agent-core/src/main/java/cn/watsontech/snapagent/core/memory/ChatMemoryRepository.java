package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.List;

/**
 * Persistence SPI for chat memory.
 *
 * <p>Implementations store the full message list per conversation.
 * {@link MessageWindowChatMemory} wraps this and applies the sliding
 * window policy on read.</p>
 */
public interface ChatMemoryRepository {

    /**
     * Save the full message list for a conversation (replaces previous).
     *
     * @param conversationId the conversation identifier
     * @param messages       the complete message list
     */
    void save(String conversationId, List<Message> messages);

    /**
     * Load the full message list for a conversation.
     *
     * @param conversationId the conversation identifier
     * @return list of messages (oldest first), never null
     */
    List<Message> load(String conversationId);

    /**
     * Delete all messages for a conversation.
     *
     * @param conversationId the conversation identifier
     */
    void delete(String conversationId);
}
