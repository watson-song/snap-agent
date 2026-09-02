package cn.watsontech.snapagent.boot2x.memory;

import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.llm.ToolUseBlock;
import cn.watsontech.snapagent.core.memory.ChatMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-based {@link ChatMemoryRepository} that persists conversation messages
 * as JSON files. Each conversation is stored as a separate JSON file under
 * the configured base directory.
 *
 * <p>File layout:</p>
 * <pre>
 * {baseDir}/
 *   {conversationId}.json    — message list for that conversation
 * </pre>
 *
 * <p>Thread safety: each read/write acquires a file-level lock via
 * synchronized blocks keyed on the conversation ID.</p>
 */
public class FileChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileChatMemoryRepository.class);

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileChatMemoryRepository(String baseDirPath) {
        this(baseDirPath, new ObjectMapper());
    }

    public FileChatMemoryRepository(String baseDirPath, ObjectMapper mapper) {
        if (baseDirPath == null || baseDirPath.isEmpty()) {
            throw new IllegalArgumentException("baseDirPath cannot be null or empty");
        }
        this.baseDir = Paths.get(baseDirPath);
        this.mapper = mapper;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            log.warn("Failed to create chat memory directory {}: {}", this.baseDir, e.getMessage());
        }
    }

    @Override
    public synchronized void save(String conversationId, List<Message> messages) {
        if (conversationId == null) return;
        File file = conversationFile(conversationId);
        try {
            List<Map<String, Object>> data = new ArrayList<Map<String, Object>>();
            for (Message msg : messages) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
                m.put("toolUseId", msg.getToolUseId());
                if (msg.hasToolUses()) {
                    List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
                    for (ToolUseBlock tu : msg.getToolUses()) {
                        Map<String, Object> t = new LinkedHashMap<String, Object>();
                        t.put("id", tu.getId());
                        t.put("name", tu.getName());
                        t.put("input", tu.getInput());
                        tools.add(t);
                    }
                    m.put("toolUses", tools);
                }
                data.add(m);
            }
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            writeFile(file, json);
        } catch (IOException e) {
            log.warn("Failed to save chat memory for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    @Override
    public synchronized List<Message> load(String conversationId) {
        if (conversationId == null) return Collections.emptyList();
        File file = conversationFile(conversationId);
        if (!file.exists()) {
            return Collections.emptyList();
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<Map<String, Object>> data = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Message> messages = new ArrayList<Message>();
            for (Map<String, Object> m : data) {
                String role = getString(m, "role");
                String content = getString(m, "content");
                String toolUseId = getString(m, "toolUseId");
                List<ToolUseBlock> toolUses = parseToolUses(m);
                messages.add(new Message(role, content, toolUseId, toolUses));
            }
            return messages;
        } catch (IOException e) {
            log.warn("Failed to load chat memory for conversation {}: {}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public synchronized void delete(String conversationId) {
        if (conversationId == null) return;
        File file = conversationFile(conversationId);
        if (file.exists()) {
            if (!file.delete()) {
                log.warn("Failed to delete chat memory file: {}", file);
            }
        }
    }

    @Override
    public List<String> listConversations(String userId) {
        File dir = baseDir.toFile();
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<String>();
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".json")) {
                ids.add(name.substring(0, name.length() - 5));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    // ---- Helpers ----

    private File conversationFile(String conversationId) {
        // Sanitize conversation ID for use as filename
        String safeId = conversationId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(baseDir.toFile(), safeId + ".json");
    }

    private void writeFile(File file, String content) throws IOException {
        java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private List<ToolUseBlock> parseToolUses(Map<String, Object> msgMap) {
        Object obj = msgMap.get("toolUses");
        if (!(obj instanceof List)) {
            return null;
        }
        List<?> toolList = (List<?>) obj;
        if (toolList.isEmpty()) {
            return null;
        }
        List<ToolUseBlock> result = new ArrayList<ToolUseBlock>();
        for (Object item : toolList) {
            if (item instanceof Map) {
                Map<String, Object> t = (Map<String, Object>) item;
                String id = getString(t, "id");
                String name = getString(t, "name");
                Object input = t.get("input");
                Map<String, Object> inputMap = (input instanceof Map)
                        ? (Map<String, Object>) input
                        : Collections.<String, Object>emptyMap();
                result.add(new ToolUseBlock(id, name, inputMap));
            }
        }
        return result.isEmpty() ? null : result;
    }
}
