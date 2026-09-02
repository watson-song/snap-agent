package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * JDBC-backed {@link ConversationStore}.
 *
 * <p>Requires {@code spring-jdbc} on the classpath and a configured
 * {@code JdbcTemplate} bean. Creates the {@code snap_agent_conversations}
 * table automatically if it does not exist.</p>
 *
 * <p>Messages are delegated to {@link ChatMemoryRepository} (single source
 * of truth), matching the same split used by {@link FileConversationStore}.</p>
 */
public class JdbcConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcConversationStore.class);

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS snap_agent_conversations ("
                    + "id VARCHAR(128) NOT NULL,"
                    + "user_id VARCHAR(255) NOT NULL,"
                    + "skill_id VARCHAR(128),"
                    + "title VARCHAR(512),"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "message_count INT DEFAULT 0,"
                    + "PRIMARY KEY (user_id, id)"
                    + ")";

    private static final String UPSERT_MERGE =
            "MERGE INTO snap_agent_conversations (id, user_id, skill_id, title, created_at, updated_at, message_count)"
                    + " KEY (user_id, id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    // Fallback for databases that don't support MERGE (MySQL, PostgreSQL)
    private static final String UPSERT_MYSQL =
            "INSERT INTO snap_agent_conversations (id, user_id, skill_id, title, created_at, updated_at, message_count)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE title=VALUES(title), updated_at=VALUES(updated_at), message_count=VALUES(message_count)";

    private static final String SELECT_BY_ID =
            "SELECT id, user_id, skill_id, title, created_at, updated_at, message_count"
                    + " FROM snap_agent_conversations WHERE id = ? AND user_id = ?";

    private static final String SELECT_LIST =
            "SELECT id, user_id, skill_id, title, created_at, updated_at, message_count"
                    + " FROM snap_agent_conversations WHERE user_id = ?"
                    + " ORDER BY updated_at DESC";

    private static final String SELECT_LIST_BY_SKILL =
            "SELECT id, user_id, skill_id, title, created_at, updated_at, message_count"
                    + " FROM snap_agent_conversations WHERE user_id = ? AND skill_id = ?"
                    + " ORDER BY updated_at DESC";

    private static final String DELETE =
            "DELETE FROM snap_agent_conversations WHERE id = ? AND user_id = ?";

    private final JdbcTemplate jdbc;
    private final ChatMemoryRepository chatMemoryRepository;
    private volatile boolean tableReady;

    public JdbcConversationStore(JdbcTemplate jdbc, ChatMemoryRepository chatMemoryRepository) {
        this.jdbc = jdbc;
        this.chatMemoryRepository = chatMemoryRepository;
        ensureTable();
    }

    private void ensureTable() {
        if (tableReady) return;
        try {
            jdbc.execute(CREATE_TABLE);
            tableReady = true;
            log.info("Created/verified snap_agent_conversations table");
        } catch (Exception e) {
            log.warn("Failed to create snap_agent_conversations table: {}", e.getMessage());
        }
    }

    @Override
    public Conversation save(Conversation conversation) {
        ensureTable();
        String convId = conversation.getId();
        long now = System.currentTimeMillis();
        boolean isNew = convId == null || convId.isEmpty();
        if (isNew) {
            convId = "conv_" + now + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        long createdAt = isNew ? now : conversation.getCreatedAt();

        // Save messages to ChatMemoryRepository
        if (chatMemoryRepository != null && conversation.getMessages() != null) {
            try {
                List<Message> messages = new ArrayList<Message>();
                for (ConversationMessage convMsg : conversation.getMessages()) {
                    messages.add(new Message(convMsg.getRole(), convMsg.getContent(), null, null));
                }
                chatMemoryRepository.save(convId, messages);
            } catch (Exception e) {
                log.error("Failed to save messages to ChatMemoryRepository: {}", e.getMessage());
            }
        }

        String title = conversation.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = "新对话";
        }

        int msgCount = conversation.getMessages() != null ? conversation.getMessages().size() : 0;
        try {
            jdbc.update(UPSERT_MERGE, convId, conversation.getUserId(), conversation.getSkillId(),
                    title, createdAt, now, msgCount);
        } catch (Exception mergeEx) {
            // Fallback for MySQL/PostgreSQL
            try {
                jdbc.update(UPSERT_MYSQL, convId, conversation.getUserId(), conversation.getSkillId(),
                        title, createdAt, now, msgCount);
            } catch (Exception e) {
                log.error("Failed to save conversation {}: {}", convId, e.getMessage());
                return conversation;
            }
        }

        return new Conversation(convId, conversation.getUserId(), conversation.getSkillId(),
                title, createdAt, now, conversation.getMessages());
    }

    @Override
    public Conversation load(String conversationId, String userId) {
        ensureTable();
        try {
            return jdbc.queryForObject(SELECT_BY_ID, (rs, rowNum) -> {
                String id = rs.getString("id");
                String uid = rs.getString("user_id");
                String sid = rs.getString("skill_id");
                String title = rs.getString("title");
                long createdAt = rs.getLong("created_at");
                long updatedAt = rs.getLong("updated_at");

                // Load messages from ChatMemoryRepository
                List<ConversationMessage> messages = loadMessages(id);
                return new Conversation(id, uid, sid, title, createdAt, updatedAt, messages);
            }, conversationId, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<ConversationSummary> list(String userId, String skillId) {
        ensureTable();
        String sql = (skillId != null && !skillId.isEmpty()) ? SELECT_LIST_BY_SKILL : SELECT_LIST;
        Object[] args = (skillId != null && !skillId.isEmpty())
                ? new Object[]{userId, skillId}
                : new Object[]{userId};
        return jdbc.query(sql, args, (rs, rowNum) -> new ConversationSummary(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("skill_id"),
                rs.getString("title"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("message_count")
        ));
    }

    @Override
    public boolean delete(String conversationId, String userId) {
        ensureTable();
        int rows = jdbc.update(DELETE, conversationId, userId);
        if (rows > 0 && chatMemoryRepository != null) {
            try {
                chatMemoryRepository.delete(conversationId);
            } catch (Exception e) {
                log.warn("Failed to delete messages from ChatMemoryRepository: {}", e.getMessage());
            }
        }
        return rows > 0;
    }

    @Override
    public String exportMarkdown(String conversationId, String userId) {
        Conversation conv = load(conversationId, userId);
        if (conv == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(conv.getTitle() != null ? conv.getTitle() : "对话记录").append("\n\n");
        sb.append("- **Skill**: ").append(conv.getSkillId()).append("\n");
        sb.append("- **用户**: ").append(conv.getUserId()).append("\n\n---\n\n");
        for (ConversationMessage msg : conv.getMessages()) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append("## ").append(role).append("\n\n");
            sb.append(msg.getContent() != null ? msg.getContent() : "").append("\n\n---\n\n");
        }
        return sb.toString();
    }

    private List<ConversationMessage> loadMessages(String conversationId) {
        if (chatMemoryRepository == null) return Collections.emptyList();
        try {
            List<Message> coreMessages = chatMemoryRepository.load(conversationId);
            if (coreMessages == null || coreMessages.isEmpty()) return Collections.emptyList();
            List<ConversationMessage> result = new ArrayList<ConversationMessage>();
            for (Message msg : coreMessages) {
                result.add(new ConversationMessage(msg.getRole(), msg.getContent(),
                        System.currentTimeMillis(), null));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load messages for conversation {}: {}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
