package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolDispatcher;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import cn.watsontech.snapagent.core.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies the auto-wrapping logic that turns built-in {@link ToolProvider} beans
 * into system plugins registered in {@link PluginRegistry}.
 *
 * <p>Simulates the {@code pluginRegistry} bean method in
 * {@link SnapAgentAutoConfiguration}: collect all ToolProvider beans, wrap each
 * as a system PluginDescriptor, and register. Then verifies that
 * {@link ToolDispatcher} routes dispatch calls via the registry.</p>
 */
class PluginAutoWrappingTest {

    @Test
    void shouldWrapToolProvidersAsSystemPlugins() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolProvider mysql = mockProvider("mysql_query");
        ToolProvider redis = mockProvider("redis_get");

        // Simulate auto-wrapping logic from SnapAgentAutoConfiguration.pluginRegistry()
        for (ToolProvider p : Arrays.asList(mysql, redis)) {
            PluginDescriptor desc = new PluginDescriptor(
                    p.name(), p.name(), p.name(), "", "built-in",
                    true, true, true, p, null, null, null);
            registry.register(desc);
        }

        List<PluginDescriptor> plugins = registry.list();
        assertThat(plugins).hasSize(2);
        for (PluginDescriptor desc : plugins) {
            assertThat(desc.isSystem()).isTrue();
            assertThat(desc.isEnabled()).isTrue();
            assertThat(desc.isDefault()).isTrue();
            assertThat(desc.getVersion()).isEqualTo("built-in");
        }
    }

    @Test
    void shouldRouteDispatchViaRegistry() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolProvider mysql = mockProvider("mysql_query");
        when(mysql.execute(Mockito.anyMap(), Mockito.any(ToolContext.class)))
                .thenReturn(ToolResult.success("query-ok", 1, 0));

        PluginDescriptor desc = new PluginDescriptor(
                "mysql_query", "mysql_query", "MySQL", "", "built-in",
                true, true, true, mysql, null, null, null);
        registry.register(desc);

        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        assertThat(dispatcher.availableToolTypes()).contains("mysql_query");
        assertThat(dispatcher.activePlugins()).hasSize(1);

        Map<String, Object> args = new HashMap<>();
        ToolContext ctx = new ToolContext("task-1", "user-1", null);
        ToolResult result = dispatcher.dispatch("mysql_query", args, ctx);
        assertThat(result.getContent()).isEqualTo("query-ok");
    }

    @Test
    void shouldSkipProvidersWithNullName() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolProvider validProvider = mockProvider("valid_tool");
        ToolProvider nullNameProvider = Mockito.mock(ToolProvider.class);
        when(nullNameProvider.name()).thenReturn(null);

        // Simulate auto-wrapping with null-name guard
        List<ToolProvider> providers = new ArrayList<>(Arrays.asList(validProvider, nullNameProvider));
        for (ToolProvider p : providers) {
            if (p == null || p.name() == null) continue;
            PluginDescriptor desc = new PluginDescriptor(
                    p.name(), p.name(), p.name(), "", "built-in",
                    true, true, true, p, null, null, null);
            registry.register(desc);
        }

        assertThat(registry.list()).hasSize(1);
        assertThat(registry.getPlugin("valid_tool")).isNotNull();
    }

    @Test
    void shouldOverrideDefaultPluginWithPluginOverrides() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        // Register default plugin
        ToolProvider defaultProvider = mockProvider("mysql_query");
        registry.register(new PluginDescriptor(
                "default-mysql", "mysql_query", "Default MySQL", "", "built-in",
                true, true, true, defaultProvider, null, null, null));

        // Register alternative plugin (non-default)
        ToolProvider remoteProvider = mockProvider("mysql_query");
        registry.register(new PluginDescriptor(
                "remote-mysql", "mysql_query", "Remote MySQL", "", "1.0",
                false, true, false, remoteProvider, null, null, null));

        ToolDispatcher dispatcher = new ToolDispatcher(registry, 50000);

        // Without override: default plugin is active
        assertThat(dispatcher.activePlugins()).hasSize(1);
        assertThat(dispatcher.activePlugins().iterator().next().getPluginId())
                .isEqualTo("default-mysql");

        // With override: remote plugin is active
        Map<String, String> overrides = new HashMap<>();
        overrides.put("mysql_query", "remote-mysql");
        assertThat(dispatcher.activePlugins(overrides).iterator().next().getPluginId())
                .isEqualTo("remote-mysql");
    }

    private ToolProvider mockProvider(String name) {
        ToolProvider provider = Mockito.mock(ToolProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.schema()).thenReturn("{}");
        return provider;
    }
}
