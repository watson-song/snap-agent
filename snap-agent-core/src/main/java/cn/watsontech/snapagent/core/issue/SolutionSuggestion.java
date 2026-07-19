package cn.watsontech.snapagent.core.issue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggested solutions for an issue, containing multiple {@link SolutionOption}s
 * with a recommended option and rationale.
 *
 * <p>Immutable value object. The options list is defensively copied.</p>
 */
public final class SolutionSuggestion {
    private final List<SolutionOption> options;
    private final String recommendedOptionId;
    private final String rationale;
    private final String relatedCode;

    public SolutionSuggestion(List<SolutionOption> options, String recommendedOptionId, String rationale, String relatedCode) {
        this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
        this.recommendedOptionId = recommendedOptionId;
        this.rationale = rationale;
        this.relatedCode = relatedCode;
    }

    public List<SolutionOption> getOptions() { return Collections.unmodifiableList(options); }
    public String getRecommendedOptionId() { return recommendedOptionId; }
    public String getRationale() { return rationale; }
    public String getRelatedCode() { return relatedCode; }

    @Override
    public String toString() {
        return "SolutionSuggestion{options=" + options.size() + ", recommendedOptionId='" + recommendedOptionId + "', rationale='" + rationale + "'}";
    }
}
