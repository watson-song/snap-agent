package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ToolCallbackRegistry SPI 升级")
class ToolCallbackRegistrySpiTest {

    @Test
    @DisplayName("default getAll 返回空列表")
    void defaultGetAll() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    @DisplayName("default register 抛 UnsupportedOperationException")
    void defaultRegisterThrows() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThatThrownBy(() -> registry.register(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("default unregister 抛 UnsupportedOperationException")
    void defaultUnregisterThrows() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThatThrownBy(() -> registry.unregister("test"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("default toToolDefinitionsJson 返回空数组")
    void defaultToJson() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.toToolDefinitionsJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("default subset 返回自身")
    void defaultSubset() {
        ToolCallbackRegistry registry = new ToolCallbackRegistry() {
            @Override public ToolCallback find(String toolName) { return null; }
        };
        assertThat(registry.subset(null)).isSameAs(registry);
    }
}
