package cn.watsontech.snapagent.boot2x.tool.mcp;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSseClientTest {

    @Test
    void shouldParseToolListFromJsonRpcResponse() {
        String jsonRpcResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
                "{\"name\":\"get_order_status\",\"description\":\"查询订单状态\"," +
                "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}}" +
                "]}}";

        List<McpToolInfo> tools = McpSseClient.parseToolList(jsonRpcResponse);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("get_order_status");
        assertThat(tools.get(0).getDescription()).isEqualTo("查询订单状态");
        assertThat(tools.get(0).getInputSchema()).contains("orderId");
    }

    @Test
    void shouldBuildJsonRpcRequest() {
        String json = McpSseClient.buildRequest(1, "tools/list", Collections.emptyMap());
        assertThat(json).contains("\"jsonrpc\":\"2.0\"");
        assertThat(json).contains("\"id\":1");
        assertThat(json).contains("\"method\":\"tools/list\"");
    }

    // ---- P1: connect() full flow ----

    @Test
    void shouldConnectViaSseThenInitializeAndListTools() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        // 1. SSE response delivering the POST endpoint
        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages?token=abc\"}\n\n");
        // 2. initialize JSON-RPC response
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}");
        // 3. tools/list JSON-RPC response
        String toolsJson = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" +
                "{\"name\":\"search_table\",\"description\":\"Search tables\"," +
                "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}" +
                "]}}";
        Response toolsResponse = buildResponse(200, "application/json", toolsJson);

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse);

        McpSseClient client = new McpSseClient(
                "http://localhost:1234", "X-Bdp-Token", "token-abc", httpClient);

        List<McpToolInfo> tools = client.connect();

        // Tools discovered
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("search_table");
        assertThat(tools.get(0).getDescription()).isEqualTo("Search tables");
        assertThat(tools.get(0).getInputSchema()).contains("q");

        // 3 HTTP calls: SSE + initialize + tools/list
        assertThat(captured).hasSize(3);

        // SSE request
        Request sseReq = captured.get(0);
        assertThat(sseReq.url().toString()).isEqualTo("http://localhost:1234/sse");
        assertThat(sseReq.header("accept")).isEqualTo("text/event-stream");
        assertThat(sseReq.header("X-Bdp-Token")).isEqualTo("token-abc");

        // initialize request body must carry protocolVersion + clientInfo.name=snap-agent
        Request initReq = captured.get(1);
        assertThat(initReq.method()).isEqualTo("POST");
        assertThat(initReq.url().toString()).isEqualTo("http://localhost:1234/messages?token=abc");
        assertThat(initReq.header("X-Bdp-Token")).isEqualTo("token-abc");
        String initBody = bodyToString(initReq);
        assertThat(initBody).contains("\"method\":\"initialize\"");
        assertThat(initBody).contains("\"protocolVersion\":\"2024-11-05\"");
        assertThat(initBody).contains("\"name\":\"snap-agent\"");

        // tools/list request
        Request toolsReq = captured.get(2);
        String toolsBody = bodyToString(toolsReq);
        assertThat(toolsBody).contains("\"method\":\"tools/list\"");
    }

    @Test
    void shouldThrowWhenSseEndpointEventNotReceived() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        // SSE response without an endpoint event
        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"comment\":\"no endpoint here\"}\n\n");

        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(sseResponse);

        McpSseClient client = new McpSseClient(
                "http://localhost:1234", "X-Bdp-Token", "token", httpClient);

        assertThatThrownBy(client::connect)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("MCP server did not send endpoint event");
    }

    @Test
    void shouldThrowWhenSseConnectReturnsHttpError() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        Response errorResponse = buildResponse(503, "text/plain", "unavailable");

        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(errorResponse);

        McpSseClient client = new McpSseClient(
                "http://localhost:1234", null, null, httpClient);

        assertThatThrownBy(client::connect)
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("MCP SSE connect failed")
                .hasMessageContaining("503");
    }

    // ---- P1: callTool JSON-RPC passthrough ----

    @Test
    void shouldCallToolViaJsonRpcPassthrough() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        // SSE + initialize + tools/list responses to set up postEndpoint via connect()
        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages\"}\n\n");
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Response toolsResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");
        // tools/call response carrying content[].text
        String callToolJson = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[" +
                "{\"type\":\"text\",\"text\":\"row-1\"}," +
                "{\"type\":\"text\",\"text\":\" row-2\"}" +
                "]}}";
        Response callToolResponse = buildResponse(200, "application/json", callToolJson);

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse, callToolResponse);

        McpSseClient client = new McpSseClient(
                "http://localhost:1234", "X-Bdp-Token", "tok", httpClient);
        client.connect(); // establishes postEndpoint

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("table", "orders");
        String result = client.callTool("search_table", args, 30);

        // Raw JSON-RPC response is returned verbatim (content[].text extraction is McpToolProvider's job)
        assertThat(result).contains("\"result\"");
        assertThat(result).contains("\"content\"");
        assertThat(result).contains("row-1");
        assertThat(result).contains("row-2");

        // 4th request is the tools/call POST
        assertThat(captured).hasSize(4);
        Request callReq = captured.get(3);
        assertThat(callReq.method()).isEqualTo("POST");
        assertThat(callReq.url().toString()).isEqualTo("http://localhost:1234/messages");
        assertThat(callReq.header("X-Bdp-Token")).isEqualTo("tok");
        String body = bodyToString(callReq);
        assertThat(body).contains("\"jsonrpc\":\"2.0\"");
        assertThat(body).contains("\"method\":\"tools/call\"");
        assertThat(body).contains("\"name\":\"search_table\"");
        assertThat(body).contains("orders");
    }

    @Test
    void shouldHandleNullArgumentsInCallTool() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages\"}\n\n");
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Response toolsResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");
        Response callResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse, callResponse);

        McpSseClient client = new McpSseClient("http://localhost:1234", null, null, httpClient);
        client.connect();

        // null arguments must not NPE — coerced to empty map
        String result = client.callTool("noop_tool", null, 5);
        assertThat(result).contains("ok");

        // The tools/call request should carry an empty arguments object, not null
        String body = bodyToString(captured.get(3));
        assertThat(body).contains("\"arguments\":");
    }

    // ---- P2: auth header missing branch ----

    @Test
    void shouldNotSendAuthHeaderWhenValueMissing() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages\"}\n\n");
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Response toolsResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse);

        // BDP_TOKEN not set: authHeader configured but value is null
        McpSseClient client = new McpSseClient(
                "http://localhost:1234", "X-Bdp-Token", null, httpClient);
        client.connect();

        // No request should carry the auth header when the value is absent
        for (Request req : captured) {
            assertThat(req.header("X-Bdp-Token")).isNull();
        }
    }

    @Test
    void shouldNotSendAuthHeaderWhenHeaderNameBlank() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages\"}\n\n");
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Response toolsResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse);

        // Empty auth header name — applyAuth must short-circuit without adding anything
        McpSseClient client = new McpSseClient(
                "http://localhost:1234", "", "some-token", httpClient);
        client.connect();

        assertThat(captured).hasSize(3);
    }

    @Test
    void shouldStripTrailingSlashFromBaseUrl() throws Exception {
        okhttp3.OkHttpClient httpClient = mock(okhttp3.OkHttpClient.class);
        Call call = mock(Call.class);

        Response sseResponse = buildResponse(200, "text/event-stream",
                "data:{\"endpoint\":\"/messages\"}\n\n");
        Response initResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        Response toolsResponse = buildResponse(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}");

        List<Request> captured = new ArrayList<>();
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return call;
        });
        when(call.execute()).thenReturn(sseResponse, initResponse, toolsResponse);

        // Trailing slash on baseUrl must be normalized so SSE URL is not "...//sse"
        McpSseClient client = new McpSseClient(
                "http://localhost:1234/", null, null, httpClient);
        client.connect();

        assertThat(captured.get(0).url().toString()).isEqualTo("http://localhost:1234/sse");
    }

    // ---- helpers ----

    private static Response buildResponse(int code, String contentType, String bodyContent) {
        MediaType mediaType = contentType != null ? MediaType.parse(contentType) : null;
        ResponseBody body = ResponseBody.create(mediaType, bodyContent);
        return new Response.Builder()
                .request(new Request.Builder().url("http://localhost:1234/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(body)
                .build();
    }

    private static String bodyToString(Request request) throws java.io.IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }
}
