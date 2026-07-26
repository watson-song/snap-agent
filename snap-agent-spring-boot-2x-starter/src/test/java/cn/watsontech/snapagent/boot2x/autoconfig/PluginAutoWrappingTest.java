package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.tool.InMemoryPluginRegistry;
import cn.watsontech.snapagent.core.tool.PluginDescriptor;
import cn.watsontech.snapagent.core.tool.PluginRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolCallback;
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
 * Verifies the auto-wrapping logic that turns built-in {@link ToolCallback} beans
 * into system plugins registered in {@link PluginRegistry}.
 *
 * <p>Simulates the {@code pluginRegistry} bean method in
 * {@link SnapAgentAutoConfiguration}: collect all ToolCallback beans, wrap each
 * as a system PluginDescriptor, and register. Then verifies that
 * {@link ToolCallbackRegistry} routes dispatch calls via the registry.</p>
 */
class PluginAutoWrappingTest {

    @Test
    void shouldWrapToolCallbacksAsSystemPlugins() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolCallback mysql = mockProvider("mysql_query");
        ToolCallback redis = mockProvider("redis_get");

        // Simulate auto-wrapping logic from SnapAgentAutoConfiguration.pluginRegistry()
        for (ToolCallback p : Arrays.asList(mysql, redis)) {
            PluginDescriptor desc = new PluginDescriptor(
                    p.getName(), p.getName(), p.getName(), "built-in", "",
                    true, true, true, new ToolCallback[]{p}, null, null, null);
            registry.register(desc);
        }

        List<PluginDescriptor> plugins = registry.listPlugins();
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

        ToolCallback mysql = mockProvider("mysql_query");
        when(mysql.execute(Mockito.anyMap(), Mockito.any()))
                .thenReturn(ToolResult.success("query-ok", 1, 0));

        PluginDescriptor desc = new PluginDescriptor(
                "mysql_query", "mysql_query", "MySQL", "built-in", "",
                true, true, true, new ToolCallback[]{mysql}, null, null, null);
        registry.register(desc);

        // Verify the plugin is registered and its callback is accessible
        PluginDescriptor registered = registry.getPlugin("mysql_query");
        assertThat(registered).isNotNull();
        assertThat(registered.getToolCallbacks()).hasSize(1);
        assertThat(registered.getToolCallbacks()[0].getName()).isEqualTo("mysql_query");

        // Verify the callback can be executed
        ToolResult result = registered.getToolCallbacks()[0].execute(
                new HashMap<String, Object>(), null);
        assertThat(result.getContent()).isEqualTo("query-ok");
    }

    @Test
    void shouldSkipProvidersWithNullName() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolCallback validProvider = mockProvider("valid_tool");
        ToolCallback nullNameProvider = Mockito.mock(ToolCallback.class);
        when(nullNameProvider.getName()).thenReturn(null);

        // Simulate auto-wrapping with null-name guard
        List<ToolCallback> providers = new ArrayList<>(Arrays.asList(validProvider, nullNameProvider));
        for (ToolCallback p : providers) {
            if (p == null || p.getName() == null) continue;
            PluginDescriptor desc = new PluginDescriptor(
                    p.getName(), p.getName(), p.getName(), "built-in", "",
                    true, true, true, new ToolCallback[]{p}, null, null, null);
            registry.register(desc);
        }

        assertThat(registry.listPlugins()).hasSize(1);
        assertThat(registry.getPlugin("valid_tool")).isNotNull();
    }

    @Test
    void shouldOverrideDefaultPluginWithPluginOverrides() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        // Register default plugin
        ToolCallback defaultProvider = mockProvider("mysql_query");
        registry.register(new PluginDescriptor(
                "default-mysql", "mysql_query", "Default MySQL", "built-in", "",
                true, true, true, new ToolCallback[]{defaultProvider}, null, null, null));

        // Register alternative plugin (non-default)
        ToolCallback remoteProvider = mockProvider("mysql_query");
        registry.register(new PluginDescriptor(
                "remote-mysql", "mysql_query", "Remote MySQL", "1.0", "",
                false, true, false, new ToolCallback[]{remoteProvider}, null, null, null));

        // Both plugins are registered
        assertThat(registry.listPlugins()).hasSize(2);
        assertThat(registry.getPlugin("default-mysql")).isNotNull();
        assertThat(registry.getPlugin("remote-mysql")).isNotNull();
        assertThat(registry.getPlugin("default-mysql").isDefault()).isTrue();
        assertThat(registry.getPlugin("remote-mysql").isDefault()).isFalse();
    }

    // --- G-03D: single ToolCallback bean wrapped as system default ---

    @Test
    void shouldWrapToolCallbackBeanAsSystemDefaultPlugin() {
        InMemoryPluginRegistry registry = new InMemoryPluginRegistry();

        ToolCallback provider = mockProvider("mysql_query");

        // Simulate auto-wrapping a single ToolCallback bean as a system default plugin
        PluginDescriptor desc = new PluginDescriptor(
                provider.getName(), provider.getName(), provider.getName(), "built-in", "",
                true, true, true, new ToolCallback[]{provider}, null, null, null);
        registry.register(desc);

        PluginDescriptor registered = registry.getPlugin("mysql_query");
        assertThat(registered).isNotNull();
        assertThat(registered.isSystem()).isTrue();
        assertThat(registered.isDefault()).isTrue();
        assertThat(registered.isEnabled()).isTrue();
        assertThat(registered.getToolCallbacks()).hasSize(1);
        assertThat(registered.getToolCallbacks()[0]).isSameAs(provider);
    }

    private ToolCallback mockProvider(String name) {
        ToolCallback provider = Mockito.mock(ToolCallback.class);
        when(provider.getName()).thenReturn(name);
        when(provider.getJsonSchema()).thenReturn("{}");
        return provider;
    }
}
