package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorInjectionCacheTest {

    @Test
    void shouldReturnEntryAfterPut() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant now = Instant.now();
        cache.put("key1", "<p>hello</p>", now, 3600);

        InjectionCacheEntry entry = cache.get("key1");
        assertThat(entry).isNotNull();
        assertThat(entry.getHtml()).isEqualTo("<p>hello</p>");
        assertThat(entry.isExpired()).isFalse();
    }

    @Test
    void shouldReturnNullForMissingKey() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        assertThat(cache.get("nonexistent")).isNull();
    }

    @Test
    void shouldReturnNullForExpiredEntry() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        cache.put("key1", "<p>expired</p>", past, 3600);

        assertThat(cache.get("key1")).isNull();
    }

    @Test
    void shouldRespectPerEntryTtl() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant now = Instant.now();

        // entry1: TTL = 1 hour
        cache.put("key1", "<p>short</p>", now, 3600);
        // entry2: TTL = 24 hours
        cache.put("key2", "<p>long</p>", now, 86400);

        // both should be present
        assertThat(cache.get("key1")).isNotNull();
        assertThat(cache.get("key2")).isNotNull();
    }

    @Test
    void shouldEnforceMaxTtl() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant now = Instant.now();
        // Request TTL of 999 days, should be capped to 7 days
        cache.put("key1", "<p>huge ttl</p>", now, 999 * 86400);

        InjectionCacheEntry entry = cache.get("key1");
        assertThat(entry).isNotNull();
        // expiresAt should be roughly 7 days from now, not 999 days
        long maxTtlSeconds = 7 * 24 * 3600;
        long actualTtl = entry.getExpiresAt().getEpochSecond() - now.getEpochSecond();
        assertThat(actualTtl).isLessThanOrEqualTo(maxTtlSeconds);
    }

    @Test
    void shouldSupportInvalidateAll() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant now = Instant.now();
        cache.put("key1", "<p>a</p>", now, 3600);
        cache.put("key2", "<p>b</p>", now, 3600);

        cache.invalidateAll();
        assertThat(cache.get("key1")).isNull();
        assertThat(cache.get("key2")).isNull();
    }

    @Test
    void shouldReportSize() {
        AnchorInjectionCache cache = new AnchorInjectionCache();
        Instant now = Instant.now();
        assertThat(cache.size()).isEqualTo(0);
        cache.put("key1", "<p>a</p>", now, 3600);
        cache.put("key2", "<p>b</p>", now, 3600);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void shouldUseCustomMaxSize() {
        AnchorInjectionCache cache = new AnchorInjectionCache(2);
        Instant now = Instant.now();
        cache.put("key1", "<p>a</p>", now, 3600);
        cache.put("key2", "<p>b</p>", now, 3600);
        cache.put("key3", "<p>c</p>", now, 3600); // should evict oldest

        assertThat(cache.size()).isLessThanOrEqualTo(2);
    }
}
