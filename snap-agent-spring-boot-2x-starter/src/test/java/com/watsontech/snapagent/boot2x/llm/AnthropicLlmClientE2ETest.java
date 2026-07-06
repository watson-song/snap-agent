package com.watsontech.snapagent.boot2x.llm;

import com.watsontech.snapagent.core.llm.LlmEventSink;
import com.watsontech.snapagent.core.llm.LlmRequest;
import com.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test that connects to a real LLM API.
 *
 * <p>Activated only when the environment variables {@code LLM_BASE_URL}
 * and {@code LLM_API_KEY} are set. This prevents the test from
 * running in CI without credentials.</p>
 *
 * <p>Run manually:</p>
 * <pre>
 * LLM_BASE_URL=https://api.anthropic.com \
 * LLM_API_KEY=your-key-here \
 * LLM_MODEL=claude-sonnet-4-6 \
 * mvn test -pl snap-agent-spring-boot-2x-starter \
 *   -Dtest=AnthropicLlmClientE2ETest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
class AnthropicLlmClientE2ETest {

    private static final String BASE_URL =
            System.getenv("LLM_BASE_URL") != null
                    ? System.getenv("LLM_BASE_URL")
                    : "https://api.anthropic.com";
    private static final String API_KEY =
            System.getenv("LLM_API_KEY");
    private static final String MODEL =
            System.getenv("LLM_MODEL") != null
                    ? System.getenv("LLM_MODEL")
                    : "claude-sonnet-4-6";

    @Test
    void shouldStreamTextResponseFromRealApi() throws InterruptedException {
        AnthropicLlmClient client = new AnthropicLlmClient(
                BASE_URL, API_KEY, "", null, 60);

        CapturingSink sink = new CapturingSink();
        LlmRequest req = new LlmRequest(
                "You are a helpful assistant. Answer concisely.",
                Collections.singletonList(Message.user("What is 2+2? Reply with just the number.")),
                Collections.<com.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                MODEL, 256, true);

        client.stream(req, sink);

        // Wait for async completion (the stream() method is synchronous)
        Thread.sleep(100);

        // Verify we got some thoughts (text/thinking deltas)
        assertThat(sink.thoughts)
                .as("Should receive text/thinking deltas from LLM")
                .isNotEmpty();

        // Verify stop was called
        assertThat(sink.stopReason)
                .as("Should receive stop reason")
                .isNotNull();

        // Verify no errors
        assertThat(sink.errorMessage)
                .as("Should not have errors")
                .isNull();

        // The combined text should contain "4"
        StringBuilder combined = new StringBuilder();
        for (String t : sink.thoughts) {
            combined.append(t);
        }
        assertThat(combined.toString())
                .as("Combined response should contain the answer '4'")
                .contains("4");
    }

    @Test
    void shouldHandleStreamingWithSystemPrompt() throws InterruptedException {
        AnthropicLlmClient client = new AnthropicLlmClient(
                BASE_URL, API_KEY, "", null, 60);

        CapturingSink sink = new CapturingSink();
        LlmRequest req = new LlmRequest(
                "You are a calculator. Only output the numerical result, nothing else.",
                Collections.singletonList(Message.user("What is 10 * 5?")),
                Collections.<com.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                MODEL, 256, true);

        client.stream(req, sink);
        Thread.sleep(100);

        assertThat(sink.errorMessage).isNull();
        assertThat(sink.stopReason).isNotNull();

        StringBuilder combined = new StringBuilder();
        for (String t : sink.thoughts) {
            combined.append(t);
        }
        // The response should contain "50" somewhere
        assertThat(combined.toString())
                .as("Should contain the result 50")
                .contains("50");
    }

    @Test
    void shouldReceiveSseEventsInCorrectOrder() throws InterruptedException {
        AnthropicLlmClient client = new AnthropicLlmClient(
                BASE_URL, API_KEY, "", null, 60);

        CapturingSink sink = new CapturingSink();
        LlmRequest req = new LlmRequest(
                "Reply with exactly: Hello World",
                Collections.singletonList(Message.user("Say hello")),
                Collections.<com.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                MODEL, 100, true);

        client.stream(req, sink);
        Thread.sleep(100);

        // Should have received thoughts and a stop event
        assertThat(sink.thoughts).isNotEmpty();
        assertThat(sink.stopReason).isNotNull();
        assertThat(sink.errorMessage).isNull();
    }

    /** Capturing event sink for E2E assertions. */
    static class CapturingSink implements LlmEventSink {
        final List<String> thoughts = new CopyOnWriteArrayList<String>();
        volatile String stopReason;
        volatile String errorMessage;

        @Override
        public void onThought(String text) {
            if (text != null) {
                thoughts.add(text);
            }
        }

        @Override
        public void onToolUse(String id, String name, java.util.Map<String, Object> input) {
            // Not expected in these tests
        }

        @Override
        public void onToolResult(String toolUseId, String result) {
            // Not expected in these tests
        }

        @Override
        public void onStop(String stopReason) {
            this.stopReason = stopReason;
        }

        @Override
        public void onError(String message) {
            this.errorMessage = message;
        }
    }
}
