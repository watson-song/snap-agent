package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
