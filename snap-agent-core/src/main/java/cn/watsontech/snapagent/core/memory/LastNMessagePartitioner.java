package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link MessagePartitioner} that keeps all history messages
 * and appends the current user message.
 *
 * <p>This preserves the pre-extraction behavior of {@code AgentNode}:
 * {@code messages.addAll(history); messages.add(userMessage);}.</p>
 *
 * <p>When a maximum message count is configured, the oldest messages
 * are dropped to stay within the limit. With {@code maxMessages = 0}
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
        if (history != null && !history.isEmpty()) {
            if (maxMessages > 0 && history.size() > maxMessages) {
                result.addAll(history.subList(history.size() - maxMessages, history.size()));
            } else {
                result.addAll(history);
            }
        }
        if (userMessage != null && !userMessage.isEmpty()) {
            result.add(Message.user(userMessage));
        }
        return result;
    }

    public int getMaxMessages() {
        return maxMessages;
    }
}
