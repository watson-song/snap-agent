package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FileChatMemoryRepository}.
 */
class FileChatMemoryRepositoryTest {

    @TempDir
    Path tempDir;

    private FileChatMemoryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new FileChatMemoryRepository(tempDir.toString());
    }

    @Test
    void saveAndLoad_simpleMessages() {
        List<Message> messages = Arrays.asList(
                Message.user("hello"),
                Message.assistant("hi there"),
                Message.system("you are helpful"));

        repo.save("conv-1", messages);
        List<Message> loaded = repo.load("conv-1");

        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getRole()).isEqualTo("user");
        assertThat(loaded.get(0).getContent()).isEqualTo("hello");
        assertThat(loaded.get(1).getRole()).isEqualTo("assistant");
        assertThat(loaded.get(1).getContent()).isEqualTo("hi there");
        assertThat(loaded.get(2).getRole()).isEqualTo("system");
    }

    @Test
    void saveAndLoad_toolUseMessages() {
        Map<String, Object> input = new HashMap<String, Object>();
        input.put("sql", "SELECT 1");
        ToolUseBlock toolUse = new ToolUseBlock("tu-1", "mysql_query", input);
        Message assistantMsg = Message.assistant("let me query", Collections.singletonList(toolUse));
        Message toolResult = Message.toolResult("tu-1", "result: 1");

        repo.save("conv-2", Arrays.asList(assistantMsg, toolResult));
        List<Message> loaded = repo.load("conv-2");

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).getRole()).isEqualTo("assistant");
        assertThat(loaded.get(0).hasToolUses()).isTrue();
        assertThat(loaded.get(0).getToolUses()).hasSize(1);
        assertThat(loaded.get(0).getToolUses().get(0).getName()).isEqualTo("mysql_query");
        assertThat(loaded.get(1).getRole()).isEqualTo("tool");
        assertThat(loaded.get(1).getToolUseId()).isEqualTo("tu-1");
    }

    @Test
    void load_nonExistent_returnsEmpty() {
        List<Message> loaded = repo.load("non-existent");
        assertThat(loaded).isEmpty();
    }

    @Test
    void load_nullId_returnsEmpty() {
        List<Message> loaded = repo.load(null);
        assertThat(loaded).isEmpty();
    }

    @Test
    void save_overwritesExisting() {
        repo.save("conv-3", Collections.singletonList(Message.user("first")));
        repo.save("conv-3", Collections.singletonList(Message.user("second")));

        List<Message> loaded = repo.load("conv-3");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getContent()).isEqualTo("second");
    }

    @Test
    void delete_removesFile() {
        repo.save("conv-4", Collections.singletonList(Message.user("test")));
        assertThat(repo.load("conv-4")).isNotEmpty();

        repo.delete("conv-4");
        assertThat(repo.load("conv-4")).isEmpty();
    }

    @Test
    void delete_nonExistent_noError() {
        repo.delete("non-existent"); // should not throw
    }

    @Test
    void listConversations_returnsAllIds() {
        repo.save("alpha", Collections.singletonList(Message.user("a")));
        repo.save("beta", Collections.singletonList(Message.user("b")));
        repo.save("gamma", Collections.singletonList(Message.user("c")));

        List<String> ids = repo.listConversations(null);
        assertThat(ids).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void listConversations_emptyDir_returnsEmpty() {
        FileChatMemoryRepository emptyRepo = new FileChatMemoryRepository(
                tempDir.resolve("empty").toString());
        assertThat(emptyRepo.listConversations(null)).isEmpty();
    }

    @Test
    void saveAndLoad_nullContent_handlesGracefully() {
        Message msg = new Message("user", null, null);
        repo.save("conv-null", Collections.singletonList(msg));
        List<Message> loaded = repo.load("conv-null");
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getRole()).isEqualTo("user");
        assertThat(loaded.get(0).getContent()).isNull();
    }

    @Test
    void constructor_nullPath_throws() {
        try {
            new FileChatMemoryRepository(null);
            assertThat(false).as("Should have thrown").isTrue();
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    void constructor_emptyPath_throws() {
        try {
            new FileChatMemoryRepository("");
            assertThat(false).as("Should have thrown").isTrue();
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
