package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import cn.watsontech.snapagent.core.memory.InMemoryChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConversationStoreTest {

    private JdbcTemplate jdbc;
    private ChatMemoryRepository chatMemoryRepo;
    private JdbcConversationStore store;

    @BeforeEach
    void setUp() {
        EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(db);
        chatMemoryRepo = new InMemoryChatMemoryRepository();
        store = new JdbcConversationStore(jdbc, chatMemoryRepo);
    }

    @Test
    void shouldSaveAndLoadConversation() {
        List<ConversationMessage> msgs = new ArrayList<ConversationMessage>();
        msgs.add(new ConversationMessage("user", "hello", System.currentTimeMillis(), null));
        Conversation conv = new Conversation(null, "user1", "skill1", "Test",
                0, 0, msgs);

        Conversation saved = store.save(conv);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo("user1");

        Conversation loaded = store.load(saved.getId(), "user1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getTitle()).isEqualTo("Test");
    }

    @Test
    void shouldReturnNullForWrongUser() {
        Conversation conv = new Conversation(null, "user1", "skill1", "Test",
                0, 0, Collections.<ConversationMessage>emptyList());
        Conversation saved = store.save(conv);

        Conversation loaded = store.load(saved.getId(), "wrong-user");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldListConversations() {
        store.save(new Conversation(null, "user1", "skill1", "Conv1",
                0, 0, Collections.<ConversationMessage>emptyList()));
        store.save(new Conversation(null, "user1", "skill2", "Conv2",
                0, 0, Collections.<ConversationMessage>emptyList()));
        store.save(new Conversation(null, "user2", "skill1", "Conv3",
                0, 0, Collections.<ConversationMessage>emptyList()));

        List<ConversationSummary> user1All = store.list("user1", null);
        assertThat(user1All).hasSize(2);

        List<ConversationSummary> user1Skill1 = store.list("user1", "skill1");
        assertThat(user1Skill1).hasSize(1);
        assertThat(user1Skill1.get(0).getSkillId()).isEqualTo("skill1");
    }

    @Test
    void shouldDeleteConversation() {
        Conversation conv = new Conversation(null, "user1", "skill1", "Test",
                0, 0, Collections.<ConversationMessage>emptyList());
        Conversation saved = store.save(conv);

        boolean deleted = store.delete(saved.getId(), "user1");
        assertThat(deleted).isTrue();

        Conversation loaded = store.load(saved.getId(), "user1");
        assertThat(loaded).isNull();
    }

    @Test
    void shouldExportMarkdown() {
        List<ConversationMessage> msgs = new ArrayList<ConversationMessage>();
        msgs.add(new ConversationMessage("user", "hello world", System.currentTimeMillis(), null));
        Conversation conv = new Conversation(null, "user1", "skill1", "Test Export",
                0, 0, msgs);
        Conversation saved = store.save(conv);

        String md = store.exportMarkdown(saved.getId(), "user1");
        assertThat(md).contains("Test Export");
        assertThat(md).contains("hello world");
    }
}
