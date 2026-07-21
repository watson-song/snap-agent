package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
