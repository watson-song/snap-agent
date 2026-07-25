package cn.watsontech.snapagent.core.memory;

import cn.watsontech.snapagent.core.llm.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the ChatMemory SPI: ChatMemory, ChatMemoryRepository,
 * MessageWindowChatMemory (sliding window), InMemoryChatMemoryRepository.
 */
@DisplayName("ChatMemory — sliding window + repository")
class MessageWindowChatMemoryTest {

    // ---- InMemoryChatMemoryRepository ----

    @Test
    @DisplayName("InMemoryRepository: save + load round-trip")
    void shouldSaveAndLoadMessages() {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        List<Message> messages = Arrays.asList(
            Message.system("sys"),
            Message.user("hello"),
            Message.assistant("hi there")
        );
        repo.save("c1", messages);
        List<Message> loaded = repo.load("c1");
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getRole()).isEqualTo("system");
        assertThat(loaded.get(2).getContent()).isEqualTo("hi there");
    }

    @Test
    @DisplayName("InMemoryRepository: delete removes conversation")
    void shouldDeleteConversation() {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        repo.save("c1", Arrays.asList(Message.user("hello")));
        assertThat(repo.load("c1")).hasSize(1);
        repo.delete("c1");
        assertThat(repo.load("c1")).isEmpty();
    }

    @Test
    @DisplayName("InMemoryRepository: load unknown conversation → empty list")
    void shouldReturnEmptyForUnknownConversation() {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        assertThat(repo.load("unknown")).isEmpty();
    }

    @Test
    @DisplayName("InMemoryRepository: null conversationId → no-op")
    void shouldHandleNullConversationId() {
        InMemoryChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        repo.save(null, Arrays.asList(Message.user("x")));
        assertThat(repo.load(null)).isEmpty();
        repo.delete(null); // no exception
    }

    // ---- MessageWindowChatMemory: basic add + get ----

    @Test
    @DisplayName("add + get: single message round-trip")
    void shouldAddAndGetSingleMessage() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", Message.user("hello"));
        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("add multiple messages → all retrievable within window")
    void shouldAddMultipleMessages() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", Message.system("sys"));
        memory.add("c1", Message.user("msg1"));
        memory.add("c1", Message.assistant("resp1"));
        memory.add("c1", Message.user("msg2"));
        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(4);
    }

    // ---- Sliding window eviction ----

    @Test
    @DisplayName("window=3: 4th message evicts oldest non-system message")
    void shouldEvictOldestNonSystemMessage() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository(), 3);
        memory.add("c1", Message.system("sys"));
        memory.add("c1", Message.user("msg1"));
        memory.add("c1", Message.assistant("resp1"));
        // Window is now full (3 messages)
        memory.add("c1", Message.user("msg2"));
        // "msg1" should be evicted, keeping sys + resp1 + msg2
        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getRole()).isEqualTo("system");
        assertThat(result.get(1).getContent()).isEqualTo("resp1");
        assertThat(result.get(2).getContent()).isEqualTo("msg2");
    }

    @Test
    @DisplayName("window=5: system messages always retained even when window exceeded")
    void shouldAlwaysRetainSystemMessages() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository(), 5);
        memory.add("c1", Message.system("sys1"));
        memory.add("c1", Message.user("u1"));
        memory.add("c1", Message.assistant("a1"));
        memory.add("c1", Message.user("u2"));
        memory.add("c1", Message.assistant("a2"));
        // Window is full (5 messages)
        memory.add("c1", Message.user("u3"));
        // Should keep sys1 + a1 + u2 + a2 + u3 (evict u1)
        List<Message> result = memory.get("c1", 10);
        assertThat(result).hasSize(5);
        assertThat(result.get(0).getRole()).isEqualTo("system");
        assertThat(result.get(1).getContent()).isEqualTo("a1");
        assertThat(result.get(4).getContent()).isEqualTo("u3");
    }

    @Test
    @DisplayName("get with lastN=2 returns last 2 non-system messages + system")
    void shouldReturnLastNWithSystemRetained() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", Message.system("sys"));
        memory.add("c1", Message.user("u1"));
        memory.add("c1", Message.assistant("a1"));
        memory.add("c1", Message.user("u2"));
        memory.add("c1", Message.assistant("a2"));

        List<Message> result = memory.get("c1", 2);
        // lastN=2, but system always retained → sys + last 1 non-system = a2
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRole()).isEqualTo("system");
        assertThat(result.get(1).getContent()).isEqualTo("a2");
    }

    @Test
    @DisplayName("clear removes all messages")
    void shouldClearConversation() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", Message.user("hello"));
        memory.add("c1", Message.assistant("hi"));
        memory.clear("c1");
        assertThat(memory.get("c1", 10)).isEmpty();
    }

    // ---- Constructor validation ----

    @Test
    @DisplayName("null repository → IllegalArgumentException")
    void shouldThrowOnNullRepository() {
        assertThatThrownBy(() -> new MessageWindowChatMemory(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxMessages < 1 → IllegalArgumentException")
    void shouldThrowOnInvalidMaxMessages() {
        assertThatThrownBy(() -> new MessageWindowChatMemory(new InMemoryChatMemoryRepository(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("default maxMessages = 20")
    void shouldDefaultMaxMessagesTo20() {
        MessageWindowChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        assertThat(memory.getMaxMessages()).isEqualTo(20);
    }

    // ---- Multi-conversation ----

    @Test
    @DisplayName("multiple conversations are isolated")
    void shouldIsolateConversations() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", Message.user("hello from c1"));
        memory.add("c2", Message.user("hello from c2"));

        assertThat(memory.get("c1", 10)).hasSize(1);
        assertThat(memory.get("c1", 10).get(0).getContent()).isEqualTo("hello from c1");
        assertThat(memory.get("c2", 10)).hasSize(1);
        assertThat(memory.get("c2", 10).get(0).getContent()).isEqualTo("hello from c2");
    }

    // ---- Null handling ----

    @Test
    @DisplayName("add(null, message) → no-op, no exception")
    void shouldHandleNullConversationIdOnAdd() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add(null, Message.user("hello"));
        assertThat(memory.get(null, 10)).isEmpty();
    }

    @Test
    @DisplayName("add(conversationId, null) → no-op, no exception")
    void shouldHandleNullMessage() {
        ChatMemory memory = new MessageWindowChatMemory(new InMemoryChatMemoryRepository());
        memory.add("c1", null);
        assertThat(memory.get("c1", 10)).isEmpty();
    }
}
