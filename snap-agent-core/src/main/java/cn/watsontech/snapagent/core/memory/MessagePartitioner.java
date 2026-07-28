package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.List;

/**
 * Strategy for assembling the final message list sent to the LLM from
 * conversation history and the current user message.
 *
 * <p>Implementations control how much history is included, whether old
 * messages are dropped or summarized, and how the user message is
 * appended. This isolates context-window management from the
 * {@link cn.watsontech.snapagent.core.graph.react.AgentNode}.</p>
 *
 * <p>Default implementation: {@link LastNMessagePartitioner} (keeps all
 * history, appends user message — current behavior before extraction).</p>
 */
public interface MessagePartitioner {

    /**
     * Build the message list for the LLM request.
     *
     * @param history     conversation history from memory (may be null or empty)
     * @param userMessage the current user message
     * @return the final ordered message list to send to the LLM
     */
    List<Message> partition(List<Message> history, String userMessage);
}
