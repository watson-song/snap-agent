package cn.watsontech.snapagent.core.knowledge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SearchResult} value object (GAP-7).
 *
 * <p>Verifies getters return exactly the values supplied at construction time,
 * and that the score is preserved verbatim (no rounding/clamping at this layer;
 * clamping is the searcher's responsibility).</p>
 */
class SearchResultTest {

    private KnowledgeFragment sampleFragment() {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("category", "诊断手册");
        return new KnowledgeFragment(
                "数据库诊断", "连接池打满排查步骤", "db-diagnostics.md:section-2", metadata);
    }

    @Test
    void getFragment_shouldReturnExactFragmentSuppliedAtConstruction() {
        KnowledgeFragment fragment = sampleFragment();
        SearchResult result = new SearchResult(fragment, 0.85);

        assertThat(result.getFragment()).isSameAs(fragment);
        assertThat(result.getFragment().getTitle()).isEqualTo("数据库诊断");
        assertThat(result.getFragment().getSource()).isEqualTo("db-diagnostics.md:section-2");
    }

    @Test
    void getScore_shouldReturnExactScoreSuppliedAtConstruction() {
        SearchResult result = new SearchResult(sampleFragment(), 0.42);

        assertThat(result.getScore()).isEqualTo(0.42);
    }

    @Test
    void getScore_shouldPreserveBoundaryValuesZeroAndOne() {
        KnowledgeFragment fragment = sampleFragment();

        SearchResult zero = new SearchResult(fragment, 0.0);
        assertThat(zero.getScore()).isEqualTo(0.0);

        SearchResult one = new SearchResult(fragment, 1.0);
        assertThat(one.getScore()).isEqualTo(1.0);
    }

    @Test
    void getFragment_shouldAllowNullFragmentForDegradedResults() {
        // SearchResult does not forbid null fragments — it is a transparent
        // value object. Callers (KnowledgeBase) never pass null, but the
        // value object itself should not synthesize a non-null substitute.
        SearchResult result = new SearchResult(null, 0.0);

        assertThat(result.getFragment()).isNull();
        assertThat(result.getScore()).isEqualTo(0.0);
    }

    @Test
    void shouldPreserveMetadataFromFragmentAndMetadataIsUnmodifiable() {
        KnowledgeFragment fragment = sampleFragment();
        SearchResult result = new SearchResult(fragment, 0.9);

        Map<String, String> metadata = result.getFragment().getMetadata();
        assertThat(metadata).containsEntry("category", "诊断手册");
        // Metadata must be unmodifiable (defensive copy on the fragment)
        assertThatThrownBy(() -> metadata.put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldConstructWithFragmentHavingNullMetadata() {
        KnowledgeFragment fragment = new KnowledgeFragment(
                "标题", "内容", "src", null);
        SearchResult result = new SearchResult(fragment, 0.5);

        assertThat(result.getFragment().getMetadata()).isEmpty();
        assertThat(result.getScore()).isEqualTo(0.5);
    }

    @Test
    void shouldKeepFragmentAndScoreIndependentAcrossInstances() {
        // Two SearchResults built from the same fragment but different scores
        // must each report their own score.
        KnowledgeFragment fragment = sampleFragment();
        SearchResult r1 = new SearchResult(fragment, 0.1);
        SearchResult r2 = new SearchResult(fragment, 0.9);

        assertThat(r1.getScore()).isEqualTo(0.1);
        assertThat(r2.getScore()).isEqualTo(0.9);
        assertThat(r1.getFragment()).isSameAs(r2.getFragment());
    }
}
