package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolResult;
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
}
