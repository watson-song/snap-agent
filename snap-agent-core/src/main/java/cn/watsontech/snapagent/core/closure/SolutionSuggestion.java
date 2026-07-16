package cn.watsontech.snapagent.core.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A solution suggestion containing multiple candidate options and a recommendation.
 */
public class SolutionSuggestion {

    private String id;
    private List<SolutionOption> options;
    private String recommendedOptionId;
    private String relatedCode;
    private String rationale;

    public SolutionSuggestion() {
        this.options = new ArrayList<SolutionOption>();
    }

    public SolutionSuggestion(String id, List<SolutionOption> options,
                               String recommendedOptionId, String relatedCode,
                               String rationale) {
        this.id = id;
        this.options = options != null ? new ArrayList<SolutionOption>(options)
                : new ArrayList<SolutionOption>();
        this.recommendedOptionId = recommendedOptionId;
        this.relatedCode = relatedCode;
        this.rationale = rationale;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public List<SolutionOption> getOptions() { return Collections.unmodifiableList(options); }
    public void setOptions(List<SolutionOption> options) {
        this.options = options != null ? new ArrayList<SolutionOption>(options)
                : new ArrayList<SolutionOption>();
    }
    public String getRecommendedOptionId() { return recommendedOptionId; }
    public void setRecommendedOptionId(String recommendedOptionId) { this.recommendedOptionId = recommendedOptionId; }
    public String getRelatedCode() { return relatedCode; }
    public void setRelatedCode(String relatedCode) { this.relatedCode = relatedCode; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    @Override
    public String toString() {
        return "SolutionSuggestion{id='" + id + "', options=" + options.size()
                + ", recommended=" + recommendedOptionId + "}";
    }
}
