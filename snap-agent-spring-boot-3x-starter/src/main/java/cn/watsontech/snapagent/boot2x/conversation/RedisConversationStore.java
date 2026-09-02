package cn.watsontech.snapagent.boot2x.conversation;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Redis-backed {@link ConversationStore}.
 *
 * <p>Requires {@code spring-data-redis} on the classpath and a configured
 * {@code StringRedisTemplate} bean. Uses Redis hashes for metadata and
 * delegates message storage to {@link ChatMemoryRepository}.</p>
 *
 * <p>Key layout:</p>
 * <ul>
 *   <li>{@code snap-agent:conv:{userId}:{conversationId}} — hash of metadata fields</li>
 *   <li>{@code snap-agent:conv-index:{userId}} — sorted set of conversation IDs, scored by updatedAt</li>
 * </ul>
 */
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    private static final String CONV_PREFIX = "snap-agent:conv:";
    private static final String INDEX_PREFIX = "snap-agent:conv-index:";

    private final StringRedisTemplate redis;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectMapper mapper;

    public RedisConversationStore(StringRedisTemplate redis,
                                  ChatMemoryRepository chatMemoryRepository) {
        this(redis, chatMemoryRepository, new ObjectMapper());
    }

    public RedisConversationStore(StringRedisTemplate redis,
                                  ChatMemoryRepository chatMemoryRepository,
                                  ObjectMapper mapper) {
        this.redis = redis;
        this.chatMemoryRepository = chatMemoryRepository;
        this.mapper = mapper;
    }

    @Override
    public Conversation save(Conversation conversation) {
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

        String key = convKey(conversation.getUserId(), convId);
        try {
            redis.opsForHash().put(key, "id", convId);
            redis.opsForHash().put(key, "userId", conversation.getUserId());
            redis.opsForHash().put(key, "skillId", nvl(conversation.getSkillId()));
            redis.opsForHash().put(key, "title", title);
            redis.opsForHash().put(key, "createdAt", String.valueOf(createdAt));
            redis.opsForHash().put(key, "updatedAt", String.valueOf(now));
            redis.opsForHash().put(key, "messageCount", String.valueOf(msgCount));

            // Update sorted set index
            redis.opsForZSet().add(indexKey(conversation.getUserId()), convId, now);
        } catch (Exception e) {
            log.error("Failed to save conversation {} to Redis: {}", convId, e.getMessage());
            return conversation;
        }

        return new Conversation(convId, conversation.getUserId(), conversation.getSkillId(),
                title, createdAt, now, conversation.getMessages());
    }

    @Override
    public Conversation load(String conversationId, String userId) {
        String key = convKey(userId, conversationId);
        try {
            Object idVal = redis.opsForHash().get(key, "id");
            if (idVal == null) return null;

            String uid = str(redis.opsForHash().get(key, "userId"));
            if (!userId.equals(uid)) {
                log.warn("Ownership mismatch: conversation {} belongs to {}, requested by {}",
                        conversationId, uid, userId);
                return null;
            }

            long createdAt = longVal(redis.opsForHash().get(key, "createdAt"));
            long updatedAt = longVal(redis.opsForHash().get(key, "updatedAt"));
            String sid = str(redis.opsForHash().get(key, "skillId"));
            String title = str(redis.opsForHash().get(key, "title"));

            List<ConversationMessage> messages = loadMessages(conversationId);
            return new Conversation(conversationId, userId, sid, title, createdAt, updatedAt, messages);
        } catch (Exception e) {
            log.warn("Failed to load conversation {} from Redis: {}", conversationId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<ConversationSummary> list(String userId, String skillId) {
        try {
            Set<String> ids = redis.opsForZSet().reverseRange(indexKey(userId), 0, -1);
            if (ids == null || ids.isEmpty()) return Collections.emptyList();

            List<ConversationSummary> result = new ArrayList<ConversationSummary>();
            for (String id : ids) {
                String key = convKey(userId, id);
                Object idVal = redis.opsForHash().get(key, "id");
                if (idVal == null) continue;

                String sid = str(redis.opsForHash().get(key, "skillId"));
                if (skillId != null && !skillId.isEmpty() && !skillId.equals(sid)) continue;

                result.add(new ConversationSummary(
                        id, userId, sid,
                        str(redis.opsForHash().get(key, "title")),
                        longVal(redis.opsForHash().get(key, "createdAt")),
                        longVal(redis.opsForHash().get(key, "updatedAt")),
                        intVal(redis.opsForHash().get(key, "messageCount"))
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list conversations for user {}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean delete(String conversationId, String userId) {
        String key = convKey(userId, conversationId);
        try {
            Boolean existed = redis.hasKey(key);
            if (existed != null && existed) {
                redis.delete(key);
                redis.opsForZSet().remove(indexKey(userId), conversationId);
                if (chatMemoryRepository != null) {
                    try {
                        chatMemoryRepository.delete(conversationId);
                    } catch (Exception e) {
                        log.warn("Failed to delete messages from ChatMemoryRepository: {}", e.getMessage());
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to delete conversation {}: {}", conversationId, e.getMessage());
            return false;
        }
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

    private String convKey(String userId, String convId) {
        return CONV_PREFIX + userId + ":" + convId;
    }

    private String indexKey(String userId) {
        return INDEX_PREFIX + userId;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static long longVal(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int intVal(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
