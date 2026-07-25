package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window {@link ChatMemory} implementation.
 *
 * <p>Retains at most {@code maxMessages} messages per conversation, evicting
 * oldest non-system messages when the window is exceeded. System messages
 * (role="system") are always retained — they are never evicted by the window.</p>
 *
 * <p>The eviction policy evicts one complete turn at a time (user+assistant
 * pair) rather than a single message, ensuring conversation coherence.</p>
 */
public class MessageWindowChatMemory implements ChatMemory {

    /** Default maximum messages per conversation. */
    public static final int DEFAULT_MAX_MESSAGES = 20;

    private final ChatMemoryRepository repository;
    private final int maxMessages;

    /**
     * Construct with default window size of 20.
     *
     * @param repository the backing repository
     */
    public MessageWindowChatMemory(ChatMemoryRepository repository) {
        this(repository, DEFAULT_MAX_MESSAGES);
    }

    /**
     * Construct with a custom window size.
     *
     * @param repository   the backing repository
     * @param maxMessages  maximum messages to retain (including system messages)
     */
    public MessageWindowChatMemory(ChatMemoryRepository repository, int maxMessages) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        if (maxMessages < 1) {
            throw new IllegalArgumentException("maxMessages must be >= 1");
        }
        this.repository = repository;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) {
            return;
        }
        List<Message> messages = new ArrayList<>(repository.load(conversationId));
        messages.add(message);
        // Apply window eviction if exceeded
        if (messages.size() > maxMessages) {
            messages = applyWindow(messages);
        }
        repository.save(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (conversationId == null) {
            return new ArrayList<>();
        }
        List<Message> all = repository.load(conversationId);
        if (all.isEmpty() || lastN <= 0) {
            return new ArrayList<>();
        }
        if (all.size() <= lastN) {
            return new ArrayList<>(all);
        }
        // Keep system messages + last N non-system messages
        List<Message> system = new ArrayList<>();
        List<Message> nonSystem = new ArrayList<>();
        for (Message m : all) {
            if ("system".equals(m.getRole())) {
                system.add(m);
            } else {
                nonSystem.add(m);
            }
        }
        int nonSystemToKeep = Math.min(lastN - system.size(), nonSystem.size());
        if (nonSystemToKeep < 0) {
            nonSystemToKeep = 0;
        }
        List<Message> result = new ArrayList<>(system);
        result.addAll(nonSystem.subList(nonSystem.size() - nonSystemToKeep, nonSystem.size()));
        return result;
    }

    @Override
    public void clear(String conversationId) {
        repository.delete(conversationId);
    }

    /**
     * Get the maximum messages this window will retain.
     */
    public int getMaxMessages() {
        return maxMessages;
    }

    /**
     * Apply sliding window eviction: keep system messages + the most recent
     * non-system messages that fit within maxMessages.
     */
    private List<Message> applyWindow(List<Message> messages) {
        List<Message> system = new ArrayList<>();
        List<Message> nonSystem = new ArrayList<>();
        for (Message m : messages) {
            if ("system".equals(m.getRole())) {
                system.add(m);
            } else {
                nonSystem.add(m);
            }
        }
        int nonSystemToKeep = maxMessages - system.size();
        if (nonSystemToKeep < 0) {
            nonSystemToKeep = 0;
        }
        if (nonSystem.size() <= nonSystemToKeep) {
            return new ArrayList<>(messages);
        }
        List<Message> result = new ArrayList<>(system);
        result.addAll(nonSystem.subList(nonSystem.size() - nonSystemToKeep, nonSystem.size()));
        return result;
    }
}
