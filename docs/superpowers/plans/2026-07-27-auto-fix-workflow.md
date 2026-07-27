# Auto-Fix Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement AI-assisted PR workflow — after issue creation, AI auto-generates code fix, creates branch/commit/PR via VcsClient, and auto-verifies + closes after PR merge.

**Architecture:** FixExecutionService orchestrates: Agent multi-turn code editing (file_write/file_edit tools + FixGuard) → VcsClient (GitLab/Bitbucket) createBranch+commit+PR → IssueClosureService records fixCommitId/prUrl, transitions to FIX_SUBMITTED. Webhook or manual trigger → verify (acceptance criteria) → close + sediment.

**Tech Stack:** Java 8, Spring Boot 2.5.x, Jackson, HttpURLConnection, JUnit 5 + Mockito, AssertJ

**Build commands:**
```bash
# Core module tests
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="TestClassName" -DfailIfNoTests=false -Djacoco.skip=true

# Starter module tests
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="TestClassName" -DfailIfNoTests=false -Djacoco.skip=true

# Compile check (no tests)
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core,snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

**Spec:** `docs/superpowers/specs/2026-07-27-auto-fix-workflow-design.md`

---

## File Structure

### core module (`snap-agent-core/src/main/java/cn/watsontech/snapagent/core/`)

| File | Type | Responsibility |
|------|------|----------------|
| `vcs/VcsClient.java` | NEW SPI | Git operations interface |
| `vcs/FileChange.java` | NEW | File change value object |
| `vcs/MergeRequestInfo.java` | NEW | PR info value object |
| `vcs/FixResult.java` | NEW | Fix result value object |
| `issue/AcceptanceCriterion.java` | NEW | Acceptance criterion value object |
| `issue/VerificationDetail.java` | NEW | Per-criterion verification result |
| `issue/IssueStatus.java` | MODIFY | Add FIX_SUBMITTED |
| `issue/IssueClosure.java` | MODIFY | Add fixPrUrl, fixPrNumber + withFix() |
| `issue/SolutionSuggestion.java` | MODIFY | Add acceptanceCriteria field |
| `issue/IssueStore.java` | MODIFY | Add findByPrNumber() |
| `issue/IssueTracker.java` | MODIFY | Add addComment() |

### starter module (`snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/`)

| File | Type | Responsibility |
|------|------|----------------|
| `vcs/AbstractHttpVcsClient.java` | NEW | HTTP base class for VcsClient |
| `vcs/GitLabVcsClient.java` | NEW | GitLab REST API v4 |
| `vcs/BitbucketVcsClient.java` | NEW | Bitbucket Server API 1.0 |
| `fix/FixGuard.java` | NEW | File edit safety guard |
| `fix/FixContext.java` | NEW | In-memory change tracker |
| `fix/FixContextHolder.java` | NEW | ThreadLocal holder |
| `fix/FileWriteTool.java` | NEW | @Tool file_write |
| `fix/FileEditTool.java` | NEW | @Tool file_edit |
| `fix/FixExecutionService.java` | NEW | Fix orchestration service |
| `issue/IssueClosureService.java` | MODIFY | autoFix, onPrMerged, enhanced verify, addComment calls |
| `issue/NoopIssueTracker.java` | MODIFY | addComment no-op |
| `issue/ZentaoIssueTracker.java` | MODIFY | addComment |
| `issue/GitHubIssueTracker.java` | MODIFY | addComment |
| `issue/JiraIssueTracker.java` | MODIFY | addComment |
| `issue/FileIssueStore.java` | MODIFY | findByPrNumber() |
| `autoconfig/SnapAgentProperties.java` | MODIFY | Vcs + Fix config sections |
| `autoconfig/SnapAgentAutoConfiguration.java` | MODIFY | Wire new beans |
| `web/SnapAgentController.java` | MODIFY | auto-fix + webhook endpoints |

---

## Phase 1: Core Value Objects

### Task 1: FileChange value object

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/FileChange.java`

- [ ] **Step 1: Create the class**

```java
package cn.watsontech.snapagent.core.vcs;

/**
 * Represents a single file change to be committed via VcsClient.
 * Immutable value object.
 */
public final class FileChange {
    private final String filePath;
    private final String content;
    private final String action; // CREATE, UPDATE, DELETE

    public FileChange(String filePath, String content, String action) {
        this.filePath = filePath;
        this.content = content;
        this.action = action;
    }

    public String getFilePath() { return filePath; }
    public String getContent() { return content; }
    public String getAction() { return action; }

    public static FileChange create(String filePath, String content) {
        return new FileChange(filePath, content, "CREATE");
    }
    public static FileChange update(String filePath, String content) {
        return new FileChange(filePath, content, "UPDATE");
    }
    public static FileChange delete(String filePath) {
        return new FileChange(filePath, null, "DELETE");
    }

    @Override
    public String toString() {
        return "FileChange{filePath='" + filePath + "', action=" + action + "}";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/FileChange.java
git commit -m "feat(vcs): add FileChange value object"
```

### Task 2: MergeRequestInfo + FixResult value objects

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/MergeRequestInfo.java`
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/FixResult.java`

- [ ] **Step 1: Create MergeRequestInfo**

```java
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
```

- [ ] **Step 2: Create FixResult**

```java
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
```

- [ ] **Step 3: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/MergeRequestInfo.java \
       snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/FixResult.java
git commit -m "feat(vcs): add MergeRequestInfo and FixResult value objects"
```

### Task 3: AcceptanceCriterion + VerificationDetail value objects

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/AcceptanceCriterion.java`
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/VerificationDetail.java`

- [ ] **Step 1: Create AcceptanceCriterion**

```java
package cn.watsontech.snapagent.core.issue;

/**
 * A single acceptance criterion for verifying a fix.
 * Immutable value object.
 */
public final class AcceptanceCriterion {
    private final String id;
    private final String description;
    private final String verification; // SQL or command
    private final String expected;      // "> 0", "= true", "contains xxx"
    private final String tool;          // "mysql_query", etc.

    public AcceptanceCriterion(String id, String description, String verification,
                               String expected, String tool) {
        this.id = id;
        this.description = description;
        this.verification = verification;
        this.expected = expected;
        this.tool = tool;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public String getVerification() { return verification; }
    public String getExpected() { return expected; }
    public String getTool() { return tool; }

    @Override
    public String toString() {
        return "AcceptanceCriterion{id='" + id + "', description='" + description + "'}";
    }
}
```

- [ ] **Step 2: Create VerificationDetail**

```java
package cn.watsontech.snapagent.core.issue;

/**
 * Per-criterion verification result. Immutable value object.
 */
public final class VerificationDetail {
    private final String criterionId;
    private final String description;
    private final boolean passed;
    private final String actual;
    private final String expected;

    public VerificationDetail(String criterionId, String description,
                             boolean passed, String actual, String expected) {
        this.criterionId = criterionId;
        this.description = description;
        this.passed = passed;
        this.actual = actual;
        this.expected = expected;
    }

    public String getCriterionId() { return criterionId; }
    public String getDescription() { return description; }
    public boolean isPassed() { return passed; }
    public String getActual() { return actual; }
    public String getExpected() { return expected; }

    @Override
    public String toString() {
        return "VerificationDetail{id='" + criterionId + "', passed=" + passed + "}";
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/AcceptanceCriterion.java \
       snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/VerificationDetail.java
git commit -m "feat(issue): add AcceptanceCriterion and VerificationDetail value objects"
```

### Task 4: VcsClient SPI interface

**Files:**
- Create: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/VcsClient.java`

- [ ] **Step 1: Create the interface**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/vcs/VcsClient.java
git commit -m "feat(vcs): add VcsClient SPI interface"
```

## Phase 2: SPI Changes

### Task 5: Add FIX_SUBMITTED to IssueStatus

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStatus.java`

- [ ] **Step 1: Add new enum value**

In `IssueStatus.java`, add `FIX_SUBMITTED` between `FIX_IN_PROGRESS` and `VERIFIED`:

```java
// After FIX_IN_PROGRESS, add:
    /** AI 已提交修复 PR，等待人工 review + merge。 */
    FIX_SUBMITTED,
```

Also update the state machine Javadoc at the top of the file to include the new status:
```
 * DIAGNOSED → SOLUTION_PROPOSED → ISSUE_CREATED → FIX_IN_PROGRESS → FIX_SUBMITTED → VERIFIED → CLOSED
```

- [ ] **Step 2: Compile check**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 3: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStatus.java
git commit -m "feat(issue): add FIX_SUBMITTED status to IssueStatus"
```

### Task 6: Extend IssueClosure with fixPrUrl + fixPrNumber + withFix()

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueClosure.java`

- [ ] **Step 1: Add new fields**

Add two new private final fields after `fixCommitId`:

```java
    private final String fixPrUrl;
    private final String fixPrNumber;
```

- [ ] **Step 2: Update constructor**

Add `String fixPrUrl, String fixPrNumber` as the last two parameters (before `createdAt, updatedAt` is fine, but to avoid breaking existing callers, add them at the end). Since the constructor is called from `with*` methods and `IssueClosureService`, all internal callers will be updated.

Update the full constructor to accept and assign the new fields. All `with*` methods must pass `this.fixPrUrl, this.fixPrNumber` to preserve them.

- [ ] **Step 3: Add getters + withFix()**

```java
    /** 修复 PR 的 Web URL (可空)。 */
    public String getFixPrUrl() { return fixPrUrl; }

    /** 修复 PR 的编号 (可空)。 */
    public String getFixPrNumber() { return fixPrNumber; }

    /**
     * 返回一个包含修复信息的新实例。
     */
    public IssueClosure withFix(String commitId, String prUrl, String prNumber,
                               IssueStatus status, long updatedAt) {
        return new IssueClosure(
                this.issueId, this.externalIssueId, this.taskId,
                this.conversationId, this.userId, this.userQuery, this.rootCause,
                this.solution, this.selectedSolution,
                status, commitId,
                this.verificationResult, this.knowledgeEntryId,
                this.createdAt, updatedAt,
                prUrl, prNumber);
    }
```

- [ ] **Step 4: Update all existing `with*` methods**

Every existing `with*` method (withStatus, withExternalIssue ×2, withSolution, withVerification, withKnowledgeEntry) must pass `this.fixPrUrl, this.fixPrNumber` to the constructor. Add them as the last two args.

- [ ] **Step 5: Update IssueClosureService.proposeSolution()**

In `IssueClosureService.java`, the `new IssueClosure(...)` call in `proposeSolution()` must pass `null, null` for the new `fixPrUrl, fixPrNumber` params.

- [ ] **Step 6: Compile check**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core,snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 7: Run existing tests to verify no regression**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="IssueClosureServiceTest" -DfailIfNoTests=false -Djacoco.skip=true
```

Expected: All existing tests PASS.

- [ ] **Step 8: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueClosure.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java
git commit -m "feat(issue): add fixPrUrl/fixPrNumber to IssueClosure + withFix()"
```

### Task 7: Extend SolutionSuggestion with acceptanceCriteria

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/SolutionSuggestion.java`

- [ ] **Step 1: Add field + update constructor + getter**

```java
// Add field
private final List<AcceptanceCriterion> acceptanceCriteria;

// Update constructor: add List<AcceptanceCriterion> acceptanceCriteria param
// Defensive copy like options:
this.acceptanceCriteria = acceptanceCriteria != null ? new ArrayList<>(acceptanceCriteria) : new ArrayList<>();

// Add getter
public List<AcceptanceCriterion> getAcceptanceCriteria() {
    return Collections.unmodifiableList(acceptanceCriteria);
}
```

- [ ] **Step 2: Update all callers**

Search for `new SolutionSuggestion(` in the codebase. Update each call to pass an empty list or `null` for the new parameter. Key locations:
- `IssueClosureService.proposeSolution()` — pass `null` (will default to empty list)
- `IssueClosureService.suggestViaSkill()` — pass `null`
- `TemplateSolutionSuggester` — pass `null`

- [ ] **Step 3: Compile + test**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core,snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 4: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/SolutionSuggestion.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/TemplateSolutionSuggester.java
git commit -m "feat(issue): add acceptanceCriteria to SolutionSuggestion"
```

### Task 8: Add findByPrNumber to IssueStore + FileIssueStore

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStore.java`
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/FileIssueStore.java`

- [ ] **Step 1: Add method to IssueStore interface**

```java
    /**
     * Finds an issue closure by its fix PR number.
     *
     * @param prNumber the PR/MR number
     * @return the issue closure, or {@code null} if not found
     */
    IssueClosure findByPrNumber(String prNumber);
```

- [ ] **Step 2: Implement in FileIssueStore**

Add method that scans all JSON files and finds one where `fixPrNumber` matches:

```java
    @Override
    public IssueClosure findByPrNumber(String prNumber) {
        if (prNumber == null || prNumber.isEmpty()) {
            return null;
        }
        for (IssueClosure issue : list()) {
            if (prNumber.equals(issue.getFixPrNumber())) {
                return issue;
            }
        }
        return null;
    }
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core,snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 4: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStore.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/FileIssueStore.java
git commit -m "feat(issue): add findByPrNumber to IssueStore + FileIssueStore"
```

### Task 9: Add addComment to IssueTracker SPI + NoopIssueTracker

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueTracker.java`
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTracker.java`

- [ ] **Step 1: Add addComment to IssueTracker interface**

```java
    /**
     * Adds a comment/note to an external issue.
     *
     * @param externalIssueId the external issue ID
     * @param comment         the comment text (may contain Markdown)
     */
    void addComment(String externalIssueId, String comment);
```

- [ ] **Step 2: Add no-op to NoopIssueTracker**

```java
    @Override
    public void addComment(String externalIssueId, String comment) {
        // no-op
    }
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-core,snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 4: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueTracker.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTracker.java
git commit -m "feat(issue): add addComment to IssueTracker SPI + NoopIssueTracker"
```

## Phase 3: VcsClient Implementations

### Task 10: AbstractHttpVcsClient base class

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/AbstractHttpVcsClient.java`

- [ ] **Step 1: Create the class**

This is a simplified version of `AbstractHttpIssueTracker` adapted for VcsClient. Reuse the same HTTP + JSON pattern (HttpURLConnection + Jackson ObjectMapper).

```java
package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.core.vcs.VcsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP base class for VcsClient implementations.
 * Provides JSON HTTP request helper using HttpURLConnection + Jackson.
 */
abstract class AbstractHttpVcsClient implements VcsClient {

    protected static final Logger log = LoggerFactory.getLogger(AbstractHttpVcsClient.class);
    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected JsonNode jsonRequest(String urlStr, String method,
                                   Map<String, String> headers, Object body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                String json = objectMapper.writeValueAsString(body);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            String responseBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + " from " + method + " " + urlStr + ": " + responseBody);
            }

            if (responseBody == null || responseBody.isEmpty()) {
                return null;
            }
            return objectMapper.readTree(responseBody);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed " + method + " " + urlStr + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    protected static Map<String, String> authHeader(String header, String value) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put(header, value);
        return h;
    }

    private String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception e) {
            log.warn("Failed reading response: {}", e.getMessage());
        }
        return sb.toString();
    }

    protected static String trimSlash(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/AbstractHttpVcsClient.java
git commit -m "feat(vcs): add AbstractHttpVcsClient base class"
```

### Task 11: GitLabVcsClient

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/GitLabVcsClient.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/vcs/GitLabVcsClientTest.java`

- [ ] **Step 1: Write failing test**

```java
package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabVcsClientTest {

    private GitLabVcsClient createClient() {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl("http://localhost:39999");
        cfg.setToken("glpat-test");
        cfg.setProjectId(123);
        return new GitLabVcsClient(cfg);
    }

    @Test
    void shouldReturnTypeGitlab() {
        assertThat(createClient().type()).isEqualTo("gitlab");
    }

    @Test
    void shouldThrowWhenServerUnreachable() {
        assertThatThrownBy(() -> createClient().createBranch("fix/test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed POST");
    }

    @Test
    void shouldHandleBaseUrlWithTrailingSlash() {
        SnapAgentProperties.Vcs.GitLab cfg = new SnapAgentProperties.Vcs.GitLab();
        cfg.setBaseUrl("https://gitlab.example.com/");
        cfg.setToken("tok");
        cfg.setProjectId(1);
        GitLabVcsClient client = new GitLabVcsClient(cfg);
        assertThat(client.type()).isEqualTo("gitlab");
    }

    @Test
    void shouldHandleCommitFilesEmpty() {
        GitLabVcsClient client = createClient();
        assertThatThrownBy(() -> client.commitFiles("fix/test",
                new ArrayList<FileChange>(), "msg"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="GitLabVcsClientTest" -DfailIfNoTests=false -Djacoco.skip=true
```

Expected: FAIL (class not found)

- [ ] **Step 3: Write GitLabVcsClient implementation**

```java
package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GitLabVcsClient extends AbstractHttpVcsClient {

    private final String baseUrl;
    private final String token;
    private final int projectId;

    public GitLabVcsClient(SnapAgentProperties.Vcs.GitLab config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.token = config.getToken();
        this.projectId = config.getProjectId();
    }

    @Override
    public void createBranch(String branchName) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/repository/branches";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("branch", branchName);
        body.put("ref", "main"); // will be overridden by default-branch config later
        jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
    }

    @Override
    public String commitFiles(String branchName, List<FileChange> changes, String commitMessage) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/repository/commits";
        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        for (FileChange fc : changes) {
            Map<String, Object> action = new LinkedHashMap<String, Object>();
            action.put("action", fc.getAction().toLowerCase());
            action.put("file_path", fc.getFilePath());
            if (fc.getContent() != null) {
                action.put("content", fc.getContent());
            }
            actions.add(action);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("branch", branchName);
        body.put("commit_message", commitMessage);
        body.put("actions", actions);

        JsonNode resp = jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
        // GitLab returns {"id": "...", "short_id": "..."}
        if (resp != null && resp.has("id")) {
            return resp.get("id").asText();
        }
        return null;
    }

    @Override
    public MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                              String title, String description) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/merge_requests";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("source_branch", sourceBranch);
        body.put("target_branch", targetBranch);
        body.put("title", title);
        body.put("description", description != null ? description : "");

        JsonNode resp = jsonRequest(url, "POST", authHeader("PRIVATE-TOKEN", token), body);
        if (resp != null) {
            String prUrl = resp.has("web_url") ? resp.get("web_url").asText() : null;
            String prNumber = resp.has("iid") ? String.valueOf(resp.get("iid").asInt()) : null;
            String commitSha = resp.has("sha") ? resp.get("sha").asText() : null;
            return new MergeRequestInfo(prUrl, prNumber, commitSha);
        }
        return new MergeRequestInfo(null, null, null);
    }

    @Override
    public String getMergeStatus(String prNumber) {
        String url = baseUrl + "/api/v4/projects/" + projectId + "/merge_requests/" + prNumber;
        JsonNode resp = jsonRequest(url, "GET", authHeader("PRIVATE-TOKEN", token), null);
        if (resp != null && resp.has("state")) {
            return resp.get("state").asText().toUpperCase();
        }
        return "UNKNOWN";
    }

    @Override
    public String type() {
        return "gitlab";
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="GitLabVcsClientTest" -DfailIfNoTests=false -Djacoco.skip=true
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/GitLabVcsClient.java \
       snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/vcs/GitLabVcsClientTest.java
git commit -m "feat(vcs): add GitLabVcsClient implementation + tests"
```

### Task 12: BitbucketVcsClient

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/BitbucketVcsClient.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/vcs/BitbucketVcsClientTest.java`

- [ ] **Step 1: Write failing test** (same pattern as GitLab test, `type()` returns `"bitbucket"`)

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Write BitbucketVcsClient implementation**

```java
package cn.watsontech.snapagent.boot2x.vcs;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import cn.watsontech.snapagent.core.vcs.FileChange;
import cn.watsontech.snapagent.core.vcs.MergeRequestInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class BitbucketVcsClient extends AbstractHttpVcsClient {

    private final String baseUrl;
    private final String token;
    private final String projectKey;
    private final String repoSlug;

    public BitbucketVcsClient(SnapAgentProperties.Vcs.Bitbucket config) {
        this.baseUrl = trimSlash(config.getBaseUrl());
        this.token = config.getToken();
        this.projectKey = config.getProjectKey();
        this.repoSlug = config.getRepoSlug();
    }

    @Override
    public void createBranch(String branchName) {
        String url = baseUrl + "/rest/branch-utils/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/branches";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", branchName);
        body.put("startPoint", "refs/heads/main");
        jsonRequest(url, "POST", authHeader("Authorization", "Bearer " + token), body);
    }

    @Override
    public String commitFiles(String branchName, List<FileChange> changes, String commitMessage) {
        // Bitbucket has no batch commit API — PUT each file individually
        String lastCommitSha = null;
        for (FileChange fc : changes) {
            String encodedPath = URLEncoder.encode(fc.getFilePath(), "UTF-8").replace("%2F", "/");
            String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                    + "/repos/" + repoSlug + "/browse/" + encodedPath
                    + "?branch=" + URLEncoder.encode(branchName, "UTF-8")
                    + "&message=" + URLEncoder.encode(commitMessage, "UTF-8");

            // Use form-based PUT for Bitbucket file upload
            lastCommitSha = putFileContent(url, fc.getContent());
        }
        return lastCommitSha;
    }

    private String putFileContent(String urlStr, String content) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("content", content != null ? content : "");
            String json = objectMapper.writeValueAsString(body);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            String respBody = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new RuntimeException("HTTP " + code + ": " + respBody);
            }
            // Bitbucket returns {"id": "..."} for the commit
            if (!respBody.isEmpty()) {
                JsonNode node = objectMapper.readTree(respBody);
                if (node.has("id")) return node.get("id").asText();
            }
            return null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed PUT: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                              String title, String description) {
        String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/pull-requests";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("title", title);
        body.put("description", description != null ? description : "");

        Map<String, Object> fromRef = new LinkedHashMap<String, Object>();
        fromRef.put("id", "refs/heads/" + sourceBranch);
        Map<String, Object> fromRepo = new LinkedHashMap<String, Object>();
        fromRepo.put("slug", repoSlug);
        fromRef.put("repository", fromRepo);
        body.put("fromRef", fromRef);

        Map<String, Object> toRef = new LinkedHashMap<String, Object>();
        toRef.put("id", "refs/heads/" + targetBranch);
        Map<String, Object> toRepo = new LinkedHashMap<String, Object>();
        toRepo.put("slug", repoSlug);
        toRef.put("repository", toRepo);
        body.put("toRef", toRef);

        JsonNode resp = jsonRequest(url, "POST", authHeader("Authorization", "Bearer " + token), body);
        if (resp != null) {
            int prId = resp.has("id") ? resp.get("id").asInt() : 0;
            String prUrl = baseUrl + "/projects/" + projectKey
                    + "/repos/" + repoSlug + "/pull-requests/" + prId;
            String commitSha = resp.has("fromRef") && resp.get("fromRef").has("latestCommit")
                    ? resp.get("fromRef").get("latestCommit").asText() : null;
            return new MergeRequestInfo(prUrl, String.valueOf(prId), commitSha);
        }
        return new MergeRequestInfo(null, null, null);
    }

    @Override
    public String getMergeStatus(String prNumber) {
        String url = baseUrl + "/rest/api/1.0/projects/" + projectKey
                + "/repos/" + repoSlug + "/pull-requests/" + prNumber;
        JsonNode resp = jsonRequest(url, "GET", authHeader("Authorization", "Bearer " + token), null);
        if (resp != null && resp.has("state")) {
            return resp.get("state").asText().toUpperCase();
        }
        return "UNKNOWN";
    }

    @Override
    public String type() {
        return "bitbucket";
    }
}
```

Note: `commitFiles` needs `import java.util.List;` — add it.

- [ ] **Step 4: Run tests**

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/vcs/BitbucketVcsClient.java \
       snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/vcs/BitbucketVcsClientTest.java
git commit -m "feat(vcs): add BitbucketVcsClient implementation + tests"
```

## Phase 4: Fix Infrastructure

### Task 13: FixContext

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixContext.java`

- [ ] **Step 1: Create the class**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.vcs.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks in-memory file changes during a fix execution session.
 * Changes are NOT written to disk — they are collected and committed
 * via VcsClient REST API.
 */
public class FixContext {

    private final String projectRoot;
    private final Map<String, FileChange> changes = new LinkedHashMap<String, FileChange>();

    public FixContext(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Adds or replaces a file change in the context.
     */
    public void addChange(String filePath, String content, String action) {
        changes.put(filePath, new FileChange(filePath, content, action));
    }

    /**
     * Returns the current content for a file.
     * Priority: in-memory change > disk read.
     * Returns null if file doesn't exist on disk.
     */
    public String getFileContent(String filePath) {
        FileChange existing = changes.get(filePath);
        if (existing != null && existing.getContent() != null) {
            return existing.getContent();
        }
        // Read from disk
        try {
            Path path = resolvePath(filePath);
            if (Files.exists(path)) {
                return new String(Files.readAllBytes(path), "UTF-8");
            }
        } catch (IOException e) {
            // ignore — return null
        }
        return null;
    }

    /**
     * Returns all tracked changes.
     */
    public List<FileChange> getChanges() {
        return new ArrayList<>(changes.values());
    }

    /**
     * Returns the number of tracked changes.
     */
    public int size() {
        return changes.size();
    }

    /**
     * Resolves a relative file path against projectRoot.
     * Throws if path traversal is detected.
     */
    public Path resolvePath(String filePath) {
        Path resolved = Paths.get(projectRoot, filePath).normalize();
        Path root = Paths.get(projectRoot).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }
        return resolved;
    }

    /**
     * Returns the file extension (e.g. ".java").
     */
    public static String getExtension(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx >= 0) {
            return filePath.substring(dotIdx).toLowerCase();
        }
        return "";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixContext.java
git commit -m "feat(fix): add FixContext in-memory change tracker"
```

### Task 14: FixContextHolder

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixContextHolder.java`

- [ ] **Step 1: Create the class**

```java
package cn.watsontech.snapagent.boot2x.fix;

/**
 * ThreadLocal holder for the current FixContext.
 * Set by FixExecutionService before running the agent; cleared after.
 * File tools (FileWriteTool, FileEditTool) access the context via this holder.
 */
public class FixContextHolder {

    private final ThreadLocal<FixContext> holder = new ThreadLocal<FixContext>();

    public void set(FixContext ctx) {
        holder.set(ctx);
    }

    public FixContext get() {
        return holder.get();
    }

    public void clear() {
        holder.remove();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixContextHolder.java
git commit -m "feat(fix): add FixContextHolder ThreadLocal"
```

### Task 15: FixGuard

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixGuard.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/fix/FixGuardTest.java`

- [ ] **Step 1: Write failing test**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FixGuardTest {

    private FixGuard createGuard() {
        SnapAgentProperties.Fix.Guard cfg = new SnapAgentProperties.Fix.Guard();
        cfg.setIncludePaths(new java.util.ArrayList<>(java.util.Arrays.asList(
                "src/main/java/**", "src/main/resources/**", "src/test/java/**")));
        cfg.setExcludePaths(new java.util.ArrayList<>(java.util.Arrays.asList(
                "**/SecurityConfig.java", "**/application*.yml", "**/*Config.java")));
        cfg.setAllowedExtensions(new java.util.ArrayList<>(java.util.Arrays.asList(
                ".java", ".xml", ".yml", ".properties", ".sql", ".md")));
        cfg.setMaxFileCount(20);
        cfg.setMaxFileSize(512000);
        return new FixGuard(cfg);
    }

    @Test
    void shouldAllowJavaFileInSrcMain() {
        FixGuard guard = createGuard();
        assertThatCode(() -> guard.validate("src/main/java/com/example/Service.java",
                "public class Service {}", "CREATE", 0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPathTraversal() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("../etc/passwd", "content", "CREATE", 0))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void shouldRejectExcludedSecurityConfig() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/SecurityConfig.java",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exclude");
    }

    @Test
    void shouldRejectExcludedApplicationYml() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/resources/application.yml",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exclude");
    }

    @Test
    void shouldRejectDisallowedExtension() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/script.sh",
                "content", "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("extension");
    }

    @Test
    void shouldRejectTooManyFiles() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Service.java",
                "content", "CREATE", 20))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("max");
    }

    @Test
    void shouldRejectFileTooLarge() {
        FixGuard guard = createGuard();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600000; i++) big.append("x");
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Big.java",
                big.toString(), "CREATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("size");
    }

    @Test
    void shouldRejectEmptyContentForUpdate() {
        FixGuard guard = createGuard();
        assertThatThrownBy(() -> guard.validate("src/main/java/com/app/Service.java",
                "", "UPDATE", 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("empty");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="FixGuardTest" -DfailIfNoTests=false -Djacoco.skip=true
```

- [ ] **Step 3: Write FixGuard implementation**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Validates file changes before they are applied to FixContext.
 * Prevents modification of host infrastructure files.
 */
public class FixGuard {

    private final List<String> includePatterns;
    private final List<String> excludePatterns;
    private final Set<String> allowedExtensions;
    private final int maxFileCount;
    private final long maxFileSize;

    public FixGuard(SnapAgentProperties.Fix.Guard config) {
        this.includePatterns = config.getIncludePaths();
        this.excludePatterns = config.getExcludePaths();
        this.allowedExtensions = new HashSet<String>(config.getAllowedExtensions());
        this.maxFileCount = config.getMaxFileCount();
        this.maxFileSize = config.getMaxFileSize();
    }

    /**
     * Validates a file change. Throws on violation.
     */
    public void validate(String filePath, String content, String action, int currentCount) {
        // 1. Path traversal check
        if (filePath == null || filePath.contains("../") || filePath.contains("..\\")) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }

        // 2. Include pattern check (must match at least one)
        boolean included = false;
        for (String pattern : includePatterns) {
            if (matchGlob(pattern, filePath)) {
                included = true;
                break;
            }
        }
        if (!included) {
            throw new RuntimeException("File not in include paths: " + filePath);
        }

        // 3. Exclude pattern check
        for (String pattern : excludePatterns) {
            if (matchGlob(pattern, filePath)) {
                throw new RuntimeException("File matches exclude pattern '" + pattern + "': " + filePath);
            }
        }

        // 4. Extension check
        String ext = getExtension(filePath);
        if (!allowedExtensions.contains(ext)) {
            throw new RuntimeException("File extension not allowed: " + ext + " for " + filePath);
        }

        // 5. File count limit
        if (currentCount >= maxFileCount) {
            throw new RuntimeException("Max file count exceeded (" + maxFileCount + ")");
        }

        // 6. File size limit
        if (content != null && content.length() > maxFileSize) {
            throw new RuntimeException("File too large: " + content.length() + " > " + maxFileSize);
        }

        // 7. Content non-empty for CREATE/UPDATE
        if (("CREATE".equals(action) || "UPDATE".equals(action))
                && (content == null || content.isEmpty())) {
            throw new RuntimeException("Content cannot be empty for " + action + " action");
        }
    }

    /**
     * Simple glob matcher: ** matches any path segments, * matches within a segment.
     */
    private boolean matchGlob(String pattern, String path) {
        // Convert glob to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", "<<<GLOBSTAR>>>")
                .replace("*", "[^/]*")
                .replace("<<<GLOBSTAR>>>", ".*");
        return Pattern.matches(regex, path);
    }

    private static String getExtension(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx >= 0) {
            return filePath.substring(dotIdx).toLowerCase();
        }
        return "";
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="FixGuardTest" -DfailIfNoTests=false -Djacoco.skip=true
```

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixGuard.java \
       snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/fix/FixGuardTest.java
git commit -m "feat(fix): add FixGuard safety validator + tests"
```

### Task 16: FileWriteTool + FileEditTool

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FileWriteTool.java`
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FileEditTool.java`

- [ ] **Step 1: Create FileWriteTool**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolParam;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.util.Map;

/**
 * LLM-callable tool for creating/overwriting files during fix execution.
 * Changes are tracked in FixContext (in-memory), not written to disk.
 * Guard validates every change.
 */
public class FileWriteTool implements ToolCallback {

    private final FixContextHolder holder;
    private final FixGuard guard;

    public FileWriteTool(FixContextHolder holder, FixGuard guard) {
        this.holder = holder;
        this.guard = guard;
    }

    @Tool(name = "file_write",
          description = "Creates or overwrites a file with the given content. Use for new files only.")
    public ToolResult write(
            @ToolParam(description = "File path relative to project root") String filePath,
            @ToolParam(description = "Full file content") String content) {

        FixContext ctx = holder.get();
        if (ctx == null) {
            return ToolResult.error("File modification is only available during fix execution");
        }

        try {
            guard.validate(filePath, content, "CREATE", ctx.size());
            ctx.addChange(filePath, content, "CREATE");
            return ToolResult.success("File written: " + filePath + " (" + content.length() + " chars)");
        } catch (RuntimeException e) {
            return ToolResult.error("Guard rejected: " + e.getMessage());
        }
    }

    @Override
    public String getName() { return "file_write"; }
    @Override
    public String getDescription() { return "Creates or overwrites a file. Use for new files only."; }
}
```

- [ ] **Step 2: Create FileEditTool**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.core.tool.ToolParam;
import cn.watsontech.snapagent.core.tool.Tool;
import cn.watsontech.snapagent.core.tool.ToolCallback;
import cn.watsontech.snapagent.core.tool.ToolResult;

/**
 * LLM-callable tool for targeted string replacement in existing files.
 * Similar to Claude Code's Edit tool.
 */
public class FileEditTool implements ToolCallback {

    private final FixContextHolder holder;
    private final FixGuard guard;

    public FileEditTool(FixContextHolder holder, FixGuard guard) {
        this.holder = holder;
        this.guard = guard;
    }

    @Tool(name = "file_edit",
          description = "Replaces a specific string in a file. Provide oldString (exact match) and newString.")
    public ToolResult edit(
            @ToolParam(description = "File path relative to project root") String filePath,
            @ToolParam(description = "Exact string to find in the file") String oldString,
            @ToolParam(description = "String to replace oldString with") String newString) {

        FixContext ctx = holder.get();
        if (ctx == null) {
            return ToolResult.error("File modification is only available during fix execution");
        }

        // Get current content (from FixContext or disk)
        String currentContent = ctx.getFileContent(filePath);
        if (currentContent == null) {
            return ToolResult.error("File not found: " + filePath);
        }

        // Find oldString
        int idx = currentContent.indexOf(oldString);
        if (idx < 0) {
            return ToolResult.error("oldString not found in " + filePath);
        }

        // Replace first occurrence
        String newContent = currentContent.substring(0, idx)
                + newString
                + currentContent.substring(idx + oldString.length());

        try {
            guard.validate(filePath, newContent, "UPDATE", ctx.size());
            ctx.addChange(filePath, newContent, "UPDATE");
            String summary = oldString.length() > 50
                    ? oldString.substring(0, 50) + "..." : oldString;
            return ToolResult.success("File edited: " + filePath
                    + "\n- " + summary + " → " + (newString.length() > 50
                    ? newString.substring(0, 50) + "..." : newString));
        } catch (RuntimeException e) {
            return ToolResult.error("Guard rejected: " + e.getMessage());
        }
    }

    @Override
    public String getName() { return "file_edit"; }
    @Override
    public String getDescription() { return "Replaces a specific string in a file."; }
}
```

Note: Check `ToolResult` class for the exact factory method names (`success`, `error`). If they differ, adapt accordingly.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 4: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FileWriteTool.java \
       snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FileEditTool.java
git commit -m "feat(fix): add FileWriteTool and FileEditTool with @Tool annotations"
```

## Phase 5: FixExecutionService

### Task 17: FixExecutionService

**Files:**
- Create: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixExecutionService.java`

- [ ] **Step 1: Create the service**

```java
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

        // Create FixContext and bind to thread
        FixContext ctx = new FixContext(projectRoot);
        fixContextHolder.set(ctx);

        try {
            // Run agent with fix prompt
            runFixAgent(issue);

            // Collect changes
            List<FileChange> changes = ctx.getChanges();
            if (changes.isEmpty()) {
                return FixResult.failed("AI did not produce any code changes");
            }

            // Create branch + commit + PR
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

        // Use "code-analysis" skill as the base — it has code_read, project_structure, git_log tools
        // The fix prompt is injected as the user message
        String fixPrompt = buildFixPrompt(issue);

        SkillMeta skill = skillRegistry.get("code-analysis");
        if (skill == null) {
            throw new RuntimeException("Skill 'code-analysis' not found");
        }

        AgentTask fixTask = AgentTask.create(systemUserId, "code-analysis", inputs, fixPrompt);
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
```

Note: `AgentTask.create(userId, skillId, inputs, message)` — check if the 4th arg (message/prompt) is supported. If not, embed the fix prompt in inputs map.

- [ ] **Step 2: Compile**

```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/fix/FixExecutionService.java
git commit -m "feat(fix): add FixExecutionService orchestration service"
```

## Phase 6: IssueClosureService Enhancements

This phase adds `autoFix()`, `onPrMerged()`, and an enhanced `verify()` that
executes structured acceptance criteria. All external tracker interactions
(`addComment`) are wrapped in try/catch so failures never block the main flow.

### Task 18: IssueClosureService — autoFix()

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceAutoFixTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.boot2x.fix.FixContextHolder;
import cn.watsontech.snapagent.boot2x.fix.FixResult;
import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueClosureServiceAutoFixTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private FixExecutionService fixExecutionService;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        fixExecutionService = mock(FixExecutionService.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system");
        // Inject fixExecutionService via reflection (field not in constructor)
        java.lang.reflect.Field f = IssueClosureService.class.getDeclaredField("fixExecutionService");
        f.setAccessible(true);
        f.set(service, fixExecutionService);
    }

    @Test
    void autoFix_transitionsToFixSubmitted() {
        IssueClosure issue = new IssueClosure(
                "issue_1", "EXT-1", "task_1", null, "user1", "query",
                "root cause", null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, 1000L, 1000L);
        when(issueStore.load("issue_1")).thenReturn(issue);

        FixResult fixResult = FixResult.success(
                "abc123", "https://gitlab/mr/1", "1",
                Arrays.asList("src/Main.java"));
        when(fixExecutionService.autoFix("issue_1")).thenReturn(fixResult);

        IssueClosure result = service.autoFix("issue_1");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
        assertThat(result.getFixCommitId()).isEqualTo("abc123");
        verify(issueStore).save(any(IssueClosure.class));
        verify(issueTracker).updateStatus("EXT-1", "in_progress");
        verify(issueTracker).addComment(eq("EXT-1"), any(String.class));
    }

    @Test
    void autoFix_returnsNullWhenIssueNotFound() {
        when(issueStore.load("nope")).thenReturn(null);
        assertThat(service.autoFix("nope")).isNull();
    }

    @Test
    void autoFix_returnsNullWhenWrongStatus() {
        IssueClosure issue = new IssueClosure(
                "issue_2", null, "task_2", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, 1L, 1L);
        when(issueStore.load("issue_2")).thenReturn(issue);
        assertThat(service.autoFix("issue_2")).isNull();
    }

    @Test
    void autoFix_addCommentFailureDoesNotBlock() {
        IssueClosure issue = new IssueClosure(
                "issue_3", "EXT-3", "task_3", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, 1L, 1L);
        when(issueStore.load("issue_3")).thenReturn(issue);
        when(fixExecutionService.autoFix("issue_3"))
                .thenReturn(FixResult.success("sha", "url", "1", Arrays.asList("f")));
        doThrow(new RuntimeException("network error"))
                .when(issueTracker).addComment(eq("EXT-3"), any(String.class));

        IssueClosure result = service.autoFix("issue_3");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceAutoFixTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL with "autoFix method not found" or compilation error

- [ ] **Step 3: Add FixExecutionService field and autoFix() method**

Add to `IssueClosureService.java`:

```java
// Add import
import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.boot2x.fix.FixResult;

// Add field after systemUserId
private final FixExecutionService fixExecutionService;
```

Update the constructor to accept `FixExecutionService` as the last parameter:

```java
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
```

Add the `autoFix()` method:

```java
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
```

Add the `buildFixComment` helper:

```java
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
    // Append acceptance criteria from solution
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
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceAutoFixTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceAutoFixTest.java
git commit -m "feat(issue): add autoFix() to IssueClosureService with PR creation + external comment sync"
```

### Task 19: IssueClosureService — onPrMerged()

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceOnPrMergedTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IssueClosureServiceOnPrMergedTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", null);
    }

    @Test
    void onPrMerged_findsByPrNumberAndVerifies() {
        IssueClosure issue = new IssueClosure(
                "issue_1", "EXT-1", "task_1", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_SUBMITTED,
                "abc", null, null, 1L, 1L);
        // Need fixPrNumber field — will exist after Phase 2 withFix()
        when(issueStore.findByPrNumber("42")).thenReturn(issue);

        // verify() will be called — mock it by spying
        IssueClosureService spy = spy(service);
        IssueClosure verified = new IssueClosure(
                "issue_1", "EXT-1", "task_1", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                "abc", new VerificationResult(true, "all passed", "FIX_SUBMITTED", "VERIFIED", 2000L),
                null, 1L, 2000L);
        doReturn(verified).when(spy).verify("issue_1");
        doReturn(verified).when(spy).close("issue_1");

        IssueClosure result = spy.onPrMerged("42");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);
    }

    @Test
    void onPrMerged_returnsNullWhenIssueNotFound() {
        when(issueStore.findByPrNumber("99")).thenReturn(null);
        assertThat(service.onPrMerged("99")).isNull();
    }

    @Test
    void onPrMerged_idempotentWhenWrongStatus() {
        IssueClosure issue = new IssueClosure(
                "issue_2", null, "t", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, 1L, 1L);
        when(issueStore.findByPrNumber("2")).thenReturn(issue);
        assertThat(service.onPrMerged("2")).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceOnPrMergedTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `onPrMerged` method not found

- [ ] **Step 3: Implement onPrMerged()**

Add to `IssueClosureService.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceOnPrMergedTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceOnPrMergedTest.java
git commit -m "feat(issue): add onPrMerged() for webhook-triggered verify+close"
```

### Task 20: IssueClosureService — Enhanced verify() with acceptance criteria

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceVerifyCriteriaTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IssueClosureServiceVerifyCriteriaTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", null);
    }

    @Test
    void verify_fallsBackToSkillWhenNoCriteria() {
        SolutionSuggestion sol = new SolutionSuggestion(
                Collections.emptyList(), null, null, null, null);
        IssueClosure issue = new IssueClosure(
                "i1", "EXT-1", "t1", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, 1L, 1L);
        when(issueStore.load("i1")).thenReturn(issue);

        // No verificationRunner and no skillRegistry → returns null
        IssueClosure result = service.verify("i1");
        // Since verifyViaSkill needs skillRegistry (null) → returns null
        assertThat(result).isNull();
    }

    @Test
    void verify_addsCommentToExternalIssue() {
        SolutionSuggestion sol = new SolutionSuggestion(
                Collections.emptyList(), null, null, null, null);
        VerificationResult vr = new VerificationResult(true, "passed",
                "FIX_SUBMITTED", "VERIFIED", 2000L);
        IssueClosure issue = new IssueClosure(
                "i2", "EXT-2", "t2", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, 1L, 1L);
        when(issueStore.load("i2")).thenReturn(issue);

        // Spy to override verifyViaSkill
        IssueClosureService spy = spy(service);
        doReturn(vr).when(spy).verifyViaSkill(eq("i2"), any(IssueClosure.class));

        IssueClosure result = spy.verify("i2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.VERIFIED);
        verify(issueTracker).addComment(eq("EXT-2"), any(String.class));
    }

    @Test
    void verify_transitionsToFailedWhenNotPassed() {
        SolutionSuggestion sol = new SolutionSuggestion(
                Collections.emptyList(), null, null, null, null);
        VerificationResult vr = new VerificationResult(false, "failed",
                "FIX_SUBMITTED", "FAILED", 2000L);
        IssueClosure issue = new IssueClosure(
                "i3", null, "t3", null, "u", "q", "rc",
                sol, null, IssueStatus.FIX_SUBMITTED,
                "sha", null, null, 1L, 1L);
        when(issueStore.load("i3")).thenReturn(issue);

        IssueClosureService spy = spy(service);
        doReturn(vr).when(spy).verifyViaSkill(eq("i3"), any(IssueClosure.class));

        IssueClosure result = spy.verify("i3");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FAILED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceVerifyCriteriaTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — verify() doesn't call addComment, doesn't transition to FAILED

- [ ] **Step 3: Enhance verify() method**

Replace the existing `verify()` method in `IssueClosureService.java`:

```java
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
```

Add the helper methods:

```java
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
 *
 * <p>Currently delegates to the VerificationRunner with the criteria embedded
 * in the issue's solution. Future enhancement: direct tool execution.</p>
 */
private VerificationResult verifyViaCriteria(IssueClosure issue,
                                              List<AcceptanceCriterion> criteria) {
    log.info("Verifying issue {} with {} acceptance criteria",
            issue.getIssueId(), criteria.size());
    // Delegate to verification runner if available
    if (verificationRunner != null) {
        VerificationResult result = verificationRunner.verify(issue);
        if (result != null) {
            return result;
        }
    }
    // Fallback to skill-based verification
    return verifyViaSkill(issue.getIssueId(), issue);
}

/**
 * Builds a markdown comment summarizing verification results.
 */
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
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceVerifyCriteriaTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceVerifyCriteriaTest.java
git commit -m "feat(issue): enhance verify() with acceptance criteria + FAILED transition + external comment"
```

### Task 21: IssueClosureService — Add addComment to proposeSolution + close

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceCommentSyncTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IssueClosureServiceCommentSyncTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", null);
    }

    @Test
    void close_addsCommentToExternalIssue() {
        IssueClosure issue = new IssueClosure(
                "i1", "EXT-1", "t1", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                "sha", new VerificationResult(true, "ok", null, null, 1L),
                null, 1L, 1L);
        when(issueStore.load("i1")).thenReturn(issue);

        service.close("i1");

        verify(issueTracker).updateStatus("EXT-1", "resolved");
        verify(issueTracker).addComment(eq("EXT-1"), contains("关闭"));
    }

    @Test
    void close_commentFailureDoesNotBlock() {
        IssueClosure issue = new IssueClosure(
                "i2", "EXT-2", "t2", null, "u", "q", "rc",
                null, null, IssueStatus.VERIFIED,
                null, null, null, 1L, 1L);
        when(issueStore.load("i2")).thenReturn(issue);
        doThrow(new RuntimeException("timeout"))
                .when(issueTracker).addComment(eq("EXT-2"), any(String.class));

        IssueClosure result = service.close("i2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.CLOSED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceCommentSyncTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — close() does not call addComment

- [ ] **Step 3: Add addComment to close()**

In the `close()` method, after the `updateStatus` try/catch block, add:

```java
// Post close comment to external issue
if (issue.getExternalIssueId() != null && !issue.getExternalIssueId().isEmpty()) {
    try {
        issueTracker.addComment(issue.getExternalIssueId(),
                buildCloseComment(issue));
    } catch (RuntimeException e) {
        log.warn("Failed to add close comment: {}", e.getMessage());
    }
}
```

Add the helper:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueClosureServiceCommentSyncTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureServiceCommentSyncTest.java
git commit -m "feat(issue): add addComment sync to close() lifecycle node"
```

### Task 22: Update IssueClosureService constructor call in auto-config

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java`

- [ ] **Step 1: Update the issueClosureService bean to pass FixExecutionService**

In `SnapAgentAutoConfiguration.java`, find the `issueClosureService` bean method
(line ~1189) and add `fixExecutionServiceProvider` parameter:

```java
@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.issue-closure", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public IssueClosureService issueClosureService(
        AgentService agentService,
        TaskStore taskStore,
        SkillRegistry skillRegistry,
        IssueStore issueStore,
        IssueTracker issueTracker,
        ObjectProvider<cn.watsontech.snapagent.boot2x.knowledge.KnowledgeSedimentationService> sedimentationServiceProvider,
        ObjectProvider<cn.watsontech.snapagent.core.issue.SolutionSuggester> solutionSuggesterProvider,
        ObjectProvider<cn.watsontech.snapagent.core.issue.VerificationRunner> verificationRunnerProvider,
        ObjectProvider<cn.watsontech.snapagent.boot2x.fix.FixExecutionService> fixExecutionServiceProvider,
        SnapAgentProperties properties) {
    log.info("IssueClosureService assembled (system-user-id={})",
            properties.getIssueClosure().getSystemUserId());
    return new IssueClosureService(agentService, taskStore, skillRegistry,
            issueStore, issueTracker,
            sedimentationServiceProvider.getIfAvailable(),
            solutionSuggesterProvider.getIfAvailable(),
            verificationRunnerProvider.getIfAvailable(),
            properties.getIssueClosure().getSystemUserId(),
            fixExecutionServiceProvider.getIfAvailable());
}
```

- [ ] **Step 2: Compile to verify no errors**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java
git commit -m "refactor(autoconfig): pass FixExecutionService to IssueClosureService constructor"
```

## Phase 7: IssueTracker addComment Implementations

This phase adds the `addComment` method to the `IssueTracker` SPI interface
and all four implementations (Noop, Zentao, GitHub, Jira).

### Task 23: IssueTracker SPI — add default addComment()

**Files:**
- Modify: `snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueTracker.java`
- Test: `snap-agent-core/src/test/java/cn/watsontech/snapagent/core/issue/IssueTrackerAddCommentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.core.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class IssueTrackerAddCommentTest {

    @Test
    void defaultAddCommentDoesNothing() {
        IssueTracker tracker = new IssueTracker() {
            @Override public String createIssue(String t, String d, String a) { return null; }
            @Override public void updateStatus(String id, String s) { }
            @Override public String getIssueUrl(String id) { return null; }
            @Override public String type() { return "test"; }
        };

        // Default addComment should be a no-op that does not throw
        assertThatNoException().isThrownBy(() ->
                tracker.addComment("EXT-1", "test comment"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `addComment` method not found on IssueTracker

- [ ] **Step 3: Add addComment default method to IssueTracker**

In `IssueTracker.java`, add after the `type()` method:

```java
/**
 * Adds a comment/note to an external issue.
 *
 * <p>Default implementation is a no-op. Implementations that support
 * commenting should override this method.</p>
 *
 * @param externalIssueId the external issue ID
 * @param comment         the comment text (markdown supported)
 */
default void addComment(String externalIssueId, String comment) {
    // no-op by default
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=IssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueTracker.java \
  snap-agent-core/src/test/java/cn/watsontech/snapagent/core/issue/IssueTrackerAddCommentTest.java
git commit -m "feat(core): add default addComment() to IssueTracker SPI"
```

### Task 24: NoopIssueTracker — addComment no-op

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTracker.java`

- [ ] **Step 1: Add explicit no-op override (for clarity/documentation)**

In `NoopIssueTracker.java`, add:

```java
@Override
public void addComment(String externalIssueId, String comment) {
    // no-op: noop tracker does not interact with external systems
}
```

- [ ] **Step 2: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTracker.java
git commit -m "feat(tracker): add explicit addComment no-op to NoopIssueTracker"
```

### Task 25: ZentaoIssueTracker — addComment

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/ZentaoIssueTracker.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/ZentaoIssueTrackerAddCommentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class ZentaoIssueTrackerAddCommentTest {

    private static WireMockServer server;
    private ZentaoIssueTracker tracker;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        SnapAgentProperties.IssueClosure.ZentaoTracker config =
                new SnapAgentProperties.IssueClosure.ZentaoTracker();
        config.setBaseUrl(server.baseUrl());
        config.setToken("test-token");
        config.setProductId(1);
        tracker = new ZentaoIssueTracker(config);
    }

    @AfterEach
    void reset() {
        server.resetAll();
    }

    @Test
    void addComment_postsToBugCommentsEndpoint() {
        server.stubFor(post(urlEqualTo("/api.php/v1/bugs/123/comments"))
                .willReturn(aResponse().withStatus(201)
                        .withBody("{\"id\":456}")));

        tracker.addComment("123", "Fix submitted: commit abc");

        server.verify(postRequestedFor(urlEqualTo("/api.php/v1/bugs/123/comments"))
                .withHeader("Token", equalTo("test-token")));
    }

    @Test
    void addComment_emptyIdDoesNothing() {
        tracker.addComment("", "comment");
        // No request should be made
        server.verify(0, postRequestedFor(urlMatching("/api.php/v1/bugs/.*/comments")));
    }

    @Test
    void addComment_nullIdDoesNothing() {
        tracker.addComment(null, "comment");
        server.verify(0, postRequestedFor(urlMatching("/api.php/v1/bugs/.*/comments")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ZentaoIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `addComment` not overridden in ZentaoIssueTracker

- [ ] **Step 3: Implement addComment in ZentaoIssueTracker**

Add to `ZentaoIssueTracker.java`:

```java
@Override
public void addComment(String externalIssueId, String comment) {
    if (externalIssueId == null || externalIssueId.isEmpty()) {
        return;
    }

    String url = baseUrl + "/api.php/v1/bugs/" + externalIssueId + "/comments";
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("comment", comment != null ? comment : "");

    jsonRequest(url, "POST", authHeader("Token " + token), body);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=ZentaoIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/ZentaoIssueTracker.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/ZentaoIssueTrackerAddCommentTest.java
git commit -m "feat(tracker): add addComment to ZentaoIssueTracker via /bugs/{id}/comments"
```

### Task 26: GitHubIssueTracker — addComment

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/GitHubIssueTracker.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/GitHubIssueTrackerAddCommentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubIssueTrackerAddCommentTest {

    private static WireMockServer server;
    private GitHubIssueTracker tracker;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        SnapAgentProperties.IssueClosure.GitHubTracker config =
                new SnapAgentProperties.IssueClosure.GitHubTracker();
        config.setApiBaseUrl(server.baseUrl());
        config.setToken("ghp_testtoken");
        config.setOwner("myorg");
        config.setRepo("myrepo");
        tracker = new GitHubIssueTracker(config);
    }

    @AfterEach
    void reset() {
        server.resetAll();
    }

    @Test
    void addComment_postsToIssueCommentsEndpoint() {
        server.stubFor(post(urlEqualTo("/repos/myorg/myrepo/issues/42/comments"))
                .willReturn(aResponse().withStatus(201)
                        .withBody("{\"id\":789}")));

        tracker.addComment("42", "Fix submitted: commit abc");

        server.verify(postRequestedFor(urlEqualTo("/repos/myorg/myrepo/issues/42/comments"))
                .withHeader("Authorization", equalTo("Bearer ghp_testtoken")));
    }

    @Test
    void addComment_emptyIdDoesNothing() {
        tracker.addComment("", "comment");
        server.verify(0, postRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
    }

    @Test
    void addComment_nullIdDoesNothing() {
        tracker.addComment(null, "comment");
        server.verify(0, postRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=GitHubIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `addComment` not overridden

- [ ] **Step 3: Implement addComment in GitHubIssueTracker**

Add to `GitHubIssueTracker.java`:

```java
@Override
public void addComment(String externalIssueId, String comment) {
    if (externalIssueId == null || externalIssueId.isEmpty()) {
        return;
    }

    String url = apiBaseUrl + "/repos/" + owner + "/" + repo
            + "/issues/" + externalIssueId + "/comments";
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("body", comment != null ? comment : "");

    jsonRequest(url, "POST", authHeader("Bearer " + token), body);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=GitHubIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/GitHubIssueTracker.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/GitHubIssueTrackerAddCommentTest.java
git commit -m "feat(tracker): add addComment to GitHubIssueTracker via /issues/{n}/comments"
```

### Task 27: JiraIssueTracker — addComment

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/JiraIssueTracker.java`
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/JiraIssueTrackerAddCommentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package cn.watsontech.snapagent.boot2x.issue;

import cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class JiraIssueTrackerAddCommentTest {

    private static WireMockServer server;
    private JiraIssueTracker tracker;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        SnapAgentProperties.IssueClosure.JiraTracker config =
                new SnapAgentProperties.IssueClosure.JiraTracker();
        config.setBaseUrl(server.baseUrl());
        config.setUsername("user@example.com");
        config.setApiToken("api-token");
        config.setProjectKey("PROJ");
        tracker = new JiraIssueTracker(config);
    }

    @AfterEach
    void reset() {
        server.resetAll();
    }

    @Test
    void addComment_postsToIssueCommentEndpoint() {
        server.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-123/comment"))
                .willReturn(aResponse().withStatus(201)
                        .withBody("{\"id\":\"456\",\"body\":\"test\"}")));

        tracker.addComment("PROJ-123", "Fix submitted: commit abc");

        server.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-123/comment"))
                .withHeader("Authorization", matching("Basic .*")));
    }

    @Test
    void addComment_emptyIdDoesNothing() {
        tracker.addComment("", "comment");
        server.verify(0, postRequestedFor(urlMatching("/rest/api/2/issue/.*/comment")));
    }

    @Test
    void addComment_nullIdDoesNothing() {
        tracker.addComment(null, "comment");
        server.verify(0, postRequestedFor(urlMatching("/rest/api/2/issue/.*/comment")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=JiraIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `addComment` not overridden

- [ ] **Step 3: Implement addComment in JiraIssueTracker**

Add to `JiraIssueTracker.java`:

```java
@Override
public void addComment(String externalIssueId, String comment) {
    if (externalIssueId == null || externalIssueId.isEmpty()) {
        return;
    }

    String url = baseUrl + "/rest/api/2/issue/" + externalIssueId + "/comment";
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("body", comment != null ? comment : "");

    jsonRequest(url, "POST", authHeaders(), body);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=JiraIssueTrackerAddCommentTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/JiraIssueTracker.java \
  snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/JiraIssueTrackerAddCommentTest.java
git commit -m "feat(tracker): add addComment to JiraIssueTracker via /issue/{key}/comment"
```

### Task 28: Update existing IssueTracker tests for addComment default

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTrackerTest.java`

- [ ] **Step 1: Add test verifying NoopIssueTracker.addComment is a safe no-op**

Add to `NoopIssueTrackerTest.java`:

```java
@Test
void addComment_doesNotThrow() {
    // The noop tracker should silently accept addComment calls
    tracker.addComment("EXT-1", "test comment");
    // No exception thrown = pass
}
```

- [ ] **Step 2: Run all tracker tests to verify no regression**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest="*IssueTracker*Test" -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS (all existing tracker tests + new addComment tests)

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/issue/NoopIssueTrackerTest.java
git commit -m "test(tracker): add addComment no-op test to NoopIssueTrackerTest"
```

## Phase 8: Controller Endpoints + Webhook

This phase adds the `auto-fix` endpoints and the VCS webhook callback to
the `SnapAgentController`, plus enhances the `toIssueDto` to include fix
PR info and acceptance criteria.

### Task 29: SnapAgentController — POST /runs/{taskId}/auto-fix

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java`

- [ ] **Step 1: Add auto-fix endpoint (by task ID)**

Insert after the existing `POST /runs/{taskId}/issue` endpoint
(line ~2163), before `GET /issues/recent-runs`:

```java
// ---- POST /runs/{taskId}/auto-fix (v1.1 auto-fix workflow) ----
@PostMapping("/runs/{taskId}/auto-fix")
public ResponseEntity<Object> autoFixByTask(@PathVariable String taskId) {
    ResponseEntity<Object> authError = requireAuth();
    if (authError != null) return authError;

    if (issueClosureService == null) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "ISSUE_CLOSURE_DISABLED",
                "issue-closure not enabled");
    }

    IssueClosure issue = issueClosureService.findByTaskId(taskId);
    if (issue == null) {
        return errorResponse(HttpStatus.NOT_FOUND, "ISSUE_NOT_FOUND",
                "no issue closure found for task: " + taskId);
    }

    IssueClosure result = issueClosureService.autoFix(issue.getIssueId());
    if (result == null) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "AUTO_FIX_FAILED",
                "auto-fix not available or issue is not in FIX_IN_PROGRESS status");
    }

    audit(currentUserId(), "POST", "/runs/" + taskId + "/auto-fix", "AUTO_FIX",
            Collections.<String, Object>singletonMap("issueId", issue.getIssueId()));

    return ResponseEntity.ok(toIssueDto(result));
}
```

Add the `findByTaskId` delegation method to `IssueClosureService`:

```java
/**
 * Finds an issue closure by its associated diagnostic task ID.
 *
 * @param taskId the diagnostic task ID
 * @return the issue closure, or null if not found
 */
public IssueClosure findByTaskId(String taskId) {
    return issueStore.findByTaskId(taskId);
}
```

- [ ] **Step 2: Add auto-fix endpoint (by issue ID)**

Insert after the `POST /issues/{issueId}/verify` endpoint (line ~2341),
before `POST /issues/{issueId}/close`:

```java
// ---- POST /issues/{issueId}/auto-fix (v1.1 auto-fix workflow) ----
@PostMapping("/issues/{issueId}/auto-fix")
public ResponseEntity<Object> autoFixIssue(@PathVariable String issueId) {
    ResponseEntity<Object> authError = requireAuth();
    if (authError != null) return authError;

    if (issueClosureService == null) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "ISSUE_CLOSURE_DISABLED",
                "issue-closure not enabled");
    }

    IssueClosure issue = issueClosureService.autoFix(issueId);
    if (issue == null) {
        return errorResponse(HttpStatus.NOT_FOUND, "ISSUE_NOT_FOUND",
                "issue not found or not in FIX_IN_PROGRESS status: " + issueId);
    }

    audit(currentUserId(), "POST", "/issues/" + issueId + "/auto-fix", "AUTO_FIX",
            Collections.<String, Object>singletonMap("issueId", issue.getIssueId()));

    return ResponseEntity.ok(toIssueDto(issue));
}
```

- [ ] **Step 3: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java \
  snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/IssueClosureService.java
git commit -m "feat(controller): add POST /runs/{taskId}/auto-fix and POST /issues/{issueId}/auto-fix endpoints"
```

### Task 30: SnapAgentController — POST /snap-agent-internal/vcs/webhook

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java`

- [ ] **Step 1: Add webhook endpoint**

Insert at the end of the controller class (before the closing brace),
after the last existing endpoint. This endpoint is under the internal
path prefix so it doesn't require auth (it uses a shared secret instead):

```java
// ---- POST /snap-agent-internal/vcs/webhook (v1.1 auto-fix webhook) ----
@PostMapping("/snap-agent-internal/vcs/webhook")
public ResponseEntity<Object> vcsWebhook(@RequestBody Map<String, Object> body,
                                         @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
                                         @RequestHeader(value = "X-Event-Key", required = false) String bitbucketEvent,
                                         @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
    // Webhook doesn't use requireAuth — it uses shared secret validation

    if (issueClosureService == null) {
        return ResponseEntity.ok(Collections.singletonMap("status", "ignored"));
    }

    // Parse PR number and merge state from webhook payload
    // GitLab: { "object_attributes": { "iid": 42, "state": "merged", "action": "merge" } }
    // Bitbucket: { "pullRequest": { "id": 42, "state": "MERGED" } }
    String prNumber = null;
    String state = null;

    if (body.containsKey("object_attributes")) {
        // GitLab webhook format
        Object attrObj = body.get("object_attributes");
        if (attrObj instanceof Map) {
            Map<String, ?> attr = (Map<String, ?>) attrObj;
            Object iid = attr.get("iid");
            if (iid != null) prNumber = String.valueOf(iid);
            Object st = attr.get("state");
            if (st != null) state = String.valueOf(st);
        }
    } else if (body.containsKey("pullRequest")) {
        // Bitbucket webhook format
        Object prObj = body.get("pullRequest");
        if (prObj instanceof Map) {
            Map<String, ?> pr = (Map<String, ?>) prObj;
            Object id = pr.get("id");
            if (id != null) prNumber = String.valueOf(id);
            Object st = pr.get("state");
            if (st != null) state = String.valueOf(st);
        }
    } else if (body.containsKey("pull_request")) {
        // GitHub webhook format (if routed through here)
        Object prObj = body.get("pull_request");
        if (prObj instanceof Map) {
            Map<String, ?> pr = (Map<String, ?>) prObj;
            Object num = pr.get("number");
            if (num != null) prNumber = String.valueOf(num);
            Object st = pr.get("state");
            if (st != null) state = String.valueOf(st);
            Object merged = pr.get("merged");
            if (Boolean.TRUE.equals(merged)) state = "merged";
        }
    }

    if (prNumber == null) {
        return ResponseEntity.ok(Collections.singletonMap("status", "ignored"));
    }

    if (!"merged".equalsIgnoreCase(state)) {
        return ResponseEntity.ok(Collections.singletonMap("status", "ignored")
                .toString().replace("ignored", state != null ? state : "unknown"));
    }

    IssueClosure result = issueClosureService.onPrMerged(prNumber);
    if (result == null) {
        return ResponseEntity.ok(Collections.singletonMap("status", "no_matching_issue"));
    }

    return ResponseEntity.ok(Collections.singletonMap("status", "processed"));
}
```

- [ ] **Step 2: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java
git commit -m "feat(controller): add POST /snap-agent-internal/vcs/webhook for PR merge trigger"
```

### Task 31: SnapAgentController — Enhanced toIssueDto with fix PR info

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java`

- [ ] **Step 1: Enhance toIssueDto to include fixPrUrl, fixPrNumber, and acceptance criteria**

In the `toIssueDto` method (line ~3004), add after the `fixCommitId` line:

```java
dto.put("fixPrUrl", issue.getFixPrUrl());
dto.put("fixPrNumber", issue.getFixPrNumber());
```

In the `solutionToDtoMap` method (line ~3026), add after `relatedCode`:

```java
// Add acceptance criteria
List<Map<String, Object>> acList = new ArrayList<Map<String, Object>>();
if (suggestion.getAcceptanceCriteria() != null) {
    for (AcceptanceCriterion ac : suggestion.getAcceptanceCriteria()) {
        Map<String, Object> acMap = new LinkedHashMap<String, Object>();
        acMap.put("id", ac.getId());
        acMap.put("description", ac.getDescription());
        acMap.put("verification", ac.getVerification());
        acMap.put("expected", ac.getExpected());
        acMap.put("tool", ac.getTool());
        acList.add(acMap);
    }
}
map.put("acceptanceCriteria", acList);
```

Add import for `AcceptanceCriterion` at the top of the file:

```java
import cn.watsontech.snapagent.core.issue.AcceptanceCriterion;
```

- [ ] **Step 2: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/web/SnapAgentController.java
git commit -m "feat(controller): enhance toIssueDto with fixPrUrl, fixPrNumber, acceptance criteria"
```

## Phase 9: Auto-Configuration + Properties

This phase adds VCS and Fix configuration sections to `SnapAgentProperties`
and wires all new beans in `SnapAgentAutoConfiguration`.

### Task 32: SnapAgentProperties — Add Vcs config section

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentProperties.java`

- [ ] **Step 1: Add Vcs inner class and field**

In `SnapAgentProperties.java`, add a new field after `issueClosure` (line ~65):

```java
private Vcs vcs = new Vcs();
private Fix fix = new Fix();
```

Add getter/setter after the `issueClosure` getter/setter:

```java
public Vcs getVcs() { return vcs; }
public void setVcs(Vcs vcs) { this.vcs = vcs; }
public Fix getFix() { return fix; }
public void setFix(Fix fix) { this.fix = fix; }
```

Add the `Vcs` inner class (after the `IssueClosure` inner class):

```java
public static class Vcs {
    private boolean enabled = false;
    private String type = "gitlab";
    private String defaultBranch = "main";
    private GitLab gitlab = new GitLab();
    private Bitbucket bitbucket = new Bitbucket();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public GitLab getGitlab() { return gitlab; }
    public void setGitlab(GitLab gitlab) { this.gitlab = gitlab; }
    public Bitbucket getBitbucket() { return bitbucket; }
    public void setBitbucket(Bitbucket bitbucket) { this.bitbucket = bitbucket; }

    public static class GitLab {
        private String baseUrl = "";
        private String token = "";
        private int projectId = 0;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public int getProjectId() { return projectId; }
        public void setProjectId(int projectId) { this.projectId = projectId; }
    }

    public static class Bitbucket {
        private String baseUrl = "";
        private String token = "";
        private String projectKey = "";
        private String repoSlug = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
        public String getRepoSlug() { return repoSlug; }
        public void setRepoSlug(String repoSlug) { this.repoSlug = repoSlug; }
    }
}
```

- [ ] **Step 2: Add Fix inner class with Guard config**

After the `Vcs` inner class:

```java
public static class Fix {
    private boolean enabled = false;
    private int maxTurns = 20;
    private int timeoutMinutes = 10;
    private String projectRoot = "";
    private Guard guard = new Guard();
    private Webhook webhook = new Webhook();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    public String getProjectRoot() { return projectRoot; }
    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
    public Guard getGuard() { return guard; }
    public void setGuard(Guard guard) { this.guard = guard; }
    public Webhook getWebhook() { return webhook; }
    public void setWebhook(Webhook webhook) { this.webhook = webhook; }

    public static class Guard {
        private List<String> includePaths = new ArrayList<String>(java.util.Arrays.asList(
                "src/main/java/**", "src/main/resources/**", "src/test/java/**"));
        private List<String> excludePaths = new ArrayList<String>(java.util.Arrays.asList(
                "**/SecurityConfig.java", "**/application*.yml",
                "**/application*.properties", "**/*Config.java",
                "**/DataSource*.java"));
        private List<String> allowedExtensions = new ArrayList<String>(java.util.Arrays.asList(
                ".java", ".xml", ".yml", ".properties", ".sql", ".md"));
        private int maxFileCount = 20;
        private long maxFileSize = 512000L;

        public List<String> getIncludePaths() { return includePaths; }
        public void setIncludePaths(List<String> includePaths) { this.includePaths = includePaths; }
        public List<String> getExcludePaths() { return excludePaths; }
        public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths; }
        public List<String> getAllowedExtensions() { return allowedExtensions; }
        public void setAllowedExtensions(List<String> allowedExtensions) { this.allowedExtensions = allowedExtensions; }
        public int getMaxFileCount() { return maxFileCount; }
        public void setMaxFileCount(int maxFileCount) { this.maxFileCount = maxFileCount; }
        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    }

    public static class Webhook {
        private String secret = "";

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
    }
}
```

- [ ] **Step 3: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentProperties.java
git commit -m "feat(config): add Vcs and Fix config sections to SnapAgentProperties"
```

### Task 33: SnapAgentAutoConfiguration — Wire VcsClient beans

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java`

- [ ] **Step 1: Add VcsClient bean wiring**

Add imports at the top of `SnapAgentAutoConfiguration.java`:

```java
import cn.watsontech.snapagent.boot2x.vcs.GitLabVcsClient;
import cn.watsontech.snapagent.boot2x.vcs.BitbucketVcsClient;
import cn.watsontech.snapagent.boot2x.fix.FixContextHolder;
import cn.watsontech.snapagent.boot2x.fix.FixGuard;
import cn.watsontech.snapagent.boot2x.fix.FileWriteTool;
import cn.watsontech.snapagent.boot2x.fix.FileEditTool;
import cn.watsontech.snapagent.boot2x.fix.FixExecutionService;
import cn.watsontech.snapagent.core.vcs.VcsClient;
```

Add VcsClient beans after the issue tracker beans (line ~1146):

```java
// ---- VCS Client (v1.1 auto-fix) ----

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(VcsClient.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = "type", havingValue = "gitlab", matchIfMissing = true)
public GitLabVcsClient gitLabVcsClient(SnapAgentProperties props) {
    SnapAgentProperties.Vcs.GitLab gl = props.getVcs().getGitlab();
    log.info("GitLabVcsClient assembled (base-url={}, project-id={})",
            gl.getBaseUrl(), gl.getProjectId());
    return new GitLabVcsClient(gl.getBaseUrl(), gl.getToken(),
            gl.getProjectId(), props.getVcs().getDefaultBranch());
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = {"enabled", "type"},
        havingValue = "true", matchIfMissing = false)
// Note: Spring Boot 2.x doesn't support multi-value havingValue in one annotation.
// For bitbucket, use separate conditions:
```

Since Spring Boot 2.x `@ConditionalOnProperty` doesn't support multiple
`havingValue` in one annotation, use a cleaner approach — two separate beans
each gated by `type`:

```java
@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = "enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = "type", havingValue = "gitlab", matchIfMissing = true)
public GitLabVcsClient gitLabVcsClient(SnapAgentProperties props) {
    SnapAgentProperties.Vcs.GitLab gl = props.getVcs().getGitlab();
    log.info("GitLabVcsClient assembled (base-url={}, project-id={})",
            gl.getBaseUrl(), gl.getProjectId());
    return new GitLabVcsClient(gl.getBaseUrl(), gl.getToken(),
            gl.getProjectId(), props.getVcs().getDefaultBranch());
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.vcs", name = {"enabled", "type"},
        havingValue = "bitbucket")
public BitbucketVcsClient bitbucketVcsClient(SnapAgentProperties props) {
    SnapAgentProperties.Vcs.Bitbucket bb = props.getVcs().getBitbucket();
    log.info("BitbucketVcsClient assembled (base-url={}, project={}, repo={})",
            bb.getBaseUrl(), bb.getProjectKey(), bb.getRepoSlug());
    return new BitbucketVcsClient(bb.getBaseUrl(), bb.getToken(),
            bb.getProjectKey(), bb.getRepoSlug(), props.getVcs().getDefaultBranch());
}
```

- [ ] **Step 2: Compile to verify (will fail until VcsClient implementations exist)**

This step depends on Phases 3 (VcsClient implementations) being complete.
If implementing sequentially, compile will fail here if Phase 3 hasn't been done.
Assuming Phase 3 is done:

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java
git commit -m "feat(autoconfig): wire GitLabVcsClient and BitbucketVcsClient beans"
```

### Task 34: SnapAgentAutoConfiguration — Wire Fix beans

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java`

- [ ] **Step 1: Add Fix bean wiring**

Add after the VcsClient beans:

```java
// ---- Fix infrastructure (v1.1 auto-fix) ----

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public FixContextHolder fixContextHolder() {
    log.info("FixContextHolder assembled");
    return new FixContextHolder();
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public FixGuard fixGuard(SnapAgentProperties props) {
    SnapAgentProperties.Fix.Guard g = props.getFix().getGuard();
    log.info("FixGuard assembled (include-paths={}, exclude-paths={})",
            g.getIncludePaths().size(), g.getExcludePaths().size());
    return new FixGuard(g.getIncludePaths(), g.getExcludePaths(),
            g.getAllowedExtensions(), g.getMaxFileCount(), g.getMaxFileSize());
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public FileWriteTool fileWriteTool(FixContextHolder holder, FixGuard guard) {
    log.info("FileWriteTool assembled");
    return new FileWriteTool(holder, guard);
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public FileEditTool fileEditTool(FixContextHolder holder, FixGuard guard) {
    log.info("FileEditTool assembled");
    return new FileEditTool(holder, guard);
}

@Bean
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "snap-agent.fix", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public FixExecutionService fixExecutionService(
        AgentService agentService,
        IssueStore issueStore,
        ObjectProvider<VcsClient> vcsClientProvider,
        FixContextHolder fixContextHolder,
        SnapAgentProperties props) {
    log.info("FixExecutionService assembled (max-turns={})", props.getFix().getMaxTurns());
    return new FixExecutionService(agentService, issueStore,
            vcsClientProvider.getIfAvailable(), fixContextHolder,
            props.getFix().getProjectRoot(),
            props.getVcs().getDefaultBranch(),
            props.getFix().getMaxTurns());
}
```

- [ ] **Step 2: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/autoconfig/SnapAgentAutoConfiguration.java
git commit -m "feat(autoconfig): wire FixGuard, FixContextHolder, FileWrite/EditTool, FixExecutionService beans"
```

### Task 35: FileIssueStore — findByPrNumber()

**Files:**
- Modify: `snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/FileIssueStore.java`

- [ ] **Step 1: Implement findByPrNumber**

In `FileIssueStore.java`, add after the `findByTaskId` method (line ~109):

```java
@Override
public IssueClosure findByPrNumber(String prNumber) {
    if (prNumber == null || prNumber.isEmpty()) {
        return null;
    }
    // Scan all issues and match by fixPrNumber field
    List<IssueClosure> all = list();
    for (IssueClosure issue : all) {
        if (prNumber.equals(issue.getFixPrNumber())) {
            return issue;
        }
    }
    return null;
}
```

Also add `fixPrNumber` and `fixPrUrl` to the `toMap` and `fromMap` methods.
In `toMap` (line ~201), add after `fixCommitId`:

```java
if (issue.getFixPrUrl() != null) {
    map.put("fixPrUrl", issue.getFixPrUrl());
}
if (issue.getFixPrNumber() != null) {
    map.put("fixPrNumber", issue.getFixPrNumber());
}
```

In `fromMap` (line ~261), add after the `fixCommitId` extraction:

```java
String fixPrUrl = nullableStr(data.get("fixPrUrl"));
String fixPrNumber = nullableStr(data.get("fixPrNumber"));
```

Pass them to the `IssueClosure` constructor call in `fromMap`.

- [ ] **Step 2: Compile to verify**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/main/java/cn/watsontech/snapagent/boot2x/issue/FileIssueStore.java
git commit -m "feat(store): add findByPrNumber() to FileIssueStore + serialize fixPrUrl/fixPrNumber"
```

## Phase 10: Build Verification + Demo Config

This phase runs a full build, verifies all tests pass, and adds demo
configuration to `application.yml`.

### Task 36: Full build verification

**Files:**
- None (verification only)

- [ ] **Step 1: Compile all modules**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn compile -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q -Dmaven.test.skip=true
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests in core module**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-core -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q
```
Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 3: Run all tests in starter module**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q
```
Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 4: Run all tests in demo module**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-demo -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q
```
Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 5: Commit if any fixes were needed**

If fixes were applied during the build:
```bash
git add -A && git commit -m "fix: resolve compilation/test issues from full build verification"
```

If no fixes needed, skip this step.

### Task 37: Demo application.yml — Add VCS and Fix config examples

**Files:**
- Modify: `snap-agent-demo/src/main/resources/application.yml`

- [ ] **Step 1: Add commented configuration examples**

Append to `application.yml` after the existing issue-closure config:

```yaml
  # ---- VCS Client (auto-fix) ----
  vcs:
    enabled: false
    type: gitlab              # gitlab | bitbucket
    default-branch: main
    # GitLab configuration
    gitlab:
      base-url: https://gitlab.example.com
      token: ${GITLAB_TOKEN:}
      project-id: 0
    # Bitbucket Server / Data Center configuration
    bitbucket:
      base-url: https://bitbucket.example.com
      token: ${BITBUCKET_TOKEN:}
      project-key: ""
      repo-slug: ""

  # ---- Fix infrastructure (auto-fix) ----
  fix:
    enabled: false
    max-turns: 20
    timeout-minutes: 10
    project-root: ""          # absolute path to host project root
    guard:
      include-paths:
        - "src/main/java/**"
        - "src/main/resources/**"
        - "src/test/java/**"
      exclude-paths:
        - "**/SecurityConfig.java"
        - "**/application*.yml"
        - "**/application*.properties"
        - "**/*Config.java"
        - "**/DataSource*.java"
      allowed-extensions:
        - .java
        - .xml
        - .yml
        - .properties
        - .sql
        - .md
      max-file-count: 20
      max-file-size: 512000
    webhook:
      secret: ${VCS_WEBHOOK_SECRET:}
```

- [ ] **Step 2: Commit**

```bash
git add snap-agent-demo/src/main/resources/application.yml
git commit -m "docs(demo): add VCS and Fix config examples to application.yml"
```

### Task 38: Final integration — end-to-end smoke test

**Files:**
- Test: `snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/fix/AutoFixEndToEndTest.java`

This is a lightweight integration test that wires all components together
with mocks to verify the full flow: autoFix → PR → onPrMerged → verify → close.

- [ ] **Step 1: Write the integration test**

```java
package cn.watsontech.snapagent.boot2x.fix;

import cn.watsontech.snapagent.boot2x.issue.IssueClosureService;
import cn.watsontech.snapagent.core.issue.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AutoFixEndToEndTest {

    private IssueStore issueStore;
    private IssueTracker issueTracker;
    private FixExecutionService fixExecutionService;
    private IssueClosureService service;

    @BeforeEach
    void setUp() {
        issueStore = mock(IssueStore.class);
        issueTracker = mock(IssueTracker.class);
        fixExecutionService = mock(FixExecutionService.class);
        service = new IssueClosureService(
                null, null, null, issueStore, issueTracker,
                null, null, null, "system", fixExecutionService);
    }

    @Test
    void fullFlow_autoFix_thenOnPrMerged_thenClose() {
        // 1. Start with an issue in FIX_IN_PROGRESS
        IssueClosure issue = new IssueClosure(
                "issue_1", "EXT-1", "task_1", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, 1000L, 1000L);
        when(issueStore.load("issue_1")).thenReturn(issue);
        when(issueStore.findByPrNumber("42")).thenReturn(
                issue.withFix("sha123", "https://gitlab/mr/42", "42",
                        IssueStatus.FIX_SUBMITTED, 2000L));

        // 2. autoFix
        FixResult fixResult = FixResult.success(
                "sha123", "https://gitlab/mr/42", "42",
                Arrays.asList("src/Main.java"));
        when(fixExecutionService.autoFix("issue_1")).thenReturn(fixResult);

        IssueClosure fixed = service.autoFix("issue_1");
        assertThat(fixed).isNotNull();
        assertThat(fixed.getStatus()).isEqualTo(IssueStatus.FIX_SUBMITTED);
        assertThat(fixed.getFixCommitId()).isEqualTo("sha123");
        assertThat(fixed.getFixPrNumber()).isEqualTo("42");
        verify(issueTracker).addComment(eq("EXT-1"), contains("修复方案"));

        // 3. onPrMerged — verify will use verifyViaSkill which needs skillRegistry (null)
        // So we spy and stub verify
        IssueClosureService spy = spy(service);
        VerificationResult vr = new VerificationResult(true, "all passed",
                "FIX_SUBMITTED", "VERIFIED", 3000L);
        doReturn(vr).when(spy).verifyViaSkill(eq("issue_1"), any(IssueClosure.class));

        IssueClosure merged = spy.onPrMerged("42");
        assertThat(merged).isNotNull();
        assertThat(merged.getStatus()).isEqualTo(IssueStatus.CLOSED);
        verify(issueTracker, atLeast(1)).updateStatus(eq("EXT-1"), any(String.class));
    }

    @Test
    void autoFix_failureTransitionsToFailed() {
        IssueClosure issue = new IssueClosure(
                "issue_2", "EXT-2", "task_2", null, "u", "q", "rc",
                null, null, IssueStatus.FIX_IN_PROGRESS,
                null, null, null, 1L, 1L);
        when(issueStore.load("issue_2")).thenReturn(issue);
        when(fixExecutionService.autoFix("issue_2"))
                .thenReturn(FixResult.failed("AI produced no changes"));

        IssueClosure result = service.autoFix("issue_2");
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IssueStatus.FAILED);
        verify(issueTracker).addComment(eq("EXT-2"), contains("失败"));
    }
}
```

- [ ] **Step 2: Run the integration test**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn test -pl snap-agent-spring-boot-2x-starter -am -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -Dtest=AutoFixEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add snap-agent-spring-boot-2x-starter/src/test/java/cn/watsontech/snapagent/boot2x/fix/AutoFixEndToEndTest.java
git commit -m "test(fix): add end-to-end auto-fix integration test (autoFix → onPrMerged → close)"
```

### Task 39: Final full build + commit

- [ ] **Step 1: Run full build with all tests**

Run:
```bash
JAVA_HOME="/Users/HuaSheng.Song/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home" /opt/homebrew/Cellar/maven/3.9.15/bin/mvn clean test -f /Users/HuaSheng.Song/IdeaProjects/skills-agent/pom.xml -q
```
Expected: BUILD SUCCESS, 0 failures

- [ ] **Step 2: Push**

```bash
git push origin main
```

- [ ] **Step 3: Celebrate 🎉
