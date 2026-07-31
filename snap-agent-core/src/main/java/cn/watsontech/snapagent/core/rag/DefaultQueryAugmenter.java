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

    /**
     * CJK-aware token estimator (P2-16).
     *
     * <p>Replaces the crude {@code chars / 3.5} heuristic with a smarter rule:
     * <ul>
     *   <li>Each CJK character (CJK Unified Ideographs, Hiragana, Katakana,
     *       Hangul) counts as 1 token.</li>
     *   <li>Each run of ASCII letters/digits (an English word) counts as 1 token.</li>
     *   <li>Standalone punctuation/symbols count as 1 token each.</li>
     * </ul>
     *
     * <p>This is significantly more accurate for Chinese text, where one
     * character ≈ one token, while still giving a reasonable estimate for
     * English (one word ≈ one token).</p>
     *
     * @param text input text (may be null/empty)
     * @return estimated token count
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (isCjk(c)) {
                tokens++;
                i++;
            } else if (Character.isLetterOrDigit(c)) {
                // Consume a run of letters/digits (one English word token).
                while (i < len) {
                    char ch = text.charAt(i);
                    if (Character.isLetterOrDigit(ch) && !isCjk(ch)) {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens++;
            } else {
                // Punctuation or symbol — count as a single token.
                tokens++;
                i++;
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK Unified Ideographs
                || (c >= 0x3400 && c <= 0x4DBF)  // CJK Extension A
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK Compatibility Ideographs
                || (c >= 0x3040 && c <= 0x30FF)  // Hiragana + Katakana
                || (c >= 0xAC00 && c <= 0xD7AF); // Hangul Syllables
    }
}
