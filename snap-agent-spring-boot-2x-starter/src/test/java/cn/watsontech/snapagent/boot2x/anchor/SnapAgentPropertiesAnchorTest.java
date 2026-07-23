package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapAgentProperties.Anchor}.
 *
 * <p>Validates default values and configuration binding for the anchor
 * feature properties.</p>
 */
class SnapAgentPropertiesAnchorTest {

    @Test
    void shouldUseDefaultsWhenNotConfigured() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();

        assertThat(anchor.isEnabled()).isTrue();
        assertThat(anchor.getClassifierModel()).isEmpty();
        assertThat(anchor.getClassifierConfidenceThreshold()).isEqualTo(0.5);
        assertThat(anchor.getDisabledPaths()).isEmpty();
        assertThat(anchor.getMaxContextChars()).isEqualTo(8000);
        assertThat(anchor.isPreprocessEnabled()).isTrue();
        assertThat(anchor.getPreprocessTimeoutMs()).isEqualTo(5000);
        assertThat(anchor.getSummaryCacheTtlSeconds()).isEqualTo(600);
        assertThat(anchor.getSummaryThresholdChars()).isEqualTo(4000);
    }

    @Test
    void shouldSetAndGetAllFields() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();
        anchor.setEnabled(false);
        anchor.setClassifierModel("claude-haiku-4-5-20251001");
        anchor.setClassifierConfidenceThreshold(0.7);
        anchor.setDisabledPaths(Arrays.asList("/payment/**", "/admin/**"));
        anchor.setMaxContextChars(16000);
        anchor.setPreprocessEnabled(false);
        anchor.setPreprocessTimeoutMs(8000);
        anchor.setSummaryCacheTtlSeconds(1800);
        anchor.setSummaryThresholdChars(6000);

        assertThat(anchor.isEnabled()).isFalse();
        assertThat(anchor.getClassifierModel()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(anchor.getClassifierConfidenceThreshold()).isEqualTo(0.7);
        assertThat(anchor.getDisabledPaths()).containsExactly("/payment/**", "/admin/**");
        assertThat(anchor.getMaxContextChars()).isEqualTo(16000);
        assertThat(anchor.isPreprocessEnabled()).isFalse();
        assertThat(anchor.getPreprocessTimeoutMs()).isEqualTo(8000);
        assertThat(anchor.getSummaryCacheTtlSeconds()).isEqualTo(1800);
        assertThat(anchor.getSummaryThresholdChars()).isEqualTo(6000);
    }

    @Test
    void shouldIntegrateWithParentProperties() {
        SnapAgentProperties parent = new SnapAgentProperties();

        assertThat(parent.getAnchor()).isNotNull();
        assertThat(parent.getAnchor()).isInstanceOf(SnapAgentProperties.Anchor.class);
        assertThat(parent.getAnchor().isEnabled()).isTrue();
    }

    @Test
    void shouldAllowMutatingNestedAnchorConfig() {
        SnapAgentProperties parent = new SnapAgentProperties();
        parent.getAnchor().setEnabled(false);
        parent.getAnchor().setMaxContextChars(4000);

        assertThat(parent.getAnchor().isEnabled()).isFalse();
        assertThat(parent.getAnchor().getMaxContextChars()).isEqualTo(4000);
    }

    @Test
    void shouldHandleNullDisabledPathsAsEmpty() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();
        anchor.setDisabledPaths(null);

        // Even when explicitly set to null, getter should return empty list (defensive)
        assertThat(anchor.getDisabledPaths()).isNotNull().isEmpty();
    }

    @Test
    void shouldDetermineIfPathIsBlacklisted() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();
        anchor.setDisabledPaths(Arrays.asList("/payment/**", "/admin/security/**"));

        assertThat(anchor.isPathDisabled("/payment/checkout")).isTrue();
        assertThat(anchor.isPathDisabled("/payment")).isTrue();
        assertThat(anchor.isPathDisabled("/admin/security/users")).isTrue();
        assertThat(anchor.isPathDisabled("/docs/intro")).isFalse();
        assertThat(anchor.isPathDisabled("/orders/123")).isFalse();
    }

    @Test
    void shouldReturnFalseForBlacklistCheckWhenNoBlacklist() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();

        assertThat(anchor.isPathDisabled("/any/path")).isFalse();
    }

    @Test
    void shouldHandleExactMatchInBlacklist() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();
        anchor.setDisabledPaths(Arrays.asList("/exact/path"));

        assertThat(anchor.isPathDisabled("/exact/path")).isTrue();
        assertThat(anchor.isPathDisabled("/exact/path/sub")).isTrue();
        assertThat(anchor.isPathDisabled("/other")).isFalse();
    }

    // ---- GAP-7: resolveEffectiveTtl boundary values ----

    @ParameterizedTest(name = "[{index}] ttl={0} -> {1} ({2})")
    @MethodSource("resolveEffectiveTtlCases")
    void shouldResolveEffectiveTtlCorrectly(int requestedTtl, long expected, String description) {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();

        long result = anchor.resolveEffectiveTtl(requestedTtl);

        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> resolveEffectiveTtlCases() {
        // Defaults: min=60, max=604800, default=3600
        return Stream.of(
                // <= 0 → default (3600)
                Arguments.of(0, 3600L, "zero returns default"),
                Arguments.of(-1, 3600L, "negative returns default"),
                // < min → min (60)
                Arguments.of(30, 60L, "below min returns min"),
                Arguments.of(59, 60L, "just below min returns min"),
                // boundary: equals min → not raised
                Arguments.of(60, 60L, "equals min passes through"),
                // normal value
                Arguments.of(3600, 3600L, "normal value passes through"),
                // boundary: equals max → not capped
                Arguments.of(604800, 604800L, "equals max passes through"),
                // > max → max (604800)
                Arguments.of(999999, 604800L, "above max returns max"),
                Arguments.of(604801, 604800L, "just above max returns max")
        );
    }

    @Test
    void shouldRespectCustomMinAndMaxTtl() {
        SnapAgentProperties.Anchor anchor = new SnapAgentProperties.Anchor();
        anchor.setInjectionCacheMinTtlSeconds(120);
        anchor.setInjectionCacheMaxTtlSeconds(86400);
        anchor.setInjectionDefaultCacheTtl(600);

        // 0 → custom default
        assertThat(anchor.resolveEffectiveTtl(0)).isEqualTo(600L);
        // below custom min → custom min
        assertThat(anchor.resolveEffectiveTtl(60)).isEqualTo(120L);
        // above custom max → custom max
        assertThat(anchor.resolveEffectiveTtl(100000)).isEqualTo(86400L);
        // within range
        assertThat(anchor.resolveEffectiveTtl(3600)).isEqualTo(3600L);
    }
}
