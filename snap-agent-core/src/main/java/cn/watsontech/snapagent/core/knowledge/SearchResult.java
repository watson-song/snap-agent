package cn.watsontech.snapagent.core.knowledge;

/**
 * A search result containing a {@link KnowledgeFragment} and its relevance score.
 *
 * <p>Returned by {@link KnowledgeBase#searchWithScores(String, int, double)}.
 * The score is in the range [0.0, 1.0] as defined by the {@link KnowledgeSearcher}.</p>
 */
public final class SearchResult {

    private final KnowledgeFragment fragment;
    private final double score;

    public SearchResult(KnowledgeFragment fragment, double score) {
        this.fragment = fragment;
        this.score = score;
    }

    public KnowledgeFragment getFragment() {
        return fragment;
    }

    public double getScore() {
        return score;
    }
}
