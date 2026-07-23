package cn.watsontech.snapagent.boot2x.tool.mcp;

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
 * Unit tests for {@link McpToolProvider} — verifies MCP tool name prefixing,
 * delegated execution via {@link McpSseClient}, and error handling.
 */
class McpToolProviderTest {

    private static final ToolContext CTX = new ToolContext("task-1", "user-1", null);

    @Test
    void shouldRegisterToolWithMcpPrefix() {
        McpToolProvider provider = new McpToolProvider(
                "order-service", "get_order_status", "查询订单状态",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}",
                mock(McpSseClient.class));

        assertThat(provider.name()).isEqualTo("mcp__order-service__get_order_status");
        assertThat(provider.schema()).contains("mcp__order-service__get_order_status");
        assertThat(provider.schema()).contains("查询订单状态");
    }

    @Test
    void shouldProxyExecutionToMcpClient() throws Exception {
        McpSseClient client = mock(McpSseClient.class);
        when(client.callTool(eq("get_order_status"), anyMap(), anyInt()))
                .thenReturn("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"订单已发货\"}]}}");

        McpToolProvider provider = new McpToolProvider(
                "order-service", "get_order_status", "查询订单状态",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}",
                client);

        Map<String, Object> args = new HashMap<>();
        args.put("orderId", "ORD-001");
        ToolResult result = provider.execute(args, CTX);

        assertThat(result.getError()).isNull();
        assertThat(result.getContent()).contains("订单已发货");
    }

    @Test
    void shouldHandleMcpCallError() throws Exception {
        McpSseClient client = mock(McpSseClient.class);
        when(client.callTool(anyString(), anyMap(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

        McpToolProvider provider = new McpToolProvider(
                "order-service", "get_order_status", "查询订单状态", "{}", client);

        ToolResult result = provider.execute(new HashMap<>(), CTX);
        assertThat(result.getError()).contains("connection refused");
    }

    // ---- P2: schema() JSON validity ----

    @Test
    void shouldProduceValidJsonSchemaWithNameDescriptionAndInputSchema() throws Exception {
        String inputSchema = "{\"type\":\"object\",\"properties\":{" +
                "\"orderId\":{\"type\":\"string\",\"description\":\"Order id\"}" +
                "},\"required\":[\"orderId\"]}";
        McpToolProvider provider = new McpToolProvider(
                "order-service", "get_order_status", "查询订单状态", inputSchema, mock(McpSseClient.class));

        String schema = provider.schema();

        // Parse the schema as JSON to assert structural validity
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(schema);

        assertThat(node.get("name").asText()).isEqualTo("mcp__order-service__get_order_status");
        assertThat(node.get("description").asText()).isEqualTo("查询订单状态");

        JsonNode inputSchemaNode = node.get("input_schema");
        assertThat(inputSchemaNode).isNotNull();
        assertThat(inputSchemaNode.get("type").asText()).isEqualTo("object");
        assertThat(inputSchemaNode.get("properties").has("orderId")).isTrue();
        assertThat(inputSchemaNode.get("required").get(0).asText()).isEqualTo("orderId");
    }

    @Test
    void shouldFallBackToEmptyInputSchemaWhenNull() throws Exception {
        McpToolProvider provider = new McpToolProvider(
                "srv", "tool", "desc", null, mock(McpSseClient.class));

        String schema = provider.schema();

        JsonNode node = new ObjectMapper().readTree(schema);
        assertThat(node.get("name").asText()).isEqualTo("mcp__srv__tool");
        assertThat(node.get("description").asText()).isEqualTo("desc");
        // null inputSchema must be substituted with "{}" so the JSON stays valid
        assertThat(node.get("input_schema").isObject()).isTrue();
        assertThat(node.get("input_schema").size()).isZero();
    }

    @Test
    void shouldEscapeDescriptionWithSpecialCharacters() throws Exception {
        // Description containing a double quote and backslash must be JSON-escaped
        McpToolProvider provider = new McpToolProvider(
                "srv", "tool", "he said \"hi\" \\ done", "{}", mock(McpSseClient.class));

        String schema = provider.schema();

        JsonNode node = new ObjectMapper().readTree(schema);
        assertThat(node.get("description").asText()).isEqualTo("he said \"hi\" \\ done");
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

        McpToolProvider provider = new McpToolProvider(
                "srv", "multi", "multi-content", "{}", client);

        ToolResult result = provider.execute(new HashMap<>(), CTX);

        assertThat(result.getError()).isNull();
        // Only type=="text" items are extracted; image items skipped
        assertThat(result.getContent()).isEqualTo("alphabeta");
    }
}
