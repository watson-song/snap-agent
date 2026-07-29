package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link MessageChatMemoryAdvisor} — verifies order,
 * beforeNode history injection, afterNode persistence (per-node),
 * exception isolation, and conversation-id fallback logic.
 *
 * <p>The advisor persists in ReAct-loop order:
 * <ul>
 *   <li><b>entry</b> → saves user message</li>
 *   <li><b>agent</b> → saves assistant turn WITH tool_use blocks</li>
 *   <li><b>tools</b> → saves tool_result messages with proper toolUseId</li>
 * </ul>
 * </p>
 */
@DisplayName("MessageChatMemoryAdvisor — history injection + per-node persistence")
class MessageChatMemoryAdvisorTest {

    // ---- Constructor validation ----

    @Test
    @DisplayName("null chatMemory → IllegalArgumentException")
    void shouldThrowOnNullChatMemory() {
        assertThatThrownBy(() -> new MessageChatMemoryAdvisor(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null chatMemory in 3-arg constructor → IllegalArgumentException")
    void shouldThrowOnNullChatMemory3Arg() {
        assertThatThrownBy(() -> new MessageChatMemoryAdvisor(null, 10, "conversation.id"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- SPI metadata ----

    @Test
    @DisplayName("getOrder() = 100")
    void shouldReturnOrder100() {
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(new MessageWindowChatMemory(new InMemoryChatMemoryRepository()));
        assertThat(advisor.getOrder()).isEqualTo(100);
    }

    @Test
    @DisplayName("getName() = \"chat-memory\"")
    void shouldReturnNameChatMemory() {
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(new MessageWindowChatMemory(new InMemoryChatMemoryRepository()));
        assertThat(advisor.getName()).isEqualTo("chat-memory");
    }

    @Test
    @DisplayName("default retrieveLastN = 20")
    void shouldDefaultRetrieveLastNTo20() {
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(
            new MessageWindowChatMemory(new InMemoryChatMemoryRepository()));
        assertThat(advisor.getRetrieveLastN()).isEqualTo(20);
    }

    @Test
    @DisplayName("custom retrieveLastN passed through")
    void shouldAcceptCustomRetrieveLastN() {
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(
            new MessageWindowChatMemory(new InMemoryChatMemoryRepository()), 5, "conversation.id");
        assertThat(advisor.getRetrieveLastN()).isEqualTo(5);
    }

    // ---- beforeNode: history injection ----

    @Test
    @DisplayName("beforeNode: loads history into state[memory.messages]")
    void shouldLoadHistoryIntoStateBeforeNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);
        memory.add("conv-1", Message.user("hello"));
        memory.add("conv-1", Message.assistant("hi there"));

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1").with("conversation.id", "conv-1");

        GraphState result = advisor.beforeNode("agent", state, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) result.get("memory.messages");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("hello");
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(history.get(1).getContent()).isEqualTo("hi there");
    }

    @Test
    @DisplayName("beforeNode: no conversation.id → falls back to threadId")
    void shouldFallBackToThreadIdWhenNoConversationId() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);
        memory.add("thread-xyz", Message.user("stored under threadId"));

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        // No "conversation.id" in state — should use threadId
        GraphState state = GraphState.empty("thread-xyz");

        GraphState result = advisor.beforeNode("agent", state, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) result.get("memory.messages");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getContent()).isEqualTo("stored under threadId");
    }

    @Test
    @DisplayName("beforeNode: no conversation.id and no matching threadId → empty list")
    void shouldReturnEmptyWhenNoConversationIdOrThreadIdMatch() throws InterruptException {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);

        GraphState state = GraphState.empty("thread-no-data");
        GraphState result = advisor.beforeNode("agent", state, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) result.get("memory.messages");
        assertThat(history).isEmpty();
    }

    @Test
    @DisplayName("beforeNode: ChatMemory.get() throws → empty list, no exception propagates")
    void shouldIsolateExceptionsInBeforeNode() throws InterruptException {
        ChatMemory throwingMemory = new ChatMemory() {
            @Override
            public void add(String conversationId, Message message) {}
            @Override
            public List<Message> get(String conversationId, int lastN) {
                throw new RuntimeException("simulated storage failure");
            }
            @Override
            public void clear(String conversationId) {}
        };

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(throwingMemory);
        GraphState state = GraphState.empty("thread-1").with("conversation.id", "conv-1");

        GraphState result = advisor.beforeNode("agent", state, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) result.get("memory.messages");
        assertThat(history).isEmpty();
    }

    // ---- afterNode: entry node → saves user message ----

    @Test
    @DisplayName("afterNode: entry node saves user.message to ChatMemory")
    void shouldSaveUserMessageAfterEntryNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "what is the weather?");

        advisor.afterNode("entry", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo("user");
        assertThat(saved.get(0).getContent()).isEqualTo("what is the weather?");
    }

    @Test
    @DisplayName("afterNode: entry node with no user.message → nothing saved")
    void shouldNotSaveWhenNoUserMessageAfterEntry() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1");

        advisor.afterNode("entry", state, null);

        assertThat(memory.get("conv-1", 10)).isEmpty();
    }

    // ---- afterNode: agent node → saves assistant turn WITH tool_use blocks ----

    @Test
    @DisplayName("afterNode: agent node saves assistant turn WITH tool_use blocks")
    void shouldSaveAssistantWithToolUseBlocksAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        Map<String, Object> input1 = new HashMap<>();
        input1.put("sql", "SELECT 1");
        Map<String, Object> input2 = new HashMap<>();
        input2.put("sql", "SELECT 2");
        List<ToolUseBlock> toolUseBlocks = Arrays.asList(
            new ToolUseBlock("tool-use-1", "mysql_query", input1),
            new ToolUseBlock("tool-use-2", "mysql_query", input2)
        );

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.THOUGHT, "Let me run two queries.")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUseBlocks);

        advisor.afterNode("agent", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo("assistant");
        assertThat(saved.get(0).getContent()).isEqualTo("Let me run two queries.");
        assertThat(saved.get(0).hasToolUses()).isTrue();
        assertThat(saved.get(0).getToolUses()).hasSize(2);
        assertThat(saved.get(0).getToolUses().get(0).getId()).isEqualTo("tool-use-1");
        assertThat(saved.get(0).getToolUses().get(1).getId()).isEqualTo("tool-use-2");
    }

    @Test
    @DisplayName("afterNode: agent node saves text-only assistant turn when no tool_use blocks")
    void shouldSaveTextOnlyAssistantAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.THOUGHT, "The weather is sunny.");

        advisor.afterNode("agent", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo("assistant");
        assertThat(saved.get(0).getContent()).isEqualTo("The weather is sunny.");
        assertThat(saved.get(0).hasToolUses()).isFalse();
    }

    @Test
    @DisplayName("afterNode: agent node with no thought and no tool_use → nothing saved")
    void shouldNotSaveWhenNoThoughtOrToolUseAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("stop_reason", "end_turn");

        advisor.afterNode("agent", state, null);

        assertThat(memory.get("conv-1", 10)).isEmpty();
    }

    @Test
    @DisplayName("afterNode: agent node with tool_use blocks but empty thought → saves assistant with empty text")
    void shouldSaveAssistantWithToolUseBlocksEvenWhenThoughtIsEmpty() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        List<ToolUseBlock> toolUseBlocks = Collections.singletonList(
            new ToolUseBlock("tu-1", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT 1"))
        );

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUseBlocks);

        advisor.afterNode("agent", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo("assistant");
        assertThat(saved.get(0).getContent()).isEmpty();
        assertThat(saved.get(0).hasToolUses()).isTrue();
    }

    // ---- afterNode: tools node → saves tool_result messages with proper toolUseId ----

    @Test
    @DisplayName("afterNode: tools node saves tool_results matched to tool_use ids by index")
    void shouldSaveToolResultsAfterToolsNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        List<ToolUseBlock> toolUseBlocks = Arrays.asList(
            new ToolUseBlock("tool-use-1", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT 1")),
            new ToolUseBlock("tool-use-2", "mysql_query", Collections.<String, Object>singletonMap("sql", "SELECT 2"))
        );
        List<ToolResult> toolResults = new ArrayList<>(Arrays.asList(
            new ToolResult("result-1", 1, false, 10, null),
            new ToolResult("result-2", 1, false, 10, null)
        ));

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUseBlocks)
            .with(StateKeys.TOOL_RESULTS, toolResults);

        advisor.afterNode("tools", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        // 2 tool_result messages
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRole()).isEqualTo("tool");
        assertThat(saved.get(0).getContent()).isEqualTo("result-1");
        assertThat(saved.get(0).getToolUseId()).isEqualTo("tool-use-1");
        assertThat(saved.get(1).getRole()).isEqualTo("tool");
        assertThat(saved.get(1).getContent()).isEqualTo("result-2");
        assertThat(saved.get(1).getToolUseId()).isEqualTo("tool-use-2");
    }

    @Test
    @DisplayName("afterNode: tools node saves error content when ToolResult has error")
    void shouldSaveErrorContentAfterToolsNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        List<ToolUseBlock> toolUseBlocks = Collections.singletonList(
            new ToolUseBlock("tu-err", "mysql_query", Collections.<String, Object>singletonMap("sql", "BAD SQL"))
        );
        List<ToolResult> toolResults = new ArrayList<>(Collections.singletonList(
            ToolResult.error("DB connection failed", 100)
        ));

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUseBlocks)
            .with(StateKeys.TOOL_RESULTS, toolResults);

        advisor.afterNode("tools", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getRole()).isEqualTo("tool");
        assertThat(saved.get(0).getContent()).isEqualTo("Error: DB connection failed");
        assertThat(saved.get(0).getToolUseId()).isEqualTo("tu-err");
    }

    @Test
    @DisplayName("afterNode: tools node with no tool_results → nothing saved")
    void shouldNotSaveWhenNoToolResultsAfterToolsNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1");

        advisor.afterNode("tools", state, null);

        assertThat(memory.get("conv-1", 10)).isEmpty();
    }

    // ---- afterNode: unknown node → no-op ----

    @Test
    @DisplayName("afterNode: unknown node name → no-op (state unchanged, nothing saved)")
    void shouldNoOpForUnknownNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "hello")
            .with("thought", "response");

        GraphState result = advisor.afterNode("unknown_node", state, null);

        // No messages should have been saved
        assertThat(memory.get("conv-1", 10)).isEmpty();
        // State should be unchanged
        assertThat(result).isEqualTo(state);
    }

    // ---- afterNode: conversation ID fallback ----

    @Test
    @DisplayName("afterNode: no conversation.id → saves under threadId fallback")
    void shouldSaveUnderThreadIdWhenNoConversationIdAfterEntryNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        // threadId set but no conversation.id
        GraphState state = GraphState.empty("thread-1")
            .with("user.message", "hello");

        advisor.afterNode("entry", state, null);

        // Advisor falls back to threadId, so it saves under threadId
        List<Message> saved = memory.get("thread-1", 10);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("afterNode: no conversation.id and no threadId → no-op")
    void shouldNoOpWhenNoConversationIdOrThreadId() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        // Both conversation.id and threadId are null
        GraphState state = GraphState.empty(null)
            .with("user.message", "hello");

        GraphState result = advisor.afterNode("entry", state, null);

        // No messages saved
        assertThat(repo.size()).isEqualTo(0);
        // State unchanged
        assertThat(result).isEqualTo(state);
    }

    // ---- afterNode: exception isolation ----

    @Test
    @DisplayName("afterNode: ChatMemory.add() throws → no exception propagates")
    void shouldIsolateExceptionsInAfterNode() throws InterruptException {
        ChatMemory throwingMemory = new ChatMemory() {
            @Override
            public void add(String conversationId, Message message) {
                throw new RuntimeException("simulated write failure");
            }
            @Override
            public List<Message> get(String conversationId, int lastN) {
                return Collections.emptyList();
            }
            @Override
            public void clear(String conversationId) {}
        };

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(throwingMemory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "hello")
            .with("thought", "response");

        // Should not throw
        GraphState result = advisor.afterNode("entry", state, null);
        assertThat(result).isEqualTo(state);

        // Agent node should also not throw
        result = advisor.afterNode("agent", state, null);
        assertThat(result).isEqualTo(state);
    }

    // ---- Custom conversation ID key ----

    @Test
    @DisplayName("custom conversationIdKey: reads from custom state key")
    void shouldReadFromCustomConversationIdKey() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);
        memory.add("custom-conv-id", Message.user("hello custom"));

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory, 10, "my.conversation.key");
        GraphState state = GraphState.empty("thread-1").with("my.conversation.key", "custom-conv-id");

        GraphState result = advisor.beforeNode("agent", state, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) result.get("memory.messages");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getContent()).isEqualTo("hello custom");
    }

    // ---- Advisor interface compliance ----

    @Test
    @DisplayName("MessageChatMemoryAdvisor implements Advisor")
    void shouldImplementAdvisorInterface() {
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(
            new MessageWindowChatMemory(new InMemoryChatMemoryRepository()));
        assertThat(advisor).isInstanceOf(Advisor.class);
    }

    // ---- Round-trip: entry → agent → tools → beforeNode loads full history ----

    @Test
    @DisplayName("round-trip: entry+agent+tools persist, beforeNode loads full history next turn")
    void shouldRoundTripSaveAndLoadAcrossReActTurns() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory, 20, "conversation.id");

        // --- Turn 1: entry → agent → tools ---

        // entry: saves user message
        GraphState entryState = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "what is the database size?");
        advisor.afterNode("entry", entryState, null);

        // agent: saves assistant turn with tool_use blocks
        List<ToolUseBlock> toolUses = Collections.singletonList(
            new ToolUseBlock("tu-1", "mysql_query",
                Collections.<String, Object>singletonMap("sql", "SELECT COUNT(*) FROM information_schema.TABLES"))
        );
        GraphState agentState = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.THOUGHT, "Let me check the database size.")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUses);
        advisor.afterNode("agent", agentState, null);

        // tools: saves tool_result with matching toolUseId
        List<ToolResult> toolResults = new ArrayList<>(Collections.singletonList(
            new ToolResult("42 tables", 1, false, 50, null)
        ));
        GraphState toolsState = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with(StateKeys.TOOL_USE_BLOCKS, toolUses)
            .with(StateKeys.TOOL_RESULTS, toolResults);
        advisor.afterNode("tools", toolsState, null);

        // --- Turn 2: beforeNode should load the full ReAct history ---
        GraphState turn2 = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "what is the database size?");

        GraphState beforeTurn2 = advisor.beforeNode("agent", turn2, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) beforeTurn2.get("memory.messages");
        // [user, assistant(thought, toolUses), tool_result]
        assertThat(history).hasSize(3);

        // 1. User message
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(0).getContent()).isEqualTo("what is the database size?");

        // 2. Assistant turn WITH tool_use blocks
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(history.get(1).getContent()).isEqualTo("Let me check the database size.");
        assertThat(history.get(1).hasToolUses()).isTrue();
        assertThat(history.get(1).getToolUses().get(0).getId()).isEqualTo("tu-1");

        // 3. Tool result referencing the tool_use id
        assertThat(history.get(2).getRole()).isEqualTo("tool");
        assertThat(history.get(2).getContent()).isEqualTo("42 tables");
        assertThat(history.get(2).getToolUseId()).isEqualTo("tu-1");
    }
}
