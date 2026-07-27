package cn.watsontech.snapagent.core.vcs;

import java.util.List;

/**
 * Git hosting platform SPI for automated code fixes.
 *
 * <p>Implementations connect to GitLab, Bitbucket, GitHub, etc.
 * to create branches, commit files, and create pull requests.</p>
 */
public interface VcsClient {

    /**
     * Creates a new branch based on the default branch.
     *
     * @param branchName the new branch name
     */
    void createBranch(String branchName);

    /**
     * Commits file changes to the specified branch.
     *
     * @param branchName     target branch
     * @param changes        file changes to commit
     * @param commitMessage  commit message
     * @return the commit SHA
     */
    String commitFiles(String branchName, List<FileChange> changes, String commitMessage);

    /**
     * Creates a pull/merge request.
     *
     * @param sourceBranch  source branch (fix branch)
     * @param targetBranch  target branch (default branch)
     * @param title         PR title
     * @param description   PR description
     * @return merge request info (prUrl, prNumber, commitSha)
     */
    MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                        String title, String description);

    /**
     * Queries the merge status of a pull request.
     *
     * @param prNumber the PR number/iid
     * @return status string: "OPEN", "MERGED", "CLOSED", "CONFLICT"
     */
    String getMergeStatus(String prNumber);

    /**
     * Returns the type identifier: "gitlab", "bitbucket", etc.
     */
    String type();
}
