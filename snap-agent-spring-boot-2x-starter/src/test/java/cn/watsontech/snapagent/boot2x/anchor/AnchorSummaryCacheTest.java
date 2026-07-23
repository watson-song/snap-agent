package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AnchorSummaryCache}.
 *
 * <p>Validates SHA-256 key generation, cache hit/miss semantics,
 * TTL-based eviction, and LRU eviction.</p>
 */
class AnchorSummaryCacheTest {

    private SnapAgentProperties.Anchor props;
    private AnchorSummaryCache cache;

    @BeforeEach
    void setUp() {
        props = new SnapAgentProperties.Anchor();
        props.setSummaryCacheTtlSeconds(2); // short TTL for testing
        cache = new AnchorSummaryCache(props);
    }

    @Test
    void shouldComputeOnMiss() {
        AtomicInteger calls = new AtomicInteger(0);
        String content = "这是测试内容，长度足够唯一。";

        String result = cache.getOrCreate(content, () -> {
            calls.incrementAndGet();
            return "摘要结果";
        });

        assertThat(result).isEqualTo("摘要结果");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldReturnCachedValueOnHit() {
        AtomicInteger calls = new AtomicInteger(0);
        String content = "需要摘要的长内容。";

        cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "first"; });
        String result2 = cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "second"; });

        assertThat(result2).isEqualTo("first");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldCacheDifferentContentSeparately() {
        AtomicInteger calls = new AtomicInteger(0);

        cache.getOrCreate("内容A", () -> { calls.incrementAndGet(); return "summaryA"; });
        cache.getOrCreate("内容B", () -> { calls.incrementAndGet(); return "summaryB"; });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void shouldHandleNullSupplierResult() {
        String content = "test";
        String result = cache.getOrCreate(content, () -> null);

        // Null result should not be cached (or cached as null); subsequent calls should recompute
        assertThat(result).isNull();
    }

    @Test
    void shouldExpireEntriesAfterTtl() throws InterruptedException {
        props.setSummaryCacheTtlSeconds(1);
        cache = new AnchorSummaryCache(props);

        AtomicInteger calls = new AtomicInteger(0);
        String content = "TTL测试";

        cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "first"; });
        Thread.sleep(1200); // wait for TTL to expire
        cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "second"; });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void shouldReuseCachedValueWithinTtl() throws InterruptedException {
        props.setSummaryCacheTtlSeconds(3);
        cache = new AnchorSummaryCache(props);

        AtomicInteger calls = new AtomicInteger(0);
        String content = "still-fresh";

        cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "first"; });
        Thread.sleep(1000); // 1 second, within 3s TTL
        cache.getOrCreate(content, () -> { calls.incrementAndGet(); return "second"; });

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldGenerateConsistentKeyForSameContent() {
        // Two different string instances with same content should hit same cache slot
        AtomicInteger calls = new AtomicInteger(0);
        String contentA = new String("same-content-here-unique");
        String contentB = new String("same-content-here-unique");

        cache.getOrCreate(contentA, () -> { calls.incrementAndGet(); return "v1"; });
        cache.getOrCreate(contentB, () -> { calls.incrementAndGet(); return "v2"; });

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldRespectMaxSizeForLruEviction() {
        // Set a cache with very small max size
        props.setSummaryCacheTtlSeconds(600);
        cache = new AnchorSummaryCache(props, 3); // max 3 entries

        AtomicInteger calls = new AtomicInteger(0);
        // Fill with 4 entries, first should be evicted
        cache.getOrCreate("content-1", () -> { calls.incrementAndGet(); return "s1"; });
        cache.getOrCreate("content-2", () -> { calls.incrementAndGet(); return "s2"; });
        cache.getOrCreate("content-3", () -> { calls.incrementAndGet(); return "s3"; });
        cache.getOrCreate("content-4", () -> { calls.incrementAndGet(); return "s4"; });
        // Access content-1 again — should recompute (evicted)
        cache.getOrCreate("content-1", () -> { calls.incrementAndGet(); return "s1-again"; });

        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void shouldProvideSizeMethod() {
        cache.getOrCreate("a", () -> "sa");
        cache.getOrCreate("b", () -> "sb");
        cache.getOrCreate("c", () -> "sc");

        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void shouldProvideInvalidateAllMethod() {
        cache.getOrCreate("a", () -> "sa");
        cache.getOrCreate("b", () -> "sb");
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidateAll();

        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldHandleLargeContentHashing() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("这是一段长内容，用于测试 SHA-256 在大输入下的性能。");
        }
        String largeContent = sb.toString();

        AtomicInteger calls = new AtomicInteger(0);
        String result = cache.getOrCreate(largeContent, () -> {
            calls.incrementAndGet();
            return "large-summary";
        });

        assertThat(result).isEqualTo("large-summary");
        assertThat(calls.get()).isEqualTo(1);
    }

    // ---- G-413: hashKey static method ----

    @Test
    @DisplayName("G-413: hashKey should produce deterministic hash for same content")
    void shouldProduceDeterministicHashForSameContent() {
        String content = "test content for hashing";

        String hash1 = AnchorSummaryCache.hashKey(content);
        String hash2 = AnchorSummaryCache.hashKey(new String(content));

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("G-413: hashKey should produce different hashes for different content")
    void shouldProduceDifferentHashesForDifferentContent() {
        String hash1 = AnchorSummaryCache.hashKey("content A");
        String hash2 = AnchorSummaryCache.hashKey("content B");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("G-413: hashKey should return valid SHA-256 hex string (64 chars)")
    void shouldReturnValidSha256HexString() {
        String hash = AnchorSummaryCache.hashKey("any content");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
