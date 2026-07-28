package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SummarizingChatMemory} — verifies that when messages
 * exceed the window threshold, old messages are summarized (not dropped),
 * and the summary is injected as a system message.
 */
@DisplayName("SummarizingChatMemory — summary compression instead of hard truncation")
class SummarizingChatMemoryTest {

    /** Simple stub summarizer that returns a fixed summary string. */
    private Summarizer stubSummarizer = messages -> "SUMMARY: " + messages.size() + " msgs";

    // ---- Constructor validation ----

    @Test
    @DisplayName("null repository → IllegalArgumentException")
    void shouldThrowOnNullRepository() {
        assertThatThrownBy(() -> new SummarizingChatMemory(null, stubSummarizer, 5, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null summarizer → IllegalArgumentException")
    void shouldThrowOnNullSummarizer() {
        assertThatThrownBy(() ->
            new SummarizingChatMemory(new InMemoryChatMemoryRepository(), null, 5, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxMessages < 2 → IllegalArgumentException")
    void shouldThrowOnInvalidMaxMessages() {
        assertThatThrownBy(() ->
            new SummarizingChatMemory(new InMemoryChatMemoryRepository(), stubSummarizer, 1, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Below threshold: behaves like regular window ----

    @Test
    @DisplayName("messages below threshold → no summarization, all retrievable")
    void shouldNotSummarizeBelowThreshold() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 10, 8);

        memory.add("c1", Message.user("hello"));
        memory.add("c1", Message.assistant("hi"));

        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("hello");
    }

    // ---- Above threshold: summarize old messages ----

    @Test
    @DisplayName("exceeds threshold → oldest messages summarized as system message")
    void shouldSummarizeOldMessagesWhenExceeded() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 4, 2);

        memory.add("c1", Message.user("msg1"));
        memory.add("c1", Message.assistant("resp1"));
        // 2 messages, threshold=2, not exceeded yet

        memory.add("c1", Message.user("msg2"));
        memory.add("c1", Message.assistant("resp2"));
        // 4 messages, maxMessages=4, adding one more should trigger summarization

        memory.add("c1", Message.user("msg3"));
        // Should now have: [system summary of msg1+resp1, msg2, resp2, msg3] = 4 messages

        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).getRole()).isEqualTo("system");
        assertThat(result.get(0).getContent()).contains("SUMMARY");
        assertThat(result.get(1).getContent()).isEqualTo("msg2");
        assertThat(result.get(2).getContent()).isEqualTo("resp2");
        assertThat(result.get(3).getContent()).isEqualTo("msg3");
    }

    // ---- System messages never summarized ----

    @Test
    @DisplayName("system messages are never summarized or evicted")
    void shouldNeverSummarizeSystemMessages() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 4, 2);

        memory.add("c1", Message.system("important system instruction"));
        memory.add("c1", Message.user("msg1"));
        memory.add("c1", Message.assistant("resp1"));
        memory.add("c1", Message.user("msg2"));
        // At max (4), adding another triggers summarization of msg1+resp1

        memory.add("c1", Message.assistant("resp2"));

        List<Message> result = memory.get("c1", 10);
        // Should have: [system(important), system(summary), msg2, resp2] = 4
        assertThat(result).hasSize(4);
        // Original system message preserved
        assertThat(result.get(0).getContent()).isEqualTo("important system instruction");
        assertThat(result.get(0).getRole()).isEqualTo("system");
        // Summary is also a system message
        assertThat(result.get(1).getRole()).isEqualTo("system");
        assertThat(result.get(1).getContent()).contains("SUMMARY");
        // Recent messages preserved
        assertThat(result.get(2).getContent()).isEqualTo("msg2");
        assertThat(result.get(3).getContent()).isEqualTo("resp2");
    }

    // ---- get with lastN ----

    @Test
    @DisplayName("get with lastN returns last N messages")
    void shouldReturnLastN() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 10, 8);

        memory.add("c1", Message.user("u1"));
        memory.add("c1", Message.assistant("a1"));
        memory.add("c1", Message.user("u2"));
        memory.add("c1", Message.assistant("a2"));

        List<Message> result = memory.get("c1", 2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("u2");
        assertThat(result.get(1).getContent()).isEqualTo("a2");
    }

    // ---- Clear ----

    @Test
    @DisplayName("clear removes all messages")
    void shouldClear() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 10, 8);

        memory.add("c1", Message.user("hello"));
        memory.clear("c1");
        assertThat(memory.get("c1", 10)).isEmpty();
    }

    // ---- Multiple summarization rounds ----

    @Test
    @DisplayName("multiple summarization rounds accumulate summaries")
    void shouldAccumulateSummariesOverMultipleRounds() {
        SummarizingChatMemory memory = new SummarizingChatMemory(
            new InMemoryChatMemoryRepository(), stubSummarizer, 4, 2);

        memory.add("c1", Message.user("msg1"));
        memory.add("c1", Message.assistant("resp1"));
        memory.add("c1", Message.user("msg2"));
        memory.add("c1", Message.assistant("resp2"));
        memory.add("c1", Message.user("msg3"));
        // Round 1: summarize msg1+resp1 → [summary1, msg2, resp2, msg3]

        memory.add("c1", Message.assistant("resp3"));
        // Round 2: summarize msg2+resp2 → [summary1, summary2, msg3, resp3]

        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).getRole()).isEqualTo("system");
        assertThat(result.get(0).getContent()).contains("SUMMARY");
        assertThat(result.get(1).getRole()).isEqualTo("system");
        assertThat(result.get(1).getContent()).contains("SUMMARY");
        assertThat(result.get(2).getContent()).isEqualTo("msg3");
        assertThat(result.get(3).getContent()).isEqualTo("resp3");
    }
}
