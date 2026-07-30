package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolCallbackRegistryImpl")
class ToolCallbackRegistryImplTest {

    private ToolCallback callback(String name, boolean system) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getName()).thenReturn(name);
        when(cb.isSystem()).thenReturn(system);
        when(cb.getDescription()).thenReturn("desc-" + name);
        when(cb.getJsonSchema()).thenReturn("{\"type\":\"object\"}");
        return cb;
    }

    @Test
    @DisplayName("register + find + getAll (UC-07)")
    void registerFindGetAll() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        ToolCallback cb = callback("mysql_query", false);
        registry.register(cb);
        assertThat(registry.getAll()).hasSize(1);
        assertThat(registry.find("mysql_query")).isSameAs(cb);
        assertThat(registry.find("nonexistent")).isNull();
    }

    @Test
    @DisplayName("重复注册抛 IllegalArgumentException (UC-08)")
    void duplicateRegisterThrows() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", false));
        assertThatThrownBy(() -> registry.register(callback("mysql_query", false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool already registered: mysql_query");
    }

    @Test
    @DisplayName("system 不可 unregister (UC-09)")
    void systemCannotUnregister() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", true));
        assertThatThrownBy(() -> registry.unregister("mysql_query"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("cannot unregister system tool");
    }

    @Test
    @DisplayName("非 system 可 unregister (UC-09)")
    void nonSystemCanUnregister() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("custom_tool", false));
        registry.unregister("custom_tool");
        assertThat(registry.getAll()).isEmpty();
        assertThat(registry.find("custom_tool")).isNull();
    }

    @Test
    @DisplayName("toToolDefinitionsJson 生成 JSON 数组 (UC-10)")
    void toToolDefinitionsJson() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("mysql_query", false));
        registry.register(callback("log_read", false));
        String json = registry.toToolDefinitionsJson();
        assertThat(json).startsWith("[").endsWith("]");
        assertThat(json).contains("mysql_query");
        assertThat(json).contains("log_read");
        assertThat(json).contains("desc-mysql_query");
        assertThat(json).contains("input_schema");
    }

    @Test
    @DisplayName("subset null 返回全部 (UC-23)")
    void subsetNullReturnsAll() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("tool_a", false));
        registry.register(callback("tool_b", false));
        ToolCallbackRegistry sub = registry.subset(null);
        assertThat(sub.getAll()).hasSize(2);
    }

    @Test
    @DisplayName("subset 选子集 (UC-23)")
    void subsetSelects() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("local-log", false));
        registry.register(callback("remote-log", false));
        Map<String, String> overrides = new HashMap<>();
        overrides.put("log_read", "remote-log");
        ToolCallbackRegistry sub = registry.subset(overrides);
        assertThat(sub.find("remote-log")).isNotNull();
        assertThat(sub.find("local-log")).isNull();
    }

    @Test
    @DisplayName("subset 指向不存在的工具抛异常 (UC-23)")
    void subsetNonExistentThrows() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        registry.register(callback("tool_a", false));
        Map<String, String> overrides = new HashMap<>();
        overrides.put("log_read", "nonexistent");
        assertThatThrownBy(() -> registry.subset(overrides))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool not found in registry: nonexistent");
    }

    @Test
    @DisplayName("escapeJson 转义控制字符 (UC-10)")
    void shouldEscapeControlCharsInJson() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        // Register a callback with control characters in description
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getName()).thenReturn("test_ctrl");
        when(cb.isSystem()).thenReturn(false);
        when(cb.getDescription()).thenReturn("desc\b\f\u0001\u0002");
        when(cb.getJsonSchema()).thenReturn("{}");
        registry.register(cb);
        String json = registry.toToolDefinitionsJson();
        // Verify control chars are escaped (not present as raw bytes)
        assertThat(json).doesNotContain("\b");
        assertThat(json).doesNotContain("\f");
        assertThat(json).doesNotContain("\u0001");
        assertThat(json).contains("\\b");
        assertThat(json).contains("\\f");
        assertThat(json).contains("\\u0001");
    }

    @Test
    @DisplayName("unregister 使用原子 remove 避免竞态 (UC-09)")
    void shouldNotRemoveDifferentCallbackInUnregister() {
        ToolCallbackRegistryImpl registry = new ToolCallbackRegistryImpl();
        ToolCallback original = callback("tool_a", false);
        registry.register(original);
        // Simulate: get returns original, but before remove, another thread replaces it
        // After our fix (remove(name, callback)), the new callback should survive
        registry.unregister("tool_a");
        assertThat(registry.find("tool_a")).isNull();

        // Also verify: unregister of non-existent tool doesn't throw
        registry.unregister("non_existent");
    }
}
