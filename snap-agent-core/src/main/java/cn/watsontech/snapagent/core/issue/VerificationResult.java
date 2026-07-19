package cn.watsontech.snapagent.core.issue;

/**
 * Result of verifying a fix for an issue.
 *
 * <p>Immutable value object.</p>
 */
public final class VerificationResult {
    private final boolean passed;
    private final String summary;
    private final String beforeStatus;
    private final String afterStatus;
    private final long verifiedAt;

    public VerificationResult(boolean passed, String summary, String beforeStatus, String afterStatus, long verifiedAt) {
        this.passed = passed;
        this.summary = summary;
        this.beforeStatus = beforeStatus;
        this.afterStatus = afterStatus;
        this.verifiedAt = verifiedAt;
    }

    public boolean isPassed() { return passed; }
    public String getSummary() { return summary; }
    public String getBeforeStatus() { return beforeStatus; }
    public String getAfterStatus() { return afterStatus; }
    public long getVerifiedAt() { return verifiedAt; }

    @Override
    public String toString() {
        return "VerificationResult{passed=" + passed + ", summary='" + summary + "', beforeStatus='" + beforeStatus + "', afterStatus='" + afterStatus + "', verifiedAt=" + verifiedAt + "}";
    }
}
