package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LRU + TTL cache for anchor content summaries.
 *
 * <p>Uses Caffeine under the hood with a SHA-256 content hash as the key.
 * The TTL is driven by {@code snap-agent.anchor.summary-cache-ttl-seconds}.
 * Default max entries: 256.</p>
 */
public class AnchorSummaryCache {

    private static final int DEFAULT_MAX_SIZE = 256;

    private final Cache<String, String> cache;

    /** Creates a cache with default max size (256) and TTL from config. */
    public AnchorSummaryCache(SnapAgentProperties.Anchor props) {
        this(props, DEFAULT_MAX_SIZE);
    }

    /** Creates a cache with the specified max size and TTL from config. */
    public AnchorSummaryCache(SnapAgentProperties.Anchor props, int maxSize) {
        long ttlSeconds = props.getSummaryCacheTtlSeconds() > 0
                ? props.getSummaryCacheTtlSeconds()
                : 600;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns the cached summary for the given content, or computes it via
     * the supplier if absent. The content is hashed with SHA-256 to produce
     * a fixed-length cache key (avoids storing large strings as keys).
     *
     * @param content  the raw page-section content (Markdown)
     * @param supplier computes the summary when cache miss occurs
     * @return the summary (may be null if supplier returns null)
     */
    public String getOrCreate(String content, Supplier<String> supplier) {
        String key = hashKey(content);
        String result = cache.get(key, k -> supplier.get());
        cache.cleanUp();
        return result;
    }

    /** Returns the number of entries currently in the cache. */
    public int size() {
        return cache.asMap().size();
    }

    /** Removes all entries from the cache. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Generates a SHA-256 hex hash of the content string. */
    static String hashKey(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec; this should never happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
