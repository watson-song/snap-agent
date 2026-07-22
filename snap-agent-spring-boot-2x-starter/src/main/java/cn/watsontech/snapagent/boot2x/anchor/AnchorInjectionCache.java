package cn.watsontech.snapagent.boot2x.anchor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * LRU + per-entry TTL cache for anchor content injection results.
 *
 * <p>Uses Caffeine under the hood with a global maximum size and a
 * conservative global TTL ceiling (7 days). Per-entry TTL is enforced
 * via {@link InjectionCacheEntry#isExpired()} — entries that have passed
 * their declared TTL return null on {@link #get(String)}.</p>
 *
 * <p>Cache key format: {@code userId:sourceId:anchorName:pageUrl}</p>
 */
public class AnchorInjectionCache {

    static final long MAX_TTL_SECONDS = 7 * 24 * 3600; // 7 days hard ceiling
    private static final int DEFAULT_MAX_SIZE = 512;

    private final Cache<String, InjectionCacheEntry> cache;

    /** Creates a cache with default max size (512). */
    public AnchorInjectionCache() {
        this(DEFAULT_MAX_SIZE);
    }

    /** Creates a cache with the specified max size. */
    public AnchorInjectionCache(int maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE)
                .expireAfterWrite(MAX_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns the cache entry if it exists and has not expired.
     * Expired entries are invalidated and null is returned.
     */
    public InjectionCacheEntry get(String key) {
        InjectionCacheEntry entry = cache.getIfPresent(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cache.invalidate(key);
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
    }

    /** Returns the number of entries currently in the cache. */
    public int size() {
        cache.cleanUp();
        return cache.asMap().size();
    }

    /** Removes all entries from the cache. */
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
