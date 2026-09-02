package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.Summarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LLM-powered {@link Summarizer} that calls an {@link LlmClient} to generate
 * a compressed summary of old conversation messages.
 *
 * <p>The summarizer builds a prompt that includes all messages to be summarized,
 * asks the LLM to produce a concise summary (~500 chars), and returns the result
 * as a system message string.</p>
 *
 * <p>On failure, falls back to a simple truncation (same as {@link TruncatingSummarizer}).</p>
 */
public class LlmSummarizer implements Summarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmSummarizer.class);

    private static final String SYSTEM_PROMPT =
            "You are a conversation summarizer. Given a list of conversation messages, "
            + "produce a concise summary (under 500 characters) that captures:\n"
            + "- What the user was trying to accomplish\n"
            + "- Key findings and conclusions\n"
            + "- Tools that were used and their results\n"
            + "- Any unresolved issues\n\n"
            + "Format: plain text, no markdown. Write in the same language as the conversation.";

    private static final int TIMEOUT_SECONDS = 30;

    private final LlmClient llmClient;
    private final String model;

    public LlmSummarizer(LlmClient llmClient) {
        this(llmClient, null);
    }

    public LlmSummarizer(LlmClient llmClient, String model) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient cannot be null");
        }
        this.llmClient = llmClient;
        this.model = model;
    }

    @Override
    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        try {
            // Build the conversation text to summarize
            StringBuilder conversationText = new StringBuilder();
            for (Message msg : messages) {
                String role = msg.getRole() != null ? msg.getRole() : "unknown";
                String content = msg.getContent() != null ? msg.getContent() : "";
                // Truncate very long tool results
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "... [truncated]";
                }
                conversationText.append(role).append(": ").append(content).append("\n");
            }

            // Build LLM request
            List<Message> userMessages = new ArrayList<Message>();
            userMessages.add(Message.user("Summarize this conversation:\n\n" + conversationText.toString()));

            LlmRequest request = new LlmRequest(
                    SYSTEM_PROMPT, userMessages, Collections.<cn.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                    model, 1000, false);

            // Call LLM synchronously via a blocking sink
            final StringBuilder accumulated = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);

            LlmEventSink sink = new LlmEventSink() {
                @Override
                public void onThought(String text) {
                    accumulated.append(text);
                }
                @Override
                public void onToolUse(String id, String name, Map<String, Object> input) {
                    // no-op for summarization
                }
                @Override
                public void onToolResult(String toolUseId, String result) {
                    // no-op
                }
                @Override
                public void onStop(String stopReason) {
                    latch.countDown();
                }
                @Override
                public void onError(String message) {
                    log.warn("LLM summarization error: {}", message);
                    latch.countDown();
                }
            };

            llmClient.stream(request, sink, "summarizer-" + Thread.currentThread().getId());

            // Wait for completion
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("LLM summarization timed out after {}s, falling back to truncation", TIMEOUT_SECONDS);
                return new TruncatingSummarizer().summarize(messages);
            }

            String summary = accumulated.toString().trim();
            if (summary.isEmpty()) {
                log.warn("LLM returned empty summary, falling back to truncation");
                return new TruncatingSummarizer().summarize(messages);
            }
            return summary;

        } catch (RuntimeException e) {
            log.warn("LLM summarization failed, falling back to truncation: {}", e.getMessage());
            return new TruncatingSummarizer().summarize(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM summarization interrupted, falling back to truncation");
            return new TruncatingSummarizer().summarize(messages);
        }
    }
}
