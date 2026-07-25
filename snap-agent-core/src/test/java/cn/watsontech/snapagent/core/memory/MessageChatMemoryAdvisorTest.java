package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import cn.watsontech.snapagent.core.graph.hitl.InterruptException;
import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link MessageChatMemoryAdvisor} — verifies order,
 * beforeNode history injection, afterNode persistence, exception isolation,
 * and conversation-id fallback logic.
 */
@DisplayName("MessageChatMemoryAdvisor — history injection + persistence")
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

    // ---- afterNode: persistence ----

    @Test
    @DisplayName("afterNode: non-agent node → no-op (state unchanged)")
    void shouldNoOpForNonAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "hello")
            .with("thought", "response");

        GraphState result = advisor.afterNode("entry", state, null);

        // No messages should have been saved
        assertThat(memory.get("conv-1", 10)).isEmpty();
        // State should be unchanged
        assertThat(result).isEqualTo(state);
    }

    @Test
    @DisplayName("afterNode: agent node saves user.message + thought to ChatMemory")
    void shouldSaveUserMessageAndThoughtAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "what is the weather?")
            .with("thought", "The weather is sunny.")
            .with("stop_reason", "end_turn");

        advisor.afterNode("agent", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getRole()).isEqualTo("user");
        assertThat(saved.get(0).getContent()).isEqualTo("what is the weather?");
        assertThat(saved.get(1).getRole()).isEqualTo("assistant");
        assertThat(saved.get(1).getContent()).isEqualTo("The weather is sunny.");
    }

    @Test
    @DisplayName("afterNode: no conversation.id → saves under threadId fallback")
    void shouldSaveUnderThreadIdWhenNoConversationIdAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        // threadId set but no conversation.id
        GraphState state = GraphState.empty("thread-1")
            .with("user.message", "hello")
            .with("thought", "hi");

        advisor.afterNode("agent", state, null);

        // Advisor falls back to threadId, so it saves under threadId
        List<Message> saved = memory.get("thread-1", 10);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getContent()).isEqualTo("hello");
        assertThat(saved.get(1).getContent()).isEqualTo("hi");
    }

    @Test
    @DisplayName("afterNode: no user.message and no thought → nothing saved")
    void shouldNotSaveWhenNoUserMessageOrThought() throws InterruptException {
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
    @DisplayName("afterNode: saves tool_results if present")
    void shouldSaveToolResultsAfterAgentNode() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);

        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory);
        List<Object> toolResults = new ArrayList<>(Arrays.<Object>asList("result-1", "result-2"));
        GraphState state = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "run query")
            .with("thought", "executing query")
            .with("tool_results", toolResults);

        advisor.afterNode("agent", state, null);

        List<Message> saved = memory.get("conv-1", 10);
        // user + thought + 2 tool results = 4 messages
        assertThat(saved).hasSize(4);
        assertThat(saved.get(2).getRole()).isEqualTo("tool");
        assertThat(saved.get(2).getContent()).isEqualTo("result-1");
        assertThat(saved.get(3).getContent()).isEqualTo("result-2");
    }

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
        GraphState result = advisor.afterNode("agent", state, null);
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

    // ---- Round-trip: before + after ----

    @Test
    @DisplayName("round-trip: afterNode saves, beforeNode loads in next turn")
    void shouldRoundTripSaveAndLoadAcrossTurns() throws InterruptException {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        ChatMemory memory = new MessageWindowChatMemory(repo);
        MessageChatMemoryAdvisor advisor = new MessageChatMemoryAdvisor(memory, 10, "conversation.id");

        // Turn 1: user asks, agent responds
        GraphState turn1 = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "what is 2+2?")
            .with("thought", "2+2 = 4");

        advisor.afterNode("agent", turn1, null);

        // Turn 2: beforeNode should load the history from turn 1
        GraphState turn2 = GraphState.empty("thread-1")
            .with("conversation.id", "conv-1")
            .with("user.message", "and 3+3?");

        GraphState beforeTurn2 = advisor.beforeNode("agent", turn2, null);

        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) beforeTurn2.get("memory.messages");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getContent()).isEqualTo("what is 2+2?");
        assertThat(history.get(1).getContent()).isEqualTo("2+2 = 4");
    }
}
