package cn.watsontech.snapagent.boot2x.knowledge;

import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.knowledge.KnowledgeSearcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Simple keyword-overlap scorer for {@link KnowledgeSearcher}.
 *
 * <p>No external NLP dependencies. Tokenizes the query and fragment text,
 * then computes an overlap ratio. Title matches are weighted 2× to boost
 * fragments whose title directly references the queried topic.</p>
 *
 * <p><b>Tokenization:</b></p>
 * <ul>
 *   <li><b>English/Latin:</b> split on whitespace and punctuation, lowercased,
 *       tokens shorter than 2 characters are dropped (filters "a", "of", etc.).</li>
 *   <li><b>Chinese (CJK):</b> 2-character bigrams (n-gram) — e.g.
 *       "补货策略" → ["补货", "货策", "策略"]. Single CJK characters are kept
 *       only when the text has no adjacent CJK char (standalone).</li>
 * </ul>
 *
 * <p><b>Scoring formula:</b></p>
 * <pre>
 *   score = (titleHits × 2 + contentHits) / (queryTokenCount × 2)
 * </pre>
 * Clamped to [0.0, 1.0].
 */
public class SimpleKeywordSearcher implements KnowledgeSearcher {

    @Override
    public double score(String query, KnowledgeFragment fragment) {
        if (query == null || query.isEmpty() || fragment == null) {
            return 0.0;
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> querySet = new HashSet<String>(queryTokens);
        List<String> titleTokens = tokenize(fragment.getTitle());
        List<String> contentTokens = tokenize(fragment.getContent());

        Set<String> titleSet = new HashSet<String>(titleTokens);
        Set<String> contentSet = new HashSet<String>(contentTokens);

        int titleHits = 0;
        int contentHits = 0;
        for (String token : querySet) {
            if (titleSet.contains(token)) {
                titleHits++;
            }
            if (contentSet.contains(token)) {
                contentHits++;
            }
        }

        int queryTokenCount = querySet.size();
        double raw = (double) (titleHits * 2 + contentHits) / (queryTokenCount * 2.0);
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /**
     * Tokenize text into lowercase tokens. Mixes Latin word splitting with CJK bigrams.
     *
     * @param text the text to tokenize
     * @return list of distinct tokens (order preserved for debugging)
     */
    List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }

        StringBuilder currentWord = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                // Flush any pending Latin word
                if (currentWord.length() > 0) {
                    addToken(tokens, currentWord.toString());
                    currentWord = new StringBuilder();
                }
                // CJK bigram: combine with next char if it's also CJK
                // Overlapping bigrams (advance by 1, not 2) so "补货策略" → ["补货","货策","策略"]
                if (i + 1 < text.length() && isCjk(text.charAt(i + 1))) {
                    tokens.add(text.substring(i, i + 2));
                } else {
                    // Standalone CJK char (no adjacent CJK) — keep as single token
                    tokens.add(String.valueOf(c));
                }
            } else if (Character.isLetterOrDigit(c)) {
                currentWord.append(Character.toLowerCase(c));
            } else {
                // Whitespace / punctuation — flush Latin word
                if (currentWord.length() > 0) {
                    addToken(tokens, currentWord.toString());
                    currentWord = new StringBuilder();
                }
            }
        }
        if (currentWord.length() > 0) {
            addToken(tokens, currentWord.toString());
        }
        return tokens;
    }

    private void addToken(List<String> tokens, String token) {
        // Drop very short Latin tokens (< 2 chars) — acts as a crude stopword filter.
        // CJK bigrams are always >= 2 chars (they're 2 chars), and standalone CJK is 1 char.
        if (token.length() >= 2) {
            tokens.add(token);
        }
    }

    /**
     * Checks whether a character is CJK (Chinese/Japanese/Korean).
     */
    private boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')    // CJK Unified Ideographs
                || (c >= '\u3400' && c <= '\u4DBF')  // CJK Extension A
                || (c >= '\uF900' && c <= '\uFAFF'); // CJK Compatibility Ideographs
    }
}
