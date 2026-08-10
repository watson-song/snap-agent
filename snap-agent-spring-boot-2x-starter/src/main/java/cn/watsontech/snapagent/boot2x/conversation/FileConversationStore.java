package cn.watsontech.snapagent.boot2x.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.watsontech.snapagent.boot2x.conversation.Conversation;
import cn.watsontech.snapagent.boot2x.conversation.ConversationMessage;
import cn.watsontech.snapagent.boot2x.conversation.ConversationStore;
import cn.watsontech.snapagent.boot2x.conversation.ConversationSummary;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link ConversationStore} that persists conversation metadata as JSON files
 * and delegates message storage to {@link ChatMemoryRepository}.
 *
 * <p>Architecture: Single source of truth for messages</p>
 * <ul>
 *   <li>Metadata (id, userId, skillId, title, timestamps) → JSON file</li>
 *   <li>Messages → ChatMemoryRepository (single source of truth)</li>
 * </ul>
 *
 * <p>File layout: {@code {baseDir}/conversations/{userId}/{conversationId}.json}</p>
 * <p>JSON only contains metadata, messages are loaded from ChatMemoryRepository on demand.</p>
 */
public class FileConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(FileConversationStore.class);
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final ChatMemoryRepository chatMemoryRepository;

    public FileConversationStore(String baseDirPath, ChatMemoryRepository chatMemoryRepository) {
        this(baseDirPath, new ObjectMapper(), chatMemoryRepository);
    }

    public FileConversationStore(String baseDirPath, ObjectMapper mapper, ChatMemoryRepository chatMemoryRepository) {
        String path = baseDirPath;
        if (path != null && path.startsWith("file:")) {
            path = path.substring(5);
        }
        this.baseDir = path != null ? Paths.get(path) : null;
        this.mapper = mapper;
        this.chatMemoryRepository = chatMemoryRepository;

        if (this.baseDir != null) {
            try {
                if (!Files.isDirectory(this.baseDir)) {
                    Files.createDirectories(this.baseDir);
                    log.info("Created conversations directory: {}", this.baseDir);
                }
            } catch (IOException e) {
                log.warn("Failed to create conversations directory {}: {}",
                        this.baseDir, e.getMessage());
            }
        }

        if (this.chatMemoryRepository == null) {
            log.warn("ChatMemoryRepository is null, messages will not be persisted");
        }
    }

    @Override
    public Conversation save(Conversation conversation) {
        if (baseDir == null) {
            log.warn("Conversations base directory not configured; cannot save");
            return conversation;
        }

        String convId = conversation.getId();
        long now = System.currentTimeMillis();
        boolean isNew = convId == null || convId.isEmpty();

        if (isNew) {
            convId = "conv_" + now + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        Path userDir = getUserDir(conversation.getUserId());
        if (userDir == null) {
            return conversation;
        }

        long createdAt = isNew ? now : conversation.getCreatedAt();
        // Preserve original createdAt for existing conversations
        if (!isNew) {
            Conversation existing = loadMetadataOnly(convId, conversation.getUserId(), userDir);
            if (existing != null) {
                createdAt = existing.getCreatedAt();
            }
        }

        String title = conversation.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = deriveTitle(conversation.getMessages());
        }

        // Save messages to ChatMemoryRepository (single source of truth)
        if (chatMemoryRepository != null && conversation.getMessages() != null) {
            try {
                List<Message> messages = new ArrayList<Message>();
                for (ConversationMessage convMsg : conversation.getMessages()) {
                    // Convert ConversationMessage to Message
                    messages.add(new Message(
                            convMsg.getRole(),
                            convMsg.getContent(),
                            null,  // toolUseId not used in ConversationMessage
                            null   // toolUses not used in ConversationMessage
                    ));
                }
                chatMemoryRepository.save(convId, messages);
                log.info("Saved {} messages to ChatMemoryRepository for conversation {}",
                        messages.size(), convId);
            } catch (Exception e) {
                log.error("Failed to save messages to ChatMemoryRepository for conversation {}: {}",
                        convId, e.getMessage());
            }
        }

        // Save metadata only (without messages) to JSON file
        Conversation metadataOnly = new Conversation(
                convId, conversation.getUserId(), conversation.getSkillId(),
                title, createdAt, now, Collections.<ConversationMessage>emptyList());

        Path file = userDir.resolve(convId + ".json");
        try {
            Map<String, Object> data = toMetadataMap(metadataOnly);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.createDirectories(userDir);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved conversation metadata {} to {}", convId, file);
        } catch (IOException e) {
            log.error("Failed to save conversation metadata {}: {}", convId, e.getMessage());
        }

        return new Conversation(convId, conversation.getUserId(), conversation.getSkillId(),
                title, createdAt, now, conversation.getMessages());
    }

    @Override
    public Conversation load(String conversationId, String userId) {
        Path userDir = getUserDir(userId);
        if (userDir == null) {
            return null;
        }

        // Load metadata from JSON
        Conversation metadata = loadMetadataOnly(conversationId, userId, userDir);
        if (metadata == null) {
            return null;
        }

        // Load messages from ChatMemoryRepository (on-demand)
        List<ConversationMessage> messages = Collections.emptyList();
        if (chatMemoryRepository != null) {
            try {
                List<Message> coreMessages = chatMemoryRepository.load(conversationId);
                log.info("Loaded {} messages from ChatMemoryRepository for conversation {}",
                        coreMessages != null ? coreMessages.size() : 0, conversationId);
                if (coreMessages != null && !coreMessages.isEmpty()) {
                    messages = new ArrayList<ConversationMessage>();
                    for (Message msg : coreMessages) {
                        // Convert Message to ConversationMessage
                        messages.add(new ConversationMessage(
                                msg.getRole(),
                                msg.getContent(),
                                System.currentTimeMillis(),  // Use current timestamp
                                null  // taskId not available in Message
                        ));
                    }
                    log.info("Converted {} messages for conversation {}",
                            messages.size(), conversationId);
                }
            } catch (Exception e) {
                log.warn("Failed to load messages from ChatMemoryRepository for conversation {}: {}",
                        conversationId, e.getMessage());
            }
        }

        // Merge metadata and messages
        return new Conversation(
                metadata.getId(),
                metadata.getUserId(),
                metadata.getSkillId(),
                metadata.getTitle(),
                metadata.getCreatedAt(),
                metadata.getUpdatedAt(),
                messages
        );
    }

    @Override
    public List<ConversationSummary> list(String userId, String skillId) {
        Path userDir = getUserDir(userId);
        if (userDir == null || !Files.isDirectory(userDir)) {
            return Collections.<ConversationSummary>emptyList();
        }

        List<ConversationSummary> summaries = new ArrayList<ConversationSummary>();
        try {
            Files.list(userDir).filter(f -> f.toString().endsWith(".json")).forEach(file -> {
                try {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    Map<String, Object> data = mapper.readValue(json,
                            new TypeReference<Map<String, Object>>() {});
                    String sid = str(data.get("skillId"));
                    if (skillId != null && !skillId.isEmpty() && !skillId.equals(sid)) {
                        return;
                    }
                    summaries.add(new ConversationSummary(
                            str(data.get("id")),
                            str(data.get("userId")),
                            sid,
                            str(data.get("title")),
                            longVal(data.get("createdAt")),
                            longVal(data.get("updatedAt")),
                            intVal(data.get("messageCount"), countMessages(data))));
                } catch (Exception e) {
                    log.warn("Failed to read conversation file {}: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list conversations for user {}: {}", userId, e.getMessage());
        }

        // Sort by updatedAt descending (newest first)
        summaries.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        return summaries;
    }

    @Override
    public boolean delete(String conversationId, String userId) {
        Path userDir = getUserDir(userId);
        if (userDir == null) {
            return false;
        }
        Path file = userDir.resolve(conversationId + ".json");
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Deleted conversation {} for user {}", conversationId, userId);
            }
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete conversation {}: {}", conversationId, e.getMessage());
            return false;
        }
    }

    @Override
    public String exportMarkdown(String conversationId, String userId) {
        Conversation conv = load(conversationId, userId);
        if (conv == null) {
            return null;
        }
        return toMarkdown(conv);
    }

    // ---- helpers ----

    private Path getUserDir(String userId) {
        if (baseDir == null || userId == null || userId.isEmpty()) {
            return null;
        }
        // Sanitize userId for filesystem safety
        String safeUser = userId.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return baseDir.resolve("conversations").resolve(safeUser);
    }

    /**
     * Load only metadata from JSON file (without messages).
     * Messages are loaded on-demand from ChatMemoryRepository.
     */
    private Conversation loadMetadataOnly(String conversationId, String userId, Path userDir) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        Path file = userDir.resolve(conversationId + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<String, Object> data = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            // Verify ownership
            String storedUserId = str(data.get("userId"));
            if (!userId.equals(storedUserId)) {
                log.warn("Ownership mismatch: conversation {} belongs to {}, requested by {}",
                        conversationId, storedUserId, userId);
                return null;
            }
            return fromMetadataMap(data);
        } catch (Exception e) {
            log.warn("Failed to load conversation metadata {}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    /**
     * Convert Conversation to metadata-only map (without messages).
     */
    private Map<String, Object> toMetadataMap(Conversation conv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", conv.getId());
        map.put("userId", conv.getUserId());
        map.put("skillId", conv.getSkillId());
        map.put("title", conv.getTitle());
        map.put("createdAt", conv.getCreatedAt());
        map.put("updatedAt", conv.getUpdatedAt());
        map.put("messageCount", conv.getMessageCount());
        // Note: messages are NOT included - they're in ChatMemoryRepository
        return map;
    }

    /**
     * Convert metadata map to Conversation (with empty message list).
     */
    private Conversation fromMetadataMap(Map<String, Object> data) {
        return new Conversation(
                str(data.get("id")),
                str(data.get("userId")),
                str(data.get("skillId")),
                str(data.get("title")),
                longVal(data.get("createdAt")),
                longVal(data.get("updatedAt")),
                Collections.<ConversationMessage>emptyList()  // Messages loaded separately
        );
    }

    private String deriveTitle(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "新对话";
        }
        for (ConversationMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent().trim();
                if (content.isEmpty()) continue;
                // Truncate to 30 chars for title
                return content.length() > 30 ? content.substring(0, 30) + "..." : content;
            }
        }
        return "新对话";
    }

    private String toMarkdown(Conversation conv) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(conv.getTitle() != null ? conv.getTitle() : "对话记录").append("\n\n");
        sb.append("- **Skill**: ").append(conv.getSkillId()).append("\n");
        sb.append("- **用户**: ").append(conv.getUserId()).append("\n");
        sb.append("- **创建时间**: ").append(formatDate(conv.getCreatedAt())).append("\n");
        sb.append("- **更新时间**: ").append(formatDate(conv.getUpdatedAt())).append("\n");
        sb.append("- **消息数**: ").append(conv.getMessageCount()).append("\n\n");
        sb.append("---\n\n");

        for (ConversationMessage msg : conv.getMessages()) {
            String role = msg.getRole();
            String label;
            String icon;
            if ("user".equals(role)) {
                label = "用户";
                icon = "👤";
            } else if ("assistant".equals(role)) {
                label = "助手";
                icon = "🤖";
            } else {
                label = role;
                icon = "💬";
            }
            sb.append("## ").append(icon).append(" ").append(label);
            sb.append("  _").append(formatDate(msg.getTimestamp())).append("_\n\n");
            sb.append(msg.getContent() != null ? msg.getContent() : "").append("\n\n");
            sb.append("---\n\n");
        }

        return sb.toString();
    }

    private String formatDate(long timestamp) {
        synchronized (DATE_FMT) {
            return DATE_FMT.format(new Date(timestamp));
        }
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
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

    private static int intVal(Object obj, int fallback) {
        if (obj == null) return fallback;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static int countMessages(Map<String, Object> data) {
        Object msgs = data.get("messages");
        return msgs instanceof List ? ((List<Object>) msgs).size() : 0;
    }
}
