package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.core.conversation.Conversation;
import cn.watsontech.snapagent.core.conversation.ConversationMessage;
import cn.watsontech.snapagent.core.conversation.ConversationStore;
import cn.watsontech.snapagent.core.conversation.ConversationSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileConversationStore}.
 */
class FileConversationStoreTest {

    @TempDir
    Path tempDir;

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        store = new FileConversationStore(tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        store = null;
    }

    @Test
    void shouldSaveAndLoadConversation() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.user("查询用户表"),
                ConversationMessage.assistant("用户表结构如下...")
        );
        Conversation conv = new Conversation(null, "user1", "database-query",
                "查询用户表", 0, 0, messages);

        Conversation saved = store.save(conv);

        assertThat(saved.getId()).isNotNull().isNotEmpty();
        assertThat(saved.getTitle()).isEqualTo("查询用户表");
        assertThat(saved.getMessageCount()).isEqualTo(2);
        assertThat(saved.getCreatedAt()).isGreaterThan(0);
        assertThat(saved.getUpdatedAt()).isGreaterThan(0);

        Conversation loaded = store.load(saved.getId(), "user1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getUserId()).isEqualTo("user1");
        assertThat(loaded.getSkillId()).isEqualTo("database-query");
        assertThat(loaded.getMessages()).hasSize(2);
        assertThat(loaded.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(loaded.getMessages().get(0).getContent()).isEqualTo("查询用户表");
        assertThat(loaded.getMessages().get(1).getRole()).isEqualTo("assistant");
    }

    @Test
    void shouldGenerateIdOnFirstSave() {
        Conversation conv = new Conversation(null, "user1", "skill1",
                null, 0, 0, Arrays.asList(ConversationMessage.user("hello")));

        Conversation saved = store.save(conv);

        assertThat(saved.getId()).startsWith("conv_");
    }

    @Test
    void shouldUpdateExistingConversation() {
        List<ConversationMessage> msgs1 = new ArrayList<ConversationMessage>();
        msgs1.add(ConversationMessage.user("first question"));
        Conversation conv1 = new Conversation(null, "user1", "skill1",
                null, 0, 0, msgs1);
        Conversation saved1 = store.save(conv1);

        // Wait to ensure updatedAt is different
        List<ConversationMessage> msgs2 = new ArrayList<ConversationMessage>(msgs1);
        msgs2.add(ConversationMessage.assistant("first answer"));
        msgs2.add(ConversationMessage.user("second question"));
        Conversation conv2 = new Conversation(saved1.getId(), "user1", "skill1",
                saved1.getTitle(), saved1.getCreatedAt(), 0, msgs2);
        Conversation saved2 = store.save(conv2);

        assertThat(saved2.getId()).isEqualTo(saved1.getId());
        assertThat(saved2.getMessageCount()).isEqualTo(3);
        assertThat(saved2.getCreatedAt()).isEqualTo(saved1.getCreatedAt());
    }

    @Test
    void shouldDeriveTitleFromFirstUserMessage() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.assistant("some response"),
                ConversationMessage.user("这是一个很长的查询条件用来测试标题截断功能是否正常工作")
        );
        Conversation conv = new Conversation(null, "user1", "skill1",
                null, 0, 0, messages);

        Conversation saved = store.save(conv);

        assertThat(saved.getTitle()).contains("这是一个很长的查询条件");
        assertThat(saved.getTitle().length()).isLessThanOrEqualTo(33); // 30 + "..."
    }

    @Test
    void shouldDeriveDefaultTitleWhenNoUserMessage() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.assistant("response only")
        );
        Conversation conv = new Conversation(null, "user1", "skill1",
                null, 0, 0, messages);

        Conversation saved = store.save(conv);

        assertThat(saved.getTitle()).isEqualTo("新对话");
    }

    @Test
    void shouldReturnNullForNonexistentConversation() {
        Conversation loaded = store.load("nonexistent_id", "user1");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldReturnNullForWrongUser() {
        List<ConversationMessage> messages = Arrays.asList(ConversationMessage.user("test"));
        Conversation conv = new Conversation(null, "user1", "skill1",
                null, 0, 0, messages);
        Conversation saved = store.save(conv);

        Conversation loaded = store.load(saved.getId(), "user2");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldListConversationsByUser() {
        // Save two conversations for user1
        store.save(new Conversation(null, "user1", "skill1", "conv1",
                0, 0, Arrays.asList(ConversationMessage.user("msg1"))));
        store.save(new Conversation(null, "user1", "skill2", "conv2",
                0, 0, Arrays.asList(ConversationMessage.user("msg2"))));

        List<ConversationSummary> list = store.list("user1", null);

        assertThat(list).hasSize(2);
    }

    @Test
    void shouldFilterBySkillId() {
        store.save(new Conversation(null, "user1", "skill1", "conv1",
                0, 0, Arrays.asList(ConversationMessage.user("msg1"))));
        store.save(new Conversation(null, "user1", "skill2", "conv2",
                0, 0, Arrays.asList(ConversationMessage.user("msg2"))));

        List<ConversationSummary> list = store.list("user1", "skill1");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getSkillId()).isEqualTo("skill1");
    }

    @Test
    void shouldReturnEmptyListForUnknownUser() {
        List<ConversationSummary> list = store.list("unknown_user", null);
        assertThat(list).isEmpty();
    }

    @Test
    void shouldSortByUpdatedAtDescending() throws InterruptedException {
        Conversation first = store.save(new Conversation(null, "user1", "skill1", "first",
                0, 0, Arrays.asList(ConversationMessage.user("first"))));
        Thread.sleep(10);
        Conversation second = store.save(new Conversation(null, "user1", "skill1", "second",
                0, 0, Arrays.asList(ConversationMessage.user("second"))));

        List<ConversationSummary> list = store.list("user1", null);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).getId()).isEqualTo(second.getId());
        assertThat(list.get(1).getId()).isEqualTo(first.getId());
    }

    @Test
    void shouldDeleteConversation() {
        Conversation saved = store.save(new Conversation(null, "user1", "skill1", "test",
                0, 0, Arrays.asList(ConversationMessage.user("hello"))));

        boolean deleted = store.delete(saved.getId(), "user1");
        assertThat(deleted).isTrue();

        Conversation loaded = store.load(saved.getId(), "user1");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldReturnFalseWhenDeletingNonexistent() {
        boolean deleted = store.delete("nonexistent", "user1");
        assertThat(deleted).isFalse();
    }

    @Test
    void shouldReturnFalseWhenDeletingWrongUser() {
        Conversation saved = store.save(new Conversation(null, "user1", "skill1", "test",
                0, 0, Arrays.asList(ConversationMessage.user("hello"))));

        boolean deleted = store.delete(saved.getId(), "user2");
        assertThat(deleted).isFalse();
    }

    @Test
    void shouldExportMarkdown() {
        List<ConversationMessage> messages = Arrays.asList(
                ConversationMessage.user("查询数据库"),
                ConversationMessage.assistant("查询结果如下:\n```sql\nSELECT * FROM users;\n```")
        );
        Conversation saved = store.save(new Conversation(null, "user1", "database-query",
                "测试对话", 0, 0, messages));

        String markdown = store.exportMarkdown(saved.getId(), "user1");

        assertThat(markdown).isNotNull();
        assertThat(markdown).contains("# 测试对话");
        assertThat(markdown).contains("**Skill**: database-query");
        assertThat(markdown).contains("**用户**: user1");
        assertThat(markdown).contains("👤 用户");
        assertThat(markdown).contains("查询数据库");
        assertThat(markdown).contains("🤖 助手");
        assertThat(markdown).contains("查询结果如下");
    }

    @Test
    void shouldReturnNullMarkdownForNonexistent() {
        String markdown = store.exportMarkdown("nonexistent", "user1");
        assertThat(markdown).isNull();
    }

    @Test
    void shouldSanitizeUserIdForFilesystem() {
        // User ID with special characters that could cause path traversal
        Conversation saved = store.save(new Conversation(null, "user/../evil", "skill1", "test",
                0, 0, Arrays.asList(ConversationMessage.user("test"))));

        Conversation loaded = store.load(saved.getId(), "user/../evil");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo("user/../evil");
    }

    @Test
    void shouldHandleEmptyMessageList() {
        Conversation saved = store.save(new Conversation(null, "user1", "skill1", "empty",
                0, 0, new ArrayList<ConversationMessage>()));

        Conversation loaded = store.load(saved.getId(), "user1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMessageCount()).isEqualTo(0);
    }
}
