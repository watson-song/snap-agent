package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisor that injects conversation history from {@link ChatMemory} into the
 * graph state before each node, and persists new messages after each node.
 *
 * <p>Order: 100 (runs after SafeGuard(50), before RAG(200)).</p>
 *
 * <p><b>beforeNode</b>: loads the last N messages from ChatMemory and writes
 * them to {@code state["memory.messages"]}. The AgentNode reads these and uses
 * them directly as the LLM request messages list (the full history including
 * the current user message, previous assistant turns with tool_use blocks,
 * and tool_result messages).</p>
 *
 * <p><b>afterNode</b>: persists messages in ReAct-loop order:
 * <ul>
 *   <li><b>entry</b> — saves the user message (Layer 2 — User Input)</li>
 *   <li><b>agent</b> — saves the assistant turn as {@code Message.assistant(thought, toolUseBlocks)},
 *       including tool_use blocks so subsequent tool_result messages have a
 *       matching tool_use id (required by provider APIs)</li>
 *   <li><b>tools</b> — saves each tool_result as {@code Message.toolResult(toolUseId, content)},
 *       matching the tool_use id from {@code state["tool_use_blocks"]} by index</li>
 * </ul>
 * This produces the correct conversation ordering:
 * {@code [user, assistant(thought_1, toolUses_1), tool_result_1, assistant(thought_2, toolUses_2), ...]}.
 * </p>
 */
public class MessageChatMemoryAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(MessageChatMemoryAdvisor.class);

    private final ChatMemory chatMemory;
    private final int retrieveLastN;
    private final String conversationIdKey;

    /**
     * Construct with default retrieve size of 20.
     *
     * @param chatMemory the chat memory to use
     */
    public MessageChatMemoryAdvisor(ChatMemory chatMemory) {
        this(chatMemory, MessageWindowChatMemory.DEFAULT_MAX_MESSAGES, "conversation.id");
    }

    /**
     * Construct with custom retrieve size and conversation ID state key.
     *
     * @param chatMemory       the chat memory to use
     * @param retrieveLastN    how many messages to retrieve from history
     * @param conversationIdKey state key for the conversation ID
     */
    public MessageChatMemoryAdvisor(ChatMemory chatMemory, int retrieveLastN, String conversationIdKey) {
        if (chatMemory == null) {
            throw new IllegalArgumentException("chatMemory cannot be null");
        }
        this.chatMemory = chatMemory;
        this.retrieveLastN = retrieveLastN;
        this.conversationIdKey = conversationIdKey;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public String getName() {
        return "chat-memory";
    }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        String conversationId = resolveConversationId(state);
        if (conversationId == null) {
            // No conversation ID in state — skip memory loading
            return state.with(StateKeys.MEMORY_MESSAGES, new ArrayList<Message>());
        }

        try {
            List<Message> history = chatMemory.get(conversationId, retrieveLastN);
            return state.with(StateKeys.MEMORY_MESSAGES, history);
        } catch (RuntimeException e) {
            log.warn("Failed to load chat memory for conversation {}: {}", conversationId, e.getMessage());
            return state.with(StateKeys.MEMORY_MESSAGES, new ArrayList<Message>());
        }
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) throws InterruptException {
        String conversationId = resolveConversationId(state);
        if (conversationId == null) {
            return state;
        }

        try {
            switch (nodeName) {
                case "entry":
                    persistUserMessage(state, conversationId);
                    break;
                case "agent":
                    persistAssistantTurn(state, conversationId);
                    break;
                case "tools":
                    persistToolResults(state, conversationId);
                    break;
                default:
                    // No persistence for other nodes
                    break;
            }
        } catch (RuntimeException e) {
            log.warn("Failed to persist chat memory for conversation {}: {}",
                    conversationId, e.getMessage());
        }

        return state;
    }

    /**
     * Save the user message to ChatMemory (Layer 2 — User Input).
     * Called after the entry node, which assembles the user message from
     * task inputs. The user message is saved ONCE here; subsequent ReAct
     * turns read it back from memory without re-saving.
     */
    private void persistUserMessage(GraphState state, String conversationId) {
        String userMessage = state.get(StateKeys.USER_MESSAGE);
        if (userMessage != null && !userMessage.isEmpty()) {
            chatMemory.add(conversationId, Message.user(userMessage));
        }
    }

    /**
     * Save the assistant turn to ChatMemory, including tool_use blocks.
     *
     * <p>Provider APIs (Anthropic, OpenAI) require that a subsequent
     * {@code tool_result} message references a {@code tool_use} id from
     * the preceding assistant message. Saving {@code Message.assistant(thought)}
     * without the tool_use blocks would orphan the tool_result messages
     * saved by {@link #persistToolResults}, causing API rejections.</p>
     */
    private void persistAssistantTurn(GraphState state, String conversationId) {
        String thought = state.get(StateKeys.THOUGHT);
        List<ToolUseBlock> toolUseBlocks = state.get(StateKeys.TOOL_USE_BLOCKS);
        boolean hasThought = thought != null && !thought.isEmpty();
        boolean hasToolUses = toolUseBlocks != null && !toolUseBlocks.isEmpty();
        if (hasThought || hasToolUses) {
            String text = hasThought ? thought : "";
            chatMemory.add(conversationId, Message.assistant(text, toolUseBlocks));
        }
    }

    /**
     * Save tool_result messages to ChatMemory, matching each result to the
     * corresponding tool_use id by index.
     *
     * <p>Called after the tools node. Reads {@code state["tool_use_blocks"]}
     * (written by AgentNode) and {@code state["tool_results"]} (written by
     * ToolsNode) and zips them by index to produce properly-referenced
     * {@code Message.toolResult(toolUseId, content)} messages.</p>
     */
    private void persistToolResults(GraphState state, String conversationId) {
        List<ToolUseBlock> toolUseBlocks = state.get(StateKeys.TOOL_USE_BLOCKS);
        List<ToolResult> toolResults = state.get(StateKeys.TOOL_RESULTS);
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (int i = 0; i < toolResults.size(); i++) {
            ToolResult result = toolResults.get(i);
            if (result == null) {
                continue;
            }
            // Match by index with tool_use blocks to get the tool_use id
            String toolUseId = result.getToolUseId();
            if (toolUseId == null && toolUseBlocks != null && i < toolUseBlocks.size()) {
                toolUseId = toolUseBlocks.get(i).getId();
            }
            String content = result.getContent() != null ? result.getContent()
                    : (result.getError() != null ? "Error: " + result.getError()
                    : "No output");
            chatMemory.add(conversationId, Message.toolResult(toolUseId, content));
        }
    }

    /**
     * Resolve the conversation ID from the state.
     * Falls back to the task ID if conversation ID is not set.
     */
    private String resolveConversationId(GraphState state) {
        String conversationId = state.get(conversationIdKey);
        if (conversationId != null && !conversationId.isEmpty()) {
            return conversationId;
        }
        // Fall back to thread ID if available
        String threadId = state.getThreadId();
        return threadId;
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    public int getRetrieveLastN() {
        return retrieveLastN;
    }
}
