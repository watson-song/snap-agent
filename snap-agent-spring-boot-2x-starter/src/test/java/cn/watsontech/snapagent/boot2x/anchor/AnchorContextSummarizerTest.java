package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AnchorContextSummarizer}.
 *
 * <p>Validates that the summarizer correctly prompts the LLM to produce a
 * compressed summary of long page-section content.</p>
 */
class AnchorContextSummarizerTest {

    private SnapAgentProperties.Anchor props;
    private LlmClient llmClient;
    private AnchorContextSummarizer summarizer;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties.Anchor();
        props.setSummaryThresholdChars(100);
        props.setClassifierModel("");
        llmClient = mock(LlmClient.class);
        summarizer = new AnchorContextSummarizer(llmClient, props);
    }

    @Test
    void shouldReturnContentDirectlyWhenShort() {
        String shortContent = "short content";

        String result = summarizer.summarize(shortContent);

        assertThat(result).isEqualTo(shortContent);
        verify(llmClient, times(0)).stream(any(), any(), anyString());
    }

    @Test
    void shouldCallLlmWhenContentExceedsThreshold() {
        String longContent = new String(new char[200]).replace("\0", "x");

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("这是摘要");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(longContent);

        assertThat(result).isEqualTo("这是摘要");
        verify(llmClient, times(1)).stream(any(), any(), anyString());
    }

    @Test
    void shouldAccumulateMultipleThoughtTokens() {
        String longContent = new String(new char[200]).replace("\0", "x");

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("第一段。");
            sink.onThought("第二段。");
            sink.onThought("第三段。");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(longContent);

        assertThat(result).isEqualTo("第一段。第二段。第三段。");
    }

    @Test
    void shouldReturnOriginalContentOnLlmError() {
        String longContent = new String(new char[200]).replace("\0", "x");

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onError("API timeout");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(longContent);

        assertThat(result).isEqualTo(longContent);
    }

    @Test
    void shouldReturnOriginalContentOnException() {
        String longContent = new String(new char[200]).replace("\0", "x");

        doThrow(new RuntimeException("network error"))
                .when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(longContent);

        assertThat(result).isEqualTo(longContent);
    }

    @Test
    void shouldConstructCorrectPromptForSummary() {
        String longContent = "## 长文档\n" + new String(new char[200]).replace("\0", "x");

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        summarizer.summarize(longContent);

        LlmRequest req = reqCaptor.getValue();
        assertThat(req.getMessages()).isNotEmpty();
        Message firstMsg = req.getMessages().get(0);
        assertThat(firstMsg.getContent()).contains("摘要");
    }

    @Test
    void shouldUseConfiguredModelWhenSet() {
        props.setClassifierModel("claude-haiku-4-5-20251001");
        String longContent = new String(new char[200]).replace("\0", "x");

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        summarizer.summarize(longContent);

        LlmRequest req = reqCaptor.getValue();
        assertThat(req.getModel()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void shouldUseMainModelWhenClassifierModelNotSet() {
        props.setClassifierModel("");
        String longContent = new String(new char[200]).replace("\0", "x");

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        summarizer.summarize(longContent);

        LlmRequest req = reqCaptor.getValue();
        assertThat(req).isNotNull();
    }

    @Test
    void shouldSetMaxTokensForSummary() {
        String longContent = new String(new char[200]).replace("\0", "x");

        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(reqCaptor.capture(), any(), anyString());

        summarizer.summarize(longContent);

        LlmRequest req = reqCaptor.getValue();
        assertThat(req.getMaxTokens()).isGreaterThan(0);
    }

    @Test
    void shouldHandleEmptyContentGracefully() {
        String result = summarizer.summarize("");

        assertThat(result).isEmpty();
        verify(llmClient, times(0)).stream(any(), any(), anyString());
    }

    @Test
    void shouldHandleNullContentGracefully() {
        String result = summarizer.summarize(null);

        assertThat(result).isNull();
        verify(llmClient, times(0)).stream(any(), any(), anyString());
    }

    // ---- G-404: boundary — content length equals threshold should skip LLM ----

    @Test
    @DisplayName("G-404: should not call LLM when content length equals threshold")
    void shouldNotCallLlmWhenContentLengthEqualsThreshold() {
        String content = new String(new char[100]).replace("\0", "x"); // exactly 100 chars

        String result = summarizer.summarize(content);

        assertThat(result).isEqualTo(content);
        verify(llmClient, times(0)).stream(any(), any(), anyString());
    }

    @Test
    @DisplayName("G-404: should call LLM when content length exceeds threshold by one")
    void shouldCallLlmWhenContentLengthExceedsThresholdByOne() {
        String content = new String(new char[101]).replace("\0", "x"); // 101 chars, threshold=100

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("summary");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(content);

        assertThat(result).isEqualTo("summary");
        verify(llmClient, times(1)).stream(any(), any(), anyString());
    }

    // ---- G-405: LLM onError clears accumulated and returns original ----

    @Test
    @DisplayName("G-405: should return original content when onError fires after partial thoughts")
    void shouldReturnOriginalContentWhenOnErrorClearsAccumulated() {
        String longContent = new String(new char[200]).replace("\0", "x");

        doAnswer(invocation -> {
            LlmEventSink sink = invocation.getArgument(1);
            sink.onThought("partial summary");
            sink.onError("boom");
            return null;
        }).when(llmClient).stream(any(), any(), anyString());

        String result = summarizer.summarize(longContent);

        assertThat(result).isEqualTo(longContent);
    }
}
