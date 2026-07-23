package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Unit tests for {@link AnchorContext}.
 *
 * <p>Validates the DTO that carries the page-section context from client
 * to server (name, content, pageUrl, truncated flag, original length, meta info).</p>
 */
class AnchorContextTest {

    @Test
    void shouldConstructWithAllFields() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("headings", Arrays.asList("订单状态", "物流轨迹"));
        meta.put("tableCount", 3);
        meta.put("codeBlockCount", 2);

        AnchorContext ctx = new AnchorContext(
                "订单状态区块",
                "## 订单状态\n当前状态：已发货",
                true,
                52180,
                meta,
                "/order/detail?id=123"
        );

        assertThat(ctx.getName()).isEqualTo("订单状态区块");
        assertThat(ctx.getContent()).contains("订单状态");
        assertThat(ctx.isTruncated()).isTrue();
        assertThat(ctx.getOriginalLength()).isEqualTo(52180);
        assertThat(ctx.getPageUrl()).isEqualTo("/order/detail?id=123");
        assertThat(ctx.getMeta()).containsEntry("headings", Arrays.asList("订单状态", "物流轨迹"));
        assertThat(ctx.getMeta()).containsEntry("tableCount", 3);
    }

    @Test
    void shouldConstructWithMinimalFields() {
        AnchorContext ctx = new AnchorContext("intro", "Hello", "/docs/intro");

        assertThat(ctx.getName()).isEqualTo("intro");
        assertThat(ctx.getContent()).isEqualTo("Hello");
        assertThat(ctx.isTruncated()).isFalse();
        assertThat(ctx.getOriginalLength()).isEqualTo(0);
        assertThat(ctx.getMeta()).isEmpty();
        assertThat(ctx.getPageUrl()).isEqualTo("/docs/intro");
    }

    @Test
    void shouldRoundTripFromMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "test-anchor");
        map.put("content", "## Title\nbody");
        map.put("truncated", true);
        map.put("originalLength", 9999);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("headings", Arrays.asList("Title"));
        meta.put("tableCount", 0);
        map.put("meta", meta);
        map.put("pageUrl", "/docs/test");

        AnchorContext ctx = AnchorContext.fromMap(map);

        assertThat(ctx.getName()).isEqualTo("test-anchor");
        assertThat(ctx.getContent()).isEqualTo("## Title\nbody");
        assertThat(ctx.isTruncated()).isTrue();
        assertThat(ctx.getOriginalLength()).isEqualTo(9999);
        assertThat(ctx.getPageUrl()).isEqualTo("/docs/test");
        assertThat(ctx.getMeta()).containsEntry("tableCount", 0);
    }

    @Test
    void shouldHandleNullMapGracefully() {
        AnchorContext ctx = AnchorContext.fromMap(null);

        assertThat(ctx).isNull();
    }

    @Test
    void shouldHandleMissingOptionalFieldsInMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "minimal");
        map.put("content", "content only");

        AnchorContext ctx = AnchorContext.fromMap(map);

        assertThat(ctx.getName()).isEqualTo("minimal");
        assertThat(ctx.getContent()).isEqualTo("content only");
        assertThat(ctx.isTruncated()).isFalse();
        assertThat(ctx.getOriginalLength()).isEqualTo(0);
        assertThat(ctx.getMeta()).isEmpty();
        assertThat(ctx.getPageUrl()).isNull();
    }

    @Test
    void shouldReturnNullWhenNameMissingFromMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("content", "content without name");

        AnchorContext ctx = AnchorContext.fromMap(map);

        assertThat(ctx).isNull();
    }

    @Test
    void shouldReturnNullWhenContentMissingFromMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "name only");

        AnchorContext ctx = AnchorContext.fromMap(map);

        assertThat(ctx).isNull();
    }

    @Test
    void shouldBuildAugmentedPromptWithContext() {
        AnchorContext ctx = new AnchorContext(
                "订单状态区块",
                "## 订单状态\n当前状态：已发货",
                false,
                0,
                null,
                "/order/detail?id=123"
        );

        String augmented = ctx.augmentMessage("为什么这个订单还没到？");

        assertThat(augmented).contains("订单状态区块");
        assertThat(augmented).contains("/order/detail?id=123");
        assertThat(augmented).contains("当前状态：已发货");
        assertThat(augmented).contains("为什么这个订单还没到？");
    }

    @Test
    void shouldIndicateTruncationInAugmentedPrompt() {
        AnchorContext ctx = new AnchorContext(
                "长文档",
                "## 截断内容...",
                true,
                52180,
                null,
                "/docs/long"
        );

        String augmented = ctx.augmentMessage("总结一下");

        assertThat(augmented).contains("已截断");
        assertThat(augmented).contains("52180");
    }

    @Test
    void shouldIncludeMetaInfoInAugmentedPromptWhenPresent() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("headings", Arrays.asList("第一章", "第二章"));
        meta.put("tableCount", 5);

        AnchorContext ctx = new AnchorContext(
                "multi-section",
                "## content",
                true,
                20000,
                meta,
                "/docs/multi"
        );

        String augmented = ctx.augmentMessage("讲讲结构");

        assertThat(augmented).contains("第一章");
        assertThat(augmented).contains("第二章");
        assertThat(augmented).contains("tableCount");
    }

    @Test
    void shouldDetermineIfSummaryNeeded() {
        AnchorContext shortCtx = new AnchorContext("short", "1234", "/p");
        AnchorContext longCtx = new AnchorContext("long", new String(new char[5000]).replace("\0", "x"), "/p");

        assertThat(shortCtx.needsSummary(4000)).isFalse();
        assertThat(longCtx.needsSummary(4000)).isTrue();
    }

    // ---- G-401: augmentMessage exact concatenation per Gherkin UC-05 ----

    @Test
    @DisplayName("G-401: augmentMessage should concatenate pageUrl + name + content + question")
    void shouldConcatenatePageUrlNameContentAndQuestionPerSpec() {
        AnchorContext anchor = new AnchorContext("订单状态", "已发货\nSF123", "/order/1");

        String result = anchor.augmentMessage("什么时候到？");

        assertThat(result).contains("页面 \"/order/1\"");
        assertThat(result).contains("的 \"订单状态\" 区块");
        assertThat(result).contains("区块内容：\n已发货\nSF123");
        assertThat(result).contains("用户提问：什么时候到？");
    }

    @Test
    @DisplayName("G-401: augmentMessage should show (unknown) when pageUrl is null")
    void shouldShowUnknownWhenPageUrlIsNull() {
        AnchorContext anchor = new AnchorContext("section", "content", null);

        String result = anchor.augmentMessage("q");

        assertThat(result).contains("页面 \"(unknown)\"");
    }

    @Test
    @DisplayName("G-401: augmentMessage should include truncated flag and originalLength")
    void shouldIncludeTruncatedFlagAndOriginalLengthInAugmentMessage() {
        AnchorContext anchor = new AnchorContext(
                "长文档", "## 截断内容...", true, 52180, null, null);

        String result = anchor.augmentMessage("q");

        assertThat(result).contains("内容已截断，原始长度 52180 字符");
        assertThat(result).contains("页面 \"(unknown)\"");
    }

    // ---- G-402: fromMap boundary parameterized tests per Gherkin UC-05 scenario outline ----

    static Stream<Arguments> fromMapBoundaryData() {
        return Stream.of(
                // null map → null
                Arguments.of(null, null, "null map"),
                // empty map → null
                Arguments.of(Collections.<String, Object>emptyMap(), null, "empty map"),
                // map with only name → null (missing content)
                Arguments.of(mapOf("name", "n"), null, "missing content"),
                // map with only content → null (missing name)
                Arguments.of(mapOf("content", "c"), null, "missing name"),
                // map with empty name and content → null
                Arguments.of(mapOf("name", "", "content", ""), null, "empty name and content"),
                // map with valid name and content → non-null
                Arguments.of(mapOf("name", "n", "content", "c"), "non-null", "valid name and content"),
                // map with truncated flag → non-null, truncated=true
                Arguments.of(mapOf("name", "n", "content", "c", "truncated", true), "truncated=true", "with truncated flag"),
                // map with originalLength → non-null, originalLength=99
                Arguments.of(mapOf("name", "n", "content", "c", "originalLength", 99), "originalLength=99", "with originalLength")
        );
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("fromMapBoundaryData")
    @DisplayName("G-402: fromMap boundary cases per Gherkin scenario outline")
    void shouldHandleFromMapBoundaries(Map<String, Object> map, String expectedBehavior, String displayName) {
        AnchorContext ctx = AnchorContext.fromMap(map);

        if (expectedBehavior == null) {
            assertThat(ctx).isNull();
        } else if ("non-null".equals(expectedBehavior)) {
            assertThat(ctx).isNotNull();
            assertThat(ctx.getName()).isEqualTo("n");
            assertThat(ctx.getContent()).isEqualTo("c");
        } else if ("truncated=true".equals(expectedBehavior)) {
            assertThat(ctx).isNotNull();
            assertThat(ctx.isTruncated()).isTrue();
        } else if ("originalLength=99".equals(expectedBehavior)) {
            assertThat(ctx).isNotNull();
            assertThat(ctx.getOriginalLength()).isEqualTo(99L);
        }
    }

    // ---- G-403: needsSummary threshold boundary ----

    @Test
    @DisplayName("G-403: needsSummary should return false when content length equals threshold")
    void shouldReturnFalseWhenContentLengthEqualsThreshold() {
        String content = new String(new char[100]).replace("\0", "x");
        AnchorContext ctx = new AnchorContext("test", content, "/p");

        assertThat(ctx.needsSummary(100)).isFalse();
    }

    @Test
    @DisplayName("G-403: needsSummary should return true when content length exceeds threshold by one")
    void shouldReturnTrueWhenContentLengthExceedsThresholdByOne() {
        String content = new String(new char[101]).replace("\0", "x");
        AnchorContext ctx = new AnchorContext("test", content, "/p");

        assertThat(ctx.needsSummary(100)).isTrue();
    }

    @Test
    @DisplayName("G-403: needsSummary should return false when content is null")
    void shouldReturnFalseWhenContentIsNullForNeedsSummary() {
        AnchorContext ctx = new AnchorContext("test", null, "/p");

        assertThat(ctx.needsSummary(100)).isFalse();
    }
}
