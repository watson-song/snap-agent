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
import cn.watsontech.snapagent.boot2x.codegraph.AsyncCodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.ChineseCodeGraphMessages;
import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphHotReloader;
import cn.watsontech.snapagent.boot2x.codegraph.CodeGraphTools;
import cn.watsontech.snapagent.boot2x.codegraph.H2CodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.InMemoryCodeGraphIndex;
import cn.watsontech.snapagent.boot2x.codegraph.ModuleArchitectureTools;
import cn.watsontech.snapagent.boot2x.codegraph.SimpleCodeGraphBuilder;
import cn.watsontech.snapagent.core.rag.DefaultQueryAugmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Knowledge, RAG, and code graph auto-configuration.
 *
 * <p>VectorStore-based knowledge ETL and retrieval augmentation are active
 * when {@code snap-agent.vectorstore.enabled=true}. Code graph tools are
 * active when {@code snap-agent.code-graph.enabled=true}.</p>
 *
 * <p>Fix P0-1: {@link KnowledgeSedimentationService} is wired as a bean when
 * the vector store is enabled.</p>
 *
 * <p>Fix P0-2: {@link RetrievalAugmentationAdvisor} now reads {@code snap-agent.rag}
 * and {@code snap-agent.knowledge} config values for topK, threshold, and filter.
 * An {@code @EventListener(ApplicationReadyEvent.class)} runs the ETL pipeline on
 * startup if knowledge sources are configured.</p>
 *
 * <p>Fix P0-3: The {@code CodeGraphIndex} bean is built asynchronously via
 * {@link AsyncCodeGraphIndex} so that a slow code graph build does not block
 * application startup.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
public class KnowledgeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoConfiguration.class);

    @Autowired
    private SnapAgentProperties props;

    @Autowired
    private ApplicationContext context;

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

    /**
     * Fix P0-1: Wire {@link KnowledgeSedimentationService} as a bean when the
     * vector store and embedding model are available.
     *
     * <p>Note: {@link cn.watsontech.snapagent.boot2x.autoconfig.IssueAutoConfiguration}
     * also declares a {@code KnowledgeSedimentationService} bean gated on
     * {@code snap-agent.issue-closure.enabled=true}. The {@code @ConditionalOnMissingBean}
     * on both methods ensures only one instance is created.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnBean({VectorStore.class, EmbeddingModel.class})
    @ConditionalOnMissingBean
    public KnowledgeSedimentationService knowledgeSedimentationService(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel) {
        log.info("KnowledgeSedimentationService assembled");
        return new KnowledgeSedimentationService(vectorStore, embeddingModel);
    }

    /**
     * Fix P0-2: Build {@link RetrievalAugmentationAdvisor} using config values
     * from {@code snap-agent.rag} (when enabled) or falling back to
     * {@code snap-agent.knowledge} defaults.
     */
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    @ConditionalOnBean({VectorStore.class, EmbeddingModel.class})
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            SnapAgentProperties props) {
        SnapAgentProperties.Rag ragProps = props.getRag();
        int topK = ragProps.isEnabled() ? ragProps.getTopK() : props.getKnowledge().getMaxFragments();
        double threshold = ragProps.isEnabled()
                ? ragProps.getSimilarityThreshold()
                : props.getKnowledge().getMinScore();
        String filter = ragProps.isEnabled() ? ragProps.getFilterExpression() : null;

        log.info("RetrievalAugmentationAdvisor assembled (topK={}, threshold={})", topK, threshold);
        return new RetrievalAugmentationAdvisor(
                new IdentityQueryTransformer(),
                new VectorStoreDocumentRetriever(vectorStore, threshold, filter),
                new DefaultQueryAugmenter(false),
                topK);
    }

    // ---- Code Graph (v0.8) ----

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public CodeGraphBuilder simpleCodeGraphBuilder(
            CodePathGuard codePathGuard,
            SnapAgentProperties props) {
        String scanMode = props.getCodeGraph().getScanMode();
        log.info("SimpleCodeGraphBuilder assembled (scanMode={}, scanPackages={})",
                scanMode, props.getCodeGraph().getScanPackages());

        // Extract keywords from skills for 'skills' scan mode
        Set<String> skillKeywords = new HashSet<String>();
        if ("skills".equalsIgnoreCase(scanMode)) {
            skillKeywords = extractSkillKeywords(props);
        }

        return new SimpleCodeGraphBuilder(
                codePathGuard, props.getCodeGraph().getScanPackages(),
                scanMode, skillKeywords);
    }

    /**
     * Extract keywords from all skill files for smart code graph filtering.
     * Only scans Java files that contain these keywords.
     *
     * <p>Extracts:
     * <ul>
     *   <li>Java class names (CamelCase identifiers like {@code AllocationPlanService})</li>
     *   <li>Method names (camelCase identifiers)</li>
     *   <li>Package names (dot-separated like {@code com.example.service})</li>
     *   <li>Table names (snake_case like {@code drp_allocation_plan})</li>
     *   <li>Column names from SQL examples</li>
     * </ul>
     */
    private Set<String> extractSkillKeywords(SnapAgentProperties props) {
        Set<String> keywords = new HashSet<String>();
        List<String> skillDirs = new ArrayList<String>();

        // Add builtin skills directory
        String builtinDir = props.getBuiltinSkillsDir();
        if (builtinDir != null && !builtinDir.isEmpty()) {
            skillDirs.add(builtinDir);
        }

        // Add upload skills directory
        String uploadDir = props.getUploadSkillsDir();
        if (uploadDir != null && !uploadDir.isEmpty()) {
            skillDirs.add(uploadDir);
        }

        // Scan each directory for .md files
        for (String dir : skillDirs) {
            if (dir.startsWith("classpath")) {
                // Handle classpath resources
                extractFromClasspath(dir, keywords);
            } else {
                // Handle filesystem paths
                extractFromFilesystem(dir, keywords);
            }
        }

        log.info("Extracted {} keywords from skill files for code graph filtering", keywords.size());
        return keywords;
    }

    /**
     * Extract keywords from skill files on the filesystem.
     */
    private void extractFromFilesystem(String dirPath, Set<String> keywords) {
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            log.debug("Skill directory not found: {}", dirPath);
            return;
        }

        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(file -> {
                        try {
                            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            extractKeywordsFromContent(content, keywords);
                        } catch (IOException e) {
                            log.debug("Failed to read skill file: {}", file);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan skill directory: {}", dirPath);
        }
    }

    /**
     * Extract keywords from skill files on the classpath.
     */
    private void extractFromClasspath(String classpathPattern, Set<String> keywords) {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(classpathPattern);
            for (Resource resource : resources) {
                try {
                    InputStream is = resource.getInputStream();
                    byte[] bytes = new byte[is.available()];
                    int offset = 0;
                    while (offset < bytes.length) {
                        int read = is.read(bytes, offset, bytes.length - offset);
                        if (read == -1) break;
                        offset += read;
                    }
                    is.close();
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    extractKeywordsFromContent(content, keywords);
                } catch (IOException e) {
                    log.debug("Failed to read classpath resource: {}", resource);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan classpath pattern: {}", classpathPattern);
        }
    }

    /**
     * Extract keywords from skill markdown content.
     * Extracts class names, method names, package names, table names, and column names.
     */
    private void extractKeywordsFromContent(String content, Set<String> keywords) {
        // Pattern 1: Java class names (CamelCase, typically 3+ chars, starts with uppercase)
        // Examples: AllocationPlanService, DrpAllocationPlan, SimpleCodeGraphBuilder
        Matcher classMatcher = Pattern.compile("\\b([A-Z][a-z]+(?:[A-Z][a-z]+){1,})\\b").matcher(content);
        while (classMatcher.find()) {
            String word = classMatcher.group(1);
            // Filter out common non-class words
            if (!isCommonWord(word) && word.length() >= 3) {
                keywords.add(word);
            }
        }

        // Pattern 2: Method names (camelCase, typically 2+ words)
        // Examples: getAllocationPlan, checkStatus, updateRecord
        Matcher methodMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:[A-Z][a-z0-9]+){1,})\\b").matcher(content);
        while (methodMatcher.find()) {
            String word = methodMatcher.group(1);
            if (!isCommonMethodWord(word) && word.length() >= 4) {
                keywords.add(word);
            }
        }

        // Pattern 3: Table names (snake_case, typically 3+ parts)
        // Examples: drp_allocation_plan, sys_batch_log, dws_alg_allocation_output
        Matcher tableMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:_[a-z0-9]+){2,})\\b").matcher(content);
        while (tableMatcher.find()) {
            String word = tableMatcher.group(1);
            if (word.length() >= 8) {
                keywords.add(word);
            }
        }

        // Pattern 4: Column names (snake_case, typically 2+ parts)
        // Examples: sku_code, batch_id, generate_date
        Matcher colMatcher = Pattern.compile("\\b([a-z][a-z0-9]+(?:_[a-z0-9]+)+)\\b").matcher(content);
        while (colMatcher.find()) {
            String word = colMatcher.group(1);
            if (word.length() >= 5) {
                keywords.add(word);
            }
        }

        // Pattern 5: Package names (dot-separated, 3+ parts)
        // Examples: com.watsontech.snapagent, cn.watsontech.snapagent.boot2x
        Matcher pkgMatcher = Pattern.compile("\\b([a-z][a-z0-9]+\\.[a-z][a-z0-9]+(?:\\.[a-z][a-z0-9]+)+)\\b").matcher(content);
        while (pkgMatcher.find()) {
            String word = pkgMatcher.group(1);
            if (word.length() >= 10) {
                keywords.add(word);
            }
        }
    }

    /**
     * Filter out common English words that are not class names.
     */
    private boolean isCommonWord(String word) {
        Set<String> commonWords = new HashSet<String>(Arrays.asList(
                "The", "This", "That", "When", "Where", "What", "How", "Why", "Which", "Who",
                "After", "Before", "During", "While", "Some", "Many", "All", "Each",
                "Use", "Used", "Using", "Check", "Make", "Made", "Take", "Need",
                "Show", "Find", "Get", "Set", "Run", "Stop", "Start", "Read", "Write",
                "Parse", "Build", "Data", "Date", "Time", "Name", "Type", "Code",
                "Id", "Key", "Value", "Item", "List", "Map", "Test", "Test"
        ));
        return commonWords.contains(word);
    }

    /**
     * Filter out common method names that are too generic.
     */
    private boolean isCommonMethodWord(String word) {
        Set<String> commonMethods = new HashSet<String>(Arrays.asList(
                "toString", "hashCode", "equals", "compareTo", "valueOf",
                "parseInt", "parseFloat", "parseLong", "getString", "getValue",
                "getName", "getId", "setType", "setValue", "setName", "setId",
                "isEnabled", "isEmpty", "isNull", "hasNext", "iterator",
                "contains", "indexOf", "length", "append", "insert", "delete",
                "update", "select", "query", "execute", "process"
        ));
        return commonMethods.contains(word);
    }

    /**
     * Creates the {@link CodeGraphIndex} bean.
     *
     * <p>When {@code snap-agent.code-graph.persistence=h2}, a persistent
     * {@link H2CodeGraphIndex} is created. If the H2 file already contains data
     * (e.g. pre-built in CI and baked into the image), it is loaded directly;
     * otherwise a build is triggered asynchronously.</p>
     *
     * <p>When {@code persistence=memory} (default), an {@link AsyncCodeGraphIndex}
     * wraps an {@link InMemoryCodeGraphIndex} and builds in a daemon thread.</p>
     */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodeGraphBuilder.class)
    @ConditionalOnMissingBean
    public CodeGraphIndex codeGraphIndex(CodeGraphBuilder builder, SnapAgentProperties props) {
        String persistence = props.getCodeGraph().getPersistence();
        if ("h2".equalsIgnoreCase(persistence)) {
            String h2Url = props.getCodeGraph().getH2Url();
            log.info("H2CodeGraphIndex assembled (url={})", h2Url);
            H2CodeGraphIndex h2Index = new H2CodeGraphIndex(h2Url);
            // If the DB is empty (first run), build and persist
            if (h2Index.nodeCount() == 0) {
                log.info("H2 code graph DB is empty, triggering initial build...");
                Thread buildThread = new Thread(() -> {
                    try {
                        h2Index.rebuild(builder);
                        log.info("H2 code graph build complete: {} nodes", h2Index.nodeCount());
                    } catch (Exception e) {
                        log.warn("H2 code graph build failed: {}", e.getMessage());
                    }
                }, "codegraph-h2-builder");
                buildThread.setDaemon(true);
                buildThread.start();
            } else {
                log.info("H2 code graph loaded from disk: {} nodes", h2Index.nodeCount());
            }
            return h2Index;
        }
        // Default: in-memory async build
        log.info("AsyncCodeGraphIndex scheduled (builder={})", builder.type());
        return new AsyncCodeGraphIndex(builder);
    }

    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodeGraphIndex.class)
    @ConditionalOnMissingBean
    public CodeGraphTools codeGraphTools(
            CodeGraphIndex index,
            SnapAgentProperties props) {
        String graphOutputDir = props.getUploadSkillsDir() + "/code-graphs";
        log.info("CodeGraphTools assembled (maxDepth={}, maxImpactDepth={}, graphOutput={})",
                props.getCodeGraph().getMaxDepth(), props.getCodeGraph().getMaxImpactDepth(),
                graphOutputDir);
        return new CodeGraphTools(
                index, props.getCodeGraph().getMaxDepth(),
                props.getCodeGraph().getMaxImpactDepth(),
                new ChineseCodeGraphMessages(), graphOutputDir);
    }

    /**
     * Module architecture tools: scan project packages, generate dependency
     * diagrams, store as knowledge documents.
     *
     * <p>Requires a {@link CodePathGuard} (project root access) and is
     * activated only when code-graph is enabled.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "enabled", havingValue = "true")
    @ConditionalOnBean(CodePathGuard.class)
    @ConditionalOnMissingBean
    public ModuleArchitectureTools moduleArchitectureTools(
            CodePathGuard codePathGuard,
            ObjectProvider<VectorStore> vectorStoreProvider,
            SnapAgentProperties props) {
        String knowledgeDir = props.getUploadSkillsDir() + "/knowledge";
        log.info("ModuleArchitectureTools assembled (knowledgeDir={})", knowledgeDir);
        return new ModuleArchitectureTools(
                codePathGuard, vectorStoreProvider.getIfAvailable(), knowledgeDir);
    }

    /**
     * Optional hot reloader that watches the project source root for
     * {@code .java} file changes and rebuilds the graph incrementally.
     *
     * <p>Only active when {@code snap-agent.code-graph.hot-reload-enabled=true}
     * and the source tree is accessible (local development). In k8s/CI
     * deployments where no source tree exists, this bean is not created.</p>
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "snap-agent.code-graph", name = "hot-reload-enabled", havingValue = "true")
    @ConditionalOnBean({CodeGraphIndex.class, CodeGraphBuilder.class})
    @ConditionalOnMissingBean
    public CodeGraphHotReloader codeGraphHotReloader(
            CodeGraphIndex index,
            CodeGraphBuilder builder,
            CodePathGuard codePathGuard,
            SnapAgentProperties props) {
        Path watchRoot = codePathGuard.getProjectRoot();
        long pollMs = props.getCodeGraph().getHotReloadPollMs();
        log.info("CodeGraphHotReloader assembled (watchRoot={}, pollMs={})", watchRoot, pollMs);
        CodeGraphHotReloader reloader = new CodeGraphHotReloader(watchRoot, index, builder, pollMs);
        reloader.start();
        return reloader;
    }

    // ---- ETL startup hook (P0-2) ----

    /**
     * Fix P0-2: Run the knowledge ETL pipeline on application startup if
     * knowledge sources are configured.
     *
     * <p>Iterates over each configured {@link SnapAgentProperties.KnowledgeSourceConfig}
     * and runs {@link KnowledgeETLPipeline#runAll} on the resolved directory.
     * Failures for individual sources are logged and do not prevent other
     * sources from loading.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadKnowledgeSources() {
        if (!props.getKnowledge().isEnabled() || props.getKnowledge().getSources().isEmpty()) {
            return;
        }
        KnowledgeETLPipeline pipeline = context.getBeanProvider(KnowledgeETLPipeline.class)
                .getIfAvailable();
        if (pipeline == null) {
            log.warn("Knowledge sources configured but KnowledgeETLPipeline bean not found; skipping ETL");
            return;
        }
        for (SnapAgentProperties.KnowledgeSourceConfig source : props.getKnowledge().getSources()) {
            try {
                Path dir = resolveSourceDir(source.getDir());
                if (dir == null) {
                    log.warn("Knowledge source dir is null or empty, skipping: {}", source.getDir());
                    continue;
                }
                int loaded = pipeline.runAll(dir);
                log.info("Knowledge ETL loaded {} documents from {}", loaded, source.getDir());
            } catch (Exception e) {
                log.warn("Failed to load knowledge source {}: {}", source.getDir(), e.getMessage());
            }
        }
    }

    /**
     * Resolve a knowledge source directory path.
     *
     * <p>Handles {@code classpath:} and {@code classpath*:} prefixes by
     * resolving matching resources and copying them to a temporary directory
     * (so that {@link KnowledgeETLPipeline#runAll} can walk the file tree).
     * Plain filesystem paths are returned as-is.</p>
     *
     * @param dir the configured directory path (may be {@code classpath:...} or a filesystem path)
     * @return resolved {@link Path}, or {@code null} if the input is empty
     * @throws IOException if classpath resources cannot be resolved or copied
     */
    Path resolveSourceDir(String dir) throws IOException {
        if (dir == null || dir.isEmpty()) {
            return null;
        }

        if (dir.startsWith("classpath:") || dir.startsWith("classpath*:")) {
            return resolveClasspathDir(dir);
        }

        // Filesystem path
        return Paths.get(dir);
    }

    /**
     * Resolves a {@code classpath:} or {@code classpath*:} directory by copying
     * all matching {@code .md} resources into a temporary directory tree.
     */
    private Path resolveClasspathDir(String classpathDir) throws IOException {
        String basePath;
        if (classpathDir.startsWith("classpath*:")) {
            basePath = classpathDir.substring("classpath*:".length());
        } else {
            basePath = classpathDir.substring("classpath:".length());
        }
        // Strip leading slash for relative classpath resolution
        if (basePath.startsWith("/")) {
            basePath = basePath.substring(1);
        }
        if (!basePath.endsWith("/")) {
            basePath = basePath + "/";
        }

        String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + basePath + "**/*.md";
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(pattern);

        if (resources.length == 0) {
            log.warn("No .md resources found at classpath:{}", basePath);
            return null;
        }

        Path tempDir = Files.createTempDirectory("snapagent-knowledge-");
        for (Resource res : resources) {
            String relativePath = extractRelativePath(res, basePath);
            Path target = tempDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            try (InputStream is = res.getInputStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.debug("Copied {} classpath resource(s) to {}", resources.length, tempDir);
        return tempDir;
    }

    /**
     * Extracts the relative path of a resource relative to the base classpath directory.
     */
    private String extractRelativePath(Resource res, String basePath) {
        String url;
        try {
            url = res.getURL().toString();
        } catch (IOException e) {
            return res.getFilename() != null ? res.getFilename() : "unknown.md";
        }
        // Find the base path in the URL and return everything after it
        int idx = url.indexOf(basePath);
        if (idx >= 0) {
            return url.substring(idx + basePath.length());
        }
        // Fallback: use filename
        return res.getFilename() != null ? res.getFilename() : "unknown.md";
    }
}
