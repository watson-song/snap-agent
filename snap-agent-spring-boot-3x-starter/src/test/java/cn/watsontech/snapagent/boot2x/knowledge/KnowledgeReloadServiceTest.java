package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeLoader;
import cn.watsontech.snapagent.boot2x.domain.InMemoryDomainKnowledgeIndex;
import cn.watsontech.snapagent.core.domain.DomainKnowledge;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KnowledgeReloadService} — the "code change → re-ingest"
 * coordination engine.
 */
class KnowledgeReloadServiceTest {

    @TempDir
    Path tempDir;

    private InMemoryVectorStore vectorStore;
    private DomainKnowledgeIndex domainIndex;
    private EmbeddingModel embeddingModel;
    private KnowledgeETLPipeline etlPipeline;
    private DomainKnowledgeLoader domainLoader;
    private SnapAgentProperties props;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
        domainIndex = new InMemoryDomainKnowledgeIndex();
        embeddingModel = new EmbeddingModel() {
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
    }

    private KnowledgeReloadService newService() {
        return new KnowledgeReloadService(
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

    @Test
    void reload_shouldClearAndReingestDomainKnowledge() throws Exception {
        writeMd("domain-knowledge", "concept.md",
                "---\nname: 调拨计划\ntables: [drp_allocation_plan]\nservices: [AllocationPlanService]\n---\n正文");
        props.setUploadSkillsDir(tempDir.toString());

        // Seed the store with a stale document
        vectorStore.add(Arrays.asList(
                new cn.watsontech.snapagent.core.vectorstore.Document(
                        "stale", "stale content", null, null)));
        domainIndex.put(new DomainKnowledge("旧概念", "body", null, null, null, null, null, null));

        KnowledgeReloadService.ReloadResult result = newService().reload();

        assertThat(result).isNotNull();
        assertThat(result.isVectorStoreCleared()).isTrue();
        assertThat(result.isDomainIndexCleared()).isTrue();
        assertThat(result.getConceptsLoaded()).isEqualTo(1);
        // Domain index now has the new concept, not the stale one
        assertThat(domainIndex.findByName("调拨计划")).isNotNull();
        assertThat(domainIndex.findByName("旧概念")).isNull();
    }

    @Test
    void reload_shouldReingestFilesystemKnowledgeSources() throws Exception {
        writeMd("docs-src", "guide.md", "# Guide\n\n## Intro\nSome knowledge content here.");
        props.getKnowledge().setEnabled(true);
        props.getKnowledge().getSources().add(source(tempDir.resolve("docs-src").toString()));

        KnowledgeReloadService.ReloadResult result = newService().reload();

        assertThat(result).isNotNull();
        assertThat(result.getDocumentsLoaded()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reload_shouldSkipClasspathSources() throws Exception {
        props.getKnowledge().setEnabled(true);
        props.getKnowledge().getSources().add(source("classpath:/docs/skills/"));

        KnowledgeReloadService.ReloadResult result = newService().reload();

        // classpath source is skipped (no filesystem dir), so 0 documents from sources
        assertThat(result).isNotNull();
        assertThat(result.getDocumentsLoaded()).isEqualTo(0);
    }

    @Test
    void reload_shouldReturnNullWhenNoInfrastructure() {
        KnowledgeReloadService svc = new KnowledgeReloadService(
                null, null, null, null, props);
        KnowledgeReloadService.ReloadResult result = svc.reload();
        assertThat(result).isNull();
    }

    private SnapAgentProperties.KnowledgeSourceConfig source(String dir) {
        SnapAgentProperties.KnowledgeSourceConfig c = new SnapAgentProperties.KnowledgeSourceConfig();
        c.setDir(dir);
        return c;
    }
}
