package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ToolsNode 工具执行")
class ToolsNodeTest {

    @Test
    @DisplayName("工具执行成功回传")
    void executeSuccess() throws InterruptException {
        ToolUseBlock toolUse = new ToolUseBlock("toolu_01", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT 1"));
        GraphState state = GraphState.empty("t1").with("tool_use_blocks", Collections.singletonList(toolUse));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.execute(any(), any())).thenReturn(ToolResult.success("1", 1, 10));
        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.find("mysql_query")).thenReturn(callback);

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTools()).thenReturn(registry);

        ToolsNode toolsNode = new ToolsNode(4000);
        GraphState result = toolsNode.execute(state, ctx);

        @SuppressWarnings("unchecked")
        List<ToolResult> results = result.get("tool_results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("1");
        verify(ctx, atLeast(2)).emit(any());
    }

    @Test
    @DisplayName("结果截断保护 token 预算")
    void truncateLargeResult() throws InterruptException {
        String largeContent = String.join("", Collections.nCopies(500, "x"));
        ToolUseBlock toolUse = new ToolUseBlock("toolu_01", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT *"));
        GraphState state = GraphState.empty("t1").with("tool_use_blocks", Collections.singletonList(toolUse));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.execute(any(), any())).thenReturn(ToolResult.success(largeContent, 1, 10));
        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.find("mysql_query")).thenReturn(callback);

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTools()).thenReturn(registry);

        ToolsNode toolsNode = new ToolsNode(100);
        GraphState result = toolsNode.execute(state, ctx);

        @SuppressWarnings("unchecked")
        List<ToolResult> results = result.get("tool_results");
        assertThat(results.get(0).getContent().length()).isLessThanOrEqualTo(100);
        assertThat(results.get(0).getContent()).contains("[truncated]");
    }

    @Test
    @DisplayName("工具异常不中断 — error 回传 LLM")
    void toolExceptionNoCrash() throws InterruptException {
        ToolUseBlock toolUse = new ToolUseBlock("toolu_01", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT 1"));
        GraphState state = GraphState.empty("t1").with("tool_use_blocks", Collections.singletonList(toolUse));

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.execute(any(), any())).thenThrow(new RuntimeException("DB connection failed"));
        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.find("mysql_query")).thenReturn(callback);

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTools()).thenReturn(registry);

        ToolsNode toolsNode = new ToolsNode(4000);
        GraphState result = toolsNode.execute(state, ctx);

        @SuppressWarnings("unchecked")
        List<ToolResult> results = result.get("tool_results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();
        assertThat(results.get(0).getError()).contains("DB connection failed");
    }
}
