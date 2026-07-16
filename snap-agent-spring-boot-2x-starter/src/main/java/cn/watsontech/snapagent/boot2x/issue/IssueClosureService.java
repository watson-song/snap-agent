package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.agent.AgentExecutor;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.agent.TaskStore;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStatus;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.issue.IssueTracker;
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
     * @param systemUserId          the system user ID used when executing skills
     */
    public IssueClosureService(AgentExecutor agentExecutor,
                                TaskStore taskStore,
                                SkillRegistry skillRegistry,
                                IssueStore issueStore,
                                IssueTracker issueTracker,
                                KnowledgeBase knowledgeBase,
                                KnowledgeSedimentationExtractor sedimentationExtractor,
                                String systemUserId) {
        this.agentExecutor = agentExecutor;
        this.taskStore = taskStore;
        this.skillRegistry = skillRegistry;
        this.issueStore = issueStore;
        this.issueTracker = issueTracker;
        this.knowledgeBase = knowledgeBase;
        this.sedimentationExtractor = sedimentationExtractor;
        this.systemUserId = systemUserId;
    }

    /**
     * Propose solutions for a completed diagnostic task.
     *
     * <p>Loads the diagnostic task, extracts the root cause from its report,
     * runs the "solution-suggest" skill synchronously, and creates an
     * {@link IssueClosure} with status {@link IssueStatus#SOLUTION_PROPOSED}.</p>
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

        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("root_cause", rootCause != null ? rootCause : "");
        inputs.put("original_query", userQuery != null ? userQuery : "");
        inputs.put("task_id", taskId);

        SkillMeta skill = skillRegistry.get("solution-suggest");
        if (skill == null) {
            log.error("Skill 'solution-suggest' not found in registry");
            return null;
        }

        log.info("Proposing solutions for task {} via solution-suggest skill", taskId);
        AgentTask solutionTask = AgentTask.create(systemUserId, "solution-suggest", inputs, null);
        agentExecutor.execute(solutionTask, skill);

        String solutionText = solutionTask.getReport();
        List<String> solutions = parseSolutionLines(solutionText);

        long now = System.currentTimeMillis();
        IssueClosure issue = new IssueClosure(
                "issue_" + now + "_" + randomSuffix(),
                null,
                taskId,
                null,
                userQuery,
                rootCause,
                solutions,
                null,
                IssueStatus.SOLUTION_PROPOSED,
                null,
                null,
                null,
                now,
                now
        );
        issueStore.save(issue);
        log.info("Created issue {} with {} solution(s) for task {}", issue.getIssueId(), solutions.size(), taskId);
        return issue;
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
     * Verify the fix for an issue by running the "verify-fix" skill.
     *
     * @param issueId the issue ID
     * @return the updated issue closure, or {@code null} if the issue is not found
     */
    public IssueClosure verify(String issueId) {
        IssueClosure issue = issueStore.load(issueId);
        if (issue == null) {
            log.warn("Issue not found for verify: {}", issueId);
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

        log.info("Verifying fix for issue {} via verify-fix skill", issueId);
        AgentTask verifyTask = AgentTask.create(systemUserId, "verify-fix", inputs, null);
        agentExecutor.execute(verifyTask, skill);

        String verificationResult = verifyTask.getReport();
        long now = System.currentTimeMillis();
        IssueClosure updated = issue.withVerification(verificationResult, now)
                .withStatus(IssueStatus.VERIFIED, now);
        issueStore.save(updated);
        log.info("Issue {} verified", issueId);
        return updated;
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
