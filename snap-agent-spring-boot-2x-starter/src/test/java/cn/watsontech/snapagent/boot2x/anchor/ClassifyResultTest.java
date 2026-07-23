package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClassifyResult}.
 *
 * <p>Validates the isMatch() threshold boundary logic: confidence must be
 * at or above the configured threshold AND skillId must be non-null/non-empty
 * for the result to be considered a match.</p>
 */
class ClassifyResultTest {

    // ---- G-414: isMatch threshold boundary ----

    @Test
    @DisplayName("G-414: isMatch should return true when confidence equals threshold")
    void shouldMatchWhenConfidenceEqualsThreshold() {
        ClassifyResult result = new ClassifyResult("patrol", 0.5, 0.5);

        assertThat(result.isMatch()).isTrue();
    }

    @Test
    @DisplayName("G-414: isMatch should return true when confidence exceeds threshold")
    void shouldMatchWhenConfidenceExceedsThreshold() {
        ClassifyResult result = new ClassifyResult("patrol", 0.9, 0.5);

        assertThat(result.isMatch()).isTrue();
    }

    @Test
    @DisplayName("G-414: isMatch should return false when confidence is below threshold")
    void shouldNotMatchWhenConfidenceBelowThreshold() {
        ClassifyResult result = new ClassifyResult("patrol", 0.3, 0.5);

        assertThat(result.isMatch()).isFalse();
    }

    @Test
    @DisplayName("G-414: isMatch should return false when skillId is null")
    void shouldNotMatchWhenSkillIdIsNull() {
        ClassifyResult result = new ClassifyResult(null, 0.9, 0.5);

        assertThat(result.isMatch()).isFalse();
    }

    @Test
    @DisplayName("G-414: isMatch should return false when skillId is empty string")
    void shouldNotMatchWhenSkillIdIsEmpty() {
        ClassifyResult result = new ClassifyResult("", 0.9, 0.5);

        assertThat(result.isMatch()).isFalse();
    }

    // ---- G-414: noMatch() factory ----

    @Test
    @DisplayName("G-414: noMatch() should return null skillId and zero confidence")
    void shouldNoMatchReturnNullSkillIdAndZeroConfidence() {
        ClassifyResult result = ClassifyResult.noMatch();

        assertThat(result.getSkillId()).isNull();
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.isMatch()).isFalse();
    }

    @Test
    @DisplayName("G-414: noMatch() should have a reason message")
    void shouldNoMatchHaveReasonMessage() {
        ClassifyResult result = ClassifyResult.noMatch();

        assertThat(result.getReason()).isNotNull();
        assertThat(result.getReason()).isNotEmpty();
    }

    // ---- G-414: parameterized boundary tests ----

    static Stream<Arguments> isMatchBoundaryData() {
        return Stream.of(
                // confidence at threshold → match
                Arguments.of("patrol", 0.5, 0.5, true),
                // confidence above threshold → match
                Arguments.of("patrol", 0.51, 0.5, true),
                // confidence below threshold → no match
                Arguments.of("patrol", 0.49, 0.5, false),
                // confidence at 1.0 → match
                Arguments.of("patrol", 1.0, 0.5, true),
                // confidence at 0.0 → no match
                Arguments.of("patrol", 0.0, 0.5, false),
                // null skillId at threshold → no match
                Arguments.of(null, 0.5, 0.5, false),
                // empty skillId above threshold → no match
                Arguments.of("", 0.9, 0.5, false)
        );
    }

    @ParameterizedTest(name = "skillId={0}, confidence={1}, threshold={2} → isMatch={3}")
    @MethodSource("isMatchBoundaryData")
    @DisplayName("G-414: isMatch boundary parameterized")
    void shouldEvaluateIsMatchCorrectlyAtBoundaries(String skillId, double confidence,
                                                      double threshold, boolean expected) {
        ClassifyResult result = new ClassifyResult(skillId, confidence, threshold);

        assertThat(result.isMatch()).isEqualTo(expected);
    }

    // ---- Constructor with reason ----

    @Test
    @DisplayName("G-414: four-arg constructor should set reason")
    void shouldSetReasonWithFourArgConstructor() {
        ClassifyResult result = new ClassifyResult("patrol", 0.9, 0.5, "ops match");

        assertThat(result.getReason()).isEqualTo("ops match");
        assertThat(result.getSkillId()).isEqualTo("patrol");
        assertThat(result.getConfidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("G-414: three-arg constructor should set null reason")
    void shouldSetNullReasonWithThreeArgConstructor() {
        ClassifyResult result = new ClassifyResult("patrol", 0.9, 0.5);

        assertThat(result.getReason()).isNull();
    }
}
