package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.agent.AgentService;
import cn.watsontech.snapagent.core.agent.AgentTask;
import cn.watsontech.snapagent.core.issue.IssueClosure;
import cn.watsontech.snapagent.core.issue.IssueStore;
import cn.watsontech.snapagent.core.skill.SkillMeta;
import cn.watsontech.snapagent.core.skill.SkillRegistry;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.FixResult;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import cn.watsontech.snapagent.core.vcs.VcsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the AI auto-fix flow:
 * load issue → run agent with file tools → collect changes → VcsClient branch+commit+PR.
 */
public class FixExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FixExecutionService.class);

    private final AgentService agentService;
    private final IssueStore issueStore;
    private final SkillRegistry skillRegistry;
    private final VcsClient vcsClient;
    private final FixContextHolder fixContextHolder;
    private final String projectRoot;
    private final String systemUserId;
    private final String defaultBranch;

    public FixExecutionService(AgentService agentService,
                                IssueStore issueStore,
                                SkillRegistry skillRegistry,
                                VcsClient vcsClient,
                                FixContextHolder fixContextHolder,
                                String projectRoot,
                                String systemUserId,
                                String defaultBranch) {
        this.agentService = agentService;
        this.issueStore = issueStore;
        this.skillRegistry = skillRegistry;
        this.vcsClient = vcsClient;
        this.fixContextHolder = fixContextHolder;
        this.projectRoot = projectRoot;
        this.systemUserId = systemUserId;
        this.defaultBranch = defaultBranch;
    }

    /**
     * Executes the auto-fix flow for an issue.
     *
     * @param issueId the issue ID
     * @return fix result (success or failure)
     */
    public FixResult autoFix(String issueId) {
        IssueClosure issue = issueStore.load(issueId);
        if (issue == null) {
            return FixResult.failed("Issue not found: " + issueId);
        }

        FixContext ctx = new FixContext(projectRoot);
        fixContextHolder.set(ctx);

        try {
            runFixAgent(issue);

            List<FileChange> changes = ctx.getChanges();
            if (changes.isEmpty()) {
                return FixResult.failed("AI did not produce any code changes");
            }

            String branchName = "fix/" + issue.getIssueId();
            log.info("Creating branch {} via {}", branchName, vcsClient.type());
            vcsClient.createBranch(branchName);

            String commitMessage = buildCommitMessage(issue);
            log.info("Committing {} file(s) to branch {}", changes.size(), branchName);
            String commitSha = vcsClient.commitFiles(branchName, changes, commitMessage);

            String prTitle = buildPrTitle(issue);
            String prDesc = buildPrDescription(issue, changes);
            log.info("Creating PR from {} to {}", branchName, defaultBranch);
            MergeRequestInfo mr = vcsClient.createPullRequest(branchName, defaultBranch, prTitle, prDesc);

            List<String> changedFiles = new ArrayList<String>();
            for (FileChange fc : changes) {
                changedFiles.add(fc.getFilePath());
            }

            log.info("Auto-fix completed: commit={}, pr={}", commitSha, mr.getPrUrl());
            return FixResult.success(commitSha, mr.getPrUrl(), mr.getPrNumber(), changedFiles);

        } catch (RuntimeException e) {
            log.error("Auto-fix failed for issue {}: {}", issueId, e.getMessage(), e);
            return FixResult.failed(e.getMessage());
        } finally {
            fixContextHolder.clear();
        }
    }

    private void runFixAgent(IssueClosure issue) {
        Map<String, String> inputs = new HashMap<String, String>();
        inputs.put("root_cause", issue.getRootCause() != null ? issue.getRootCause() : "");
        inputs.put("original_query", issue.getUserQuery() != null ? issue.getUserQuery() : "");
        inputs.put("selected_solution", issue.getSelectedSolution() != null ? issue.getSelectedSolution() : "");
        inputs.put("fix_prompt", buildFixPrompt(issue));

        SkillMeta skill = skillRegistry.get("code-analysis");
        if (skill == null) {
            throw new RuntimeException("Skill 'code-analysis' not found");
        }

        AgentTask fixTask = AgentTask.create(systemUserId, "code-analysis", inputs, null);
        agentService.execute(fixTask, skill);
    }

    private String buildFixPrompt(IssueClosure issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a code fix expert. Based on the diagnostic findings, implement the fix.\n\n");
        sb.append("## Original Problem\n").append(issue.getUserQuery()).append("\n\n");
        sb.append("## Root Cause\n").append(issue.getRootCause()).append("\n\n");
        sb.append("## Selected Solution\n");
        sb.append(issue.getSelectedSolution() != null ? issue.getSelectedSolution() : "N/A").append("\n\n");
        sb.append("## Instructions\n");
        sb.append("1. Use code_read to examine relevant source files\n");
        sb.append("2. Use file_edit for targeted changes (provide oldString and newString)\n");
        sb.append("3. Use file_write only for new files\n");
        sb.append("4. Keep changes minimal — fix the root cause, don't refactor\n");
        sb.append("5. After making changes, summarize what you changed and why\n");
        return sb.toString();
    }

    private String buildCommitMessage(IssueClosure issue) {
        return "fix: " + (issue.getRootCause() != null
                ? truncate(issue.getRootCause(), 72) : "issue " + issue.getIssueId())
                + "\n\nIssue: " + issue.getIssueId()
                + (issue.getExternalIssueId() != null ? "\nExternal: " + issue.getExternalIssueId() : "");
    }

    private String buildPrTitle(IssueClosure issue) {
        return "Fix: " + (issue.getRootCause() != null
                ? truncate(issue.getRootCause(), 60) : "Issue " + issue.getIssueId());
    }

    private String buildPrDescription(IssueClosure issue, List<FileChange> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Auto-generated Fix\n\n");
        sb.append("**Issue**: ").append(issue.getIssueId()).append("\n");
        if (issue.getExternalIssueId() != null) {
            sb.append("**External Issue**: ").append(issue.getExternalIssueId()).append("\n");
        }
        sb.append("\n### Root Cause\n").append(issue.getRootCause()).append("\n\n");
        sb.append("### Changed Files\n");
        for (FileChange fc : changes) {
            sb.append("- `").append(fc.getFilePath()).append("` (").append(fc.getAction()).append(")\n");
        }
        sb.append("\n### Solution\n").append(issue.getSelectedSolution() != null
                ? issue.getSelectedSolution() : "N/A").append("\n");
        sb.append("\n---\n_Generated by SnapAgent Auto-Fix_\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
