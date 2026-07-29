package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link MessagePartitioner} for the ReAct loop.
 *
 * <p>Prepends the current user message before the conversation history,
 * producing the correct message order for ReAct loops:
 * {@code [user_message, assistant_1(thought, toolUses), tool_result_1, ...]}.</p>
 *
 * <p>When a maximum message count is configured, the oldest history
 * messages are dropped to stay within the limit. With {@code maxMessages = 0}
 * (default), all messages are kept.</p>
 */
public class LastNMessagePartitioner implements MessagePartitioner {

    private final int maxMessages;

    /**
     * Construct with no message limit (keeps all history).
     */
    public LastNMessagePartitioner() {
        this(0);
    }

    /**
     * Construct with a maximum number of history messages to keep.
     *
     * @param maxMessages maximum history messages to retain; 0 = unlimited
     */
    public LastNMessagePartitioner(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public List<Message> partition(List<Message> history, String userMessage) {
        List<Message> result = new ArrayList<>();
        // User message goes first (Layer 2 — User Input)
        if (userMessage != null && !userMessage.isEmpty()) {
            result.add(Message.user(userMessage));
        }
        // Then conversation history (Layer 5 — Short-term Notes)
        if (history != null && !history.isEmpty()) {
            if (maxMessages > 0 && history.size() > maxMessages) {
                result.addAll(history.subList(history.size() - maxMessages, history.size()));
            } else {
                result.addAll(history);
            }
        }
        return result;
    }

    public int getMaxMessages() {
        return maxMessages;
    }
}
