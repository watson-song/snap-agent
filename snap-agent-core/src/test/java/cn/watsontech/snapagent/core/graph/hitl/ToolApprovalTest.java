package cn.watsontech.snapagent.core.graph.hitl;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.react.ToolsNode;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("@ToolApproval HITL")
class ToolApprovalTest {

    @Test
    @DisplayName("@ToolApproval(required=true) 触发 InterruptException")
    void approvalRequiredThrowsInterrupt() {
        ToolUseBlock toolUse = new ToolUseBlock("toolu_01", "ddl_tool",
            Collections.<String, Object>singletonMap("sql", "DROP TABLE users"));
        GraphState state = GraphState.empty("t1").with("tool_use_blocks", Collections.singletonList(toolUse));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getName()).thenReturn("ddl_tool");
        when(callback.isApprovalRequired()).thenReturn(true);
        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.find("ddl_tool")).thenReturn(callback);

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTools()).thenReturn(registry);

        ToolsNode toolsNode = new ToolsNode(4000);
        assertThatThrownBy(() -> toolsNode.execute(state, ctx))
            .isInstanceOf(InterruptException.class)
            .satisfies(e -> {
                InterruptException ie = (InterruptException) e;
                assertThat(ie.getCheckpointPayload().get("toolName")).isEqualTo("ddl_tool");
            });
    }

    @Test
    @DisplayName("非 @ToolApproval 工具不触发暂停")
    void noApprovalNoInterrupt() throws InterruptException {
        ToolUseBlock toolUse = new ToolUseBlock("toolu_01", "mysql_query",
            Collections.<String, Object>singletonMap("sql", "SELECT 1"));
        GraphState state = GraphState.empty("t1").with("tool_use_blocks", Collections.singletonList(toolUse));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.execute(any(), any())).thenReturn(ToolResult.success("1", 1, 10));
        when(callback.isApprovalRequired()).thenReturn(false);
        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.find("mysql_query")).thenReturn(callback);

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTools()).thenReturn(registry);

        ToolsNode toolsNode = new ToolsNode(4000);
        GraphState result = toolsNode.execute(state, ctx);
        @SuppressWarnings("unchecked")
        List<ToolResult> results = result.get("tool_results");
        assertThat(results).isNotNull();
    }
}
