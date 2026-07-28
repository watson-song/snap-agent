package cn.watsontech.snapagent.core.security;

import cn.watsontech.snapagent.core.graph.GraphState;
import cn.watsontech.snapagent.core.graph.StateKeys;
import cn.watsontech.snapagent.core.graph.advisor.Advisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Advisor (order=50) that filters sensitive words from user prompts
 * before node execution and sanitizes LLM output after node execution.
 *
 * <p>Whitelist words take priority over sensitive words — if a whitelist
 * word contains a sensitive word, the sensitive word is not replaced.</p>
 *
 * <p>If sanitization throws, logs WARN and does not interrupt execution.</p>
 */
public class SafeGuardAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(SafeGuardAdvisor.class);

    private final List<String> sensitiveWords;
    private final List<String> whitelist;
    private final String replacement;

    public SafeGuardAdvisor(List<String> sensitiveWords) {
        this(sensitiveWords, java.util.Collections.<String>emptyList(), "***");
    }

    public SafeGuardAdvisor(List<String> sensitiveWords, List<String> whitelist, String replacement) {
        this.sensitiveWords = sensitiveWords != null ? sensitiveWords : java.util.Collections.<String>emptyList();
        this.whitelist = whitelist != null ? whitelist : java.util.Collections.<String>emptyList();
        this.replacement = replacement != null ? replacement : "***";
    }

    @Override
    public int getOrder() { return 50; }

    @Override
    public String getName() { return "safe-guard"; }

    @Override
    public GraphState beforeNode(String nodeName, GraphState state, Object ctx) {
        String query = state.get(StateKeys.USER_QUERY);
        if (query == null) {
            return state;
        }
        try {
            String sanitized = sanitize(query);
            if (!sanitized.equals(query)) {
                log.warn("SafeGuard replaced sensitive word in prompt");
                return state.with(StateKeys.USER_QUERY, sanitized);
            }
        } catch (RuntimeException e) {
            log.warn("SafeGuard beforeNode failed", e);
        }
        return state;
    }

    @Override
    public GraphState afterNode(String nodeName, GraphState state, Object ctx) {
        String thought = state.get(StateKeys.THOUGHT);
        if (thought == null) {
            return state;
        }
        try {
            String sanitized = sanitize(thought);
            if (!sanitized.equals(thought)) {
                log.warn("SafeGuard replaced sensitive content in LLM output");
                return state.with(StateKeys.THOUGHT, sanitized);
            }
        } catch (RuntimeException e) {
            log.warn("SafeGuard afterNode failed", e);
        }
        return state;
    }

    private String sanitize(String text) {
        String result = text;
        for (String word : sensitiveWords) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (isWhitelisted(result, word)) {
                continue;
            }
            result = result.replace(word, replacement);
        }
        return result;
    }

    private boolean isWhitelisted(String text, String sensitiveWord) {
        for (String wl : whitelist) {
            if (wl == null || wl.isEmpty()) {
                continue;
            }
            if (wl.contains(sensitiveWord) && text.contains(wl)) {
                return true;
            }
        }
        return false;
    }
}
