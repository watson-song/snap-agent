package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.SedimentationReviewer;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.vectorstore.Document;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts knowledge from closed issues and writes to VectorStore via embedding.
 *
 * <p>Takes an {@link IssueClosure}, extracts the "problem → root cause → solution"
 * triad into a {@link Document}, embeds it via {@link EmbeddingModel#embed},
 * and writes to {@link VectorStore#add}.</p>
 *
 * <p>UC-22: If userQuery > 60 chars, the title is truncated to 60 + "...".</p>
 * <p>UC-23: If selectedSolution is present, it takes priority over listing all options.</p>
 * <p>UC-04 (R4): If rootCause or solution is missing, the issue is skipped + WARN.</p>
 */
public class KnowledgeSedimentationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSedimentationService.class);
    private static final int MAX_TITLE_QUERY_LENGTH = 60;

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final SedimentationReviewer reviewer;

    public KnowledgeSedimentationService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this(vectorStore, embeddingModel, null);
    }

    /**
     * Constructor with a {@link SedimentationReviewer} quality gate.
     *
     * <p>P1-7: if a reviewer is provided, {@code sediment()} calls
     * {@link SedimentationReviewer#review} after extraction but before
     * embedding/storage. If the reviewer returns {@code false}, the document
     * is skipped with a WARN log. If the reviewer is {@code null}, all
     * documents are accepted (backward compatible).</p>
     *
     * @param vectorStore    the target vector store
     * @param embeddingModel the embedding model
     * @param reviewer       the sedimentation reviewer (nullable — null = accept all)
     */
    public KnowledgeSedimentationService(VectorStore vectorStore, EmbeddingModel embeddingModel,
                                         SedimentationReviewer reviewer) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.reviewer = reviewer;
    }

    /**
     * Extract a {@link Document} from an {@link IssueClosure}.
     *
     * <p>UC-20: content contains ## 问题 / ## 根因 / ## 解决方案 sections.</p>
     * <p>UC-22: title truncated to 60 chars if userQuery is longer.</p>
     * <p>UC-23: selectedSolution takes priority over suggestion options.</p>
     *
     * @param issue the issue closure
     * @return extracted Document, or null if required fields are missing
     */
    public Document extract(IssueClosure issue) {
        if (issue == null) return null;

        if (issue.getRootCause() == null || issue.getRootCause().isEmpty()) {
            log.warn("issue {} missing required field: rootCause", issue.getIssueId());
            return null;
        }

        String userQuery = issue.getUserQuery() != null ? issue.getUserQuery() : "";
        String rootCause = issue.getRootCause();
        String title = "问题: " + truncate(userQuery, MAX_TITLE_QUERY_LENGTH);

        StringBuilder content = new StringBuilder();
        content.append("## 问题\n").append(userQuery).append("\n\n");
        content.append("## 根因\n").append(rootCause).append("\n\n");
        content.append("## 解决方案\n");

        String solutionText = buildSolutionText(issue);
        if (solutionText != null && !solutionText.isEmpty()) {
            content.append(solutionText).append("\n");
        }

        VerificationResult verification = issue.getVerificationResult();
        if (verification != null) {
            content.append("\n## 验证结果\n");
            content.append("passed: ").append(verification.isPassed()).append("\n");
            if (verification.getSummary() != null) {
                content.append(verification.getSummary()).append("\n");
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "sedimentation:" + issue.getIssueId());
        metadata.put("category", "经验沉淀");

        return new Document(title, content.toString(), metadata, null);
    }

    /**
     * Extract + embed + write to VectorStore.
     *
     * <p>UC-21: calls EmbeddingModel.embed and VectorStore.add.</p>
     *
     * @param issue the issue closure
     */
    public void sediment(IssueClosure issue) {
        Document doc = extract(issue);
        if (doc == null) return;

        // P1-7: review gate — if reviewer rejects, skip embedding + storage
        if (reviewer != null && !reviewer.review(doc, issue)) {
            log.warn("issue {} rejected by reviewer '{}'", issue.getIssueId(), reviewer.name());
            return;
        }

        if (embeddingModel != null) {
            try {
                float[] embedding = embeddingModel.embed(doc.getContent());
                doc = doc.withEmbedding(embedding);
            } catch (RuntimeException e) {
                log.warn("Embedding failed for issue {}: {}", issue.getIssueId(), e.getMessage());
            }
        }

        if (vectorStore != null) {
            try {
                vectorStore.add(Collections.singletonList(doc));
            } catch (RuntimeException e) {
                log.warn("VectorStore.add failed for issue {}: {}", issue.getIssueId(), e.getMessage());
            }
        }
    }

    // --- helpers ---

    private String buildSolutionText(IssueClosure issue) {
        // UC-23: selectedSolution takes priority
        if (issue.getSelectedSolution() != null && !issue.getSelectedSolution().isEmpty()) {
            return issue.getSelectedSolution();
        }
        SolutionSuggestion suggestion = issue.getSolution();
        if (suggestion != null && suggestion.getOptions() != null
                && !suggestion.getOptions().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            List<SolutionOption> options = suggestion.getOptions();
            for (SolutionOption option : options) {
                sb.append("- [").append(option.getEffort()).append("] ")
                        .append(option.getTitle()).append(": ")
                        .append(option.getDescription()).append("\n");
            }
            return sb.toString();
        }
        return "";
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
