package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.llm.LlmClient;
import cn.watsontech.snapagent.core.llm.LlmEventSink;
import cn.watsontech.snapagent.core.llm.LlmRequest;
import cn.watsontech.snapagent.core.llm.Message;
import cn.watsontech.snapagent.core.skill.SkillRegistry;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the anchor Q&amp;A flow: preprocess (summary + classify in parallel)
 * and execute (pick up cached results, build augmented message, call main LLM).
 *
 * <p>The preprocess phase runs the summarizer and classifier concurrently using
 * {@link CompletableFuture}. Results are stored in an in-memory map keyed by
 * preprocessId, so the subsequent execute call can pick them up.</p>
 *
 * <p>On any failure (timeout, LLM error), the orchestrator degrades gracefully:
 * the execute path falls back to inline recomputation or the general LLM path.</p>
 */
public class AnchorOrchestrator {

    private static final int MAIN_LLM_MAX_TOKENS = 4096;

    private final LlmClient llmClient;
    private final AnchorSummaryCache summaryCache;
    private final AnchorContextSummarizer summarizer;
    private final AnchorSkillClassifier classifier;
    private final SkillRegistry skillRegistry;
    private final SnapAgentProperties.Anchor props;

    private final ConcurrentMap<String, PreprocessEntry> preprocessStore = new ConcurrentHashMap<>();

    public AnchorOrchestrator(LlmClient llmClient, AnchorSummaryCache summaryCache,
                               AnchorContextSummarizer summarizer, AnchorSkillClassifier classifier,
                               SkillRegistry skillRegistry, SnapAgentProperties.Anchor props) {
        this.llmClient = llmClient;
        this.summaryCache = summaryCache;
        this.summarizer = summarizer;
        this.classifier = classifier;
        this.skillRegistry = skillRegistry;
        this.props = props;
    }

    /**
     * Starts background preprocess: summary + classify in parallel.
     *
     * @param anchor   the page-section context
     * @param question the user's question (used by the classifier)
     * @return a handle containing the preprocessId
     */
    public PreprocessResult preprocess(AnchorContext anchor, String question) {
        String preprocessId = UUID.randomUUID().toString();

        CompletableFuture<String> summaryFuture = CompletableFuture.supplyAsync(() ->
                getSummary(anchor));

        CompletableFuture<ClassifyResult> classifyFuture = CompletableFuture.supplyAsync(() ->
                classifier.classify(question, anchor.getContent()));

        preprocessStore.put(preprocessId, new PreprocessEntry(summaryFuture, classifyFuture));
        return new PreprocessResult(preprocessId);
    }

    /**
     * Waits for the preprocess to complete within the given timeout.
     * Does not throw on timeout — just returns.
     */
    public void awaitPreprocess(String preprocessId, long timeoutMs) {
        PreprocessEntry entry = preprocessStore.get(preprocessId);
        if (entry == null) return;
        try {
            CompletableFuture.allOf(entry.summaryFuture, entry.classifyFuture)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout or interruption — return gracefully
        }
    }

    /**
     * Executes the anchor Q&amp;A: picks up precomputed summary + classify result
     * (if available), builds an augmented user message, and streams the main LLM
     * response to the given sink.
     *
     * @param anchor       the page-section context
     * @param question     the user's question
     * @param preprocessId the preprocess handle (may be null)
     * @param mainSink     the sink to receive the main LLM response
     */
    public void executeWithAnchor(AnchorContext anchor, String question,
                                   String preprocessId, LlmEventSink mainSink) {
        // Get summary (from preprocess, cache, or compute inline)
        String summary = resolveSummary(anchor, preprocessId);

        // Get classify result (from preprocess or compute inline)
        ClassifyResult classifyResult = resolveClassifyResult(question, anchor, preprocessId);

        // Build augmented message with the summary (or original content)
        String augmentedMessage = buildAugmentedMessage(anchor, summary, question);

        // Call main LLM (general LLM path; skill routing is a future enhancement)
        LlmRequest mainRequest = buildMainRequest(augmentedMessage);
        llmClient.stream(mainRequest, mainSink, "anchor-main-" + Thread.currentThread().getId());
    }

    /** Resolves the summary from preprocess, cache, or inline computation. */
    private String resolveSummary(AnchorContext anchor, String preprocessId) {
        if (preprocessId != null) {
            PreprocessEntry entry = preprocessStore.get(preprocessId);
            if (entry != null && entry.summaryFuture.isDone()) {
                try {
                    return entry.summaryFuture.get();
                } catch (Exception e) {
                    // fall through to cache/inline
                }
            }
        }
        return getSummary(anchor);
    }

    /** Gets the summary from cache or computes it inline. */
    private String getSummary(AnchorContext anchor) {
        if (!anchor.needsSummary(props.getSummaryThresholdChars())) {
            return anchor.getContent();
        }
        return summaryCache.getOrCreate(anchor.getContent(),
                () -> summarizer.summarize(anchor.getContent()));
    }

    /** Resolves the classify result from preprocess or computes it inline. */
    private ClassifyResult resolveClassifyResult(String question, AnchorContext anchor,
                                                  String preprocessId) {
        if (preprocessId != null) {
            PreprocessEntry entry = preprocessStore.get(preprocessId);
            if (entry != null && entry.classifyFuture.isDone()) {
                try {
                    return entry.classifyFuture.get();
                } catch (Exception e) {
                    // fall through to inline
                }
            }
        }
        return classifier.classify(question, anchor.getContent());
    }

    /** Builds the augmented user message with anchor context + summary. */
    private String buildAugmentedMessage(AnchorContext anchor, String summary, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户正在浏览页面 \"")
                .append(anchor.getPageUrl() != null ? anchor.getPageUrl() : "(unknown)")
                .append("\" 的 \"").append(anchor.getName()).append("\" 区块。\n\n");
        if (anchor.isTruncated()) {
            sb.append("（内容已截断，原始长度 ")
                    .append(anchor.getOriginalLength()).append(" 字符）\n\n");
        }
        sb.append("区块内容：\n").append(summary != null ? summary : anchor.getContent());
        sb.append("\n\n用户提问：").append(question);
        return sb.toString();
    }

    private LlmRequest buildMainRequest(String augmentedMessage) {
        return new LlmRequest(
                "你是SnapAgent助手，帮助用户理解页面内容。请根据页面区块内容和用户提问给出回答。",
                Collections.singletonList(Message.user(augmentedMessage)),
                Collections.emptyList(),
                null,
                MAIN_LLM_MAX_TOKENS,
                true
        );
    }

    /** Internal holder for in-flight preprocess results. */
    private static class PreprocessEntry {
        final CompletableFuture<String> summaryFuture;
        final CompletableFuture<ClassifyResult> classifyFuture;

        PreprocessEntry(CompletableFuture<String> summaryFuture,
                         CompletableFuture<ClassifyResult> classifyFuture) {
            this.summaryFuture = summaryFuture;
            this.classifyFuture = classifyFuture;
        }
    }
}
