package cn.watsontech.snapagent.boot2x.domain;

import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads domain knowledge from Markdown files with YAML frontmatter.
 *
 * <p>Each .md file in the configured directory is parsed:</p>
 * <ol>
 *   <li>YAML frontmatter (between --- markers) is parsed for structured metadata</li>
 *   <li>Markdown body becomes the content</li>
 *   <li>A {@link Document} is created and stored in VectorStore (source: "domain-knowledge")</li>
 *   <li>A {@link DomainKnowledge} is created and added to the index</li>
 * </ol>
 *
 * <p>Frontmatter parsing uses a simple line-based parser (no YAML library dependency).
 * Supports: scalar values, arrays in [a, b, c] format, arrays in - item format.</p>
 */
public class DomainKnowledgeLoader {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeLoader.class);

    private static final String FRONTMATTER_DELIMITER = "---";
    private static final Pattern ARRAY_INLINE = Pattern.compile("^\\s*\\[([^\\]]*)\\]\\s*$");

    private final DomainKnowledgeIndex index;
    private final VectorStore vectorStore;

    public DomainKnowledgeLoader(DomainKnowledgeIndex index, VectorStore vectorStore) {
        this.index = index;
        this.vectorStore = vectorStore;
    }

    /**
     * Load all .md files from the given directory.
     *
     * @param dir directory to scan
     * @return list of loaded domain knowledge entries
     */
    public List<DomainKnowledge> loadFromDirectory(Path dir) {
        List<DomainKnowledge> results = new ArrayList<DomainKnowledge>();
        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("Domain knowledge directory does not exist or is not a directory: {}", dir);
            return results;
        }

        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(dir, "*.md");
            for (Path file : stream) {
                try {
                    DomainKnowledge dk = parseFile(file);
                    if (dk != null && dk.getName() != null && !dk.getName().isEmpty()) {
                        results.add(dk);
                        index.put(dk);
                        storeInVectorStore(dk, file.getFileName().toString());
                        log.info("Loaded domain knowledge: {} (tables={}, services={})",
                                dk.getName(), dk.getTables().size(), dk.getServices().size());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse domain knowledge file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan domain knowledge directory {}: {}", dir, e.getMessage());
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException e) { /* ignore */ }
            }
        }

        log.info("Domain knowledge loaded: {} concepts from {}", results.size(), dir);
        return results;
    }

    /**
     * Parse a single Markdown file with YAML frontmatter.
     *
     * @param file path to the .md file
     * @return parsed DomainKnowledge, or null if no valid frontmatter found
     */
    public DomainKnowledge parseFile(Path file) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parseContent(content);
    }

    /**
     * Parse Markdown content string with YAML frontmatter.
     */
    public DomainKnowledge parseContent(String content) {
        if (content == null || content.isEmpty()) return null;

        // Find frontmatter boundaries
        String trimmed = content.trim();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return null; // No frontmatter
        }

        int secondDelim = trimmed.indexOf(FRONTMATTER_DELIMITER, 3);
        if (secondDelim < 0) {
            return null; // Incomplete frontmatter
        }

        String frontmatter = trimmed.substring(3, secondDelim).trim();
        String body = trimmed.substring(secondDelim + 3).trim();

        // Parse frontmatter
        Map<String, Object> metadata = parseFrontmatter(frontmatter);
        String name = getStringValue(metadata, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        return new DomainKnowledge(
                name,
                body,
                getStringList(metadata, "tables"),
                getStringList(metadata, "services"),
                getStringList(metadata, "entry_points"),
                getStringList(metadata, "related_concepts"),
                getStringList(metadata, "tags"),
                metadata);
    }

    // ---- Frontmatter parser (simple, no YAML dependency) ----

    Map<String, Object> parseFrontmatter(String frontmatter) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String[] lines = frontmatter.split("\n");
        String currentKey = null;
        List<String> currentList = null;

        for (String line : lines) {
            // Check for "key: value" pattern
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0 && !line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("-")) {
                // Save previous list if any
                if (currentKey != null && currentList != null) {
                    result.put(currentKey, currentList);
                    currentList = null;
                }

                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();

                if (value.isEmpty()) {
                    // Start of a block list (next lines will be "- item")
                    currentKey = key;
                    currentList = new ArrayList<String>();
                } else if (value.startsWith("[")) {
                    // Inline array: [a, b, c]
                    Matcher m = ARRAY_INLINE.matcher(value);
                    if (m.matches()) {
                        String[] items = m.group(1).split(",");
                        List<String> list = new ArrayList<String>();
                        for (String item : items) {
                            String trimmed_item = item.trim();
                            if (!trimmed_item.isEmpty()) {
                                list.add(trimmed_item);
                            }
                        }
                        result.put(key, list);
                    } else {
                        result.put(key, value);
                    }
                    currentKey = null;
                } else {
                    // Scalar value
                    result.put(key, value);
                    currentKey = null;
                }
            } else if (line.trim().startsWith("-") && currentKey != null && currentList != null) {
                // Block list item
                String item = line.trim().substring(1).trim();
                if (!item.isEmpty()) {
                    currentList.add(item);
                }
            }
        }

        // Save last list if any
        if (currentKey != null && currentList != null) {
            result.put(currentKey, currentList);
        }

        return result;
    }

    private void storeInVectorStore(DomainKnowledge dk, String filename) {
        if (vectorStore == null) return;

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "domain-knowledge");
        metadata.put("title", dk.getName());
        metadata.put("filename", filename);
        metadata.put("version", 1);
        if (!dk.getTags().isEmpty()) {
            StringBuilder tags = new StringBuilder();
            for (int i = 0; i < dk.getTags().size(); i++) {
                if (i > 0) tags.append(",");
                tags.append(dk.getTags().get(i));
            }
            metadata.put("tags", tags.toString());
        }

        Document doc = new Document(dk.toSummary(), metadata);
        List<Document> docs = new ArrayList<Document>();
        docs.add(doc);
        vectorStore.add(docs);
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> metadata, String key) {
        Object val = metadata.get(key);
        if (val instanceof List) {
            List<String> result = new ArrayList<String>();
            for (Object item : (List<?>) val) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        if (val instanceof String) {
            String s = (String) val;
            if (s.isEmpty()) return new ArrayList<String>();
            return Arrays.asList(s.split(","));
        }
        return new ArrayList<String>();
    }

    private String getStringValue(Map<String, Object> metadata, String key) {
        Object val = metadata.get(key);
        return val != null ? val.toString() : null;
    }
}
