package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatMemory} implementation that summarizes old messages instead
 * of hard-truncating them.
 *
 * <p>When the message count exceeds {@code maxMessages}, the oldest
 * non-system messages (beyond the {@code summarizeThreshold} count of
 * most recent messages) are passed to a {@link Summarizer}. The summary
 * is injected as a system message, preserving context without unbounded
 * growth.</p>
 *
 * <p>System messages are never summarized or evicted — they are always
 * retained at the front of the message list.</p>
 */
public class SummarizingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemory.class);

    private final ChatMemoryRepository repository;
    private final Summarizer summarizer;
    private final int maxMessages;
    private final int summarizeThreshold;

    /**
     * Construct with explicit parameters.
     *
     * @param repository         the backing repository
     * @param summarizer         the summarizer to compress old messages
     * @param maxMessages        maximum messages to retain (including system + summary)
     * @param summarizeThreshold number of oldest non-system messages to summarize each round
     */
    public SummarizingChatMemory(ChatMemoryRepository repository,
                                  Summarizer summarizer,
                                  int maxMessages,
                                  int summarizeThreshold) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        if (summarizer == null) {
            throw new IllegalArgumentException("summarizer cannot be null");
        }
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be >= 2");
        }
        if (summarizeThreshold < 1) {
            throw new IllegalArgumentException("summarizeThreshold must be >= 1");
        }
        this.repository = repository;
        this.summarizer = summarizer;
        this.maxMessages = maxMessages;
        this.summarizeThreshold = summarizeThreshold;
    }

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) {
            return;
        }

        List<Message> messages = new ArrayList<>(repository.load(conversationId));
        messages.add(message);

        // Apply summarization if exceeded
        if (messages.size() > maxMessages) {
            messages = applySummarization(messages);
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

        // Keep system messages (including summaries) + last N non-system
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
     * Summarize the oldest {@code summarizeThreshold} non-system messages
     * when the window is exceeded. The summary replaces those messages
     * as a system message.
     */
    private List<Message> applySummarization(List<Message> messages) {
        // Partition into system and non-system
        List<Message> system = new ArrayList<>();
        List<Message> nonSystem = new ArrayList<>();
        for (Message m : messages) {
            if ("system".equals(m.getRole())) {
                system.add(m);
            } else {
                nonSystem.add(m);
            }
        }

        // If non-system messages fit within maxMessages, no summarization needed
        int availableSlots = maxMessages - system.size();
        if (nonSystem.size() <= availableSlots) {
            return new ArrayList<>(messages);
        }

        // Summarize the oldest summarizeThreshold non-system messages
        int toSummarizeCount = Math.min(summarizeThreshold, nonSystem.size());
        List<Message> toSummarize = new ArrayList<>(nonSystem.subList(0, toSummarizeCount));
        List<Message> toKeep = new ArrayList<>(nonSystem.subList(toSummarizeCount, nonSystem.size()));

        // Summarize the old messages
        String summaryText;
        try {
            summaryText = summarizer.summarize(new ArrayList<>(toSummarize));
        } catch (RuntimeException e) {
            log.warn("Summarization failed, falling back to hard truncation: {}", e.getMessage());
            // Fallback: keep most recent messages, drop the rest
            List<Message> result = new ArrayList<>(system);
            int fallbackKeep = Math.min(availableSlots, toKeep.size());
            result.addAll(toKeep.subList(toKeep.size() - fallbackKeep, toKeep.size()));
            return result;
        }

        // Build result: system messages + summary system message + recent messages
        List<Message> result = new ArrayList<>(system);
        result.add(Message.system(summaryText));
        result.addAll(toKeep);

        return result;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public int getSummarizeThreshold() {
        return summarizeThreshold;
    }
}
