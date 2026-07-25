package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PluginDescriptor} and {@link PluginRegistry} SPI.
 *
 * <p>Covers core-level plugin metadata model and registry SPI
 * from the 09-plugin-mcp TDD spec (UC-01, UC-02, UC-06).</p>
 */
@DisplayName("PluginDescriptor + PluginRegistry SPI")
class PluginDescriptorTest {

    // ── PluginDescriptor ─────────────────────────────────────────

    @Test
    @DisplayName("UC-01: descriptor full constructor sets all fields")
    void shouldSetAllFieldsInFullConstructor() {
        ToolCallback[] callbacks = new ToolCallback[]{new ToolCallback() {
            @Override public ToolResult execute(Map<String, Object> input, Object context) { return null; }
            @Override public String getName() { return "log_read"; }
        }};

        PluginDescriptor desc = new PluginDescriptor(
            "remote-log", "log_read", "Remote Log Plugin",
            "1.0.0", "read remote logs",
            false, true, false,
            callbacks, null, null, null
        );

        assertThat(desc.getPluginId()).isEqualTo("remote-log");
        assertThat(desc.getToolType()).isEqualTo("log_read");
        assertThat(desc.getDisplayName()).isEqualTo("Remote Log Plugin");
        assertThat(desc.getVersion()).isEqualTo("1.0.0");
        assertThat(desc.getDescription()).isEqualTo("read remote logs");
        assertThat(desc.isDefault()).isFalse();
        assertThat(desc.isEnabled()).isTrue();
        assertThat(desc.isSystem()).isFalse();
        assertThat(desc.getToolCallbacks()).hasSize(1);
        assertThat(desc.getToolCallbacks()[0].getName()).isEqualTo("log_read");
    }

    @Test
    @DisplayName("UC-01: null toolCallbacks → empty array")
    void shouldHandleNullToolCallbacks() {
        PluginDescriptor desc = new PluginDescriptor(
            "p", "t", "n", "1", "d",
            false, true, false,
            null, null, null, null
        );
        assertThat(desc.getToolCallbacks()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("UC-02: valid pluginId accepted")
    void shouldAcceptValidPluginId() {
        PluginDescriptor desc = new PluginDescriptor(
            "my-plugin_01", "log", "n", "1", "d",
            false, true, false, null, null, null, null
        );
        assertThat(desc.getPluginId()).isEqualTo("my-plugin_01");
    }

    @Test
    @DisplayName("UC-02: path traversal pluginId rejected")
    void shouldRejectPathTraversalPluginId() {
        assertThatThrownBy(() -> new PluginDescriptor(
            "../../etc/evil", "t", "n", "1", "d",
            false, true, false, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid pluginId");
    }

    @Test
    @DisplayName("UC-02: slash in pluginId rejected")
    void shouldRejectSlashInPluginId() {
        assertThatThrownBy(() -> new PluginDescriptor(
            "evil/plugin", "t", "n", "1", "d",
            false, true, false, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UC-02: shell metacharacters in pluginId rejected")
    void shouldRejectShellMetacharactersInPluginId() {
        assertThatThrownBy(() -> new PluginDescriptor(
            "evil\"; rm -rf", "t", "n", "1", "d",
            false, true, false, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("UC-06: system=true is immutable")
    void shouldHaveImmutableSystemFlag() {
        PluginDescriptor desc = new PluginDescriptor(
            "mysql", "mysql_query", "MySQL", "1", "d",
            true, true, true, null, null, null, null
        );
        assertThat(desc.isSystem()).isTrue();
        // system plugins can't be unregistered
    }

    @Test
    @DisplayName("UC-01: toString contains key fields")
    void shouldHaveMeaningfulToString() {
        PluginDescriptor desc = new PluginDescriptor(
            "remote-log", "log_read", "Remote Log", "2.0", "d",
            false, true, false, null, null, null, null
        );
        String str = desc.toString();
        assertThat(str).contains("remote-log").contains("log_read").contains("2.0");
    }

    // ── PluginRegistry SPI ───────────────────────────────────────

    @Test
    @DisplayName("UC-01: PluginRegistry is an interface")
    void shouldBeInterface() {
        assertThat(PluginRegistry.class.isInterface()).isTrue();
    }

    @Test
    @DisplayName("UC-06: system plugin cannot be unregistered")
    void shouldNotUnregisterSystemPlugin() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor systemPlugin = new PluginDescriptor(
            "mysql", "mysql_query", "MySQL", "1", "d",
            true, true, true, null, null, null, null
        );
        registry.register(systemPlugin);

        assertThatThrownBy(() -> registry.unregister("mysql"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("system");
    }

    @Test
    @DisplayName("UC-06: non-system plugin can be unregistered")
    void shouldUnregisterNonSystemPlugin() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor plugin = new PluginDescriptor(
            "remote-log", "log_read", "Remote Log", "1", "d",
            false, true, false, null, null, null, null
        );
        registry.register(plugin);

        registry.unregister("remote-log");
        assertThat(registry.getPlugin("remote-log")).isNull();
    }

    @Test
    @DisplayName("UC-01: register and getPlugin round-trip")
    void shouldRegisterAndGetPlugin() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor plugin = new PluginDescriptor(
            "my-plugin", "log_read", "My Plugin", "1.0", "d",
            false, true, false, null, null, null, null
        );
        registry.register(plugin);

        PluginDescriptor retrieved = registry.getPlugin("my-plugin");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getPluginId()).isEqualTo("my-plugin");
    }

    @Test
    @DisplayName("UC-02: duplicate register throws")
    void shouldThrowOnDuplicateRegister() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        PluginDescriptor plugin = new PluginDescriptor(
            "dup", "log", "n", "1", "d",
            false, true, false, null, null, null, null
        );
        registry.register(plugin);

        assertThatThrownBy(() -> registry.register(plugin))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("UC-06: listPlugins returns all registered")
    void shouldListAllPlugins() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
            "p1", "t1", "n", "1", "d", false, true, false, null, null, null, null));
        registry.register(new PluginDescriptor(
            "p2", "t2", "n", "1", "d", false, true, false, null, null, null, null));

        List<PluginDescriptor> all = registry.listPlugins();
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("UC-06: toggleEnabled flips enabled flag")
    void shouldToggleEnabled() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        registry.register(new PluginDescriptor(
            "p", "t", "n", "1", "d", false, true, false, null, null, null, null));

        Boolean result = registry.toggleEnabled("p");
        assertThat(result).isFalse();
        assertThat(registry.getPlugin("p").isEnabled()).isFalse();

        result = registry.toggleEnabled("p");
        assertThat(result).isTrue();
        assertThat(registry.getPlugin("p").isEnabled()).isTrue();
    }

    @Test
    @DisplayName("UC-06: toggleEnabled on nonexistent returns null")
    void shouldReturnNullWhenToggleNonExistent() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        assertThat(registry.toggleEnabled("nonexistent")).isNull();
    }

    @Test
    @DisplayName("UC-06: unregister nonexistent is a no-op")
    void shouldNoOpWhenUnregisterNonExistent() {
        PluginRegistry registry = new InMemoryPluginRegistry();
        // should not throw
        registry.unregister("nonexistent");
    }
}
