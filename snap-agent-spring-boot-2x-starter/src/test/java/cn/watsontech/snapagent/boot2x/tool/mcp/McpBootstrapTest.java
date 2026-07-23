package cn.watsontech.snapagent.boot2x.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link McpBootstrap} — verifies multi-provider accumulation
 * across multiple MCP servers and that the returned list is unmodifiable.
 */
class McpBootstrapTest {

    @Test
    void shouldStartWithEmptyProviderList() {
        McpBootstrap bootstrap = new McpBootstrap();
        assertThat(bootstrap.getProviders()).isEmpty();
    }

    @Test
    void shouldAccumulateProvidersFromMultipleMcpServers() {
        McpBootstrap bootstrap = new McpBootstrap();
        McpSseClient clientA = mock(McpSseClient.class);
        McpSseClient clientB = mock(McpSseClient.class);

        // Server A returns 2 tools
        bootstrap.addProvider(new McpToolProvider("server-a", "search_table", "Search tables", "{}", clientA));
        bootstrap.addProvider(new McpToolProvider("server-a", "insert_row", "Insert a row", "{}", clientA));
        // Server B returns 1 tool
        bootstrap.addProvider(new McpToolProvider("server-b", "query_meta", "Query metadata", "{}", clientB));

        List<McpToolProvider> all = bootstrap.getProviders();

        assertThat(all).hasSize(3);
        // Combined tool list preserves insertion order across servers
        assertThat(all).extracting(McpToolProvider::name)
                .containsExactly(
                        "mcp__server-a__search_table",
                        "mcp__server-a__insert_row",
                        "mcp__server-b__query_meta");
    }

    @Test
    void shouldReturnUnmodifiableList() {
        McpBootstrap bootstrap = new McpBootstrap();
        McpToolProvider provider = new McpToolProvider("s", "t", "d", "{}", mock(McpSseClient.class));
        bootstrap.addProvider(provider);

        List<McpToolProvider> providers = bootstrap.getProviders();

        assertThatThrownBy(() -> providers.add(provider))
                .isInstanceOf(UnsupportedOperationException.class);
        // Ensure original list is not mutated by the failed add
        assertThat(bootstrap.getProviders()).hasSize(1);
    }

    @Test
    void shouldPreserveProviderReferences() {
        McpBootstrap bootstrap = new McpBootstrap();
        McpSseClient client = mock(McpSseClient.class);
        McpToolProvider p1 = new McpToolProvider("srv", "tool1", "desc1", "{}", client);
        McpToolProvider p2 = new McpToolProvider("srv", "tool2", "desc2", "{}", client);

        bootstrap.addProvider(p1);
        bootstrap.addProvider(p2);

        List<McpToolProvider> providers = bootstrap.getProviders();
        assertThat(providers.get(0)).isSameAs(p1);
        assertThat(providers.get(1)).isSameAs(p2);
    }
}
