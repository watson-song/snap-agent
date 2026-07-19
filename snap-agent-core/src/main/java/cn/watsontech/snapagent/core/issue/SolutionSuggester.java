package cn.watsontech.snapagent.core.issue;

/**
 * SPI for generating solution suggestions from a diagnosed issue.
 */
public interface SolutionSuggester {
    /**
     * Generate a solution suggestion for the given issue.
     *
     * @param issue             the diagnosed issue
     * @param transcriptSummary summary of the diagnostic transcript (may be null)
     * @return a SolutionSuggestion with candidate options, never null
     */
    SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary);
}
