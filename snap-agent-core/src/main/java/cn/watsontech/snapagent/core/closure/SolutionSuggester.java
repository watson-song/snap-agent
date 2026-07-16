package cn.watsontech.snapagent.core.closure;

/**
 * SPI for generating solution suggestions based on diagnostic results.
 *
 * <p>Implementations analyze the issue's root cause, transcript, and optionally
 * the code graph to produce candidate solutions with recommendations.</p>
 */
public interface SolutionSuggester {

    /**
     * Generate a solution suggestion for the given issue.
     *
     * @param issue             the issue closure record (may not have solution yet)
     * @param transcriptSummary summary of the diagnostic transcript (root cause analysis)
     * @return a solution suggestion with candidate options and a recommendation
     */
    SolutionSuggestion suggest(IssueClosure issue, String transcriptSummary);
}
