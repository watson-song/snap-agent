package cn.watsontech.snapagent.core.issue;

/**
 * 问题闭环记录, 跟踪从诊断到沉淀的全生命周期。
 *
 * <p>Immutable value object: all fields are fixed at construction time.
 * Mutations are expressed via {@code with*} methods that return a new
 * {@link IssueClosure} instance, leaving the original unchanged.</p>
 *
 * <p>The {@code solution} field holds a {@link SolutionSuggestion} (nullable)
 * and {@code verificationResult} holds a {@link VerificationResult} (nullable).
 * Both are themselves immutable value objects, so no defensive copy is required
 * at this layer.</p>
 */
public final class IssueClosure {

    private final String issueId;
    private final String externalIssueId;
    private final String taskId;
    private final String conversationId;
    private final String userQuery;
    private final String rootCause;
    private final SolutionSuggestion solution;
    private final String selectedSolution;
    private final IssueStatus status;
    private final String fixCommitId;
    private final VerificationResult verificationResult;
    private final String knowledgeEntryId;
    private final long createdAt;
    private final long updatedAt;

    /**
     * Full-argument constructor.
     *
     * @param issueId            内部闭环 ID (UUID)
     * @param externalIssueId    外部 Issue ID (Jira/工单, 可空)
     * @param taskId             关联的诊断任务 ID
     * @param conversationId     关联的会话 ID (可空)
     * @param userQuery          用户原始问题
     * @param rootCause          根因摘要
     * @param solution           方案建议 (可空)
     * @param selectedSolution  用户选择的方案 ID (可空)
     * @param status             当前状态
     * @param fixCommitId        修复 commit (可空)
     * @param verificationResult 验证结果 (可空)
     * @param knowledgeEntryId   沉淀到知识库的条目 ID (可空)
     * @param createdAt          创建时间 (epoch millis)
     * @param updatedAt          更新时间 (epoch millis)
     */
    public IssueClosure(String issueId, String externalIssueId, String taskId,
                        String conversationId, String userQuery, String rootCause,
                        SolutionSuggestion solution, String selectedSolution,
                        IssueStatus status, String fixCommitId,
                        VerificationResult verificationResult, String knowledgeEntryId,
                        long createdAt, long updatedAt) {
        this.issueId = issueId;
        this.externalIssueId = externalIssueId;
        this.taskId = taskId;
        this.conversationId = conversationId;
        this.userQuery = userQuery;
        this.rootCause = rootCause;
        this.solution = solution;
        this.selectedSolution = selectedSolution;
        this.status = status;
        this.fixCommitId = fixCommitId;
        this.verificationResult = verificationResult;
        this.knowledgeEntryId = knowledgeEntryId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 内部闭环 ID (UUID)。 */
    public String getIssueId() {
        return issueId;
    }

    /** 外部 Issue ID (Jira/工单, 可空)。 */
    public String getExternalIssueId() {
        return externalIssueId;
    }

    /** 关联的诊断任务 ID。 */
    public String getTaskId() {
        return taskId;
    }

    /** 关联的会话 ID (可空)。 */
    public String getConversationId() {
        return conversationId;
    }

    /** 用户原始问题。 */
    public String getUserQuery() {
        return userQuery;
    }

    /** 根因摘要。 */
    public String getRootCause() {
        return rootCause;
    }

    /**
     * 方案建议 (可空)。
     *
     * @return the SolutionSuggestion, or null if not yet proposed
     */
    public SolutionSuggestion getSolution() {
        return solution;
    }

    /** 用户选择的方案 ID (可空)。 */
    public String getSelectedSolution() {
        return selectedSolution;
    }

    /** 当前状态。 */
    public IssueStatus getStatus() {
        return status;
    }

    /** 修复 commit (可空)。 */
    public String getFixCommitId() {
        return fixCommitId;
    }

    /**
     * 验证结果 (可空)。
     *
     * @return the VerificationResult, or null if not yet verified
     */
    public VerificationResult getVerificationResult() {
        return verificationResult;
    }

    /** 沉淀到知识库的条目 ID (可空)。 */
    public String getKnowledgeEntryId() {
        return knowledgeEntryId;
    }

    /** 创建时间 (epoch millis)。 */
    public long getCreatedAt() {
        return createdAt;
    }

    /** 更新时间 (epoch millis)。 */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 返回一个状态和更新时间变更后的新实例, 其余字段不变。
     *
     * @param status    新状态
     * @param updatedAt 新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated status and updatedAt
     */
    public IssueClosure withStatus(IssueStatus status, long updatedAt) {
        return new IssueClosure(
                this.issueId, this.externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                this.solution, this.selectedSolution,
                status, this.fixCommitId,
                this.verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    /**
     * 返回一个外部 Issue ID、状态和更新时间变更后的新实例, 其余字段不变。
     *
     * @param externalIssueId 外部 Issue ID
     * @param status           新状态
     * @param updatedAt        新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated externalIssueId, status and updatedAt
     */
    public IssueClosure withExternalIssue(String externalIssueId, IssueStatus status, long updatedAt) {
        return new IssueClosure(
                this.issueId, externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                this.solution, this.selectedSolution,
                status, this.fixCommitId,
                this.verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    /**
     * 返回一个外部 Issue ID、选择方案、状态和更新时间变更后的新实例, 其余字段不变。
     *
     * <p>{@code selectedSolution} 保留为 String (用户选择方案的 ID, 而非
     * {@link SolutionSuggestion} 整体)。</p>
     *
     * @param externalIssueId  外部 Issue ID
     * @param selectedSolution 用户选择的方案 ID
     * @param status            新状态
     * @param updatedAt         新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated externalIssueId, selectedSolution, status and updatedAt
     */
    public IssueClosure withExternalIssue(String externalIssueId, String selectedSolution,
                                          IssueStatus status, long updatedAt) {
        return new IssueClosure(
                this.issueId, externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                this.solution, selectedSolution,
                status, this.fixCommitId,
                this.verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    /**
     * 返回一个方案建议和更新时间变更后的新实例, 其余字段不变。
     *
     * @param solution  方案建议
     * @param updatedAt 新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated solution and updatedAt
     */
    public IssueClosure withSolution(SolutionSuggestion solution, long updatedAt) {
        return new IssueClosure(
                this.issueId, this.externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                solution, this.selectedSolution,
                this.status, this.fixCommitId,
                this.verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    /**
     * 返回一个验证结果和更新时间变更后的新实例, 其余字段不变。
     *
     * @param verificationResult 验证结果
     * @param updatedAt          新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated verificationResult and updatedAt
     */
    public IssueClosure withVerification(VerificationResult verificationResult, long updatedAt) {
        return new IssueClosure(
                this.issueId, this.externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                this.solution, this.selectedSolution,
                this.status, this.fixCommitId,
                verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    /**
     * 返回一个知识条目 ID 和更新时间变更后的新实例, 其余字段不变。
     *
     * @param knowledgeEntryId 沉淀到知识库的条目 ID
     * @param updatedAt        新的更新时间 (epoch millis)
     * @return new {@link IssueClosure} with updated knowledgeEntryId and updatedAt
     */
    public IssueClosure withKnowledgeEntry(String knowledgeEntryId, long updatedAt) {
        return new IssueClosure(
                this.issueId, this.externalIssueId, this.taskId,
                this.conversationId, this.userQuery, this.rootCause,
                this.solution, this.selectedSolution,
                this.status, this.fixCommitId,
                this.verificationResult, knowledgeEntryId,
                this.createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "IssueClosure{issueId='" + issueId + "', externalIssueId='"
                + externalIssueId + "', taskId='" + taskId
                + "', status=" + status + ", solution="
                + (solution != null ? "present(" + solution.getOptions().size() + " options)" : "null")
                + ", knowledgeEntryId='" + knowledgeEntryId + "', updatedAt="
                + updatedAt + "}";
    }
}
