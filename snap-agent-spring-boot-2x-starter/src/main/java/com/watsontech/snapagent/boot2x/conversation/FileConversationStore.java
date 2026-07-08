package com.watsontech.snapagent.boot2x.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watsontech.snapagent.core.conversation.Conversation;
import com.watsontech.snapagent.core.conversation.ConversationMessage;
import com.watsontech.snapagent.core.conversation.ConversationStore;
import com.watsontech.snapagent.core.conversation.ConversationSummary;
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
 * Default {@link ConversationStore} that persists conversations as JSON files
 * under a base directory (typically the upload-skills directory).
 *
 * <p>File layout: {@code {baseDir}/conversations/{userId}/{conversationId}.json}</p>
 *
 * <p>JSON is used for structured storage (easy to parse back); markdown is
 * generated on-the-fly by {@link #exportMarkdown(String, String)} for
 * download.</p>
 */
public class FileConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(FileConversationStore.class);
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileConversationStore(String baseDirPath) {
        this(baseDirPath, new ObjectMapper());
    }

    public FileConversationStore(String baseDirPath, ObjectMapper mapper) {
        String path = baseDirPath;
        if (path != null && path.startsWith("file:")) {
            path = path.substring(5);
        }
        this.baseDir = path != null ? Paths.get(path) : null;
        this.mapper = mapper;
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
            Conversation existing = loadRaw(convId, conversation.getUserId(), userDir);
            if (existing != null) {
                createdAt = existing.getCreatedAt();
            }
        }

        String title = conversation.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = deriveTitle(conversation.getMessages());
        }

        Conversation saved = new Conversation(
                convId, conversation.getUserId(), conversation.getSkillId(),
                title, createdAt, now, conversation.getMessages());

        Path file = userDir.resolve(convId + ".json");
        try {
            Map<String, Object> data = toMap(saved);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.createDirectories(userDir);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved conversation {} ({} messages) to {}",
                    convId, saved.getMessageCount(), file);
        } catch (IOException e) {
            log.error("Failed to save conversation {}: {}", convId, e.getMessage());
        }

        return saved;
    }

    @Override
    public Conversation load(String conversationId, String userId) {
        Path userDir = getUserDir(userId);
        if (userDir == null) {
            return null;
        }
        return loadRaw(conversationId, userId, userDir);
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

    private Conversation loadRaw(String conversationId, String userId, Path userDir) {
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
            return fromMap(data);
        } catch (Exception e) {
            log.warn("Failed to load conversation {}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toMap(Conversation conv) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", conv.getId());
        map.put("userId", conv.getUserId());
        map.put("skillId", conv.getSkillId());
        map.put("title", conv.getTitle());
        map.put("createdAt", conv.getCreatedAt());
        map.put("updatedAt", conv.getUpdatedAt());
        map.put("messageCount", conv.getMessageCount());

        List<Map<String, Object>> msgList = new ArrayList<Map<String, Object>>();
        for (ConversationMessage msg : conv.getMessages()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("timestamp", msg.getTimestamp());
            msgList.add(m);
        }
        map.put("messages", msgList);
        return map;
    }

    @SuppressWarnings("unchecked")
    private Conversation fromMap(Map<String, Object> data) {
        List<ConversationMessage> messages = new ArrayList<ConversationMessage>();
        Object msgsObj = data.get("messages");
        if (msgsObj instanceof List) {
            for (Object item : (List<Object>) msgsObj) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    messages.add(new ConversationMessage(
                            str(m.get("role")),
                            str(m.get("content")),
                            longVal(m.get("timestamp"))));
                }
            }
        }
        return new Conversation(
                str(data.get("id")),
                str(data.get("userId")),
                str(data.get("skillId")),
                str(data.get("title")),
                longVal(data.get("createdAt")),
                longVal(data.get("updatedAt")),
                messages);
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
