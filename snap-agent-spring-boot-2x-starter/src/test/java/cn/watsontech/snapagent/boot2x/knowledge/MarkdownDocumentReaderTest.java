package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.vectorstore.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MarkdownDocumentReader} — the default {@code DocumentReader}
 * for {@code .md} files.
 *
 * <p>P2-9: verifies that the reader loads file content into a single Document
 * with correct metadata (source, category).</p>
 */
@DisplayName("MarkdownDocumentReader — .md file → Document")
class MarkdownDocumentReaderTest {

    @TempDir
    Path tempDir;

    private final MarkdownDocumentReader reader = new MarkdownDocumentReader();

    @Test
    @DisplayName("supportedExtension → 'md'")
    void shouldReturnMdAsSupportedExtension() {
        assertThat(reader.supportedExtension()).isEqualTo("md");
    }

    @Test
    @DisplayName("read .md file → single Document with full content")
    void shouldReadMarkdownFileIntoSingleDocument() throws IOException {
        Path file = tempDir.resolve("guide.md");
        String content = "# Guide\n## Section A\nContent A\n## Section B\nContent B\n";
        Files.write(file, content.getBytes());

        List<Document> docs = reader.read(file);

        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("read → metadata.source = file name")
    void shouldIncludeSourceMetadata() throws IOException {
        Path file = tempDir.resolve("handbook.md");
        Files.write(file, "# Handbook\n## S1\ncontent\n".getBytes());

        List<Document> docs = reader.read(file);

        assertThat(docs).hasSize(1);
        assertThat((String) docs.get(0).getMetadata("source")).isEqualTo("handbook.md");
    }

    @Test
    @DisplayName("read → metadata.category = first-level heading (# Title)")
    void shouldExtractCategoryFromH1Heading() throws IOException {
        Path file = tempDir.resolve("doc.md");
        Files.write(file, "# My Document\n## Section\nbody\n".getBytes());

        List<Document> docs = reader.read(file);

        assertThat(docs).hasSize(1);
        assertThat((String) docs.get(0).getMetadata("category")).isEqualTo("My Document");
    }

    @Test
    @DisplayName("read file without # heading → no category metadata")
    void shouldOmitCategoryWhenNoH1Heading() throws IOException {
        Path file = tempDir.resolve("noTitle.md");
        Files.write(file, "## Section\nbody\n".getBytes());

        List<Document> docs = reader.read(file);

        assertThat(docs).hasSize(1);
        assertThat((String) docs.get(0).getMetadata("category")).isNull();
    }

    @Test
    @DisplayName("read null file → empty list")
    void shouldReturnEmptyListForNullFile() {
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("read empty file → empty list")
    void shouldReturnEmptyListForEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.md");
        Files.write(file, new byte[0]);

        assertThat(reader.read(file)).isEmpty();
    }

    @Test
    @DisplayName("read → Chinese + emoji content preserved")
    void shouldPreserveChineseAndEmojiContent() throws IOException {
        Path file = tempDir.resolve("chinese.md");
        Files.write(file, "# 标题 😀\n## 第一节\n连接池配置 🔧\n".getBytes());

        List<Document> docs = reader.read(file);

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getContent()).contains("连接池配置");
        assertThat(docs.get(0).getContent()).contains("🔧");
    }
}
