package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 2.x adapter that exposes a single MCP tool as a {@link ToolCallback}.
 *
 * <p>Refactored from the 1.x {@code McpToolProvider} (which implemented the
 * now-removed {@code ToolProvider} SPI). Because MCP tool names are dynamic
 * ({@code mcp__{server}__{tool}}) and discovered at runtime from the MCP
 * server via {@code tools/list}, these tools cannot use the {@code @Tool}
 * annotation pattern (which requires compile-time-known names). Instead, a
 * {@link McpToolCallback} is built directly via the static factory
 * {@link #from(String, String, String, String, McpSseClient)} and registered
 * alongside annotation-discovered callbacks.</p>
 *
 * <p>The callback delegates execution to {@link McpSseClient#callTool} via
 * JSON-RPC and extracts text content items from the MCP {@code tools/call}
 * response. Tool names are prefixed as {@code mcp__{server}__{tool}} to
 * avoid collisions with built-in tools (e.g. {@code mysql_query},
 * {@code redis_read}).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * for (McpToolInfo info : client.connect()) {
 *     ToolCallback cb = McpTools.from(serverName, info.getName(),
 *                                     info.getDescription(), info.getInputSchema(), client);
 *     registry.register(cb);
 * }
 * }</pre>
 */
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    /**
     * Build a {@link ToolCallback} that wraps a single MCP tool.
     *
     * @param serverName  MCP server name (used to namespace the tool name)
     * @param toolName    raw tool name as reported by the MCP server
     * @param description human-readable description from the MCP server
     * @param inputSchema JSON Schema for the tool's input parameters (as returned
     *                    by the MCP server's {@code tools/list} response)
     * @param client      {@link McpSseClient} used to invoke the MCP server
     * @return a {@link ToolCallback} whose name is {@code mcp__{serverName}__{toolName}}
     */
    public static ToolCallback from(String serverName, String toolName, String description,
                                   String inputSchema, McpSseClient client) {
        return new McpToolCallback(serverName, toolName, description, inputSchema, client);
    }

    /**
     * Inner {@link ToolCallback} wrapping a single MCP tool. Delegates
     * execution to {@link McpSseClient#callTool} and extracts text content
     * items from the JSON-RPC {@code tools/call} response.
     */
    static final class McpToolCallback implements ToolCallback {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final String serverName;
        private final String toolName;
        private final String description;
        private final String inputSchema;
        private final McpSseClient client;

        McpToolCallback(String serverName, String toolName, String description,
                        String inputSchema, McpSseClient client) {
            this.serverName = serverName;
            this.toolName = toolName;
            this.description = description;
            this.inputSchema = inputSchema;
            this.client = client;
        }

        @Override
        public String getName() {
            return "mcp__" + serverName + "__" + toolName;
        }

        @Override
        public String getDescription() {
            return description != null ? description : "";
        }

        @Override
        public String getJsonSchema() {
            if (inputSchema == null || inputSchema.isEmpty()) {
                return "{}";
            }
            return inputSchema;
        }

        @Override
        public ToolResult execute(Map<String, Object> input, Object context) {
            long start = System.currentTimeMillis();
            try {
                String response = client.callTool(toolName, input, 30);
                String content = extractTextContent(response);
                return ToolResult.success(content, 0, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("MCP tool {} execution failed: {}", getName(), e.getMessage());
                return ToolResult.error("MCP tool error: " + e.getMessage(),
                        System.currentTimeMillis() - start);
            }
        }

        /**
         * Extract concatenated text content from a JSON-RPC {@code tools/call} response.
         * Falls back to the raw response when the structure is unexpected.
         */
        private String extractTextContent(String jsonRpcResponse) {
            try {
                JsonNode root = MAPPER.readTree(jsonRpcResponse);
                JsonNode result = root.path("result");
                JsonNode content = result.path("content");
                if (content.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode item : content) {
                        if ("text".equals(item.path("type").asText())) {
                            sb.append(item.path("text").asText());
                        }
                    }
                    return sb.toString();
                }
                return jsonRpcResponse;
            } catch (Exception e) {
                return jsonRpcResponse;
            }
        }
    }
}
