package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeLoader;
import cn.watsontech.snapagent.boot2x.domain.InMemoryDomainKnowledgeIndex;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KnowledgeHotReloader} — the file-watch hook that re-ingests
 * knowledge on {@code .md} changes.
 *
 * <p>Uses a {@link CountingReloadService} (subclass that counts
 * {@code reload()} invocations) so assertions are precise. As with
 * {@code CodeGraphHotReloaderTest}, assertions poll via {@link #awaitCondition}
 * because macOS's {@code PollingWatchService} does not deliver events instantly.</p>
 */
class KnowledgeHotReloaderTest {

    @TempDir
    Path tempDir;

    private InMemoryVectorStore vectorStore;
    private DomainKnowledgeIndex domainIndex;
    private KnowledgeETLPipeline etlPipeline;
    private DomainKnowledgeLoader domainLoader;
    private SnapAgentProperties props;
    private CountingReloadService reloadService;

    /** Subclass that counts reload() invocations. */
    private static class CountingReloadService extends KnowledgeReloadService {
        final AtomicInteger count = new AtomicInteger();

        CountingReloadService(InMemoryVectorStore vs, DomainKnowledgeIndex idx,
                              KnowledgeETLPipeline etl, DomainKnowledgeLoader loader,
                              SnapAgentProperties props) {
            super(vs, idx, etl, loader, props);
        }

        @Override
        public ReloadResult reload() {
            count.incrementAndGet();
            return super.reload();
        }
    }

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        domainIndex = new InMemoryDomainKnowledgeIndex();
        EmbeddingModel embeddingModel = new EmbeddingModel() {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                List<float[]> r = new java.util.ArrayList<float[]>();
                for (int i = 0; i < texts.size(); i++) r.add(new float[]{1.0f, 0.0f});
                return r;
            }
        };
        etlPipeline = new KnowledgeETLPipeline(vectorStore, embeddingModel);
        domainLoader = new DomainKnowledgeLoader(domainIndex, vectorStore);
        props = new SnapAgentProperties();
        reloadService = new CountingReloadService(
                vectorStore, domainIndex, etlPipeline, domainLoader, props);
    }

    private void writeMd(String dirName, String filename, String content) throws Exception {
        File dir = tempDir.resolve(dirName).toFile();
        dir.mkdirs();
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, filename)), StandardCharsets.UTF_8);
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    private void writeFile(String relative, String content) throws Exception {
        File f = tempDir.resolve(relative).toFile();
        f.getParentFile().mkdirs();
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(content);
        } finally {
            w.close();
        }
    }

    @Test
    void shouldNotStartWhenWatchRootMissing() {
        Path missing = tempDir.resolve("does-not-exist");
        KnowledgeHotReloader reloader = new KnowledgeHotReloader(missing, reloadService, 50L);
        reloader.start();
        // No exception; reloader does not run a watch loop
        reloader.stop();
    }

    @Test
    void shouldTriggerReloadOnMdFileChange() throws Exception {
        // watch root covers upload-skills-dir, which contains domain-knowledge/
        writeMd("domain-knowledge", "seed.md", "---\nname: 种子概念\n---\n正文");
        props.setUploadSkillsDir(tempDir.toString());

        KnowledgeHotReloader reloader = new KnowledgeHotReloader(tempDir, reloadService, 50L);
        reloader.start();

        // Write AFTER start (ENTRY_CREATE is reliable even on macOS polling)
        writeMd("domain-knowledge", "new-doc.md",
                "---\nname: 新概念\ntables: [t1]\nservices: [S1]\n---\n正文");

        try {
            awaitCondition(() -> reloadService.count.get() >= 1, 15000);
        } finally {
            reloader.stop();
        }

        assertThat(domainIndex.findByName("新概念")).isNotNull();
    }

    @Test
    void shouldIgnoreNonMdFiles() throws Exception {
        writeMd("domain-knowledge", "seed.md", "---\nname: 种子概念\n---\n正文");
        props.setUploadSkillsDir(tempDir.toString());

        KnowledgeHotReloader reloader = new KnowledgeHotReloader(tempDir, reloadService, 50L);
        reloader.start();

        writeFile("notes.txt", "irrelevant");

        // Sleep long enough to catch a spurious reload if one fires
        Thread.sleep(1500);
        reloader.stop();

        assertThat(reloadService.count.get()).isZero();
    }

    /**
     * Polls {@code assertion} every 100ms until it returns true or the timeout
     * elapses (macOS {@code PollingWatchService} polls ~2s with HIGH sensitivity).
     */
    private static void awaitCondition(BooleanSupplier assertion, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (assertion.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(assertion.getAsBoolean())
                .as("condition not met within %d ms", timeoutMs)
                .isTrue();
    }
}
