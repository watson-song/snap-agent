package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.skill.SkillAvailability;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("AgentNode LLM 流式调用")
class AgentNodeTest {

    private SkillMeta testSkill() {
        return new SkillMeta("test-skill", null, Collections.<String>emptyList(),
                null, "test body", SkillAvailability.AVAILABLE, null);
    }

    private AgentTask testTask() {
        return new AgentTask("t1", "u1", "test-skill", new HashMap<String, String>(), "test-model");
    }

    private ExecutionContext mockCtx(LlmClient llmClient) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getLlmClient()).thenReturn(llmClient);
        when(ctx.getTaskId()).thenReturn("t-1");
        when(ctx.isCancelled()).thenReturn(false);
        return ctx;
    }

    @Test
    @DisplayName("流式 thought 实时推送 + stop_reason 写入 state")
    void streamingThoughtAndStopReason() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        doAnswer(inv -> {
            LlmEventSink sink = inv.getArgument(1);
            sink.onThought("分析中");
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        ExecutionContext ctx = mockCtx(llmClient);
        AgentNode node = new AgentNode(testSkill(), testTask());

        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("user.message", "分析问题");

        GraphState result = node.execute(state, ctx);

        assertThat((String) result.get("stop_reason")).isEqualTo("end_turn");
        assertThat((String) result.get("thought")).isEqualTo("分析中");
    }

    @Test
    @DisplayName("RAG 上下文注入 — state[rag.context] 出现在 LLM user message")
    void ragContextInjected() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        LlmRequest[] capturedReq = new LlmRequest[1];
        doAnswer(inv -> {
            capturedReq[0] = inv.getArgument(0);
            LlmEventSink sink = inv.getArgument(1);
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        ExecutionContext ctx = mockCtx(llmClient);
        AgentNode node = new AgentNode(testSkill(), testTask());

        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("rag.context", "知识片段: 连接池 max=20")
                .with("user.message", "分析问题");

        node.execute(state, ctx);

        // Verify RAG context was injected into user message
        String userMsg = capturedReq[0].getMessages().get(0).getContent();
        assertThat(userMsg).contains("<knowledge>");
        assertThat(userMsg).contains("连接池 max=20");
        assertThat(userMsg).contains("分析问题");
    }

    @Test
    @DisplayName("max_tokens 截断标记")
    void maxTokensTruncated() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        doAnswer(inv -> {
            LlmEventSink sink = inv.getArgument(1);
            sink.onThought("部分回答...");
            sink.onStop("max_tokens");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        ExecutionContext ctx = mockCtx(llmClient);
        AgentNode node = new AgentNode(testSkill(), testTask());

        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("user.message", "分析问题");

        GraphState result = node.execute(state, ctx);

        assertThat((String) result.get("stop_reason")).isEqualTo("max_tokens");
        assertThat((Boolean) result.get("truncated")).isTrue();
    }

    @Test
    @DisplayName("tool_use blocks 累积到 state")
    void toolUseBlocksAccumulated() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        doAnswer(inv -> {
            LlmEventSink sink = inv.getArgument(1);
            sink.onThought("需要查询数据库");
            Map<String, Object> input = new HashMap<>();
            input.put("sql", "SELECT 1");
            sink.onToolUse("tu-1", "mysql_query", input);
            sink.onStop("tool_use");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        ExecutionContext ctx = mockCtx(llmClient);
        AgentNode node = new AgentNode(testSkill(), testTask());

        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("user.message", "分析问题");

        GraphState result = node.execute(state, ctx);

        assertThat((String) result.get("stop_reason")).isEqualTo("tool_use");
        @SuppressWarnings("unchecked")
        List<ToolUseBlock> blocks = (List<ToolUseBlock>) result.get("tool_use_blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getName()).isEqualTo("mysql_query");
        assertThat(blocks.get(0).getId()).isEqualTo("tu-1");
    }

    @Test
    @DisplayName("无 RAG 上下文时 user message 不包含 <knowledge> 标签")
    void noRagContextNoKnowledgeTag() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        LlmRequest[] capturedReq = new LlmRequest[1];
        doAnswer(inv -> {
            capturedReq[0] = inv.getArgument(0);
            LlmEventSink sink = inv.getArgument(1);
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        ExecutionContext ctx = mockCtx(llmClient);
        AgentNode node = new AgentNode(testSkill(), testTask());

        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("user.message", "分析问题");

        node.execute(state, ctx);

        String userMsg = capturedReq[0].getMessages().get(0).getContent();
        assertThat(userMsg).doesNotContain("<knowledge>");
        assertThat(userMsg).isEqualTo("分析问题");
    }

    @Test
    @DisplayName("tool defs 从 registry.getAll() 构建")
    void toolDefsFromRegistry() throws InterruptException {
        LlmClient llmClient = mock(LlmClient.class);
        LlmRequest[] capturedReq = new LlmRequest[1];
        doAnswer(inv -> {
            capturedReq[0] = inv.getArgument(0);
            LlmEventSink sink = inv.getArgument(1);
            sink.onStop("end_turn");
            return null;
        }).when(llmClient).stream(any(), any(), any());

        // Create a mocked ToolCallback with description + jsonSchema
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getName()).thenReturn("mysql_query");
        when(callback.getDescription()).thenReturn("执行SQL查询");
        when(callback.getJsonSchema()).thenReturn("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}}}");

        ToolCallbackRegistry registry = mock(ToolCallbackRegistry.class);
        when(registry.getAll()).thenReturn(Collections.singletonList(callback));

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getLlmClient()).thenReturn(llmClient);
        when(ctx.getTools()).thenReturn(registry);
        when(ctx.getTaskId()).thenReturn("t-1");
        when(ctx.isCancelled()).thenReturn(false);

        AgentNode node = new AgentNode(testSkill(), testTask());
        GraphState state = GraphState.empty("t1")
                .with("system.prompt", "你是诊断 agent")
                .with("user.message", "分析问题");

        node.execute(state, ctx);

        // Verify tool defs were passed to LlmRequest
        assertThat(capturedReq[0].getTools()).hasSize(1);
        assertThat(capturedReq[0].getTools().get(0).getName()).isEqualTo("mysql_query");
        assertThat(capturedReq[0].getTools().get(0).getDescription()).isEqualTo("执行SQL查询");
    }
}
