package cn.watsontech.snapagent.boot2x.tool.mcp;

import cn.watsontech.snapagent.core.tool.ToolCallback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link McpBootstrap} — verifies multi-callback accumulation
 * across multiple MCP servers and that the returned list is unmodifiable.
 */
class McpBootstrapTest {

    @Test
    void shouldStartWithEmptyCallbackList() {
        McpBootstrap bootstrap = new McpBootstrap();
        assertThat(bootstrap.getCallbacks()).isEmpty();
    }

    @Test
    void shouldAccumulateCallbacksFromMultipleMcpServers() {
        McpBootstrap bootstrap = new McpBootstrap();
        McpSseClient clientA = mock(McpSseClient.class);
        McpSseClient clientB = mock(McpSseClient.class);

        // Server A returns 2 tools
        bootstrap.addCallback(McpTools.from("server-a", "search_table", "Search tables", "{}", clientA));
        bootstrap.addCallback(McpTools.from("server-a", "insert_row", "Insert a row", "{}", clientA));
        // Server B returns 1 tool
        bootstrap.addCallback(McpTools.from("server-b", "query_meta", "Query metadata", "{}", clientB));

        List<ToolCallback> all = bootstrap.getCallbacks();

        assertThat(all).hasSize(3);
        // Combined tool list preserves insertion order across servers
        assertThat(all).extracting(ToolCallback::getName)
                .containsExactly(
                        "mcp__server-a__search_table",
                        "mcp__server-a__insert_row",
                        "mcp__server-b__query_meta");
    }

    @Test
    void shouldReturnUnmodifiableList() {
        McpBootstrap bootstrap = new McpBootstrap();
        ToolCallback callback = McpTools.from("s", "t", "d", "{}", mock(McpSseClient.class));
        bootstrap.addCallback(callback);

        List<ToolCallback> callbacks = bootstrap.getCallbacks();

        assertThatThrownBy(() -> callbacks.add(callback))
                .isInstanceOf(UnsupportedOperationException.class);
        // Ensure original list is not mutated by the failed add
        assertThat(bootstrap.getCallbacks()).hasSize(1);
    }

    @Test
    void shouldPreserveCallbackReferences() {
        McpBootstrap bootstrap = new McpBootstrap();
        McpSseClient client = mock(McpSseClient.class);
        ToolCallback c1 = McpTools.from("srv", "tool1", "desc1", "{}", client);
        ToolCallback c2 = McpTools.from("srv", "tool2", "desc2", "{}", client);

        bootstrap.addCallback(c1);
        bootstrap.addCallback(c2);

        List<ToolCallback> callbacks = bootstrap.getCallbacks();
        assertThat(callbacks.get(0)).isSameAs(c1);
        assertThat(callbacks.get(1)).isSameAs(c2);
    }
}
