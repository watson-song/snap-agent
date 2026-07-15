package cn.watsontech.snapagent.boot2x.llm;

import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test that connects to a real OpenAI-compatible LLM API.
 *
 * <p>Activated only when the environment variables {@code LLM_BASE_URL}
 * and {@code LLM_API_KEY} (or {@code LLM_AUTH_TOKEN}) are set.</p>
 *
 * <p>Run manually:</p>
 * <pre>
 * LLM_BASE_URL=http://llm-model-hub-apis.sf-express.com \
 * LLM_AUTH_TOKEN=your-jwt-token \
 * LLM_MODEL=aiplat/MiniMax-M2.5 \
 * mvn test -pl snap-agent-spring-boot-2x-starter \
 *   -Dtest=OpenAiLlmClientE2ETest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LLM_BASE_URL", matches = ".+")
class OpenAiLlmClientE2ETest {

    private static final String BASE_URL = System.getenv("LLM_BASE_URL");
    private static final String API_KEY =
            System.getenv("LLM_API_KEY") != null
                    ? System.getenv("LLM_API_KEY") : "";
    private static final String AUTH_TOKEN =
            System.getenv("LLM_AUTH_TOKEN") != null
                    ? System.getenv("LLM_AUTH_TOKEN") : "";
    private static final String MODEL =
            System.getenv("LLM_MODEL") != null
                    ? System.getenv("LLM_MODEL") : "gpt-4";

    @Test
    void shouldStreamTextResponseFromRealApi() throws InterruptedException {
        String credential = !AUTH_TOKEN.isEmpty() ? AUTH_TOKEN : API_KEY;
        OpenAiLlmClient client = new OpenAiLlmClient(BASE_URL, "", credential, null, 60);

        CapturingSink sink = new CapturingSink();
        LlmRequest req = new LlmRequest(
                "You are a helpful assistant. Answer concisely.",
                Collections.singletonList(Message.user("What is 2+2? Reply with just the number.")),
                Collections.<cn.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                MODEL, 256, true);

        client.stream(req, sink, null);

        assertThat(sink.thoughts)
                .as("Should receive content deltas from LLM")
                .isNotEmpty();

        assertThat(sink.stopReason)
                .as("Should receive stop reason")
                .isNotNull();

        assertThat(sink.errorMessage)
                .as("Should not have errors")
                .isNull();

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
        String credential = !AUTH_TOKEN.isEmpty() ? AUTH_TOKEN : API_KEY;
        OpenAiLlmClient client = new OpenAiLlmClient(BASE_URL, "", credential, null, 60);

        CapturingSink sink = new CapturingSink();
        LlmRequest req = new LlmRequest(
                "You are a calculator. Only output the numerical result, nothing else.",
                Collections.singletonList(Message.user("What is 10 * 5?")),
                Collections.<cn.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                MODEL, 256, true);

        client.stream(req, sink, null);

        assertThat(sink.errorMessage).isNull();
        assertThat(sink.stopReason).isNotNull();

        StringBuilder combined = new StringBuilder();
        for (String t : sink.thoughts) {
            combined.append(t);
        }
        assertThat(combined.toString())
                .as("Should contain the result 50")
                .contains("50");
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
        }

        @Override
        public void onToolResult(String toolUseId, String result) {
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
