package cn.watsontech.snapagent.boot2x.tool;

import cn.watsontech.snapagent.core.tool.ToolPlugin;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ToolPluginRegistry}.
 */
class ToolPluginRegistryTest {

    @Test
    void shouldReturnEmptyListWhenNoPluginsProvided() {
        ToolPluginRegistry registry = new ToolPluginRegistry(null);
        assertThat(registry.getPlugins()).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenEmptyListProvided() {
        ToolPluginRegistry registry = new ToolPluginRegistry(
                Collections.<ToolPlugin>emptyList());
        assertThat(registry.getPlugins()).isEmpty();
    }

    @Test
    void shouldReturnPluginsWhenProvided() {
        ToolPlugin plugin1 = mock(ToolPlugin.class);
        when(plugin1.name()).thenReturn("mysql-plugin");
        when(plugin1.version()).thenReturn("1.0.0");

        ToolPlugin plugin2 = mock(ToolPlugin.class);
        when(plugin2.name()).thenReturn("redis-plugin");
        when(plugin2.version()).thenReturn("2.0.0");

        ToolPluginRegistry registry = new ToolPluginRegistry(
                Arrays.asList(plugin1, plugin2));

        List<ToolPlugin> plugins = registry.getPlugins();
        assertThat(plugins).hasSize(2);
        assertThat(plugins.get(0).name()).isEqualTo("mysql-plugin");
        assertThat(plugins.get(1).name()).isEqualTo("redis-plugin");
    }

    @Test
    void shouldReturnUnmodifiableList() {
        ToolPlugin plugin = mock(ToolPlugin.class);
        ToolPluginRegistry registry = new ToolPluginRegistry(
                Collections.singletonList(plugin));

        List<ToolPlugin> plugins = registry.getPlugins();
        // Verify the list is unmodifiable by attempting to add and expecting an exception
        try {
            plugins.add(mock(ToolPlugin.class));
            org.junit.jupiter.api.Assertions.fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected — list is unmodifiable
        }
    }
}
