package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SolutionSuggestion}.
 */
class SolutionSuggestionTest {

    private SolutionOption opt(String id, String effort, boolean temporary) {
        return new SolutionOption(id, "title-" + id, "desc-" + id, effort, temporary);
    }

    @Test
    void shouldReturnConstructorValuesFromGetters() {
        List<SolutionOption> options = Arrays.asList(
                opt("a", "low", false),
                opt("b", "medium", true));
        SolutionSuggestion suggestion = new SolutionSuggestion(
                options, "a", "推荐低成本方案", "com.example.Foo");

        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions().get(0).getId()).isEqualTo("a");
        assertThat(suggestion.getOptions().get(1).getId()).isEqualTo("b");
        assertThat(suggestion.getRecommendedOptionId()).isEqualTo("a");
        assertThat(suggestion.getRationale()).isEqualTo("推荐低成本方案");
        assertThat(suggestion.getRelatedCode()).isEqualTo("com.example.Foo");
    }

    @Test
    void shouldDefensivelyCopyOptionsOnConstruction() {
        List<SolutionOption> original = new ArrayList<>();
        original.add(opt("a", "low", false));
        original.add(opt("b", "high", false));

        SolutionSuggestion suggestion = new SolutionSuggestion(
                original, "a", null, null);

        // Mutate the original list — suggestion's internal copy should be unaffected
        original.add(opt("c", "low", true));
        original.set(0, opt("hacked", "high", false));

        assertThat(suggestion.getOptions()).hasSize(2);
        assertThat(suggestion.getOptions().get(0).getId()).isEqualTo("a");
        assertThat(suggestion.getOptions()).extracting(SolutionOption::getId)
                .doesNotContain("c", "hacked");
    }

    @Test
    void shouldReturnUnmodifiableOptionsFromGetter() {
        SolutionSuggestion suggestion = new SolutionSuggestion(
                Arrays.asList(opt("a", "low", false)), "a", null, null);

        assertThatThrownBy(() -> suggestion.getOptions().add(opt("b", "low", false)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> suggestion.getOptions().set(0, opt("x", "low", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHaveEmptyOptionsWhenNullPassed() {
        SolutionSuggestion suggestion = new SolutionSuggestion(
                null, null, null, null);

        assertThat(suggestion.getOptions()).isNotNull();
        assertThat(suggestion.getOptions()).isEmpty();
        assertThat(suggestion.getRecommendedOptionId()).isNull();
        assertThat(suggestion.getRationale()).isNull();
        assertThat(suggestion.getRelatedCode()).isNull();
    }

    @Test
    void toStringShouldContainOptionsCountAndRecommendedId() {
        SolutionSuggestion suggestion = new SolutionSuggestion(
                Arrays.asList(opt("a", "low", false), opt("b", "high", false)),
                "a", "成本最低", null);

        String str = suggestion.toString();
        assertThat(str).contains("SolutionSuggestion");
        assertThat(str).contains("a");
        assertThat(str).contains("成本最低");
        // Should report option count rather than full list dump
        assertThat(str).contains("2");
    }
}
