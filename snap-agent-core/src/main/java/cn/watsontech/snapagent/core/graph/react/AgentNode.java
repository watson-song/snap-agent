package cn.watsontech.snapagent.core.graph.react;

import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TranscriptEvent;
import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.Node;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.execution.ExecutionContext;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolDef;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.memory.LastNMessagePartitioner;
import cn.watsontech.snapagent.core.memory.MessagePartitioner;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolCallbackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AgentNode: LLM streaming call with tool definitions + RAG context.
 * Parses structured output. Sets stop_reason in state.
 *
 * <p>Prompt assembly follows the 7-layer Context Stack pattern:</p>
 * <ul>
 *   <li><b>Instructions</b> — from {@code system.prompt} (EntryNode + advisors)</li>
 *   <li><b>Retrieved Facts</b> — RAG context injected into system prompt as a
 *       separate {@code <retrieved_facts>} block (not mixed into user message)</li>
 *   <li><b>Tools</b> — filtered by skill's declared tool list (UC-20)</li>
 *   <li><b>Short-term Notes</b> — conversation history from
 *       {@code memory.messages} prepended to the messages list</li>
 *   <li><b>User Input</b> — the user message from {@code user.message}</li>
 * </ul>
 */
public class AgentNode implements Node {
    private static final Logger log = LoggerFactory.getLogger(AgentNode.class);
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private static final String RAG_OPEN = "\n\n<retrieved_facts>\n";
    private static final String RAG_CLOSE = "\n</retrieved_facts>\n";

    private final SkillMeta skill;
    private final AgentTask task;
    private final MessagePartitioner messagePartitioner;

    public AgentNode(SkillMeta skill, AgentTask task) {
        this(skill, task, new LastNMessagePartitioner());
    }

    public AgentNode(SkillMeta skill, AgentTask task, MessagePartitioner messagePartitioner) {
        this.skill = skill;
        this.task = task;
        this.messagePartitioner = messagePartitioner != null ? messagePartitioner : new LastNMessagePartitioner();
    }

    @Override
    public String getName() { return "agent"; }

    @Override
    @SuppressWarnings("unchecked")
    public GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException {
        LlmClient llmClient = ctx.getLlmClient();
        ToolCallbackRegistry toolRegistry = ctx.getTools();

        // --- Layer 1 + 3: System prompt + Retrieved Facts ---
        // RAG context goes into the system prompt as a separate block,
        // NOT mixed into the user message. This keeps Retrieved Facts
        // (layer 3) independent from User Input (layer 2).
        String systemPrompt = state.get(StateKeys.SYSTEM_PROMPT);
        String ragContext = state.get(StateKeys.RAG_CONTEXT);
        if (ragContext != null && !ragContext.isEmpty()) {
            systemPrompt = systemPrompt + RAG_OPEN + ragContext + RAG_CLOSE;
        }

        // --- Layer 2: User Input ---
        String userMessage = state.get(StateKeys.USER_MESSAGE);

        // --- Build messages list ---
        // Layer 5: Short-term Notes — partition conversation history + user message
        List<Message> history = state.get(StateKeys.MEMORY_MESSAGES);
        List<Message> messages = messagePartitioner.partition(history, userMessage);

        // --- Layer 4: Tools (filtered by skill declaration) ---
        List<ToolDef> toolDefs = new ArrayList<>();
        if (toolRegistry != null) {
            List<String> skillTools = skill.getTools();
            for (ToolCallback callback : toolRegistry.getAll()) {
                if (skillTools == null || skillTools.isEmpty()
                        || skillTools.contains(callback.getName())) {
                    toolDefs.add(new ToolDef(
                        callback.getName(),
                        callback.getDescription(),
                        callback.getJsonSchema()
                    ));
                }
            }
        }

        // --- Assemble immutable LlmRequest ---
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
            .with(StateKeys.STOP_REASON, stopReason[0])
            .with(StateKeys.THOUGHT, thoughtBuilder.toString());

        if (!toolUseBlocks.isEmpty()) {
            result = result.with(StateKeys.TOOL_USE_BLOCKS, toolUseBlocks);
        }

        if ("max_tokens".equals(stopReason[0])) {
            result = result.with(StateKeys.TRUNCATED, true);
        }

        return result;
    }

    public SkillMeta getSkill() { return skill; }
    public AgentTask getTask() { return task; }
}
