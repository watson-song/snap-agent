package cn.watsontech.snapagent.boot2x.anchor;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterized unit tests for {@link AnchorInjectionOrchestrator#stripThinking(String)}.
 *
 * <p>Verifies that LLM "thinking" prefixes (English, Chinese) are stripped
 * before the first real HTML tag, and that edge cases (null, empty, DOCTYPE,
 * no-prefix) are handled correctly (TDD_SPEC GAP-1, AC4).</p>
 */
class StripThinkingTest {

    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("stripThinkingCases")
    void shouldStripThinkingPrefixWhenPresent(String raw, String expected, String description) {
        String result = AnchorInjectionOrchestrator.stripThinking(raw);
        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> stripThinkingCases() {
        return Stream.of(
                // English prefix before HTML
                Arguments.of(
                        "Let me think...<div>x</div>",
                        "<div>x</div>",
                        "英文前缀"),
                // Chinese prefix before HTML
                Arguments.of(
                        "让我想想<p>hi</p>",
                        "<p>hi</p>",
                        "中文前缀"),
                // No prefix — direct HTML
                Arguments.of(
                        "<div>direct</div>",
                        "<div>direct</div>",
                        "无前缀"),
                // DOCTYPE declaration should be preserved
                Arguments.of(
                        "<!DOCTYPE html>",
                        "<!DOCTYPE html>",
                        "DOCTYPE"),
                // null input → null output
                Arguments.of(
                        null,
                        null,
                        "null"),
                // Empty string → empty string
                Arguments.of(
                        "",
                        "",
                        "空串")
        );
    }
}
