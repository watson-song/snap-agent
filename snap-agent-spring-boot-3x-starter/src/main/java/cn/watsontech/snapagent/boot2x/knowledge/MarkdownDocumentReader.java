package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.rag.DocumentReader;
import cn.watsontech.snapagent.core.vectorstore.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link DocumentReader} for Markdown ({@code .md}) files.
 *
 * <p>Reads the entire file content as UTF-8 and returns a single
 * {@link Document}. The document's metadata includes:</p>
 * <ul>
 *   <li>{@code source} — the file name</li>
 *   <li>{@code category} — the first-level heading ({@code # Title}), if present</li>
 * </ul>
 *
 * <p>P2-9: extracted from {@code KnowledgeETLPipeline} file-loading logic.</p>
 */
public class MarkdownDocumentReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownDocumentReader.class);

    @Override
    public List<Document> read(Path file) {
        if (file == null) return Collections.emptyList();
        String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("SOURCE_LOAD_FAILED: {} — {}", file.getFileName(), e.getMessage());
            return Collections.emptyList();
        }
        if (content == null || content.isEmpty()) return Collections.emptyList();

        String fileName = file.getFileName().toString();
        String category = extractTitle(content);

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", fileName);
        if (category != null && !category.isEmpty()) {
            metadata.put("category", category);
        }
        return Collections.singletonList(new Document(content, metadata));
    }

    @Override
    public String supportedExtension() {
        return "md";
    }

    /**
     * Extract the first-level heading ({@code # Title}) as the category.
     *
     * @param content the markdown content
     * @return the title text, or empty string if no {@code # } heading exists
     */
    String extractTitle(String content) {
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                return trimmed.substring(2).trim();
            }
        }
        return "";
    }
}
