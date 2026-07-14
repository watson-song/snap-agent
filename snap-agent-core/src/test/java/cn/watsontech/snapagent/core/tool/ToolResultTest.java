package cn.watsontech.snapagent.core.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ToolResult}.
 */
class ToolResultTest {

    @Test
    void shouldSetFieldsWhenUsingSuccessFactory() {
        ToolResult result = ToolResult.success("content", 42, 150L);

        assertThat(result.getContent()).isEqualTo("content");
        assertThat(result.getRowCount()).isEqualTo(42);
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getDurationMs()).isEqualTo(150L);
        assertThat(result.getError()).isNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void shouldSetTruncatedTrueWhenUsingTruncatedFactory() {
        ToolResult result = ToolResult.truncated("big-content", 1000, 200L);

        assertThat(result.getContent()).isEqualTo("big-content");
        assertThat(result.getRowCount()).isEqualTo(1000);
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getDurationMs()).isEqualTo(200L);
        assertThat(result.getError()).isNull();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldSetErrorWhenUsingErrorFactory() {
        ToolResult result = ToolResult.error("something went wrong", 50L);

        assertThat(result.getContent()).isNull();
        assertThat(result.getRowCount()).isZero();
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.getDurationMs()).isEqualTo(50L);
        assertThat(result.getError()).isEqualTo("something went wrong");
        assertThat(result.isError()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldReturnErrorWhenErrorFactoryGivenNullMessage() {
        ToolResult result = ToolResult.error(null, 0L);

        assertThat(result.getError()).isEqualTo("unknown error");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void shouldConstructViaFullConstructor() {
        ToolResult result = new ToolResult("c", 5, true, 99L, "err");

        assertThat(result.getContent()).isEqualTo("c");
        assertThat(result.getRowCount()).isEqualTo(5);
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getDurationMs()).isEqualTo(99L);
        assertThat(result.getError()).isEqualTo("err");
    }

    @Test
    void shouldHaveConsistentToString() {
        ToolResult result = ToolResult.success("x", 1, 1L);

        String str = result.toString();
        assertThat(str).contains("x");
        assertThat(str).contains("ToolResult");
    }
}
