package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService;
import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.issue.AcceptanceCriterion;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggester;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.issue.VerificationRunner;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.vcs.FixResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestration service for the issue closure lifecycle:
 * diagnose -> propose solution -> create issue -> verify -> close + sediment.
 *
 * <p>Connects {@link AgentService} (for running solution-suggest/verify-fix skills),
 * {@link IssueStore} (for persistence), {@link IssueTracker} (for external issue
 * systems), and optionally a {@link KnowledgeSedimentationService} (for experience
 * sedimentation into the vector store).</p>
 *
 * <p>The {@link KnowledgeSedimentationService} dependency may be {@code null} when
 * knowledge features are disabled; in that case, close() still records the
 * knowledge entry ID but does not sediment into the vector store.</p>
 */
public class IssueClosureService {

    private static final Logger log = LoggerFactory.getLogger(IssueClosureService.class);

    private final AgentService agentService;
    private final TaskStore taskStore;
    private final SkillRegistry skillRegistry;
    private final IssueStore issueStore;
    private final IssueTracker issueTracker;
    private final KnowledgeSedimentationService sedimentationService;
    private final SolutionSuggester solutionSuggester;
    private final VerificationRunner verificationRunner;
    private final String systemUserId;
    private final FixExecutionService fixExecutionService;

    /**
     * Construct the issue closure service.
     *
     * @param agentService         the agent executor (for running skills synchronously)
     * @param taskStore             the task store (for looking up diagnostic tasks)
     * @param skillRegistry         the skill registry (for resolving skill metadata)
     * @param issueStore            the issue store (for persistence)
     * @param issueTracker          the issue tracker (for external issue systems)
     * @param sedimentationService  the knowledge sedimentation service (may be {@code null} if knowledge disabled)
     * @param solutionSuggester     the solution suggester (may be {@code null} to fall back to skill-based suggestion)
     * @param verificationRunner    the verification runner (may be {@code null} to fall back to skill-based verification)
     * @param systemUserId          the system user ID used when executing skills
     * @param fixExecutionService   the fix execution service (may be {@code null} if auto-fix disabled)
     */
    public IssueClosureService(AgentService agentService,
                                TaskStore taskStore,
                                SkillRegistry skillRegistry,
                                IssueStore issueStore,
                                IssueTracker issueTracker,
                                KnowledgeSedimentationService sedimentationService,
                                SolutionSuggester solutionSuggester,
                                VerificationRunner verificationRunner,
                                String systemUserId,
                                FixExecutionService fixExecutionService) {
        this.agentService = agentService;
        this.taskStore = taskStore;
        this.skillRegistry = skillRegistry;
        this.issueStore = issueStore;
        this.issueTracker = issueTracker;
        this.sedimentationService = sedimentationService;
        this.solutionSuggester = solutionSuggester;
        this.verificationRunner = verificationRunner;
        this.systemUserId = systemUserId;
        this.fixExecutionService = fixExecutionService;
    }

    /**
     * Propose solutions for a completed diagnostic task.
     *
     * <p>Loads the diagnostic task, extracts the root cause from its report,
     * then produces a {@link SolutionSuggestion}. When a {@link SolutionSuggester}
     * is configured, it is invoked directly; otherwise the "solution-suggest"
     * skill is run and its output is parsed into candidate options. The
     * resulting issue closure has status {@link IssueStatus#SOLUTION_PROPOSED}.</p>
     *
     * @param taskId the diagnostic task ID
     * @return the created issue closure, or {@code null} if the task or skill is not found
     */
    public IssueClosure proposeSolution(String taskId) {
        AgentTask task = taskStore.get(taskId);
        if (task == null) {
            log.warn("Task not found for proposeSolution: {}", taskId);
            return null;
        }

        String rootCause = task.getReport();
        String userQuery = extractUserQuery(task.getInputs());
        String userId = task.getUserId();

        long now = System.currentTimeMillis();
        // Build the issue first with DIAGNOSED status and no solution, so the
        // suggester (if any) receives an issue without a pre-existing solution.
        IssueClosure issue = new IssueClosure(
                "issue_" + now + "_" + randomSuffix(),
                null,
                taskId,
                null,
                userId,
                userQuery,
                rootCause,
                null,
                null,
                IssueStatus.DIAGNOSED,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );

        SolutionSuggestion suggestion;
        if (solutionSuggester != null) {
            log.info("Proposing solutions for task {} via SolutionSuggester", taskId);
            suggestion = solutionSuggester.suggest(issue, rootCause);
            if (suggestion == null) {
                suggestion = new SolutionSuggestion(
                        new ArrayList<SolutionOption>(), null, null, null, null);
            }
        } else {
            suggestion = suggestViaSkill(taskId, rootCause, userQuery);
            if (suggestion == null) {
                // Skill not found in fallback path — preserve legacy null result.
                return null;
            }
        }

        long updated = System.currentTimeMillis();
        issue = issue.withSolution(suggestion, updated)
                .withStatus(IssueStatus.SOLUTION_PROPOSED, updated);
        issueStore.save(issue);
        log.info("Created issue {} with {} option(s) for task {}",
                issue.getIssueId(),
                suggestion.getOptions() != null ? suggestion.getOptions().size() : 0,
                taskId);
        return issue;
    }

    /**
     * Fallback: runs the "solution-suggest" skill and parses its multi-line
     * output into a {@link SolutionSuggestion} whose options each map to one
     * non-empty line (id "opt-N", effort "medium", temporary=false).
     *
     * @return the suggestion, or {@code null} if the "solution-suggest" skill
     *         is not registered (preserving the legacy null result).
     */
    private SolutionSuggestion suggestViaSkill(String taskId, String rootCause, String userQuery) {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("root_cause", rootCause != null ? rootCause : "");
        inputs.put("original_query", userQuery != null ? userQuery : "");
        inputs.put("task_id", taskId);

        SkillMeta skill = skillRegistry.get("solution-suggest");
        if (skill == null) {
            log.error("Skill 'solution-suggest' not found in registry");
            return null;
        }

        AgentTask solutionTask = AgentTask.create(systemUserId, "solution-suggest", inputs, null);
        agentService.execute(solutionTask, skill);

        List<String> lines = parseSolutionLines(solutionTask.getReport());
        List<SolutionOption> options = new ArrayList<SolutionOption>();
        int index = 1;
        for (String line : lines) {
            String id = "opt-" + index;
            options.add(new SolutionOption(id, line, line, "medium", false));
            index++;
        }
        String recommended = options.isEmpty() ? null : "opt-1";
        return new SolutionSuggestion(options, recommended,
                "Generated from solution-suggest skill output.", null, null);
    }

    /**
     * Create an external issue for the given task, recording the user's selected solution.
     *
     * @param taskId           the diagnostic task ID
     * @param selectedSolution the user's selected solution text
     * @return the updated issue closure, or {@code null} if no issue exists for the task
     *         or the issue is not in {@link IssueStatus#SOLUTION_PROPOSED} /
     *         {@link IssueStatus#FIX_IN_PROGRESS} status
     */
    public IssueClosure createExternalIssue(String taskId, String selectedSolution) {
        IssueClosure issue = issueStore.findByTaskId(taskId);
        if (issue == null) {
            log.warn("Issue not found for taskId: {}", taskId);
            return null;
        }

        // Status guard: only SOLUTION_PROPOSED (normal entry) and FIX_IN_PROGRESS
        // (recovery when a previous noop tracker returned null) may create an
        // external issue. Terminal statuses (VERIFIED, CLOSED, FAILED) and
        // pre-solution statuses (DIAGNOSED, ISSUE_CREATED) are blocked.
        IssueStatus status = issue.getStatus();
        if (status != IssueStatus.SOLUTION_PROPOSED
                && status != IssueStatus.FIX_IN_PROGRESS) {
            log.warn("Cannot create external issue for task {}: issue status is {} (only {} or {} allowed)",
                    taskId, status, IssueStatus.SOLUTION_PROPOSED, IssueStatus.FIX_IN_PROGRESS);
            return null;
        }

        String title = issue.getRootCause() != null
                ? truncate(issue.getRootCause(), 80) : "Issue for task " + taskId;
        String description = selectedSolution != null ? selectedSolution : "";
        String externalIssueId = issueTracker.createIssue(title, description, null);

        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withExternalIssue(externalIssueId, selectedSolution,
                IssueStatus.FIX_IN_PROGRESS, now);
        issueStore.save(updated);
        log.info("Created external issue {} for issue {}", externalIssueId, issue.getIssueId());
        return updated;
    }

    /**
     * Verify the fix for an issue.
     *
     * <p>When a {@link VerificationRunner} is configured, it is invoked first.
     * If the runner returns {@code null} (e.g. when the original diagnostic task
     * is no longer in the in-memory TaskStore after an app restart), this method
     * falls back to running the "verify-fix" skill, which relies on data stored
     * on the issue itself (root_cause, original_query) rather than the lost task.
     * Otherwise the issue is transitioned to {@link IssueStatus#VERIFIED}.</p>
     *
     * @param issueId the issue ID
     * @return the updated issue closure, or {@code null} if the issue is not found
     *         or both the verification runner and the verify-fix skill are unavailable
     */
    public IssueClosure verify(String issueId) {
        IssueClosure issue = issueStore.load(issueId);
        if (issue == null) {
            log.warn("Issue not found for verify: {}", issueId);
            return null;
        }

        // 1. Extract acceptance criteria from solution
        List<AcceptanceCriterion> criteria = extractAcceptanceCriteria(issue);

        VerificationResult result;
        if (criteria != null && !criteria.isEmpty()) {
            // 2. Execute acceptance criteria
            result = verifyViaCriteria(issue, criteria);
        } else {
            // 3. Fallback: use verification runner or verify-fix skill
            if (verificationRunner != null) {
                log.info("Verifying fix for issue {} via VerificationRunner", issueId);
                result = verificationRunner.verify(issue);
                if (result == null) {
                    log.info("VerificationRunner returned null for issue {}; falling back to verify-fix skill", issueId);
                    result = verifyViaSkill(issueId, issue);
                }
            } else {
                result = verifyViaSkill(issueId, issue);
            }
        }
        if (result == null) {
            // verify-fix skill not found — preserve legacy null result.
            return null;
        }

        long now = System.currentTimeMillis();
        IssueStatus newStatus = result.isPassed() ? IssueStatus.VERIFIED : IssueStatus.FAILED;
        IssueClosure updated = issue.withVerification(result, now)
                .withStatus(newStatus, now);
        issueStore.save(updated);
        log.info("Issue {} verified (passed={})", issueId, result.isPassed());

        // Push verification comment to external issue
        if (updated.getExternalIssueId() != null && !updated.getExternalIssueId().isEmpty()) {
            try {
                issueTracker.addComment(updated.getExternalIssueId(),
                        buildVerificationComment(updated, result));
            } catch (RuntimeException e) {
                log.warn("Failed to add verification comment: {}", e.getMessage());
            }
        }

        return updated;
    }

    /**
     * Fallback: runs the "verify-fix" skill and builds a {@link VerificationResult}
     * from its report. The fix is considered passed when the report mentions
     * "通过" or "pass" (case-insensitive).
     *
     * @return the verification result, or {@code null} if the skill is not registered
     */
    VerificationResult verifyViaSkill(String issueId, IssueClosure issue) {
        if (skillRegistry == null) {
            log.error("SkillRegistry not available; cannot run verify-fix skill");
            return null;
        }
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("root_cause", issue.getRootCause() != null ? issue.getRootCause() : "");
        inputs.put("original_query", issue.getUserQuery() != null ? issue.getUserQuery() : "");
        inputs.put("issue_id", issueId);

        SkillMeta skill = skillRegistry.get("verify-fix");
        if (skill == null) {
            log.error("Skill 'verify-fix' not found in registry");
            return null;
        }

        AgentTask verifyTask = AgentTask.create(systemUserId, "verify-fix", inputs, null);
        agentService.execute(verifyTask, skill);

        String report = verifyTask.getReport();
        boolean passed = report != null
                && (report.contains("通过") || report.toLowerCase().contains("pass"));
        String beforeStatus = issue.getStatus() != null ? issue.getStatus().name() : null;
        String afterStatus = verifyTask.getStatus() != null ? verifyTask.getStatus().name() : null;
        return new VerificationResult(passed, report, beforeStatus, afterStatus,
                System.currentTimeMillis());
    }

    /**
     * Close an issue and sediment the experience into the vector store.
     *
     * <p>When a {@link KnowledgeSedimentationService} is available, extracts the
     * Q&A from the issue and writes it to the vector store via embed + add.
     * Then marks the issue as {@link IssueStatus#CLOSED}.</p>
     *
     * @param issueId the issue ID
     * @return the updated issue closure, or {@code null} if the issue is not found
     */
    public IssueClosure close(String issueId) {
        IssueClosure issue = issueStore.load(issueId);
        if (issue == null) {
            log.warn("Issue not found for close: {}", issueId);
            return null;
        }

        // Update external tracker status when an external issue exists.
        // Wrapped in try/catch — external tracker failures should not block close.
        if (issue.getExternalIssueId() != null && !issue.getExternalIssueId().isEmpty()) {
            try {
                issueTracker.updateStatus(issue.getExternalIssueId(), "resolved");
                log.info("External issue {} status updated to resolved via {}",
                        issue.getExternalIssueId(), issueTracker.type());
            } catch (RuntimeException e) {
                log.warn("Failed to update external issue {} status: {}",
                        issue.getExternalIssueId(), e.getMessage());
            }
            try {
                issueTracker.addComment(issue.getExternalIssueId(),
                        buildCloseComment(issue));
            } catch (RuntimeException e) {
                log.warn("Failed to add close comment: {}", e.getMessage());
            }
        }

        if (sedimentationService != null) {
            try {
                sedimentationService.sediment(issue);
                log.info("Issue {} sedimented into vector store", issueId);
            } catch (RuntimeException e) {
                log.warn("Sedimentation failed for issue {}: {}", issueId, e.getMessage());
            }
        }

        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withKnowledgeEntry("sedimentation:" + issueId, now)
                .withStatus(IssueStatus.CLOSED, now);
        issueStore.save(updated);
        log.info("Issue {} closed", issueId);
        return updated;
    }

    /**
     * Loads an issue closure by its ID.
     *
     * @param issueId the issue ID
     * @return the issue closure, or {@code null} if not found
     */
    public IssueClosure loadIssue(String issueId) {
        return issueStore.load(issueId);
    }

    /**
     * Lists all issue closures sorted by {@code updatedAt} descending
     * (newest first). Delegates to {@link IssueStore#list()}.
     *
     * @return list of issue closures (never null, empty if none)
     */
    public List<IssueClosure> listIssues() {
        return issueStore.list();
    }

    // ---- auto-fix workflow (v1.1) ----

    /**
     * Finds an issue closure by its associated diagnostic task ID.
     *
     * @param taskId the diagnostic task ID
     * @return the issue closure, or null if not found
     */
    public IssueClosure findByTaskId(String taskId) {
        return issueStore.findByTaskId(taskId);
    }

    /**
     * Trigger AI auto-fix for an issue: runs the fix agent, creates a branch
     * + commit + PR via VcsClient, then transitions to FIX_SUBMITTED.
     *
     * @param issueId the issue ID
     * @return the updated issue closure, or null if not found / wrong status
     */
    public IssueClosure autoFix(String issueId) {
        IssueClosure issue = issueStore.load(issueId);
        if (issue == null) {
            log.warn("Issue not found for autoFix: {}", issueId);
            return null;
        }
        if (issue.getStatus() != IssueStatus.FIX_IN_PROGRESS) {
            log.warn("Cannot auto-fix issue {}: status is {} (only FIX_IN_PROGRESS allowed)",
                    issueId, issue.getStatus());
            return null;
        }
        if (fixExecutionService == null) {
            log.warn("FixExecutionService not configured; cannot auto-fix issue {}", issueId);
            return null;
        }

        FixResult result;
        try {
            result = fixExecutionService.autoFix(issueId);
        } catch (RuntimeException e) {
            log.error("Auto-fix failed for issue {}: {}", issueId, e.getMessage(), e);
            long now = System.currentTimeMillis();
            IssueClosure failed = issue.withStatus(IssueStatus.FAILED, now);
            issueStore.save(failed);
            if (issue.getExternalIssueId() != null && !issue.getExternalIssueId().isEmpty()) {
                try {
                    issueTracker.addComment(issue.getExternalIssueId(),
                            "## ❌ 自动修复失败\n\n" + e.getMessage());
                } catch (RuntimeException ce) {
                    log.warn("Failed to post failure comment: {}", ce.getMessage());
                }
            }
            return failed;
        }

        if (!result.isSuccess()) {
            long now = System.currentTimeMillis();
            IssueClosure failed = issue.withStatus(IssueStatus.FAILED, now);
            issueStore.save(failed);
            if (issue.getExternalIssueId() != null && !issue.getExternalIssueId().isEmpty()) {
                try {
                    issueTracker.addComment(issue.getExternalIssueId(),
                            "## ❌ 自动修复失败\n\n" + result.getErrorMessage());
                } catch (RuntimeException ce) {
                    log.warn("Failed to post failure comment: {}", ce.getMessage());
                }
            }
            return failed;
        }

        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withFix(
                result.getCommitId(), result.getPrUrl(), result.getPrNumber(),
                IssueStatus.FIX_SUBMITTED, now);
        issueStore.save(updated);

        // Update external tracker status
        if (updated.getExternalIssueId() != null && !updated.getExternalIssueId().isEmpty()) {
            try {
                issueTracker.updateStatus(updated.getExternalIssueId(), "in_progress");
            } catch (RuntimeException e) {
                log.warn("Failed to update external issue status: {}", e.getMessage());
            }
            try {
                issueTracker.addComment(updated.getExternalIssueId(),
                        buildFixComment(updated, result));
            } catch (RuntimeException e) {
                log.warn("Failed to add fix comment: {}", e.getMessage());
            }
        }

        log.info("Issue {} auto-fixed: PR {} ({})",
                issueId, result.getPrUrl(), result.getCommitId());
        return updated;
    }

    /**
     * Called when a PR merge webhook is received. Finds the issue by PR number,
     * verifies the fix, and closes it if verification passes.
     *
     * <p>Idempotent: returns null if no issue matches or the issue is not in
     * FIX_SUBMITTED status.</p>
     *
     * @param prNumber the PR number/iid from the webhook
     * @return the final issue state, or null if not applicable
     */
    public IssueClosure onPrMerged(String prNumber) {
        IssueClosure issue = issueStore.findByPrNumber(prNumber);
        if (issue == null) {
            log.warn("No issue found for PR number: {}", prNumber);
            return null;
        }
        if (issue.getStatus() != IssueStatus.FIX_SUBMITTED) {
            log.info("Issue {} is not FIX_SUBMITTED (actual: {}); skipping onPrMerged",
                    issue.getIssueId(), issue.getStatus());
            return null;
        }

        IssueClosure verified = verify(issue.getIssueId());
        if (verified == null) {
            return null;
        }

        if (verified.getVerificationResult() != null
                && verified.getVerificationResult().isPassed()) {
            return close(issue.getIssueId());
        }
        return verified;
    }

    // ---- auto-fix helpers ----

    /**
     * Extracts acceptance criteria from the issue's solution suggestion.
     */
    private List<AcceptanceCriterion> extractAcceptanceCriteria(IssueClosure issue) {
        if (issue.getSolution() == null) {
            return null;
        }
        return issue.getSolution().getAcceptanceCriteria();
    }

    /**
     * Verifies an issue by executing its acceptance criteria via available tools.
     */
    private VerificationResult verifyViaCriteria(IssueClosure issue,
                                                  List<AcceptanceCriterion> criteria) {
        log.info("Verifying issue {} with {} acceptance criteria",
                issue.getIssueId(), criteria.size());
        if (verificationRunner != null) {
            VerificationResult result = verificationRunner.verify(issue);
            if (result != null) {
                return result;
            }
        }
        return verifyViaSkill(issue.getIssueId(), issue);
    }

    private String buildFixComment(IssueClosure issue, FixResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🔧 修复方案已提交\n\n");
        sb.append("**Commit**: ").append(result.getCommitId()).append("\n");
        sb.append("**PR**: ").append(result.getPrUrl()).append("\n\n");
        sb.append("### 变更文件\n");
        sb.append("| 文件 | 操作 |\n|------|------|\n");
        if (result.getChangedFiles() != null) {
            for (String file : result.getChangedFiles()) {
                sb.append("| ").append(file).append(" | UPDATE |\n");
            }
        }
        if (issue.getSolution() != null
                && issue.getSolution().getAcceptanceCriteria() != null
                && !issue.getSolution().getAcceptanceCriteria().isEmpty()) {
            sb.append("\n### 验收标准\n");
            int idx = 1;
            for (AcceptanceCriterion ac : issue.getSolution().getAcceptanceCriteria()) {
                sb.append(idx++).append(". ").append(ac.getDescription())
                  .append(" (").append(ac.getExpected()).append(")\n");
            }
        }
        sb.append("\n---\n_由 SnapAgent 自动生成_");
        return sb.toString();
    }

    private String buildVerificationComment(IssueClosure issue, VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.isPassed() ? "## ✅ 验收通过\n\n" : "## ❌ 验收未通过\n\n");
        sb.append(result.getSummary() != null ? result.getSummary() : "").append("\n\n");
        if (issue.getFixCommitId() != null) {
            sb.append("**Commit**: ").append(issue.getFixCommitId()).append("\n");
        }
        sb.append("\n---\n_由 SnapAgent 自动生成_");
        return sb.toString();
    }

    private String buildCloseComment(IssueClosure issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🔒 Issue 已关闭\n\n");
        sb.append("**根因**: ").append(truncate(issue.getRootCause(), 200)).append("\n");
        if (issue.getFixCommitId() != null) {
            sb.append("**修复 Commit**: ").append(issue.getFixCommitId()).append("\n");
        }
        if (issue.getVerificationResult() != null) {
            sb.append("**验证**: ").append(issue.getVerificationResult().isPassed() ? "通过" : "未通过").append("\n");
        }
        sb.append("\n---\n_由 SnapAgent 自动生成_");
        return sb.toString();
    }

    // ---- helpers ----

    /**
     * Extracts the user's original query from the diagnostic task's input map
     * by concatenating all non-empty input values.
     */
    private String extractUserQuery(Map<String, String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : inputs.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    /**
     * Parses multi-line solution text into a list of non-empty lines.
     */
    private List<String> parseSolutionLines(String solutionText) {
        List<String> lines = new ArrayList<String>();
        if (solutionText == null || solutionText.isEmpty()) {
            return lines;
        }
        for (String line : solutionText.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
