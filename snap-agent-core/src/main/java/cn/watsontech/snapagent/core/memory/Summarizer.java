package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.List;

/**
 * Functional interface for summarizing a list of messages into a single
 * summary string.
 *
 * <p>Used by {@link SummarizingChatMemory} to compress old conversation
 * history. Implementations may call an LLM, use an extractive algorithm,
 * or return a simple concatenation.</p>
 */
@FunctionalInterface
public interface Summarizer {

    /**
     * Summarize the given list of messages into a single string.
     *
     * @param messages the messages to summarize (oldest first)
     * @return a summary string
     */
    String summarize(List<Message> messages);
}
