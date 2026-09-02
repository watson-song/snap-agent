package cn.watsontech.snapagent.boot2x.anchor;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU + per-entry TTL cache for anchor content injection results.
 *
 * <p>Uses {@link ConcurrentHashMap} with timestamp-based TTL and size-based
 * eviction. No external cache library (Caffeine, Guava, etc.) required.</p>
 *
 * <p>Cache key format: {@code userId:sourceId:anchorName:pageUrl}</p>
 */
public class AnchorInjectionCache {

    static final long MAX_TTL_SECONDS = 7 * 24 * 3600; // 7 days hard ceiling
    private static final int DEFAULT_MAX_SIZE = 512;

    private final ConcurrentHashMap<String, InjectionCacheEntry> cache = new ConcurrentHashMap<>();
    private final int maxSize;

    /** Creates a cache with default max size (512). */
    public AnchorInjectionCache() {
        this(DEFAULT_MAX_SIZE);
    }

    /** Creates a cache with the specified max size. */
    public AnchorInjectionCache(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    }

    /**
     * Returns the cache entry if it exists and has not expired.
     * Expired entries are invalidated and null is returned.
     */
    public InjectionCacheEntry get(String key) {
        InjectionCacheEntry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        return entry;
    }

    /**
     * Stores a cache entry with the specified TTL.
     * The TTL is capped at {@link #MAX_TTL_SECONDS}.
     */
    public void put(String key, String html, Instant generatedAt, long ttlSeconds) {
        long effectiveTtl = Math.min(ttlSeconds, MAX_TTL_SECONDS);
        Instant expiresAt = generatedAt.plusSeconds(effectiveTtl);
        cache.put(key, new InjectionCacheEntry(html, generatedAt, expiresAt));
        evictIfNeeded();
    }

    /** Returns the number of entries currently in the cache. */
    public int size() {
        return cache.size();
    }

    /** Removes all entries from the cache. */
    public void invalidateAll() {
        cache.clear();
    }

    private void evictIfNeeded() {
        if (cache.size() <= maxSize) return;

        // First pass: remove expired entries
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        if (cache.size() <= maxSize) return;

        // Second pass: remove oldest entries
        cache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().getExpiresAt().toEpochMilli()))
                .limit(cache.size() - maxSize)
                .forEach(e -> cache.remove(e.getKey()));
    }
}
