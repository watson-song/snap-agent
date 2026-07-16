package cn.watsontech.snapagent.core.closure;

/**
 * Result of a verification run after a fix has been applied.
 */
public class VerificationResult {

    private String id;
    private boolean passed;
    private String summary;
    private String beforeStatus;
    private String afterStatus;
    private long verifiedAt;

    public VerificationResult() {
        this.verifiedAt = System.currentTimeMillis();
    }

    public VerificationResult(String id, boolean passed, String summary,
                               String beforeStatus, String afterStatus) {
        this.id = id;
        this.passed = passed;
        this.summary = summary;
        this.beforeStatus = beforeStatus;
        this.afterStatus = afterStatus;
        this.verifiedAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getBeforeStatus() { return beforeStatus; }
    public void setBeforeStatus(String beforeStatus) { this.beforeStatus = beforeStatus; }
    public String getAfterStatus() { return afterStatus; }
    public void setAfterStatus(String afterStatus) { this.afterStatus = afterStatus; }
    public long getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(long verifiedAt) { this.verifiedAt = verifiedAt; }

    @Override
    public String toString() {
        return "VerificationResult{id='" + id + "', passed=" + passed
                + ", summary='" + summary + "'}";
    }
}
