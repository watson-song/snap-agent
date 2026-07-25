package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCallback SPI 升级")
class ToolCallbackSpiTest {

    @Test
    @DisplayName("getDescription 默认空字符串")
    void defaultDescription() {
        ToolCallback callback = new ToolCallback() {
            @Override public ToolResult execute(java.util.Map<String, Object> input, Object context) { return null; }
            @Override public String getName() { return "test"; }
        };
        assertThat(callback.getDescription()).isEmpty();
    }

    @Test
    @DisplayName("getJsonSchema 默认空对象")
    void defaultJsonSchema() {
        ToolCallback callback = new ToolCallback() {
            @Override public ToolResult execute(java.util.Map<String, Object> input, Object context) { return null; }
            @Override public String getName() { return "test"; }
        };
        assertThat(callback.getJsonSchema()).isEqualTo("{}");
    }
}
