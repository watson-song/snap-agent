package cn.watsontech.snapagent.core.patrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A suggestion for fixing a detected issue, typically produced by LLM analysis
 * of a patrol task transcript.
 */
public class BugfixSuggestion {

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    private String taskId;
    private String rootCause;
    private List<String> affectedFiles;
    private String suggestion;
    private String confidence;
    private List<String> commitRefs;

    public BugfixSuggestion() {
        this.affectedFiles = new ArrayList<String>();
        this.commitRefs = new ArrayList<String>();
    }

    public BugfixSuggestion(String taskId, String rootCause, List<String> affectedFiles,
                            String suggestion, String confidence, List<String> commitRefs) {
        this.taskId = taskId;
        this.rootCause = rootCause;
        this.affectedFiles = affectedFiles == null ? new ArrayList<String>()
                : new ArrayList<String>(affectedFiles);
        this.suggestion = suggestion;
        this.confidence = confidence;
        this.commitRefs = commitRefs == null ? new ArrayList<String>()
                : new ArrayList<String>(commitRefs);
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public List<String> getAffectedFiles() { return affectedFiles; }
    public void setAffectedFiles(List<String> affectedFiles) {
        this.affectedFiles = affectedFiles == null ? new ArrayList<String>()
                : new ArrayList<String>(affectedFiles);
    }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public List<String> getCommitRefs() { return commitRefs; }
    public void setCommitRefs(List<String> commitRefs) {
        this.commitRefs = commitRefs == null ? new ArrayList<String>()
                : new ArrayList<String>(commitRefs);
    }

    @Override
    public String toString() {
        return "BugfixSuggestion{taskId='" + taskId + "', confidence='" + confidence
                + "', rootCause='" + rootCause + "'}";
    }
}
