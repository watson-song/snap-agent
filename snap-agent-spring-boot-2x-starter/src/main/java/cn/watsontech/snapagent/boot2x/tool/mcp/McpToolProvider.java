package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Adapts a single MCP tool to the {@link ToolProvider} SPI.
 *
 * <p>Tool name is prefixed as {@code mcp__{server}__{tool}} to avoid collisions
 * with built-in tools (e.g. {@code mysql_query}, {@code redis_read}). The provider
 * delegates execution to {@link McpSseClient} via JSON-RPC and extracts the text
 * content items from the MCP {@code tools/call} response.</p>
 */
public class McpToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final String toolName;
    private final String description;
    private final String inputSchema;
    private final McpSseClient client;

    public McpToolProvider(String serverName, String toolName, String description,
                           String inputSchema, McpSseClient client) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
    }

    @Override
    public String name() {
        return "mcp__" + serverName + "__" + toolName;
    }

    @Override
    public String schema() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(name()).append("\"");
        sb.append(",\"description\":").append(quoteJson(description));
        sb.append(",\"input_schema\":").append(inputSchema != null ? inputSchema : "{}");
        sb.append("}");
        return sb.toString();
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        try {
            String response = client.callTool(toolName, args, 30);
            String content = extractTextContent(response);
            return ToolResult.success(content, 0, 0L);
        } catch (Exception e) {
            log.error("MCP tool {} execution failed: {}", name(), e.getMessage());
            return ToolResult.error("MCP tool error: " + e.getMessage(), 0L);
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

    private String quoteJson(String value) {
        if (value == null) return "\"\"";
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }
}
