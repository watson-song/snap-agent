package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link VerificationResult}.
 */
class VerificationResultTest {

    private static final long VERIFIED_AT = 1_700_000_000_000L;

    @Test
    void shouldReturnConstructorValuesFromGettersWhenPassed() {
        VerificationResult result = new VerificationResult(
                true, "修复后超时消失, 连接池使用率正常",
                "ERROR_RATE=0.05", "ERROR_RATE=0.001",
                VERIFIED_AT);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSummary()).isEqualTo("修复后超时消失, 连接池使用率正常");
        assertThat(result.getBeforeStatus()).isEqualTo("ERROR_RATE=0.05");
        assertThat(result.getAfterStatus()).isEqualTo("ERROR_RATE=0.001");
        assertThat(result.getVerifiedAt()).isEqualTo(VERIFIED_AT);
    }

    @Test
    void shouldReturnConstructorValuesFromGettersWhenFailed() {
        VerificationResult result = new VerificationResult(
                false, "超时仍存在",
                "ERROR_RATE=0.05", "ERROR_RATE=0.04",
                VERIFIED_AT + 1_000L);

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getSummary()).isEqualTo("超时仍存在");
        assertThat(result.getBeforeStatus()).isEqualTo("ERROR_RATE=0.05");
        assertThat(result.getAfterStatus()).isEqualTo("ERROR_RATE=0.04");
        assertThat(result.getVerifiedAt()).isEqualTo(VERIFIED_AT + 1_000L);
    }

    @Test
    void shouldAllowNullableStringFields() {
        VerificationResult result = new VerificationResult(
                true, null, null, null, VERIFIED_AT);

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getSummary()).isNull();
        assertThat(result.getBeforeStatus()).isNull();
        assertThat(result.getAfterStatus()).isNull();
        assertThat(result.getVerifiedAt()).isEqualTo(VERIFIED_AT);
    }

    @Test
    void toStringShouldContainPassedSummaryAndTimestamps() {
        VerificationResult result = new VerificationResult(
                true, "ok", "before", "after", VERIFIED_AT);

        String str = result.toString();
        assertThat(str).contains("VerificationResult");
        assertThat(str).contains("passed=true");
        assertThat(str).contains("ok");
        assertThat(str).contains("before");
        assertThat(str).contains("after");
        assertThat(str).contains(String.valueOf(VERIFIED_AT));
    }
}
