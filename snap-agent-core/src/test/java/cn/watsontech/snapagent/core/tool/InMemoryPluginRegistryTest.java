package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryPluginRegistryTest {

    private InMemoryPluginRegistry registry;

    private PluginDescriptor newPlugin(String id, String toolType, boolean system, boolean isDefault) {
        return new PluginDescriptor(
                id, toolType, id, "", "1.0",
                isDefault, true, system,
                Mockito.mock(ToolProvider.class), null, null, null);
    }

    @BeforeEach
    void setUp() {
        registry = new InMemoryPluginRegistry();
    }

    @Test
    void shouldRegisterPluginAndRetrieveById() {
        PluginDescriptor desc = newPlugin("mysql", "mysql_query", true, true);
        registry.register(desc);
        assertThat(registry.getPlugin("mysql")).isSameAs(desc);
    }

    @Test
    void shouldReturnNullForUnknownPluginId() {
        assertThat(registry.getPlugin("nonexistent")).isNull();
    }

    @Test
    void shouldThrowWhenRegisteringDuplicatePluginId() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        assertThatThrownBy(() -> registry.register(newPlugin("mysql", "mysql_query", false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plugin already registered: mysql");
    }

    @Test
    void shouldAutoSetFirstPluginAsDefault() {
        PluginDescriptor desc = newPlugin("mysql", "mysql_query", true, false);
        registry.register(desc);
        assertThat(registry.getDefault("mysql_query")).isSameAs(desc);
        assertThat(desc.isDefault()).isTrue();
    }

    @Test
    void shouldThrowWhenUnregisteringSystemPlugin() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        assertThatThrownBy(() -> registry.unregister("mysql"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot unregister system plugin");
    }

    @Test
    void shouldUnregisterNonSystemPlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));
        registry.unregister("custom");
        assertThat(registry.getPlugin("custom")).isNull();
    }

    @Test
    void shouldClearDefaultWhenUnregisteringDefaultPlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));
        registry.unregister("custom");
        assertThat(registry.getDefault("log_read")).isNull();
    }

    @Test
    void shouldEnableAndDisablePlugin() {
        registry.register(newPlugin("custom", "log_read", false, true));
        registry.disable("custom");
        assertThat(registry.getPlugin("custom").isEnabled()).isFalse();
        registry.enable("custom");
        assertThat(registry.getPlugin("custom").isEnabled()).isTrue();
    }

    @Test
    void shouldSetDefaultAndClearOthers() {
        PluginDescriptor first = newPlugin("log1", "log_read", false, true);
        PluginDescriptor second = newPlugin("log2", "log_read", false, false);
        registry.register(first);
        registry.register(second);
        registry.setDefault("log_read", "log2");
        assertThat(first.isDefault()).isFalse();
        assertThat(second.isDefault()).isTrue();
        assertThat(registry.getDefault("log_read")).isSameAs(second);
    }

    @Test
    void shouldReturnPluginsForTypeFiltered() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        registry.register(newPlugin("redis", "redis_get", true, true));
        List<PluginDescriptor> result = registry.getPluginsForType("mysql_query");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPluginId()).isEqualTo("mysql");
    }

    @Test
    void shouldIncludeDisabledPluginsInGetPluginsForType() {
        PluginDescriptor desc = newPlugin("custom", "log_read", false, true);
        registry.register(desc);
        registry.disable("custom");
        List<PluginDescriptor> result = registry.getPluginsForType("log_read");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPluginId()).isEqualTo("custom");
    }

    @Test
    void shouldReturnAllPluginsFromList() {
        registry.register(newPlugin("mysql", "mysql_query", true, true));
        registry.register(newPlugin("redis", "redis_get", true, true));
        registry.register(newPlugin("custom", "log_read", false, true));
        assertThat(registry.list()).hasSize(3);
    }

    @Test
    void shouldReturnNullDefaultForUnknownToolType() {
        assertThat(registry.getDefault("nonexistent")).isNull();
    }

    @Test
    void shouldAutoPickNewDefaultWhenDefaultUnregistered() {
        PluginDescriptor first = newPlugin("log1", "log_read", false, true);
        PluginDescriptor second = newPlugin("log2", "log_read", false, false);
        registry.register(first);
        registry.register(second);
        registry.unregister("log1");
        assertThat(registry.getDefault("log_read")).isSameAs(second);
        assertThat(second.isDefault()).isTrue();
    }
}
