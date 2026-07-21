package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
import cn.watsontech.snapagent.core.issue.SolutionOption;
import cn.watsontech.snapagent.core.issue.SolutionSuggester;
import cn.watsontech.snapagent.core.issue.SolutionSuggestion;
import cn.watsontech.snapagent.core.issue.VerificationResult;
import cn.watsontech.snapagent.core.issue.VerificationRunner;
import cn.watsontech.snapagent.core.knowledge.KnowledgeBase;
import cn.watsontech.snapagent.core.knowledge.KnowledgeFragment;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
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
 * <p>Connects {@link AgentExecutor} (for running solution-suggest/verify-fix skills),
 * {@link IssueStore} (for persistence), {@link IssueTracker} (for external issue
 * systems), and {@link KnowledgeBase} (for experience sedimentation).</p>
 *
 * <p>The {@link KnowledgeBase} dependency may be {@code null} when knowledge features
 * are disabled; in that case, close() still records the knowledge entry ID but
 * does not reload the knowledge base.</p>
 */
public class IssueClosureService {

    private static final Logger log = LoggerFactory.getLogger(IssueClosureService.class);

    private final AgentExecutor agentExecutor;
    private final TaskStore taskStore;
    private final SkillRegistry skillRegistry;
    private final IssueStore issueStore;
    private final IssueTracker issueTracker;
    private final KnowledgeBase knowledgeBase;
    private final KnowledgeSedimentationExtractor sedimentationExtractor;
    private final SolutionSuggester solutionSuggester;
    private final VerificationRunner verificationRunner;
    private final String systemUserId;

    /**
     * Construct the issue closure service.
     *
     * @param agentExecutor         the agent executor (for running skills synchronously)
     * @param taskStore             the task store (for looking up diagnostic tasks)
     * @param skillRegistry         the skill registry (for resolving skill metadata)
     * @param issueStore            the issue store (for persistence)
     * @param issueTracker          the issue tracker (for external issue systems)
     * @param knowledgeBase         the knowledge base (may be {@code null} if knowledge disabled)
     * @param sedimentationExtractor the knowledge sedimentation extractor
     * @param solutionSuggester     the solution suggester (may be {@code null} to fall back to skill-based suggestion)
     * @param verificationRunner    the verification runner (may be {@code null} to fall back to skill-based verification)
     * @param systemUserId          the system user ID used when executing skills
     */
    public IssueClosureService(AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                SkillRegistry skillRegistry,
                                IssueStore issueStore,
                                IssueTracker issueTracker,
                                KnowledgeBase knowledgeBase,
                                KnowledgeSedimentationExtractor sedimentationExtractor,
                                SolutionSuggester solutionSuggester,
                                VerificationRunner verificationRunner,
                                String systemUserId) {
        this.agentExecutor = agentExecutor;
        this.taskStore = taskStore;
        this.skillRegistry = skillRegistry;
        this.issueStore = issueStore;
        this.issueTracker = issueTracker;
        this.knowledgeBase = knowledgeBase;
        this.sedimentationExtractor = sedimentationExtractor;
        this.solutionSuggester = solutionSuggester;
        this.verificationRunner = verificationRunner;
        this.systemUserId = systemUserId;
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
                now,
                now
        );

        SolutionSuggestion suggestion;
        if (solutionSuggester != null) {
            log.info("Proposing solutions for task {} via SolutionSuggester", taskId);
            suggestion = solutionSuggester.suggest(issue, rootCause);
            if (suggestion == null) {
                suggestion = new SolutionSuggestion(
                        new ArrayList<SolutionOption>(), null, null, null);
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
        agentExecutor.execute(solutionTask, skill);

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
                "Generated from solution-suggest skill output.", null);
    }

    /**
     * Create an external issue for the given task, recording the user's selected solution.
     *
     * @param taskId           the diagnostic task ID
     * @param selectedSolution the user's selected solution text
     * @return the updated issue closure, or {@code null} if no issue exists for the task
     */
    public IssueClosure createExternalIssue(String taskId, String selectedSolution) {
        IssueClosure issue = issueStore.findByTaskId(taskId);
        if (issue == null) {
            log.warn("Issue not found for taskId: {}", taskId);
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

        VerificationResult result;
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
        if (result == null) {
            // verify-fix skill not found — preserve legacy null result.
            return null;
        }

        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withVerification(result, now)
                .withStatus(IssueStatus.VERIFIED, now);
        issueStore.save(updated);
        log.info("Issue {} verified (passed={})", issueId, result.isPassed());
        return updated;
    }

    /**
     * Fallback: runs the "verify-fix" skill and builds a {@link VerificationResult}
     * from its report. The fix is considered passed when the report mentions
     * "通过" or "pass" (case-insensitive).
     *
     * @return the verification result, or {@code null} if the skill is not registered
     */
    private VerificationResult verifyViaSkill(String issueId, IssueClosure issue) {
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
        agentExecutor.execute(verifyTask, skill);

        String report = verifyTask.getReport();
        boolean passed = report != null
                && (report.contains("通过") || report.toLowerCase().contains("pass"));
        String beforeStatus = issue.getStatus() != null ? issue.getStatus().name() : null;
        String afterStatus = verifyTask.getStatus() != null ? verifyTask.getStatus().name() : null;
        return new VerificationResult(passed, report, beforeStatus, afterStatus,
                System.currentTimeMillis());
    }

    /**
     * Close an issue and sediment the experience into the knowledge base.
     *
     * <p>Extracts a knowledge fragment via {@link KnowledgeSedimentationExtractor},
     * reloads the {@link KnowledgeBase} (if available) to pick up new fragments,
     * and marks the issue as {@link IssueStatus#CLOSED}.</p>
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

        KnowledgeFragment fragment = sedimentationExtractor.extract(issue);
        log.info("Extracted knowledge fragment for issue {}: {}", issueId, fragment.getTitle());

        if (knowledgeBase != null) {
            knowledgeBase.reload();
            log.info("KnowledgeBase reloaded after sedimentation of issue {}", issueId);
        }

        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withKnowledgeEntry("sedimentation:" + issueId, now)
                .withStatus(IssueStatus.CLOSED, now);
        issueStore.save(updated);
        log.info("Issue {} closed and sedimented", issueId);
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
