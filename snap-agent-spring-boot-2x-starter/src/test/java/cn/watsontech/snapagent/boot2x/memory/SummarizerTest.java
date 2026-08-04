package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TruncatingSummarizer} and {@link LlmSummarizer}.
 */
class SummarizerTest {

    // ---- TruncatingSummarizer ----

    @Test
    void truncatingSummarizer_emptyList_returnsEmpty() {
        TruncatingSummarizer s = new TruncatingSummarizer();
        assertThat(s.summarize(Collections.<Message>emptyList())).isEmpty();
        assertThat(s.summarize(null)).isEmpty();
    }

    @Test
    void truncatingSummarizer_singleMessage_returnsSummary() {
        TruncatingSummarizer s = new TruncatingSummarizer();
        List<Message> msgs = Collections.singletonList(
                Message.user("SKU A123 没有生成补货策略"));
        String result = s.summarize(msgs);
        assertThat(result).contains("1 messages");
        assertThat(result).contains("user: SKU A123 没有生成补货策略");
    }

    @Test
    void truncatingSummarizer_multipleMessages_includesAll() {
        TruncatingSummarizer s = new TruncatingSummarizer(2000);
        List<Message> msgs = Arrays.asList(
                Message.user("问题是什么？"),
                Message.assistant("让我查一下", null),
                Message.user("好的"));
        String result = s.summarize(msgs);
        assertThat(result).contains("3 messages");
        assertThat(result).contains("user: 问题是什么？");
        assertThat(result).contains("assistant: 让我查一下");
        assertThat(result).contains("user: 好的");
    }

    @Test
    void truncatingSummarizer_exceedsMaxChars_truncates() {
        TruncatingSummarizer s = new TruncatingSummarizer(100);
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 20; i++) {
            msgs.add(Message.user("Message number " + i + " with some content"));
        }
        String result = s.summarize(msgs);
        assertThat(result.length()).isLessThan(200); // some headroom
        assertThat(result).contains("truncated");
    }

    @Test
    void truncatingSummarizer_nullContent_handlesGracefully() {
        TruncatingSummarizer s = new TruncatingSummarizer();
        List<Message> msgs = Collections.singletonList(
                new Message("user", null, null, null));
        String result = s.summarize(msgs);
        assertThat(result).contains("user: ");
    }

    @Test
    void truncatingSummarizer_invalidMaxChars_throws() {
        assertThatThrownBy(() -> new TruncatingSummarizer(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxChars");
    }

    // ---- LlmSummarizer ----

    @Test
    void llmSummarizer_nullClient_throws() {
        assertThatThrownBy(() -> new LlmSummarizer(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("llmClient");
    }

    @Test
    void llmSummarizer_emptyMessages_returnsEmpty() {
        // Mock LlmClient that does nothing
        cn.watsontech.snapagent.core.llm.LlmClient mockClient = new cn.watsontech.snapagent.core.llm.LlmClient() {
            @Override
            public void stream(cn.watsontech.snapagent.core.llm.LlmRequest req,
                               cn.watsontech.snapagent.core.llm.LlmEventSink sink,
                               String taskId) {
                sink.onStop("end_turn");
            }
        };
        LlmSummarizer s = new LlmSummarizer(mockClient);
        assertThat(s.summarize(Collections.<Message>emptyList())).isEmpty();
    }

    @Test
    void llmSummarizer_llmReturnsSummary_returnsIt() {
        cn.watsontech.snapagent.core.llm.LlmClient mockClient = new cn.watsontech.snapagent.core.llm.LlmClient() {
            @Override
            public void stream(cn.watsontech.snapagent.core.llm.LlmRequest req,
                               cn.watsontech.snapagent.core.llm.LlmEventSink sink,
                               String taskId) {
                sink.onThought("User asked about SKU A123. ");
                sink.onThought("Root cause: safety stock delay.");
                sink.onStop("end_turn");
            }
        };
        LlmSummarizer s = new LlmSummarizer(mockClient);
        List<Message> msgs = Arrays.asList(
                Message.user("SKU A123 没有补货策略"),
                Message.assistant("根因是安全库存延迟", null));
        String result = s.summarize(msgs);
        assertThat(result).contains("SKU A123");
        assertThat(result).contains("safety stock delay");
    }

    @Test
    void llmSummarizer_llmReturnsEmpty_fallsBackToTruncation() {
        cn.watsontech.snapagent.core.llm.LlmClient mockClient = new cn.watsontech.snapagent.core.llm.LlmClient() {
            @Override
            public void stream(cn.watsontech.snapagent.core.llm.LlmRequest req,
                               cn.watsontech.snapagent.core.llm.LlmEventSink sink,
                               String taskId) {
                // LLM returns nothing
                sink.onStop("end_turn");
            }
        };
        LlmSummarizer s = new LlmSummarizer(mockClient);
        List<Message> msgs = Collections.singletonList(Message.user("hello"));
        String result = s.summarize(msgs);
        // Falls back to truncation
        assertThat(result).contains("1 messages");
        assertThat(result).contains("hello");
    }

    @Test
    void llmSummarizer_llmThrows_fallsBackToTruncation() {
        cn.watsontech.snapagent.core.llm.LlmClient mockClient = new cn.watsontech.snapagent.core.llm.LlmClient() {
            @Override
            public void stream(cn.watsontech.snapagent.core.llm.LlmRequest req,
                               cn.watsontech.snapagent.core.llm.LlmEventSink sink,
                               String taskId) {
                throw new RuntimeException("LLM unavailable");
            }
        };
        LlmSummarizer s = new LlmSummarizer(mockClient);
        List<Message> msgs = Collections.singletonList(Message.user("test"));
        String result = s.summarize(msgs);
        // Falls back to truncation
        assertThat(result).contains("1 messages");
    }
}
