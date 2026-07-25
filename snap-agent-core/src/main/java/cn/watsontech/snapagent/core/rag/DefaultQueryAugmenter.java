package cn.watsontech.snapagent.core.rag;

import cn.watsontech.snapagent.core.vectorstore.Document;

import java.util.List;

/**
 * Default {@link QueryAugmenter} implementation.
 *
 * <p>When documents are found, formats them into a Markdown context section
 * appended to the original query.</p>
 *
 * <p>When no documents are found and {@code allowEmptyContext=false} (default),
 * returns a "无相关知识" instruction so the LLM knows it has no context.</p>
 *
 * <p>When no documents and {@code allowEmptyContext=true}, returns the original
 * query unchanged (empty context).</p>
 */
public class DefaultQueryAugmenter implements QueryAugmenter {

    private static final String NO_KNOWLEDGE_INSTRUCTION =
            "无相关知识，请基于你自己的知识回答";

    private final boolean allowEmptyContext;

    public DefaultQueryAugmenter() {
        this(false);
    }

    public DefaultQueryAugmenter(boolean allowEmptyContext) {
        this.allowEmptyContext = allowEmptyContext;
    }

    @Override
    public String augment(String originalQuery, List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return allowEmptyContext ? originalQuery
                    : originalQuery + "\n\n" + NO_KNOWLEDGE_INSTRUCTION;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(originalQuery);
        sb.append("\n\n## 相关知识\n");
        sb.append("以下是与当前问题相关的知识片段:\n\n");
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            sb.append("### 知识片段 ").append(i + 1).append("\n");
            String source = doc.getMetadata("source");
            if (source != null) {
                sb.append("> 来源: ").append(source).append("\n\n");
            }
            sb.append(doc.getContent()).append("\n\n");
        }
        sb.append("请结合以上知识回答用户问题。\n");
        return sb.toString();
    }

    @Override
    public boolean isAllowEmptyContext() {
        return allowEmptyContext;
    }
}
