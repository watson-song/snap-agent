package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.vectorstore.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HeadingChunker} — the default {@code Chunker} that splits
 * by {@code ## } level-2 Markdown headings.
 *
 * <p>P2-9: verifies that the chunker produces correct chunks, propagates
 * metadata, and handles edge cases (no headings, null input).</p>
 */
@DisplayName("HeadingChunker — split by ## headings")
class HeadingChunkerTest {

    private final HeadingChunker chunker = new HeadingChunker();

    private Document docWith(String content, String source, String category) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        if (source != null) meta.put("source", source);
        if (category != null) meta.put("category", category);
        return new Document(content, meta);
    }

    @Test
    @DisplayName("strategy → 'heading'")
    void shouldReturnHeadingAsStrategy() {
        assertThat(chunker.strategy()).isEqualTo("heading");
    }

    @Test
    @DisplayName("Document with 2 ## headings → 2 chunks")
    void shouldSplitByH2HeadingsIntoMultipleChunks() {
        String content = "# Title\n## Section A\nContent A\n## Section B\nContent B\n";
        Document doc = docWith(content, "test.md", "Title");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).contains("Section A");
        assertThat(chunks.get(0).getContent()).contains("Content A");
        assertThat(chunks.get(1).getContent()).contains("Section B");
        assertThat(chunks.get(1).getContent()).contains("Content B");
    }

    @Test
    @DisplayName("Document with 3 ## headings → 3 chunks")
    void shouldSplitThreeSections() {
        String content = "# Title\n## S1\nbody1\n## S2\nbody2\n## S3\nbody3\n";
        Document doc = docWith(content, "multi.md", "Title");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getContent()).contains("S1");
        assertThat(chunks.get(1).getContent()).contains("S2");
        assertThat(chunks.get(2).getContent()).contains("S3");
    }

    @Test
    @DisplayName("Document without ## headings → single chunk (full content)")
    void shouldReturnSingleChunkWhenNoHeadings() {
        String content = "# Title\nThis is just plain content.\n";
        Document doc = docWith(content, "simple.md", "Title");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).contains("plain content");
    }

    @Test
    @DisplayName("Each chunk inherits source + category metadata")
    void shouldPropagateMetadataToChunks() {
        String content = "# Guide\n## Section A\nbody A\n## Section B\nbody B\n";
        Document doc = docWith(content, "guide.md", "Guide");

        List<Document> chunks = chunker.chunk(doc);

        for (Document chunk : chunks) {
            assertThat((String) chunk.getMetadata("source")).isEqualTo("guide.md");
            assertThat((String) chunk.getMetadata("category")).isEqualTo("Guide");
        }
    }

    @Test
    @DisplayName("Content before first ## → overview chunk (if not just the # title)")
    void shouldCreateOverviewChunkForContentBeforeFirstH2() {
        String content = "# Title\nIntro paragraph\n## Section A\nbody A\n";
        Document doc = docWith(content, "overview.md", "Title");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(2);
        // First chunk = overview (contains "Intro paragraph")
        assertThat(chunks.get(0).getContent()).contains("Intro paragraph");
        // Second chunk = section A
        assertThat(chunks.get(1).getContent()).contains("Section A");
    }

    @Test
    @DisplayName("Content before first ## is just # Title → no overview chunk")
    void shouldSkipOverviewWhenOnlyH1Title() {
        String content = "# Title\n## Section A\nbody A\n";
        Document doc = docWith(content, "onlyH1.md", "Title");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).contains("Section A");
    }

    @Test
    @DisplayName("null document → empty list")
    void shouldReturnEmptyListForNullDocument() {
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    @DisplayName("empty content → empty list")
    void shouldReturnEmptyListForEmptyContent() {
        Document doc = docWith("", "empty.md", null);
        assertThat(chunker.chunk(doc)).isEmpty();
    }

    @Test
    @DisplayName("Chinese + emoji content preserved in chunks")
    void shouldPreserveChineseAndEmojiInChunks() {
        String content = "# 标题\n## 第一节\n连接池配置 😀\n## 第二节\n超时处理 🔧\n";
        Document doc = docWith(content, "chinese.md", "标题");

        List<Document> chunks = chunker.chunk(doc);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).contains("连接池配置");
        assertThat(chunks.get(0).getContent()).contains("😀");
        assertThat(chunks.get(1).getContent()).contains("超时处理");
        assertThat(chunks.get(1).getContent()).contains("🔧");
    }
}
