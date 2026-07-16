package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownKnowledgeSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void load_fromFilesystem_splitsByH2Headings() throws IOException {
        Path mdFile = tempDir.resolve("test-knowledge.md");
        String content = "# Test Knowledge File\n\n"
                + "This is the file intro.\n\n"
                + "## Section One\n"
                + "Content of section one.\n\n"
                + "## Section Two\n"
                + "Content of section two.\n";
        Files.write(mdFile, content.getBytes());

        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();

        assertThat(fragments).hasSize(3);
        // overview fragment (intro before first ##)
        assertThat(fragments.get(0).getTitle()).contains("概述");
        assertThat(fragments.get(0).getSource()).isEqualTo("test-knowledge.md:overview");
        // section 1
        assertThat(fragments.get(1).getTitle()).isEqualTo("Section One");
        assertThat(fragments.get(1).getContent()).contains("Content of section one");
        assertThat(fragments.get(1).getSource()).isEqualTo("test-knowledge.md:section-1");
        // section 2
        assertThat(fragments.get(2).getTitle()).isEqualTo("Section Two");
        assertThat(fragments.get(2).getSource()).isEqualTo("test-knowledge.md:section-2");
        // category metadata from # heading
        for (KnowledgeFragment f : fragments) {
            assertThat(f.getMetadata().get("category")).isEqualTo("Test Knowledge File");
        }
    }

    @Test
    void load_fileWithoutH2Headings_producesSingleFragment() throws IOException {
        Path mdFile = tempDir.resolve("no-sections.md");
        String content = "# File Title\n\nThis file has no section headings.\nJust plain content.\n";
        Files.write(mdFile, content.getBytes());

        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();

        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).getTitle()).isEqualTo("File Title");
        assertThat(fragments.get(0).getContent()).contains("plain content");
        assertThat(fragments.get(0).getSource()).isEqualTo("no-sections.md:overview");
    }

    @Test
    void load_fileWithoutH1Title_hasSectionButNoCategory() throws IOException {
        Path mdFile = tempDir.resolve("unnamed.md");
        String content = "## First Section\nBody text.\n";
        Files.write(mdFile, content.getBytes());

        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();

        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).getTitle()).isEqualTo("First Section");
        assertThat(fragments.get(0).getMetadata().get("category")).isNull();
    }

    @Test
    void load_multipleMdFiles_combinesAllFragments() throws IOException {
        Files.write(tempDir.resolve("file-a.md"),
                ("# A\n## A1\ncontent a1\n## A2\ncontent a2\n").getBytes());
        Files.write(tempDir.resolve("file-b.md"),
                ("# B\n## B1\ncontent b1\n").getBytes());

        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();

        // file-a: overview + A1 + A2 = 3, file-b: overview + B1 = 2
        assertThat(fragments).hasSize(5);
        assertThat(fragments).extracting(KnowledgeFragment::getTitle)
                .contains("A1", "A2", "B1");
    }

    @Test
    void load_nonexistentDir_returnsEmptyList() {
        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource("/nonexistent/path/xyz");
        List<KnowledgeFragment> fragments = source.load();
        assertThat(fragments).isEmpty();
    }

    @Test
    void load_emptyDir_returnsEmptyList() {
        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();
        assertThat(fragments).isEmpty();
    }

    @Test
    void load_skipsNonMdFiles() throws IOException {
        Files.write(tempDir.resolve("readme.txt"), "not markdown".getBytes());
        Files.write(tempDir.resolve("real.md"), "# Real\n## R1\nbody\n".getBytes());

        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        List<KnowledgeFragment> fragments = source.load();

        assertThat(fragments).hasSize(2); // overview + R1
    }

    @Test
    void type_returnsMarkdown() {
        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        assertThat(source.type()).isEqualTo("markdown");
    }

    @Test
    void reload_doesNotThrow() {
        MarkdownKnowledgeSource source = new MarkdownKnowledgeSource(tempDir.toString());
        source.reload(); // should be a no-op
        assertThat(source.load()).isEmpty();
    }
}
