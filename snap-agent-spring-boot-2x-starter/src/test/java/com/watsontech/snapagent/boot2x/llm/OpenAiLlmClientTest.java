package com.watsontech.snapagent.boot2x.llm;

import com.watsontech.snapagent.core.llm.LlmEventSink;
import com.watsontech.snapagent.core.llm.LlmRequest;
import com.watsontech.snapagent.core.llm.Message;
import com.watsontech.snapagent.core.llm.ToolDef;
import com.watsontech.snapagent.core.llm.ToolUseBlock;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenAiLlmClient} SSE parsing.
 *
 * <p>Uses a testable subclass that overrides the HTTP call to inject
 * canned SSE responses (OpenAI chat completion streaming format).</p>
 */
class OpenAiLlmClientTest {

    private CapturingEventSink sink;
    private TestableClient client;

    @BeforeEach
    void setUp() {
        sink = new CapturingEventSink();
        client = new TestableClient();
    }

    @Test
    void shouldCallOnThoughtWhenContentDeltaReceived() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\" World\"}}]}")
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Hello", " World");
        assertThat(sink.stopReason).isEqualTo("end_turn");
    }

    @Test
    void shouldAccumulateMultipleContentDeltas() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}")
                + data("{\"choices\":[{\"delta\":{\"content\":\"World\"}}]}")
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Hello ", "World");
        assertThat(sink.stopReason).isEqualTo("end_turn");
    }

    @Test
    void shouldParseToolCallsFromStreamingResponse() {
        client.setSseResponse(
                // First chunk: tool call id + name
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_abc\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"mysql_query\",\"arguments\":\"\"}}]}}]}")
                // Second chunk: partial arguments
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}}]}}]}")
                // Finish
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.toolUses).hasSize(1);
        ToolUseCapture tc = sink.toolUses.get(0);
        assertThat(tc.id).isEqualTo("call_abc");
        assertThat(tc.name).isEqualTo("mysql_query");
        assertThat(tc.input).containsEntry("sql", "SELECT 1");
        assertThat(sink.stopReason).isEqualTo("tool_use");
    }

    @Test
    void shouldHandleMultipleToolCallsInOneResponse() {
        client.setSseResponse(
                // First tool call
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"mysql_query\",\"arguments\":\"\"}}]}}]}")
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}}]}}]}")
                // Second tool call
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"id\":\"call_2\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"redis_get\",\"arguments\":\"\"}}]}}]}")
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"{\\\"key\\\":\\\"foo\\\"}\"}}]}}]}")
                // Finish
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.toolUses).hasSize(2);
        assertThat(sink.toolUses.get(0).name).isEqualTo("mysql_query");
        assertThat(sink.toolUses.get(1).name).isEqualTo("redis_get");
    }

    @Test
    void shouldHandleTextAndToolCallInSameResponse() {
        client.setSseResponse(
                // Text content
                data("{\"choices\":[{\"delta\":{\"content\":\"Let me check\"}}]}")
                // Tool call
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"mysql_query\",\"arguments\":\"\"}}]}}]}")
                + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}}]}}]}")
                // Finish
                + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Let me check");
        assertThat(sink.toolUses).hasSize(1);
        assertThat(sink.stopReason).isEqualTo("tool_use");
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
    void shouldHandleDataDoneWithoutFinishReason() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.thoughts).contains("Hi");
        assertThat(sink.stopReason).isEqualTo("end_turn");
    }

    @Test
    void shouldMapLengthFinishReasonToMaxTokens() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{\"content\":\"truncated\"},\"finish_reason\":\"length\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        assertThat(sink.stopReason).isEqualTo("max_tokens");
    }

    @Test
    void shouldBuildCorrectHttpRequest() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        Request captured = client.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.url().toString()).isEqualTo("https://api.test.com/v1/chat/completions");
        assertThat(captured.header("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(captured.header("content-type")).isEqualTo("application/json");
        assertThat(captured.method()).isEqualTo("POST");
    }

    @Test
    void shouldBuildRequestBodyWithSystemPromptAndMessages() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        Request captured = client.getLastRequest();
        assertThat(captured.body()).isNotNull();
        String bodyStr = readBody(captured);
        assertThat(bodyStr).contains("\"model\":\"gpt-4\"");
        assertThat(bodyStr).contains("\"max_tokens\":8192");
        assertThat(bodyStr).contains("\"stream\":true");
        assertThat(bodyStr).contains("\"role\":\"system\"");
        assertThat(bodyStr).contains("\"system prompt\"");
        assertThat(bodyStr).contains("\"role\":\"user\"");
        assertThat(bodyStr).contains("\"hi\"");
    }

    @Test
    void shouldBuildRequestBodyWithTools() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        ToolDef tool = new ToolDef("mysql_query", "Execute SQL", "{\"type\":\"object\"}");
        LlmRequest req = new LlmRequest("system",
                Collections.<Message>singletonList(Message.user("hi")),
                Collections.singletonList(tool),
                "gpt-4", 4096, true);

        client.stream(req, sink);

        Request captured = client.getLastRequest();
        String bodyStr = readBody(captured);
        assertThat(bodyStr).contains("\"tools\"");
        assertThat(bodyStr).contains("\"type\":\"function\"");
        assertThat(bodyStr).contains("\"mysql_query\"");
        assertThat(bodyStr).contains("\"Execute SQL\"");
    }

    @Test
    void shouldBuildRequestBodyWithToolResultMessage() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        Message toolMsg = Message.toolResult("call_01", "query result");
        LlmRequest req = new LlmRequest("system",
                Collections.singletonList(toolMsg),
                Collections.<ToolDef>emptyList(),
                "gpt-4", 8192, true);

        client.stream(req, sink);

        Request captured = client.getLastRequest();
        String bodyStr = readBody(captured);
        assertThat(bodyStr).contains("\"role\":\"tool\"");
        assertThat(bodyStr).contains("\"tool_call_id\":\"call_01\"");
        assertThat(bodyStr).contains("\"query result\"");
    }

    @Test
    void shouldBuildRequestBodyWithAssistantToolUseMessage() {
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        List<ToolUseBlock> toolUses = Collections.singletonList(
                new ToolUseBlock("call_01", "mysql_query",
                        Collections.<String, Object>singletonMap("sql", "SELECT 1")));
        Message asstMsg = Message.assistant("Let me check", toolUses);
        LlmRequest req = new LlmRequest("system",
                Collections.singletonList(asstMsg),
                Collections.<ToolDef>emptyList(),
                "gpt-4", 8192, true);

        client.stream(req, sink);

        Request captured = client.getLastRequest();
        String bodyStr = readBody(captured);
        assertThat(bodyStr).contains("\"role\":\"assistant\"");
        assertThat(bodyStr).contains("\"tool_calls\"");
        assertThat(bodyStr).contains("\"call_01\"");
        assertThat(bodyStr).contains("\"mysql_query\"");
        // arguments should be a JSON string
        assertThat(bodyStr).contains("\"arguments\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"");
    }

    @Test
    void shouldUseAuthTokenWhenProvided() {
        client = new TestableClient("https://api.test.com", "", "my-jwt-token");
        client.setSseResponse(
                data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + done());

        client.stream(simpleRequest(), sink);

        Request captured = client.getLastRequest();
        assertThat(captured.header("Authorization")).isEqualTo("Bearer my-jwt-token");
    }

    // ---- helpers ----

    private LlmRequest simpleRequest() {
        return new LlmRequest("system prompt",
                Collections.<Message>singletonList(Message.user("hi")),
                Collections.<ToolDef>emptyList(),
                "gpt-4", 8192, true);
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    private static String done() {
        return "data: [DONE]\n\n";
    }

    private static String readBody(Request captured) {
        try {
            okio.Buffer buffer = new okio.Buffer();
            captured.body().writeTo(buffer);
            return buffer.readUtf8();
        } catch (java.io.IOException e) {
            throw new AssertionError("Failed to read request body", e);
        }
    }

    /** Testable subclass that injects canned SSE responses. */
    static class TestableClient extends OpenAiLlmClient {
        private String sseResponse = "";
        private int responseCode = 200;
        private boolean throwIOException = false;
        private Request lastRequest;

        TestableClient() {
            super("https://api.test.com", "sk-test", 120);
        }

        TestableClient(String baseUrl, String apiKey, String authToken) {
            super(baseUrl, apiKey, authToken, null, 120);
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
        final List<String> thoughts = new CopyOnWriteArrayList<String>();
        final List<ToolUseCapture> toolUses = new CopyOnWriteArrayList<ToolUseCapture>();
        volatile String stopReason;
        volatile String errorMessage;

        @Override
        public void onThought(String text) {
            if (text != null) {
                thoughts.add(text);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onToolUse(String id, String name, Map<String, Object> input) {
            toolUses.add(new ToolUseCapture(id, name, input));
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

    /** Simple holder for captured tool use events. */
    static class ToolUseCapture {
        final String id;
        final String name;
        final Map<String, Object> input;

        ToolUseCapture(String id, String name, Map<String, Object> input) {
            this.id = id;
            this.name = name;
            this.input = input;
        }
    }
}
