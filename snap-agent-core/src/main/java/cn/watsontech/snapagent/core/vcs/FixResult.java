package cn.watsontech.snapagent.core.vcs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FixResult {
    private final boolean success;
    private final String commitId;
    private final String prUrl;
    private final String prNumber;
    private final List<String> changedFiles;
    private final String errorMessage;

    private FixResult(boolean success, String commitId, String prUrl, String prNumber,
                      List<String> changedFiles, String errorMessage) {
        this.success = success;
        this.commitId = commitId;
        this.prUrl = prUrl;
        this.prNumber = prNumber;
        this.changedFiles = changedFiles != null ? new ArrayList<>(changedFiles) : new ArrayList<>();
        this.errorMessage = errorMessage;
    }

    public static FixResult success(String commitId, String prUrl, String prNumber, List<String> changedFiles) {
        return new FixResult(true, commitId, prUrl, prNumber, changedFiles, null);
    }

    public static FixResult failed(String errorMessage) {
        return new FixResult(false, null, null, null, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public String getCommitId() { return commitId; }
    public String getPrUrl() { return prUrl; }
    public String getPrNumber() { return prNumber; }
    public List<String> getChangedFiles() { return Collections.unmodifiableList(changedFiles); }
    public String getErrorMessage() { return errorMessage; }
}
