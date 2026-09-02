package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * LRU + TTL cache for anchor content summaries.
 *
 * <p>Uses {@link ConcurrentHashMap} with timestamp-based TTL and size-based
 * eviction. No external cache library (Caffeine, Guava, etc.) required.</p>
 *
 * <p>Default max entries: 256. TTL from {@code snap-agent.anchor.summary-cache-ttl-seconds}.</p>
 */
public class AnchorSummaryCache {

    private static final int DEFAULT_MAX_SIZE = 256;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final int maxSize;
    private final long ttlMillis;

    /** Creates a cache with default max size (256) and TTL from config. */
    public AnchorSummaryCache(SnapAgentProperties.Anchor props) {
        this(props, DEFAULT_MAX_SIZE);
    }

    /** Creates a cache with the specified max size and TTL from config. */
    public AnchorSummaryCache(SnapAgentProperties.Anchor props, int maxSize) {
        long ttlSeconds = props.getSummaryCacheTtlSeconds() > 0
                ? props.getSummaryCacheTtlSeconds()
                : 600;
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * Returns the cached summary for the given content, or computes it via
     * the supplier if absent. The content is hashed with SHA-256 to produce
     * a fixed-length cache key.
     */
    public String getOrCreate(String content, Supplier<String> supplier) {
        String key = hashKey(content);
        long now = System.currentTimeMillis();

        CacheEntry existing = cache.get(key);
        if (existing != null && !existing.isExpired(now)) {
            return existing.value;
        }

        // Compute and store
        String result = supplier.get();
        cache.put(key, new CacheEntry(result, now + ttlMillis));
        evictIfNeeded(now);
        return result;
    }

    /** Returns the number of entries currently in the cache. */
    public int size() {
        return cache.size();
    }

    /** Removes all entries from the cache. */
    public void invalidateAll() {
        cache.clear();
    }

    private void evictIfNeeded(long now) {
        if (cache.size() <= maxSize) return;

        // First pass: remove expired entries
        cache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        if (cache.size() <= maxSize) return;

        // Second pass: remove oldest entries
        cache.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().expiresAt))
                .limit(cache.size() - maxSize)
                .forEach(e -> cache.remove(e.getKey()));
    }

    private static class CacheEntry {
        final String value;
        final long expiresAt;

        CacheEntry(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return now > expiresAt;
        }
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
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
