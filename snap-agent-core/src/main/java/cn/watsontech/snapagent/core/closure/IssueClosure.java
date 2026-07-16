package cn.watsontech.snapagent.core.closure;

/**
 * Full lifecycle record of a problem in the Q&A closed loop.
 *
 * <p>Tracks the journey from diagnosis → solution → issue → fix → verification → closure.</p>
 */
public class IssueClosure {

    private String id;
    private String conversationId;
    private String taskId;
    private String skillName;
    private String rootCauseSummary;
    private SolutionSuggestion solution;
    private IssueStatus status;
    private String externalIssueId;
    private VerificationResult verification;
    private String knowledgeEntryId;
    private long createdAt;
    private long updatedAt;

    public IssueClosure() {
        this.status = IssueStatus.DIAGNOSED;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public IssueClosure(String id, String conversationId, String taskId,
                         String skillName, String rootCauseSummary) {
        this.id = id;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.skillName = skillName;
        this.rootCauseSummary = rootCauseSummary;
        this.status = IssueStatus.DIAGNOSED;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getRootCauseSummary() { return rootCauseSummary; }
    public void setRootCauseSummary(String rootCauseSummary) { this.rootCauseSummary = rootCauseSummary; }
    public SolutionSuggestion getSolution() { return solution; }
    public void setSolution(SolutionSuggestion solution) { this.solution = solution; }
    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }
    public String getExternalIssueId() { return externalIssueId; }
    public void setExternalIssueId(String externalIssueId) { this.externalIssueId = externalIssueId; }
    public VerificationResult getVerification() { return verification; }
    public void setVerification(VerificationResult verification) { this.verification = verification; }
    public String getKnowledgeEntryId() { return knowledgeEntryId; }
    public void setKnowledgeEntryId(String knowledgeEntryId) { this.knowledgeEntryId = knowledgeEntryId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "IssueClosure{id='" + id + "', status=" + status
                + ", skill=" + skillName + "}";
    }
}
