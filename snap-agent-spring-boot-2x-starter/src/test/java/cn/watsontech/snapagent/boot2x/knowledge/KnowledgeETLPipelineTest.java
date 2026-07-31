package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("KnowledgeETLPipeline — Markdown → split → embed → VectorStore")
class KnowledgeETLPipelineTest {

    @TempDir
    Path tempDir;

    private InMemoryVectorStore store;
    private EmbeddingModel embeddingModel;
    private KnowledgeETLPipeline pipeline;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        pipeline = new KnowledgeETLPipeline(store, embeddingModel);
    }

    // UC-07: ## 分段
    @Test
    @DisplayName("文件含 ## 标题 → 切分为多个 chunk 并写入 VectorStore")
    void shouldSplitByH2Headings() throws IOException {
        Path file = tempDir.resolve("test.md");
        Files.write(file, "# Title\n## S1\n内容1\n## S2\n内容2\n".getBytes());

        int written = pipeline.run(file);

        assertThat(written).isGreaterThan(0);
        assertThat(store.size()).isEqualTo(written);
        verify(embeddingModel, atLeast(2)).embed(anyString());
    }

    @Test
    @DisplayName("Document.metadata.source 含文件名")
    void shouldIncludeSourceMetadata() throws IOException {
        Path file = tempDir.resolve("handbook.md");
        Files.write(file, "# Handbook\n## S1\ncontent\n".getBytes());

        pipeline.run(file);

        List<Document> docs = store.listAll();
        assertThat(docs).isNotEmpty();
        assertThat((String) docs.get(0).getMetadata("source")).isEqualTo("handbook.md");
    }

    // UC-08: 无 ## → 整文件作为单 chunk
    @Test
    @DisplayName("无 ## 标题 → 整文件作为单个 chunk 写入")
    void shouldHandleNoHeadingsAsSingleChunk() throws IOException {
        Path file = tempDir.resolve("simple.md");
        Files.write(file, "# Title\n纯内容无二级标题\n".getBytes());

        int written = pipeline.run(file);

        assertThat(written).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    // UC-09: 单 chunk 失败隔离
    @Test
    @DisplayName("VectorStore.add 抛异常 → 其他 chunk 正常写入")
    void shouldIsolateChunkFailures() throws IOException {
        VectorStore failingStore = mock(VectorStore.class);
        // First call throws, second succeeds, third succeeds
        doThrow(new RuntimeException("chunk 2 failed"))
                .doNothing()
                .doNothing()
                .when(failingStore).add(anyList());

        pipeline = new KnowledgeETLPipeline(failingStore, embeddingModel);
        Path file = tempDir.resolve("multi.md");
        Files.write(file, "# Title\n## S1\ncontent1\n## S2\ncontent2\n## S3\ncontent3\n".getBytes());

        int written = pipeline.run(file);

        assertThat(written).isEqualTo(2); // 2 of 3 succeeded
    }

    // UC-10: 中文+emoji token 切分
    @Test
    @DisplayName("中文+emoji → 按内容切分不截断中文")
    void shouldHandleChineseAndEmoji() throws IOException {
        Path file = tempDir.resolve("chinese.md");
        Files.write(file, "# 标题\n## 第一节\n连接池配置 😀\n## 第二节\n超时处理 🔧\n".getBytes());

        int written = pipeline.run(file);

        assertThat(written).isGreaterThan(0);
        List<Document> docs = store.listAll();
        assertThat(docs).isNotEmpty();
        for (Document doc : docs) {
            assertThat(doc.getContent()).isNotEmpty();
        }
        // UC-10: Chinese characters and emoji survive the pipeline intact
        String allContent = docs.stream()
                .map(Document::getContent)
                .reduce("", String::concat);
        assertThat(allContent).contains("连接池配置");
        assertThat(allContent).contains("😀");
        assertThat(allContent).contains("超时处理");
        assertThat(allContent).contains("🔧");
    }

    @Test
    @DisplayName("runAll → 多文件依次 ETL")
    void shouldRunAll() throws IOException {
        Files.write(tempDir.resolve("f1.md"), "# F1\n## S1\ncontent\n".getBytes());
        Files.write(tempDir.resolve("f2.md"), "# F2\n## S2\ncontent\n".getBytes());

        int total = pipeline.runAll(tempDir);

        assertThat(total).isGreaterThan(0);
        assertThat(store.size()).isEqualTo(total);
    }

    @Test
    @DisplayName("VectorStore=null → 不抛异常")
    void shouldHandleNullVectorStore() throws IOException {
        pipeline = new KnowledgeETLPipeline(null, embeddingModel);
        Path file = tempDir.resolve("test.md");
        Files.write(file, "# Title\n## S1\ncontent\n".getBytes());

        int written = pipeline.run(file);
        assertThat(written).isEqualTo(0);
    }

    @Test
    @DisplayName("file=null → 返回0")
    void shouldReturnZeroForNullFile() {
        assertThat(pipeline.run(null)).isEqualTo(0);
    }

    // P2-17: Files.walk Stream must be closed (try-with-resources)
    @Test
    @DisplayName("runAll 处理大型目录不泄漏 Stream 资源 (P2-17)")
    void runAll_closesStreamProperly() throws IOException {
        // Create a directory with many .md files plus some non-md files.
        // If the Files.walk stream were not closed, repeated runs would
        // eventually exhaust file handles; here we verify correctness of a
        // large batch processed through the try-with-resources path.
        int mdCount = 100;
        for (int i = 0; i < mdCount; i++) {
            Files.write(tempDir.resolve("doc-" + i + ".md"),
                    ("# Title " + i + "\n## Section\ncontent " + i + "\n").getBytes());
        }
        // Non-markdown files should be skipped
        Files.write(tempDir.resolve("readme.txt"), "ignore me".getBytes());
        Files.write(tempDir.resolve("notes.json"), "{}".getBytes());

        int total = pipeline.runAll(tempDir);

        assertThat(total).isEqualTo(mdCount);
        assertThat(store.size()).isEqualTo(mdCount);

        // Running again over the same directory should not leak resources or
        // throw — verifies the stream is properly closed after the first pass.
        int secondRun = pipeline.runAll(tempDir);
        assertThat(secondRun).isEqualTo(mdCount);
    }
}
