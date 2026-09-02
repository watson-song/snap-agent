package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallbackTimeoutTest {

    @Test
    void defaultTimeoutShouldBe30Seconds() {
        ToolCallback cb = new ToolCallback() {
            @Override
            public ToolResult execute(Map<String, Object> input, Object context) {
                return ToolResult.success("ok", 0, 0);
            }
            @Override
            public String getName() { return "test"; }
        };
        assertThat(cb.getTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void toolAnnotationTimeoutShouldPropagate() {
        ToolCallback[] callbacks = ToolCallbacks.from(new AnnotatedTool());
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getTimeoutSeconds()).isEqualTo(5);
    }

    @Test
    void toolAnnotationDefaultTimeoutShouldBe30() {
        ToolCallback[] callbacks = ToolCallbacks.from(new DefaultTimeoutTool());
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getTimeoutSeconds()).isEqualTo(30);
    }

    static class AnnotatedTool {
        @Tool(description = "fast tool", timeoutSeconds = 5)
        public String fast(@ToolParam(description = "input") String input) {
            return "result: " + input;
        }
    }

    static class DefaultTimeoutTool {
        @Tool(description = "default timeout tool")
        public String normal(@ToolParam(description = "input") String input) {
            return "result: " + input;
        }
    }
}
