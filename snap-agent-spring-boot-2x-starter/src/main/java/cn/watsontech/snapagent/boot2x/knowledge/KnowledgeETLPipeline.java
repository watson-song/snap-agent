package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ETL pipeline: Markdown file → split by ## headings → embed → write to VectorStore.
 *
 * <p>Reads a .md file, splits it into sections by {@code ## } (level-2) headings,
 * creates a {@link Document} for each section, embeds each via
 * {@link EmbeddingModel#embed}, and writes all documents to {@link VectorStore#add}.</p>
 *
 * <p>If no {@code ## } headings exist, the entire file becomes a single document.</p>
 *
 * <p>Single-chunk failure isolation: if {@code VectorStore.add} throws for one chunk,
 * the exception is caught + WARN logged, and remaining chunks continue.</p>
 */
public class KnowledgeETLPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeETLPipeline.class);

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public KnowledgeETLPipeline(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Run ETL on a single Markdown file.
     *
     * @param file the .md file to process
     * @return number of documents written to VectorStore
     */
    public int run(Path file) {
        if (file == null) return 0;
        String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("SOURCE_LOAD_FAILED: {} — {}", file.getFileName(), e.getMessage());
            return 0;
        }
        if (content == null || content.isEmpty()) return 0;

        String fileName = file.getFileName().toString();
        String category = extractTitle(content);
        List<Chunk> chunks = splitIntoChunks(content, fileName, category);

        int written = 0;
        for (Chunk chunk : chunks) {
            try {
                Document doc = new Document(chunk.content, chunk.metadata);
                if (embeddingModel != null) {
                    float[] embedding = embeddingModel.embed(chunk.content);
                    doc = doc.withEmbedding(embedding);
                }
                if (vectorStore != null) {
                    vectorStore.add(Collections.singletonList(doc));
                    written++;
                }
            } catch (RuntimeException e) {
                log.warn("ETL chunk failed in {}: {}", fileName, e.getMessage());
            }
        }
        log.info("ETL loaded {} documents from {} ({} chunks)", written, fileName, chunks.size());
        return written;
    }

    /**
     * Run ETL on all .md files in a directory.
     *
     * @param dir directory containing .md files
     * @return total documents written
     */
    public int runAll(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        int total = 0;
        List<Path> mdFiles = new ArrayList<Path>();
        try {
            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .forEach(mdFiles::add);
        } catch (IOException e) {
            log.warn("Failed to scan directory {}: {}", dir, e.getMessage());
            return 0;
        }
        for (Path file : mdFiles) {
            try {
                total += run(file);
            } catch (RuntimeException e) {
                log.warn("ETL failed for {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return total;
    }

    // --- splitting ---

    String extractTitle(String content) {
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                return trimmed.substring(2).trim();
            }
        }
        return "";
    }

    List<Chunk> splitIntoChunks(String content, String fileName, String category) {
        List<Chunk> chunks = new ArrayList<Chunk>();
        List<Section> sections = extractSections(content);

        if (sections.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("source", fileName);
            if (!category.isEmpty()) meta.put("category", category);
            chunks.add(new Chunk(content.trim(), meta));
            return chunks;
        }

        // Content before first ## heading → overview chunk
        int firstH2 = content.indexOf("\n## ");
        if (firstH2 < 0) firstH2 = content.indexOf("## ");
        if (firstH2 > 0) {
            String overview = content.substring(0, firstH2).trim();
            // Skip overview if it's just the h1 title (already captured as category)
            if (!overview.isEmpty() && !overview.equals("# " + category)) {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("source", fileName);
                if (!category.isEmpty()) meta.put("category", category);
                chunks.add(new Chunk(overview, meta));
            }
        }

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("source", fileName);
            if (!category.isEmpty()) meta.put("category", category);
            chunks.add(new Chunk(s.title + "\n" + s.body.trim(), meta));
        }
        return chunks;
    }

    private List<Section> extractSections(String content) {
        List<Section> sections = new ArrayList<Section>();
        String[] lines = content.split("\n", -1);
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                if (currentTitle != null) {
                    sections.add(new Section(currentTitle, currentBody.toString()));
                }
                currentTitle = trimmed.substring(3).trim();
                currentBody = new StringBuilder();
            } else if (currentTitle != null) {
                currentBody.append(line).append("\n");
            }
        }
        if (currentTitle != null) {
            sections.add(new Section(currentTitle, currentBody.toString()));
        }
        return sections;
    }

    private static class Section {
        final String title;
        final String body;
        Section(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    private static class Chunk {
        final String content;
        final Map<String, Object> metadata;
        Chunk(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }
    }
}
