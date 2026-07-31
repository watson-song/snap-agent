package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.rag.Chunker;
import cn.watsontech.snapagent.core.vectorstore.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link Chunker} that splits a {@link Document} by level-2
 * ({@code ## }) Markdown headings.
 *
 * <p>Each {@code ## } section becomes a separate chunk. Content before the
 * first {@code ## } heading becomes an overview chunk (unless it is just the
 * {@code # Title}). If no {@code ## } headings exist, the entire document is
 * returned as a single chunk.</p>
 *
 * <p>Each chunk inherits the {@code source} and {@code category} metadata
 * from the input document.</p>
 *
 * <p>P2-9: extracted from {@code KnowledgeETLPipeline.splitIntoChunks()}.</p>
 */
public class HeadingChunker implements Chunker {

    @Override
    public List<Document> chunk(Document document) {
        if (document == null) return Collections.emptyList();
        String content = document.getContent();
        if (content == null || content.isEmpty()) return Collections.emptyList();

        String source = document.getMetadata("source");
        String category = document.getMetadata("category");

        List<Document> chunks = new ArrayList<Document>();
        List<Section> sections = extractSections(content);

        if (sections.isEmpty()) {
            chunks.add(buildChunk(content.trim(), source, category));
            return chunks;
        }

        // Content before first ## heading → overview chunk
        int firstH2 = content.indexOf("\n## ");
        if (firstH2 < 0) firstH2 = content.indexOf("## ");
        if (firstH2 > 0) {
            String overview = content.substring(0, firstH2).trim();
            // Skip overview if it's just the h1 title (already captured as category)
            if (!overview.isEmpty() && !overview.equals("# " + category)) {
                chunks.add(buildChunk(overview, source, category));
            }
        }

        for (Section s : sections) {
            chunks.add(buildChunk(s.title + "\n" + s.body.trim(), source, category));
        }
        return chunks;
    }

    @Override
    public String strategy() {
        return "heading";
    }

    // --- helpers ---

    private Document buildChunk(String content, String source, String category) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        if (source != null) meta.put("source", source);
        if (category != null && !category.isEmpty()) meta.put("category", category);
        return new Document(content, meta);
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
}
