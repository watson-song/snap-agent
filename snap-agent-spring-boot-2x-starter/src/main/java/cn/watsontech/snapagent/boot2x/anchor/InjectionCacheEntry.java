package cn.watsontech.snapagent.boot2x.anchor;

import java.time.Instant;

/**
 * Immutable cache entry for injected content.
 *
 * <p>Stores the HTML output, generation timestamp, and expiration timestamp.
 * The {@link #isExpired()} method checks if the entry has passed its TTL.</p>
 */
public class InjectionCacheEntry {

    private final String html;
    private final Instant generatedAt;
    private final Instant expiresAt;

    public InjectionCacheEntry(String html, Instant generatedAt, Instant expiresAt) {
        this.html = html;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
    }

    /** Returns true if the current time is past the expiration time. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getHtml() { return html; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
