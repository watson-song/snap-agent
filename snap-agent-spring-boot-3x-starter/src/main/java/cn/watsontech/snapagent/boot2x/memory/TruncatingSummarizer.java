package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.Summarizer;

import java.util.List;

/**
 * Fallback {@link Summarizer} that truncates old messages to a fixed character
 * limit. Used when no LLM is available.
 *
 * <p>Concatenates message contents (role + text) up to {@code maxChars} total
 * characters, appending "... [truncated]" if the limit is reached.</p>
 */
public class TruncatingSummarizer implements Summarizer {

    private static final int DEFAULT_MAX_CHARS = 800;

    private final int maxChars;

    public TruncatingSummarizer() {
        this(DEFAULT_MAX_CHARS);
    }

    public TruncatingSummarizer(int maxChars) {
        if (maxChars < 50) {
            throw new IllegalArgumentException("maxChars must be >= 50");
        }
        this.maxChars = maxChars;
    }

    @Override
    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Conversation history summary (").append(messages.size()).append(" messages)]\n");

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String role = msg.getRole() != null ? msg.getRole() : "unknown";
            String text = msg.getContent() != null ? msg.getContent() : "";

            String line = role + ": " + text + "\n";
            if (sb.length() + line.length() > maxChars) {
                sb.append("... [").append(messages.size() - i)
                  .append(" more messages truncated]\n");
                break;
            }
            sb.append(line);
        }

        return sb.toString();
    }
}
