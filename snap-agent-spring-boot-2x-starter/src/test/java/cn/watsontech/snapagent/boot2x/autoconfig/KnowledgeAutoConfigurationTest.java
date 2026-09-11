package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.codegraph.AsyncCodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.AstCodeGraphBuilder;
import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService;
import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.rag.RetrievalAugmentationAdvisor;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link KnowledgeAutoConfiguration} bean wiring.
 *
 * <p>Verifies Fix P0-1 ({@link KnowledgeSedimentationService} bean),
 * Fix P0-2 ({@link RetrievalAugmentationAdvisor} config wiring and ETL startup hook),
 * and Fix P0-3 ({@link AsyncCodeGraphIndex} for async code graph build).</p>
 */
class KnowledgeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KnowledgeAutoConfiguration.class))
                    .withUserConfiguration(EnableProps.class);

    @EnableConfigurationProperties(SnapAgentProperties.class)
    static class EnableProps {}

    /**
     * Minimal {@link EmbeddingModel} stub for testing — returns a fixed 2-dim vector.
     */
    private static EmbeddingModel stubEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                List<float[]> result = new ArrayList<float[]>();
                for (int i = 0; i < texts.size(); i++) {
                    result.add(new float[]{1.0f, 0.0f});
                }
                return result;
            }
        };
    }

    // ---- Fix P0-1: KnowledgeSedimentationService bean ----

    @Test
    void shouldCreateKnowledgeSedimentationServiceWhenVectorStoreEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(KnowledgeSedimentationService.class);
                });
    }

    @Test
    void shouldNotCreateKnowledgeSedimentationServiceWhenVectorStoreDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=false")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KnowledgeSedimentationService.class);
                });
    }

    @Test
    void shouldNotCreateKnowledgeSedimentationServiceWhenVectorStoreBeanMissing() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                // No VectorStore or EmbeddingModel beans registered
                .run(context -> {
                    assertThat(context).doesNotHaveBean(KnowledgeSedimentationService.class);
                });
    }

    // ---- Fix P0-2: RetrievalAugmentationAdvisor with config values ----

    @Test
    void shouldCreateRetrievalAugmentationAdvisorWithRagConfigValues() throws Exception {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true",
                        "snap-agent.rag.enabled=true",
                        "snap-agent.rag.top-k=7",
                        "snap-agent.rag.similarity-threshold=0.85")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
                    RetrievalAugmentationAdvisor advisor =
                            context.getBean(RetrievalAugmentationAdvisor.class);
                    // Verify topK via reflection — confirms config values are wired
                    int topK = (int) getFieldValue(advisor, "topK");
                    assertThat(topK).isEqualTo(7);
                });
    }

    @Test
    void shouldFallbackToKnowledgeConfigWhenRagDisabled() throws Exception {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true",
                        "snap-agent.rag.enabled=false",
                        "snap-agent.knowledge.max-fragments=5",
                        "snap-agent.knowledge.min-score=0.3")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
                    RetrievalAugmentationAdvisor advisor =
                            context.getBean(RetrievalAugmentationAdvisor.class);
                    // When rag.enabled=false, topK falls back to knowledge.maxFragments
                    int topK = (int) getFieldValue(advisor, "topK");
                    assertThat(topK).isEqualTo(5);
                });
    }

    @Test
    void shouldUseRagDefaultsWhenNeitherRagNorKnowledgeEnabled() throws Exception {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
                    RetrievalAugmentationAdvisor advisor =
                            context.getBean(RetrievalAugmentationAdvisor.class);
                    // rag.enabled=false (default) → falls back to knowledge.maxFragments (default=3)
                    int topK = (int) getFieldValue(advisor, "topK");
                    assertThat(topK).isEqualTo(3);
                });
    }

    // ---- Fix P0-3: AsyncCodeGraphIndex ----

    @Test
    void shouldCreateAsyncCodeGraphIndexWhenCodeGraphEnabled() throws Exception {
        java.nio.file.Path tempDir = Files.createTempDirectory("snapagent-codegraph-test");
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.code.enabled=true",
                        "snap-agent.code.project-root=" + tempDir.toString(),
                        "snap-agent.code-graph.enabled=true")
                .withBean(CodePathGuard.class, () -> new CodePathGuard(
                        tempDir.toString(), Arrays.asList(".java"), 1000, 65536L))
                .run(context -> {
                    assertThat(context).hasSingleBean(CodeGraphIndex.class);
                    CodeGraphIndex index = context.getBean(CodeGraphIndex.class);
                    assertThat(index).isInstanceOf(AsyncCodeGraphIndex.class);
                });
    }

    @Test
    void shouldAssembleAstCodeGraphBuilderByDefault() throws Exception {
        java.nio.file.Path tempDir = Files.createTempDirectory("snapagent-codegraph-test");
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.code.enabled=true",
                        "snap-agent.code.project-root=" + tempDir.toString(),
                        "snap-agent.code-graph.enabled=true")
                .withBean(CodePathGuard.class, () -> new CodePathGuard(
                        tempDir.toString(), Arrays.asList(".java"), 1000, 65536L))
                .run(context -> {
                    assertThat(context).hasSingleBean(CodeGraphBuilder.class);
                    CodeGraphBuilder builder = context.getBean(CodeGraphBuilder.class);
                    assertThat(builder).isInstanceOf(AstCodeGraphBuilder.class);
                });
    }

    @Test
    void shouldNotCreateCodeGraphBeansWhenCodeGraphDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.code-graph.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CodeGraphIndex.class);
                });
    }

    // ---- KnowledgeReloadService + KnowledgeRestController wiring ----

    @Test
    void shouldAssembleKnowledgeReloadServiceWhenVectorStoreEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.knowledge.KnowledgeReloadService.class);
                });
    }

    @Test
    void shouldAssembleKnowledgeRestController() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.web.KnowledgeRestController.class);
                });
    }

    @Test
    void shouldNotAssembleKnowledgeHotReloaderByDefault() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            cn.watsontech.snapagent.boot2x.knowledge.KnowledgeHotReloader.class);
                });
    }

    @Test
    void shouldAssembleKnowledgeHotReloaderWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true",
                        "snap-agent.knowledge.hot-reload=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            cn.watsontech.snapagent.boot2x.knowledge.KnowledgeHotReloader.class);
                });
    }

    // ---- loadKnowledgeSources safety ----

    @Test
    void shouldNotFailWhenKnowledgeDisabled() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    // Context starts cleanly; loadKnowledgeSources returns early
                    assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
                });
    }

    @Test
    void shouldNotFailWhenKnowledgeEnabledButNoSources() {
        contextRunner
                .withPropertyValues(
                        "snap-agent.enabled=true",
                        "snap-agent.vectorstore.enabled=true",
                        "snap-agent.knowledge.enabled=true")
                .withBean(VectorStore.class, () -> new InMemoryVectorStore())
                .withBean(EmbeddingModel.class, KnowledgeAutoConfigurationTest::stubEmbeddingModel)
                .run(context -> {
                    // No sources configured → loadKnowledgeSources returns early
                    assertThat(context).hasSingleBean(RetrievalAugmentationAdvisor.class);
                });
    }

    // ---- helpers ----

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
