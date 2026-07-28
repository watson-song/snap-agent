package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisor that injects conversation history from {@link ChatMemory} into the
 * graph state before each node, and persists new messages after the agent node.
 *
 * <p>Order: 100 (runs after SafeGuard(50), before RAG(200)).</p>
 *
 * <p>beforeNode: loads the last N messages from ChatMemory and writes them
 * to state["memory.messages"]. The AgentNode can read these and prepend
 * them to the LLM request messages list.</p>
 *
 * <p>afterNode: when the agent node completes, saves the user message and
 * the assistant response (thought + stop_reason) to ChatMemory.</p>
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
        // Only persist after the agent node (where LLM responses are generated)
        if (!"agent".equals(nodeName)) {
            return state;
        }

        String conversationId = resolveConversationId(state);
        if (conversationId == null) {
            return state;
        }

        try {
            // Save the user message if present
            String userMessage = state.get(StateKeys.USER_MESSAGE);
            if (userMessage != null && !userMessage.isEmpty()) {
                chatMemory.add(conversationId, Message.user(userMessage));
            }

            // Save the assistant response (thought)
            String thought = state.get(StateKeys.THOUGHT);
            String stopReason = state.get(StateKeys.STOP_REASON);
            if (thought != null && !thought.isEmpty()) {
                chatMemory.add(conversationId, Message.assistant(thought));
            }

            // Save tool results if present
            List<cn.watsontech.snapagent.core.tool.ToolResult> toolResults = state.get(StateKeys.TOOL_RESULTS);
            if (toolResults != null && !toolResults.isEmpty()) {
                for (cn.watsontech.snapagent.core.tool.ToolResult result : toolResults) {
                    if (result != null) {
                        chatMemory.add(conversationId, Message.toolResult(null, result.getContent()));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to persist chat memory for conversation {}: {}", conversationId, e.getMessage());
        }

        return state;
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
