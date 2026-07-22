package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolContextTest {

    @Test
    void shouldHoldAllFields() {
        AuditCallback cb = (name, args, result) -> { };
        ToolContext ctx = new ToolContext("task-1", "user-1", cb);

        assertThat(ctx.getTaskId()).isEqualTo("task-1");
        assertThat(ctx.getUserId()).isEqualTo("user-1");
        assertThat(ctx.getAuditCallback()).isSameAs(cb);
    }

    @Test
    void shouldAllowNullableCallback() {
        ToolContext ctx = new ToolContext("task-2", "user-2", null);

        assertThat(ctx.getUserId()).isEqualTo("user-2");
        assertThat(ctx.getAuditCallback()).isNull();
    }

    @Test
    void shouldReturnEmptyPluginOverridesWhenUsingOldConstructor() {
        ToolContext ctx = new ToolContext("t1", "u1", null);

        assertThat(ctx.getPluginOverrides()).isEmpty();
        assertThat(ctx.getPluginContext()).isNull();
    }

    @Test
    void shouldStorePluginOverridesFromFourArgConstructor() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides);

        assertThat(ctx.getPluginOverrides()).containsEntry("log_read", "remote-log");
    }

    @Test
    void shouldReturnUnmodifiablePluginOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides);

        assertThatThrownBy(() -> ctx.getPluginOverrides().put("foo", "bar"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHandleNullPluginOverridesAsEmptyMap() {
        ToolContext ctx = new ToolContext("t1", "u1", null, (Map<String, String>) null);

        assertThat(ctx.getPluginOverrides()).isEmpty();
    }

    @Test
    void shouldStorePluginContextFromFiveArgConstructor() {
        PluginContext pluginCtx = Mockito.mock(PluginContext.class);
        Map<String, String> overrides = Collections.singletonMap("log_read", "remote-log");
        ToolContext ctx = new ToolContext("t1", "u1", null, overrides, pluginCtx);

        assertThat(ctx.getPluginContext()).isSameAs(pluginCtx);
        assertThat(ctx.getPluginOverrides()).containsEntry("log_read", "remote-log");
    }

    @Test
    void shouldReturnNewContextFromWithPluginContext() {
        ToolContext original = new ToolContext("t1", "u1", null,
                Collections.singletonMap("log_read", "remote-log"), null);
        PluginContext pluginCtx = Mockito.mock(PluginContext.class);

        ToolContext updated = original.withPluginContext(pluginCtx);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getTaskId()).isEqualTo("t1");
        assertThat(updated.getUserId()).isEqualTo("u1");
        assertThat(updated.getPluginOverrides()).containsEntry("log_read", "remote-log");
        assertThat(updated.getPluginContext()).isSameAs(pluginCtx);
    }

    @Test
    void shouldPreserveAuditCallbackInWithPluginContext() {
        AuditCallback cb = Mockito.mock(AuditCallback.class);
        ToolContext original = new ToolContext("t1", "u1", cb, null, null);

        ToolContext updated = original.withPluginContext(Mockito.mock(PluginContext.class));

        assertThat(updated.getAuditCallback()).isSameAs(cb);
    }
}
