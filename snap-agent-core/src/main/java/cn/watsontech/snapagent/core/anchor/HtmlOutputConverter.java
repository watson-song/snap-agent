package cn.watsontech.snapagent.core.anchor;

/**
 * Converts LLM raw output into safe, structured HTML for anchor injection.
 *
 * <p>Processing pipeline:
 * <ol>
 *   <li>{@code stripThinking} — removes LLM reasoning prefix before first HTML tag</li>
 *   <li>{@code sanitize} — removes dangerous tags (script, iframe, object, etc.)</li>
 *   <li>Wrap in container div with configurable class</li>
 *   <li>Apply template skeleton if provided</li>
 * </ol></p>
 *
 * <p>Thread-safe and immutable.</p>
 */
public class HtmlOutputConverter {

    private final boolean stripThinking;
    private final boolean sanitize;
    private final String containerClass;
    private final String template;

    /**
     * Create a converter with full defaults.
     *
     * @param stripThinking   if true, removes text before first HTML tag
     * @param sanitize         if true, removes dangerous HTML tags
     * @param containerClass   CSS class for the wrapping div (e.g. "snap-inject")
     * @param template         optional template skeleton with {content} placeholder, null for none
     */
    public HtmlOutputConverter(boolean stripThinking, boolean sanitize,
                               String containerClass, String template) {
        this.stripThinking = stripThinking;
        this.sanitize = sanitize;
        this.containerClass = containerClass != null ? containerClass : "snap-inject";
        this.template = template;
    }

    /**
     * Create a converter without a template.
     */
    public HtmlOutputConverter(boolean stripThinking, boolean sanitize, String containerClass) {
        this(stripThinking, sanitize, containerClass, null);
    }

    /**
     * Convert raw LLM output to safe HTML.
     *
     * @param raw the raw LLM output
     * @return processed HTML
     */
    public String convert(String raw) {
        if (raw == null || raw.isEmpty()) {
            return wrapInContainer("");
        }

        String result = raw;

        // Step 1: Strip thinking prefix
        if (stripThinking) {
            result = stripThinking(result);
        }

        // Step 2: Sanitize dangerous tags
        if (sanitize) {
            result = sanitize(result);
        }

        // Step 3: Apply template if present
        if (template != null && !template.isEmpty()) {
            result = template.replace("{content}", result);
        }

        // Step 4: Wrap in container div
        result = wrapInContainer(result);

        return result;
    }

    /**
     * Strip LLM reasoning/thinking text that appears before the first HTML tag.
     *
     * <p>Many LLMs output "Let me think..." or similar text before the actual
     * HTML content. This finds the first '<' followed by a letter or '/'
     * and returns from there.</p>
     *
     * @param raw raw LLM output
     * @return output starting from first HTML tag, or original if no tags found
     */
    static String stripThinking(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '<') {
                // Check if this looks like an HTML tag start
                if (i + 1 < raw.length()) {
                    char next = raw.charAt(i + 1);
                    if (Character.isLetter(next) || next == '/') {
                        return raw.substring(i);
                    }
                }
            }
        }
        // No HTML tag found — return original (might be plain text)
        return raw;
    }

    /**
     * Remove dangerous HTML tags: script, iframe, object, embed, style, meta, link.
     *
     * <p>Removes the entire tag including content for script and style.
     * For others, removes the tag but keeps content.</p>
     *
     * @param html raw HTML
     * @return sanitized HTML
     */
    static String sanitize(String html) {
        if (html == null || html.isEmpty()) return html;

        String result = html;

        // Remove script tags with content
        result = removeTagWithContent(result, "script");
        result = removeTagWithContent(result, "style");

        // Remove other dangerous tags (keep content)
        result = removeTagOnly(result, "iframe");
        result = removeTagOnly(result, "object");
        result = removeTagOnly(result, "embed");
        result = removeTagOnly(result, "meta");
        result = removeTagOnly(result, "link");

        return result;
    }

    /**
     * Remove an HTML tag along with its content (e.g. script, style).
     */
    private static String removeTagWithContent(String html, String tagName) {
        String result = html;
        String openPattern = "<" + tagName;
        String closePattern = "</" + tagName;

        int start;
        while ((start = result.toLowerCase().indexOf(openPattern)) != -1) {
            int end = result.toLowerCase().indexOf(closePattern, start);
            if (end != -1) {
                // Find the '>' after </tagName
                int closeEnd = result.indexOf('>', end);
                if (closeEnd != -1) {
                    result = result.substring(0, start) + result.substring(closeEnd + 1);
                } else {
                    result = result.substring(0, start);
                }
            } else {
                // No closing tag — remove from start to end of string
                result = result.substring(0, start);
            }
        }
        return result;
    }

    /**
     * Remove only the HTML tags but keep their content.
     */
    private static String removeTagOnly(String html, String tagName) {
        String result = html;
        String openPattern = "<" + tagName;
        String closePattern = "</" + tagName;

        // Remove opening tags
        int idx;
        while ((idx = result.toLowerCase().indexOf(openPattern)) != -1) {
            int end = result.indexOf('>', idx);
            if (end != -1) {
                result = result.substring(0, idx) + result.substring(end + 1);
            } else {
                result = result.substring(0, idx);
            }
        }

        // Remove closing tags
        while ((idx = result.toLowerCase().indexOf(closePattern)) != -1) {
            int end = result.indexOf('>', idx);
            if (end != -1) {
                result = result.substring(0, idx) + result.substring(end + 1);
            } else {
                result = result.substring(0, idx);
            }
        }

        return result;
    }

    /**
     * Wrap content in a container div.
     */
    private String wrapInContainer(String content) {
        return "<div class=\"" + containerClass + "\">" + content + "</div>";
    }

    // ---- getters ----

    public boolean isStripThinking() { return stripThinking; }
    public boolean isSanitize() { return sanitize; }
    public String getContainerClass() { return containerClass; }
    public String getTemplate() { return template; }
}
