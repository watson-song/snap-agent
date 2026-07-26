package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpTools} — verifies MCP tool name prefixing,
 * delegated execution via {@link McpSseClient}, and error handling.
 */
class McpToolProviderTest {

    private static final ToolContext CTX = new ToolContext("task-1", "user-1", null);

    @Test
    void shouldRegisterToolWithMcpPrefix() {
        ToolCallback callback = McpTools.from(
                "order-service", "get_order_status", "查询订单状态",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}",
                mock(McpSseClient.class));

        assertThat(callback.getName()).isEqualTo("mcp__order-service__get_order_status");
        assertThat(callback.getJsonSchema()).contains("orderId");
    }

    @Test
    void shouldProxyExecutionToMcpClient() throws Exception {
        McpSseClient client = mock(McpSseClient.class);
        when(client.callTool(eq("get_order_status"), anyMap(), anyInt()))
                .thenReturn("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"订单已发货\"}]}}");

        ToolCallback callback = McpTools.from(
                "order-service", "get_order_status", "查询订单状态",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}",
                client);

        Map<String, Object> args = new HashMap<>();
        args.put("orderId", "ORD-001");
        ToolResult result = callback.execute(args, CTX);

        assertThat(result.getError()).isNull();
        assertThat(result.getContent()).contains("订单已发货");
    }

    @Test
    void shouldHandleMcpCallError() throws Exception {
        McpSseClient client = mock(McpSseClient.class);
        when(client.callTool(anyString(), anyMap(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

        ToolCallback callback = McpTools.from(
                "order-service", "get_order_status", "查询订单状态", "{}", client);

        ToolResult result = callback.execute(new HashMap<>(), CTX);
        assertThat(result.getError()).contains("connection refused");
    }

    // ---- P2: getDescription() and getJsonSchema() ----

    @Test
    void shouldProduceValidJsonSchemaWithNameDescriptionAndInputSchema() throws Exception {
        String inputSchema = "{\"type\":\"object\",\"properties\":{" +
                "\"orderId\":{\"type\":\"string\",\"description\":\"Order id\"}" +
                "},\"required\":[\"orderId\"]}";
        ToolCallback callback = McpTools.from(
                "order-service", "get_order_status", "查询订单状态", inputSchema, mock(McpSseClient.class));

        assertThat(callback.getName()).isEqualTo("mcp__order-service__get_order_status");
        assertThat(callback.getDescription()).isEqualTo("查询订单状态");

        // Verify the JSON schema is valid JSON with expected fields
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(callback.getJsonSchema());
        assertThat(node.get("type").asText()).isEqualTo("object");
        assertThat(node.get("properties").has("orderId")).isTrue();
        assertThat(node.get("required").get(0).asText()).isEqualTo("orderId");
    }

    @Test
    void shouldFallBackToEmptyInputSchemaWhenNull() throws Exception {
        ToolCallback callback = McpTools.from(
                "srv", "tool", "desc", null, mock(McpSseClient.class));

        assertThat(callback.getName()).isEqualTo("mcp__srv__tool");
        assertThat(callback.getDescription()).isEqualTo("desc");
        // null inputSchema must be substituted with "{}"
        JsonNode node = new ObjectMapper().readTree(callback.getJsonSchema());
        assertThat(node.isObject()).isTrue();
        assertThat(node.size()).isZero();
    }

    @Test
    void shouldEscapeDescriptionWithSpecialCharacters() throws Exception {
        // Description containing a double quote and backslash must be preserved
        ToolCallback callback = McpTools.from(
                "srv", "tool", "he said \"hi\" \\ done", "{}", mock(McpSseClient.class));

        assertThat(callback.getDescription()).isEqualTo("he said \"hi\" \\ done");
    }

    @Test
    void shouldConcatenateMultipleTextContentItems() throws Exception {
        McpSseClient client = mock(McpSseClient.class);
        // Multiple text content items must be concatenated in order
        when(client.callTool(eq("multi"), anyMap(), anyInt()))
                .thenReturn("{\"result\":{\"content\":[" +
                        "{\"type\":\"text\",\"text\":\"alpha\"}," +
                        "{\"type\":\"image\",\"url\":\"ignored\"}," +
                        "{\"type\":\"text\",\"text\":\"beta\"}" +
                        "]}}");

        ToolCallback callback = McpTools.from(
                "srv", "multi", "multi-content", "{}", client);

        ToolResult result = callback.execute(new HashMap<>(), CTX);

        assertThat(result.getError()).isNull();
        // Only type=="text" items are extracted; image items skipped
        assertThat(result.getContent()).isEqualTo("alphabeta");
    }
}
