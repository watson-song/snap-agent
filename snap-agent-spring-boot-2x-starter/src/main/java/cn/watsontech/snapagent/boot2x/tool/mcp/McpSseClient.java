package cn.watsontech.snapagent.boot2x.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP SSE transport client. Connects to an MCP server via SSE,
 * discovers tools, and proxies tool execution calls via JSON-RPC over HTTP POST.
 */
public class McpSseClient {

    private static final Logger log = LoggerFactory.getLogger(McpSseClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String baseUrl;
    private final String authHeader;
    private final String authHeaderValue;
    private final OkHttpClient httpClient;
    private final AtomicInteger idCounter = new AtomicInteger(1);

    private volatile String postEndpoint;
    private volatile Thread sseThread;
    private volatile boolean running = false;

    public McpSseClient(String baseUrl, String authHeader, String authHeaderValue, OkHttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = authHeader;
        this.authHeaderValue = authHeaderValue;
        this.httpClient = httpClient;
    }

    /**
     * Connect to the MCP server, discover tools.
     * Opens SSE stream to get POST endpoint, sends initialize + tools/list.
     */
    public List<McpToolInfo> connect() throws IOException {
        running = true;
        Request.Builder reqBuilder = new Request.Builder()
                .url(baseUrl + "/sse")
                .header("accept", "text/event-stream");
        applyAuth(reqBuilder);

        try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("MCP SSE connect failed: HTTP " + resp.code());
            }
            okhttp3.ResponseBody body = resp.body();
            if (body == null) throw new IOException("empty SSE response");
            java.io.BufferedReader reader = new java.io.BufferedReader(body.charStream());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        if (node.has("endpoint")) {
                            postEndpoint = node.get("endpoint").asText();
                            log.info("MCP server POST endpoint: {}", postEndpoint);
                            break;
                        }
                    } catch (Exception e) {
                        // Not JSON, skip
                    }
                }
            }
        }
        if (postEndpoint == null) {
            throw new IOException("MCP server did not send endpoint event");
        }

        sendJsonRpc("initialize", initParams(), 5);
        String toolsResponse = sendJsonRpc("tools/list", Collections.emptyMap(), 10);
        List<McpToolInfo> tools = parseToolList(toolsResponse);
        log.info("MCP server returned {} tools", tools.size());
        return tools;
    }

    /**
     * Call a tool on the MCP server via JSON-RPC POST.
     */
    public String callTool(String toolName, Map<String, Object> arguments, int timeoutSeconds) throws IOException {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Collections.emptyMap());
        return sendJsonRpc("tools/call", params, timeoutSeconds);
    }

    public static List<McpToolInfo> parseToolList(String jsonRpcResponse) {
        List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
        try {
            JsonNode root = MAPPER.readTree(jsonRpcResponse);
            JsonNode result = root.path("result");
            JsonNode toolsNode = result.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    String name = t.path("name").asText();
                    String desc = t.path("description").asText("");
                    String schema = t.path("inputSchema").toString();
                    tools.add(new McpToolInfo(name, desc, schema));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse MCP tool list: {}", e.getMessage());
        }
        return tools;
    }

    public static String buildRequest(int id, String method, Map<String, Object> params) {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null && !params.isEmpty()) {
            req.put("params", params);
        }
        try {
            return MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String sendJsonRpc(String method, Map<String, Object> params, int timeoutSeconds) throws IOException {
        int id = idCounter.getAndIncrement();
        String json = buildRequest(id, method, params);

        String url = postEndpoint.startsWith("http") ? postEndpoint : baseUrl + postEndpoint;
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .header("content-type", "application/json")
                .post(RequestBody.create(JSON, json));
        applyAuth(reqBuilder);

        try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("MCP " + method + " failed: HTTP " + resp.code());
            }
            okhttp3.ResponseBody body = resp.body();
            if (body == null) throw new IOException("empty response for " + method);
            return body.string();
        }
    }

    private Map<String, Object> initParams() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", "snap-agent");
        clientInfo.put("version", "0.2");
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", caps);
        params.put("clientInfo", clientInfo);
        return params;
    }

    private void applyAuth(Request.Builder builder) {
        if (authHeader != null && !authHeader.isEmpty() && authHeaderValue != null) {
            builder.header(authHeader, authHeaderValue);
        }
    }

    public void disconnect() {
        running = false;
        if (sseThread != null) sseThread.interrupt();
        log.info("MCP client disconnected from {}", baseUrl);
    }
}
