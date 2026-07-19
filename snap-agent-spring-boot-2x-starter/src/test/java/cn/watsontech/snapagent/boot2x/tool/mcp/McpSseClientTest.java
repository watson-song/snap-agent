package cn.watsontech.snapagent.boot2x.tool.mcp;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
}
