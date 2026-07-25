package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AgentNode: LLM streaming call with tool definitions + RAG context.
 * Parses structured output. Sets stop_reason in state.
 *
 * <p>Phase 1: tool defs are empty (ToolCallback stub has no description/schema).
 * Phase 2 will add full tool definition building from ToolCallbackRegistry.</p>
 */
public class AgentNode implements Node {
    private static final Logger log = LoggerFactory.getLogger(AgentNode.class);
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final SkillMeta skill;
    private final AgentTask task;

    public AgentNode(SkillMeta skill, AgentTask task) {
        this.skill = skill;
        this.task = task;
    }

    @Override
    public String getName() { return "agent"; }

    @Override
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        LlmClient llmClient = ctx.getLlmClient();
        ToolCallbackRegistry toolRegistry = ctx.getTools();

        // Build user message with optional RAG context
        String systemPrompt = state.get("system.prompt");
        String userMessage = state.get("user.message");
        String ragContext = state.get("rag.context");
        if (ragContext != null && !ragContext.isEmpty()) {
            userMessage = "<knowledge>\n" + ragContext + "\n</knowledge>\n\n" + userMessage;
        }

        // Build messages list
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));

        // Build tool defs (Phase 1: empty, full tool defs in Phase 2)
        List<ToolDef> toolDefs = Collections.emptyList();

        // Build LlmRequest (immutable constructor)
        LlmRequest request = new LlmRequest(
            systemPrompt,
            messages,
            toolDefs,
            task.getModel(),
            DEFAULT_MAX_TOKENS,
            true  // streaming
        );

        // Stream LLM and accumulate results
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        StringBuilder thoughtBuilder = new StringBuilder();
        String[] stopReason = {"end_turn"};

        llmClient.stream(request, new LlmEventSink() {
            @Override
            public void onThought(String text) {
                ctx.emit(TranscriptEvent.thought(text));
                thoughtBuilder.append(text);
            }

            @Override
            public void onToolUse(String id, String name, Map<String, Object> input) {
                ctx.emit(TranscriptEvent.toolCall(id, name, input));
                toolUseBlocks.add(new ToolUseBlock(id, name, input));
            }

            @Override
            public void onToolResult(String toolUseId, String result) {
                // Not expected during agent turn — tool results come from ToolsNode
            }

            @Override
            public void onStop(String reason) {
                ctx.emit(TranscriptEvent.done(reason, null));
                stopReason[0] = reason;
            }

            @Override
            public void onError(String message) {
                ctx.emit(TranscriptEvent.error(message));
            }
        }, ctx.getTaskId());

        // Build new state
        GraphState result = state
            .with("stop_reason", stopReason[0])
            .with("thought", thoughtBuilder.toString());

        if (!toolUseBlocks.isEmpty()) {
            result = result.with("tool_use_blocks", toolUseBlocks);
        }

        if ("max_tokens".equals(stopReason[0])) {
            result = result.with("truncated", true);
        }

        return result;
    }

    public SkillMeta getSkill() { return skill; }
    public AgentTask getTask() { return task; }
}
