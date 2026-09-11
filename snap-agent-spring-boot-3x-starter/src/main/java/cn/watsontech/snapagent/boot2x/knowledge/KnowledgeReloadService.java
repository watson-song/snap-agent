package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.boot2x.domain.DomainKnowledgeLoader;
import cn.watsontech.snapagent.core.domain.DomainKnowledgeIndex;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;/**
 * Coordinates the knowledge reload flow: clears the vector store and domain
 * index, then re-ingests knowledge sources and domain knowledge from disk.
 *
 * <p>This is the engine behind "code changes → update the knowledge base with
 * the built-in tool". It is invoked by {@code POST /knowledge/reload} and by
 * the optional hot reloader when {@code .md} knowledge files change.</p>
 *
 * <p>Reload semantics (all idempotent):</p>
 * <ul>
 *   <li>{@link VectorStore#clear()} removes all prior documents so reloads do
 *       not accumulate stale/duplicate entries</li>
 *   <li>{@link DomainKnowledgeIndex#clear()} drops old concepts before re-index</li>
 *   <li>Knowledge sources (from {@code snap-agent.knowledge.sources}) are re-run
 *       through {@link KnowledgeETLPipeline#runAll(Path)}</li>
 *   <li>Domain knowledge files ({@code upload-skills-dir}/domain-knowledge/*.md)
 *       are re-parsed via {@link DomainKnowledgeLoader#loadFromDirectory(Path)}</li>
 * </ul>
 */
public class KnowledgeReloadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReloadService.class);

    private final VectorStore vectorStore;
    private final DomainKnowledgeIndex domainIndex;
    private final KnowledgeETLPipeline etlPipeline;
    private final DomainKnowledgeLoader domainLoader;
    private final SnapAgentProperties props;

    public KnowledgeReloadService(VectorStore vectorStore,
                                  DomainKnowledgeIndex domainIndex,
                                  KnowledgeETLPipeline etlPipeline,
                                  DomainKnowledgeLoader domainLoader,
                                  SnapAgentProperties props) {
        this.vectorStore = vectorStore;
        this.domainIndex = domainIndex;
        this.etlPipeline = etlPipeline;
        this.domainLoader = domainLoader;
        this.props = props;
    }

    /**
     * Result of a reload operation, returned to the caller for observability.
     */
    public static class ReloadResult {
        private final int documentsLoaded;
        private final int conceptsLoaded;
        private final boolean vectorStoreCleared;
        private final boolean domainIndexCleared;

        public ReloadResult(int documentsLoaded, int conceptsLoaded,
                            boolean vectorStoreCleared, boolean domainIndexCleared) {
            this.documentsLoaded = documentsLoaded;
            this.conceptsLoaded = conceptsLoaded;
            this.vectorStoreCleared = vectorStoreCleared;
            this.domainIndexCleared = domainIndexCleared;
        }

        public int getDocumentsLoaded() { return documentsLoaded; }
        public int getConceptsLoaded() { return conceptsLoaded; }
        public boolean isVectorStoreCleared() { return vectorStoreCleared; }
        public boolean isDomainIndexCleared() { return domainIndexCleared; }
    }

    /**
     * Clear and re-ingest all knowledge.
     *
     * @return summary counts, or {@code null} if nothing was reloaded (no
     *         vector store / pipeline / loader available)
     */
    public ReloadResult reload() {
        boolean vsCleared = false;
        boolean idxCleared = false;
        int documents = 0;
        int concepts = 0;

        // 1. Clear the vector store (idempotent, removes stale/duplicate docs)
        if (vectorStore != null) {
            try {
                vectorStore.clear();
                vsCleared = true;
            } catch (RuntimeException e) {
                log.warn("VectorStore.clear() failed: {}", e.getMessage());
            }
        }

        // 2. Clear the domain index
        if (domainIndex != null) {
            domainIndex.clear();
            idxCleared = true;
        }

        // 3. Re-ingest knowledge sources via ETL
        if (etlPipeline != null && vectorStore != null
                && props.getKnowledge().isEnabled()
                && props.getKnowledge().getSources() != null) {
            for (SnapAgentProperties.KnowledgeSourceConfig source : props.getKnowledge().getSources()) {
                try {
                    Path dir = resolveSourceDir(source.getDir());
                    if (dir == null) continue;
                    documents += etlPipeline.runAll(dir);
                } catch (Exception e) {
                    log.warn("Failed to reload knowledge source {}: {}",
                            source.getDir(), e.getMessage());
                }
            }
        }

        // 4. Re-ingest domain knowledge
        if (domainLoader != null && domainIndex != null) {
            Path dir = resolveDomainKnowledgeDir();
            try {
                List<?> loaded = domainLoader.loadFromDirectory(dir);
                concepts = loaded.size();
            } catch (RuntimeException e) {
                log.warn("Failed to reload domain knowledge from {}: {}",
                        dir, e.getMessage());
            }
        }

        if (vectorStore == null && domainIndex == null) {
            // Nothing to clear and nothing to re-ingest into
            return null;
        }

        log.info("Knowledge reload complete: {} documents, {} concepts (vsCleared={}, idxCleared={})",
                documents, concepts, vsCleared, idxCleared);
        return new ReloadResult(documents, concepts, vsCleared, idxCleared);
    }

    /**
     * Resolve a knowledge source directory path.
     *
     * <p>Only filesystem directories are re-loaded on change (classpath sources
     * are packaged in the JAR and already loaded at startup — they do not change
     * at runtime). {@code classpath:} sources return {@code null} to skip.</p>
     */
    private Path resolveSourceDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        if (dir.startsWith("classpath:") || dir.startsWith("classpath*:")) {
            return null;
        }
        Path path = Paths.get(dir);
        return Files.isDirectory(path) ? path : null;
    }

    private Path resolveDomainKnowledgeDir() {
        return Paths.get(props.getUploadSkillsDir()).resolve("domain-knowledge");
    }
}
