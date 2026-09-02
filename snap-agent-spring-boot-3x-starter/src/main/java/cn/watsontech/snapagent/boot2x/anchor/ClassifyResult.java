package cn.watsontech.snapagent.boot2x.anchor;

/**
 * Result of the LLM skill classifier — contains the chosen skillId (may be null
 * if no skill matched) and a confidence score in [0, 1].
 *
 * <p>{@link #isMatch()} returns true when the confidence is at or above the
 * configured threshold ({@code snap-agent.anchor.classifier-confidence-threshold})
 * AND the skillId is not null.</p>
 */
public class ClassifyResult {

    private final String skillId;
    private final double confidence;
    private final String reason;
    private final double confidenceThreshold;

    public ClassifyResult(String skillId, double confidence, double confidenceThreshold) {
        this(skillId, confidence, confidenceThreshold, null);
    }

    public ClassifyResult(String skillId, double confidence,
                          double confidenceThreshold, String reason) {
        this.skillId = skillId;
        this.confidence = confidence;
        this.confidenceThreshold = confidenceThreshold;
        this.reason = reason;
    }

    /** Returns the skillId, or null if no skill matched. */
    public String getSkillId() { return skillId; }

    /** Returns the classifier confidence in [0, 1]. */
    public double getConfidence() { return confidence; }

    /** Optional reason for the classifier's decision (typically null when matched). */
    public String getReason() { return reason; }

    /** Returns true when the result is actionable: skillId is present and confidence >= threshold. */
    public boolean isMatch() {
        return skillId != null && !skillId.isEmpty() && confidence >= confidenceThreshold;
    }

    /** Returns a no-match result with confidence 0. */
    public static ClassifyResult noMatch() {
        return new ClassifyResult(null, 0.0, 0.5, "classifier failed or returned no skill");
    }
}
