package cn.watsontech.snapagent.boot2x.anchor;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
}
