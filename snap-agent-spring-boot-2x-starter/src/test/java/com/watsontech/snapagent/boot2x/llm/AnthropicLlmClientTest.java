package com.watsontech.snapagent.boot2x.llm;

import com.watsontech.snapagent.core.llm.LlmEventSink;
import com.watsontech.snapagent.core.llm.LlmRequest;
import com.watsontech.snapagent.core.llm.Message;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AnthropicLlmClient} SSE parsing.
 *
 * <p>Uses a testable subclass that overrides the HTTP call to inject
 * canned SSE responses (TDD_SPEC §UC-14).</p>
 */
class AnthropicLlmClientTest {

    private CapturingEventSink sink;
    private TestableClient client;

    @BeforeEach
    void setUp() {
        sink = new CapturingEventSink();
        client = new TestableClient();
    }

    @Test
    void shouldCallOnThoughtWhenTextDeltaReceived() {
        client.setSseResponse(
                sse("message_start", "{\"type\":\"message_start\"}")
                + sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + sse("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}"));

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Hello");
        assertThat(sink.stopReason).isEqualTo("end_turn");
    }

    @Test
    void shouldAccumulateMultipleTextDeltas() {
        client.setSseResponse(
                sse("message_start", "{\"type\":\"message_start\"}")
                + sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"World\"}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + sse("message_stop", "{\"type\":\"message_stop\"}"));

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Hello ", "World");
    }

    @Test
    void shouldCallOnToolUseWhenToolUseBlockCompleted() {
        client.setSseResponse(
                sse("message_start", "{\"type\":\"message_start\"}")
                + sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"toolu_01\",\"name\":\"mysql_query\",\"input\":{}}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + sse("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}"));

        client.stream(simpleRequest(), sink);

        assertThat(sink.toolUseId).isEqualTo("toolu_01");
        assertThat(sink.toolUseName).isEqualTo("mysql_query");
        assertThat(sink.toolUseInput).containsEntry("sql", "SELECT 1");
        assertThat(sink.stopReason).isEqualTo("tool_use");
    }

    @Test
    void shouldCallOnErrorWhenErrorEventReceived() {
        client.setSseResponse(
                sse("error", "{\"type\":\"error\","
                + "\"error\":{\"type\":\"rate_limit_error\",\"message\":\"rate_limit\"}}"));

        client.stream(simpleRequest(), sink);

        assertThat(sink.errorMessage).contains("rate_limit");
    }

    @Test
    void shouldCallOnErrorWhenHttpStatusNotOk() {
        client.setResponseCode(429);

        client.stream(simpleRequest(), sink);

        assertThat(sink.errorMessage).isNotNull();
    }

    @Test
    void shouldCallOnErrorWhenIOExceptionThrown() {
        client.setThrowIOException(true);

        client.stream(simpleRequest(), sink);

        assertThat(sink.errorMessage).isNotNull();
    }

    @Test
    void shouldHandleMultipleContentBlocks() {
        client.setSseResponse(
                sse("message_start", "{\"type\":\"message_start\"}")
                // text block
                + sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"thinking\"}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                // tool_use block
                + sse("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"tool_use\","
                        + "\"id\":\"t2\",\"name\":\"redis_get\",\"input\":{}}}")
                + sse("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"{\\\"key\\\":\\\"foo\\\"}\"}}")
                + sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                + sse("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}")
                + sse("message_stop", "{\"type\":\"message_stop\"}"));

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("thinking");
        assertThat(sink.toolUseId).isEqualTo("t2");
        assertThat(sink.toolUseName).isEqualTo("redis_get");
        assertThat(sink.toolUseInput).containsEntry("key", "foo");
    }

    @Test
    void shouldBuildCorrectHttpRequest() {
        client.setSseResponse(sse("message_stop", "{\"type\":\"message_stop\"}"));

        client.stream(simpleRequest(), sink);

        Request captured = client.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.url().toString()).isEqualTo("https://api.test.com/v1/messages");
        assertThat(captured.header("x-api-key")).isEqualTo("sk-test");
        assertThat(captured.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(captured.header("content-type")).isEqualTo("application/json");
        assertThat(captured.method()).isEqualTo("POST");
    }

    @Test
    void shouldBuildRequestBodyWithTools() {
        client.setSseResponse(sse("message_stop", "{\"type\":\"message_stop\"}"));

        com.watsontech.snapagent.core.llm.ToolDef tool = new com.watsontech.snapagent.core.llm.ToolDef(
                "mysql_query", "Execute SQL", "{\"type\":\"object\"}");
        LlmRequest req = new LlmRequest("system prompt",
                Collections.<Message>singletonList(Message.user("hi")),
                Collections.singletonList(tool),
                "claude-sonnet-4-6", 4096, true);

        client.stream(req, sink);

        Request captured = client.getLastRequest();
        assertThat(captured.body()).isNotNull();
        try {
            okio.Buffer buffer = new okio.Buffer();
            captured.body().writeTo(buffer);
            String bodyStr = buffer.readUtf8();
            assertThat(bodyStr).contains("\"model\":\"claude-sonnet-4-6\"");
            assertThat(bodyStr).contains("\"max_tokens\":4096");
            assertThat(bodyStr).contains("\"stream\":true");
            assertThat(bodyStr).contains("\"system\":\"system prompt\"");
            assertThat(bodyStr).contains("\"tools\"");
            assertThat(bodyStr).contains("\"mysql_query\"");
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read request body", e);
        }
    }

    @Test
    void shouldBuildRequestBodyWithToolResultMessage() {
        client.setSseResponse(sse("message_stop", "{\"type\":\"message_stop\"}"));

        Message toolMsg = Message.toolResult("toolu_01", "query result");
        LlmRequest req = new LlmRequest("system",
                Collections.singletonList(toolMsg),
                Collections.<com.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                "claude-sonnet-4-6", 8192, true);

        client.stream(req, sink);

        Request captured = client.getLastRequest();
        assertThat(captured).isNotNull();
        try {
            okio.Buffer buffer = new okio.Buffer();
            captured.body().writeTo(buffer);
            String bodyStr = buffer.readUtf8();
            assertThat(bodyStr).contains("\"tool_result\"");
            assertThat(bodyStr).contains("\"tool_use_id\":\"toolu_01\"");
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read request body", e);
        }
    }

    // ---- helpers ----

    private LlmRequest simpleRequest() {
        return new LlmRequest("system prompt",
                Collections.<Message>singletonList(Message.user("hi")),
                Collections.<com.watsontech.snapagent.core.llm.ToolDef>emptyList(),
                "claude-sonnet-4-6", 8192, true);
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    /** Testable subclass that injects canned SSE responses. */
    static class TestableClient extends AnthropicLlmClient {
        private String sseResponse = "";
        private int responseCode = 200;
        private boolean throwIOException = false;
        private Request lastRequest;

        TestableClient() {
            super("https://api.test.com", "sk-test", 120);
        }

        void setSseResponse(String sse) {
            this.sseResponse = sse;
        }

        void setResponseCode(int code) {
            this.responseCode = code;
        }

        void setThrowIOException(boolean throwIo) {
            this.throwIOException = throwIo;
        }

        Request getLastRequest() {
            return lastRequest;
        }

        @Override
        protected Response executeCall(Request request) throws java.io.IOException {
            this.lastRequest = request;
            if (throwIOException) {
                throw new java.io.IOException("simulated network error");
            }
            ResponseBody body = ResponseBody.create(
                    MediaType.parse("text/event-stream"), sseResponse);
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(responseCode == 200 ? "OK" : "Error")
                    .header("content-type", "text/event-stream")
                    .body(body)
                    .build();
        }
    }

    /** Capturing event sink for test assertions. */
    static class CapturingEventSink implements LlmEventSink {
        java.util.List<String> thoughts = new java.util.ArrayList<String>();
        String toolUseId;
        String toolUseName;
        Map<String, Object> toolUseInput;
        String stopReason;
        String errorMessage;

        @Override
        public void onThought(String text) {
            thoughts.add(text);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onToolUse(String id, String name, Map<String, Object> input) {
            toolUseId = id;
            toolUseName = name;
            toolUseInput = input;
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
