package cn.watsontech.snapagent.core.issue;

/**
 * Per-criterion verification result. Immutable value object.
 */
public final class VerificationDetail {
    private final String criterionId;
    private final String description;
    private final boolean passed;
    private final String actual;
    private final String expected;

    public VerificationDetail(String criterionId, String description,
                             boolean passed, String actual, String expected) {
        this.criterionId = criterionId;
        this.description = description;
        this.passed = passed;
        this.actual = actual;
        this.expected = expected;
    }

    public String getCriterionId() { return criterionId; }
    public String getDescription() { return description; }
    public boolean isPassed() { return passed; }
    public String getActual() { return actual; }
    public String getExpected() { return expected; }

    @Override
    public String toString() {
        return "VerificationDetail{id='" + criterionId + "', passed=" + passed + "}";
    }
}
