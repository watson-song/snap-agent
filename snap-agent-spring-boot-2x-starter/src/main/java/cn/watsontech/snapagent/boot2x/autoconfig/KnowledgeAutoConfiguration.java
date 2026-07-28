package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.boot2x.tool.CodePathGuard;
import cn.watsontech.snapagent.core.codegraph.CodeGraphBuilder;
import cn.watsontech.snapagent.core.codegraph.CodeGraphIndex;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.rag.RetrievalAugmentationAdvisor;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import cn.watsontech.snapagent.boot2x.knowledge.IdentityQueryTransformer;
import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeETLPipeline;
import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService;
import cn.watsontech.snapagent.boot2x.knowledge.VectorStoreDocumentRetriever;
import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.InMemoryCodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.SimpleCodeGraphBuilder;
import cn.watsontech.snapagent.core.rag.DefaultQueryAugmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knowledge, RAG, and code graph auto-configuration.
 *
 * <p>VectorStore-based knowledge ETL and retrieval augmentation are active
 * when {@code snap-agent.vectorstore.enabled=true}. Code graph tools are
 * active when {@code snap-agent.code-graph.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class KnowledgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoConfiguration.class);

    // ---- Knowledge (v2.x) ----

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public KnowledgeETLPipeline knowledgeETLPipeline(
            ObjectProvider<VectorStore> vectorStoreProvider,
            ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        log.info("KnowledgeETLPipeline assembled");
        return new KnowledgeETLPipeline(
                vectorStoreProvider.getIfAvailable(),
                embeddingModelProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @ConditionalOnBean({VectorStore.class, EmbeddingModel.class})
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel) {
        log.info("RetrievalAugmentationAdvisor assembled");
        return new RetrievalAugmentationAdvisor(
                new IdentityQueryTransformer(),
                new VectorStoreDocumentRetriever(vectorStore),
                new DefaultQueryAugmenter(false));
    }

    // ---- Code Graph (v0.8) ----

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public CodeGraphBuilder simpleCodeGraphBuilder(
            CodePathGuard codePathGuard,
            SnapAgentProperties props) {
        log.info("SimpleCodeGraphBuilder assembled (scanPackages={})",
                props.getCodeGraph().getScanPackages());
        return new SimpleCodeGraphBuilder(
                codePathGuard, props.getCodeGraph().getScanPackages());
    }

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodeGraphBuilder.class)
    @ConditionalOnMissingBean
    public CodeGraphIndex inMemoryCodeGraphIndex(CodeGraphBuilder builder) {
        cn.watsontech.snapagent.core.codegraph.CodeGraph graph = builder.build();
        log.info("InMemoryCodeGraphIndex assembled ({} nodes, {} edges)",
                graph.nodeCount(), graph.edgeCount());
        return new InMemoryCodeGraphIndex(graph);
    }

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodeGraphIndex.class)
    @ConditionalOnMissingBean
    public CodeGraphTools codeGraphTools(
            CodeGraphIndex index,
            SnapAgentProperties props) {
        log.info("CodeGraphTools assembled (maxDepth={}, maxImpactDepth={})",
                props.getCodeGraph().getMaxDepth(), props.getCodeGraph().getMaxImpactDepth());
        return new CodeGraphTools(
                index, props.getCodeGraph().getMaxDepth(),
                props.getCodeGraph().getMaxImpactDepth());
    }
}
