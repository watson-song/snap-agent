package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link ToolPlugin} SPI interface default methods.
 *
 * <p>Verifies that a minimal direct implementation (no annotation, no
 * ToolProvider) works correctly with the documented defaults, and that
 * a full implementation can override every method.</p>
 */
class ToolPluginTest {

    // --- G-307: ToolPlugin SPI interface (non-annotation, direct implementation) ---

    @Test
    void shouldUseDefaultsForMinimalImplementation() {
        ToolPlugin plugin = new ToolPlugin() {
            @Override
            public String name() {
                return "my-plugin";
            }

            @Override
            public String version() {
                return "1.0.0";
            }
        };

        assertThat(plugin.name()).isEqualTo("my-plugin");
        assertThat(plugin.version()).isEqualTo("1.0.0");
        assertThat(plugin.description()).isEmpty();
        assertThat(plugin.toolNames()).isEmpty();
    }

    @Test
    void shouldReturnCustomValuesFromFullImplementation() {
        ToolPlugin plugin = new ToolPlugin() {
            @Override
            public String name() {
                return "remote-log";
            }

            @Override
            public String version() {
                return "2.0.0";
            }

            @Override
            public String description() {
                return "Remote log reader";
            }

            @Override
            public List<String> toolNames() {
                return Arrays.asList("log_read", "log_search");
            }
        };

        assertThat(plugin.name()).isEqualTo("remote-log");
        assertThat(plugin.version()).isEqualTo("2.0.0");
        assertThat(plugin.description()).isEqualTo("Remote log reader");
        assertThat(plugin.toolNames()).containsExactly("log_read", "log_search");
    }

    @Test
    void shouldReturnUnmodifiableEmptyListForDefaultToolNames() {
        ToolPlugin plugin = new ToolPlugin() {
            @Override
            public String name() {
                return "p";
            }

            @Override
            public String version() {
                return "1.0.0";
            }
        };

        List<String> names = plugin.toolNames();
        assertThat(names).isEmpty();
        // Collections.emptyList() is unmodifiable
        assertThatThrownBy(() -> names.add("foo"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnEmptyStringForDefaultDescription() {
        ToolPlugin plugin = new ToolPlugin() {
            @Override
            public String name() {
                return "p";
            }

            @Override
            public String version() {
                return "1.0.0";
            }
        };

        assertThat(plugin.description()).isEqualTo("");
    }
}
