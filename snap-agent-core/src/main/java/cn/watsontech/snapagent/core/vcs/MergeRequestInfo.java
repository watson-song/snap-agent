package cn.watsontech.snapagent.core.vcs;

public final class MergeRequestInfo {
    private final String prUrl;
    private final String prNumber;
    private final String commitSha;

    public MergeRequestInfo(String prUrl, String prNumber, String commitSha) {
        this.prUrl = prUrl;
        this.prNumber = prNumber;
        this.commitSha = commitSha;
    }

    public String getPrUrl() { return prUrl; }
    public String getPrNumber() { return prNumber; }
    public String getCommitSha() { return commitSha; }

    @Override
    public String toString() {
        return "MergeRequestInfo{prUrl='" + prUrl + "', prNumber='" + prNumber + "', commitSha='" + commitSha + "'}";
    }
}
