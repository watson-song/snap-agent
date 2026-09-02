package cn.watsontech.snapagent.boot2x.anchor;

import java.time.Instant;

/**
 * Result of an anchor content injection request.
 *
 * <p>Contains the generated HTML, whether it came from cache,
 * and the generation timestamp.</p>
 */
public class InjectionResult {

    private final String html;
    private final boolean cached;
    private final Instant generatedAt;

    public InjectionResult(String html, boolean cached, Instant generatedAt) {
        this.html = html;
        this.cached = cached;
        this.generatedAt = generatedAt;
    }

    /** Creates a non-cached result (freshly generated). */
    public static InjectionResult fresh(String html) {
        return new InjectionResult(html, false, Instant.now());
    }

    /** Returns a copy of this result marked as cache-hit. */
    public InjectionResult markCached() {
        return new InjectionResult(this.html, true, this.generatedAt);
    }

    public String getHtml() { return html; }
    public boolean isCached() { return cached; }
    public Instant getGeneratedAt() { return generatedAt; }
}
