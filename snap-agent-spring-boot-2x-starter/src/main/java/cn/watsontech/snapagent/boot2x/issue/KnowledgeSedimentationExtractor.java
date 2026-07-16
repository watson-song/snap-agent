package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;

import java.util.Collections;
import java.util.List;

/**
 * Extracts a {@link KnowledgeFragment} from an {@link IssueClosure},
 * sedimenting the "problem -> root cause -> solution" triad into the
 * v0.7 knowledge base.
 *
 * <p>The extracted fragment follows this structure:
 * <ul>
 *   <li><b>title</b>: {@code "问题: " + truncated user query (60 chars)}</li>
 *   <li><b>content</b>: Markdown with {@code ## 问题} / {@code ## 根因} /
 *       {@code ## 解决方案} / {@code ## 验证结果} sections</li>
 *   <li><b>source</b>: {@code "sedimentation:" + issueId}</li>
 *   <li><b>metadata</b>: {@code {category: "经验沉淀"}}</li>
 * </ul>
 * </p>
 */
public class KnowledgeSedimentationExtractor {

    private static final int MAX_TITLE_QUERY_LENGTH = 60;

    /**
     * Extracts a knowledge fragment from the given issue closure.
     *
     * @param issue the issue closure to extract from (must not be null)
     * @return a {@link KnowledgeFragment} containing the sedimented knowledge
     */
    public KnowledgeFragment extract(IssueClosure issue) {
        String title = "问题: " + truncate(issue.getUserQuery(), MAX_TITLE_QUERY_LENGTH);

        StringBuilder content = new StringBuilder();
        content.append("## 问题\n").append(issue.getUserQuery()).append("\n\n");
        content.append("## 根因\n").append(issue.getRootCause()).append("\n\n");
        content.append("## 解决方案\n");
        if (issue.getSelectedSolution() != null) {
            content.append(issue.getSelectedSolution()).append("\n");
        } else if (issue.getSolutions() != null && !issue.getSolutions().isEmpty()) {
            List<String> solutions = issue.getSolutions();
            for (String s : solutions) {
                content.append("- ").append(s).append("\n");
            }
        }
        if (issue.getVerificationResult() != null) {
            content.append("\n## 验证结果\n").append(issue.getVerificationResult()).append("\n");
        }

        return new KnowledgeFragment(
                title,
                content.toString(),
                "sedimentation:" + issue.getIssueId(),
                Collections.singletonMap("category", "经验沉淀")
        );
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
