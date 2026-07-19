package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SolutionOption}.
 */
class SolutionOptionTest {

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        SolutionOption option = new SolutionOption(
                "opt-1", "重启服务", "通过滚动重启释放连接",
                "low", false);

        assertThat(option.getId()).isEqualTo("opt-1");
        assertThat(option.getTitle()).isEqualTo("重启服务");
        assertThat(option.getDescription()).isEqualTo("通过滚动重启释放连接");
        assertThat(option.getEffort()).isEqualTo("low");
        assertThat(option.isTemporary()).isFalse();
    }

    @Test
    void shouldAllowNullableStringFields() {
        // Null title/description/effort are not rejected (no validation by spec)
        SolutionOption option = new SolutionOption(
                "opt-2", null, null, null, true);

        assertThat(option.getId()).isEqualTo("opt-2");
        assertThat(option.getTitle()).isNull();
        assertThat(option.getDescription()).isNull();
        assertThat(option.getEffort()).isNull();
        assertThat(option.isTemporary()).isTrue();
    }

    @Test
    void shouldAcceptHighEffortAndTemporaryFlag() {
        SolutionOption option = new SolutionOption(
                "opt-3", "扩容连接池", "永久修复, 提升容量",
                "high", false);

        assertThat(option.getEffort()).isEqualTo("high");
        assertThat(option.isTemporary()).isFalse();
    }

    @Test
    void toStringShouldContainIdTitleEffortAndTemporary() {
        SolutionOption option = new SolutionOption(
                "opt-9", "回滚版本", null, "medium", true);

        String str = option.toString();
        assertThat(str).contains("SolutionOption");
        assertThat(str).contains("opt-9");
        assertThat(str).contains("回滚版本");
        assertThat(str).contains("medium");
        assertThat(str).contains("temporary=true");
    }
}
