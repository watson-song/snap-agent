# AI 自动修复流程设计 (Auto-Fix Workflow)

> **Date**: 2026-07-27
> **Status**: Draft
> **Author**: AI-assisted design via brainstorming session

---

## 1. 概述

SnapAgent 是嵌入式 AI 辅助系统，运行在宿主 Web 应用上下文中。当诊断技能发现问题后，
当前 Issue Closure 流程在 `FIX_IN_PROGRESS` → `VERIFIED` 之间存在缺口：没有 AI 自动修复步骤，
`fixCommitId` 字段始终为 null。

本设计新增 **AI 辅助 PR 工作流**（类似 SWE-agent / Copilot Workspace 模式）：
AI 自动生成修复代码、创建分支、提交 PR，人工 review 合并后自动触发验证和关闭。

### 核心约束

- **嵌入式定位**：运行在宿主 Web context，不含目标程序的编译/运行环境
- **不能做**：编译检查、运行测试、部署验证（委托给宿主 CI/CD）
- **能做**：读代码、改代码、Git 操作（via REST API）、诊断验证（mysql_query 等）

### 状态机扩展

```
DIAGNOSED → SOLUTION_PROPOSED → ISSUE_CREATED → FIX_IN_PROGRESS
    → FIX_SUBMITTED (NEW) → VERIFIED → CLOSED
         ↓                        ↑
    PR created               PR merged (webhook/manual)
    AI done                  trigger verify
                                ↘ FAILED
```

`FIX_SUBMITTED`：AI 已创建 PR，等待人工 review + merge。

---

## 2. VcsClient SPI

### 2.1 接口定义

```java
public interface VcsClient {
    /** 创建分支 (基于默认分支) */
    String createBranch(String branchName);

    /** 提交文件变更到指定分支，返回 commit SHA */
    String commitFiles(String branchName, List<FileChange> changes, String commitMessage);

    /** 创建 Merge Request / Pull Request */
    MergeRequestInfo createPullRequest(String sourceBranch, String targetBranch,
                                        String title, String description);

    /** 查询 MR 合并状态 (OPEN / MERGED / CLOSED / CONFLICT) */
    String getMergeStatus(String prNumber);

    /** 返回类型标识: gitlab / bitbucket */
    String type();
}
```

### 2.2 值对象

```java
public class FileChange {
    private String filePath;     // 相对于 project-root
    private String content;      // 文件完整内容
    private String action;       // CREATE / UPDATE / DELETE
}

public class MergeRequestInfo {
    private String prUrl;
    private String prNumber;
    private String commitSha;
}

public class FixResult {
    private String commitId;
    private String prUrl;
    private String prNumber;
    private List<String> changedFiles;
    private boolean success;
    private String errorMessage;  // success=false 时填充
}
```

### 2.3 GitLabVcsClient

使用 GitLab REST API v4，认证 `PRIVATE-TOKEN` header。

| SPI 方法 | GitLab API 端点 |
|----------|----------------|
| createBranch | `POST /api/v4/projects/{id}/repository/branches` body: `{branch, ref}` |
| commitFiles | `POST /api/v4/projects/{id}/repository/commits` body: `{branch, commit_message, actions[]}` |
| createPullRequest | `POST /api/v4/projects/{id}/merge_requests` body: `{source_branch, target_branch, title, description}` |
| getMergeStatus | `GET /api/v4/projects/{id}/merge_requests/{iid}` 解析 `state` |
| type | 返回 `"gitlab"` |

GitLab 支持批量 commit（一次 API 调用提交所有文件）。

### 2.4 BitbucketVcsClient

使用 Bitbucket Server / Data Center REST API 1.0，认证 `Authorization: Bearer {token}`。

| SPI 方法 | Bitbucket API 端点 |
|----------|-------------------|
| createBranch | `POST /rest/branch-utils/1.0/projects/{key}/repos/{slug}/branches` body: `{name, startPoint}` |
| commitFiles | `PUT /rest/api/1.0/projects/{key}/repos/{slug}/browse/{path}?branch=...` (逐文件) |
| createPullRequest | `POST /rest/api/1.0/projects/{key}/repos/{slug}/pull-requests` body: `{title, description, fromRef, toRef}` |
| getMergeStatus | `GET /rest/api/1.0/projects/{key}/repos/{slug}/pull-requests/{id}` 解析 `state` |
| type | 返回 `"bitbucket"` |

Bitbucket 无批量 commit API，`commitFiles` 循环 PUT 每个文件，最后一次 PUT 产生的 commit SHA 即为最终 commit。

### 2.5 配置

```yaml
snap-agent:
  vcs:
    enabled: true
    type: gitlab              # gitlab | bitbucket
    default-branch: main
    gitlab:
      base-url: https://gitlab.example.com
      token: ${GITLAB_TOKEN}
      project-id: 123
    bitbucket:
      base-url: https://bitbucket.example.com
      token: ${BITBUCKET_TOKEN}
      project-key: PROJ
      repo-slug: my-repo
```

---

## 3. FixExecutionService

### 3.1 编排流程

```java
public class FixExecutionService {
    public FixResult autoFix(String issueId) {
        // 1. 加载 Issue，状态守卫 (FIX_IN_PROGRESS)
        IssueClosure issue = issueStore.load(issueId);

        // 2. 创建 FixContext，绑定到当前线程
        FixContext ctx = new FixContext(projectRoot);
        fixContextHolder.set(ctx);

        try {
            // 3. 运行 Agent (专用 fix prompt + code_read/file_write/file_edit)
            AgentTask fixTask = createFixTask(issue);
            agentService.execute(fixTask, skill);

            // 4. 从 FixContext 收集变更
            List<FileChange> changes = ctx.getChanges();
            if (changes.isEmpty()) {
                return FixResult.failed("AI did not produce any code changes");
            }

            // 5. VcsClient 创建分支 + 提交 + 创建 PR
            String branchName = "fix/" + issue.getIssueId();
            vcsClient.createBranch(branchName);
            String commitSha = vcsClient.commitFiles(branchName, changes,
                    buildCommitMessage(issue));
            MergeRequestInfo mr = vcsClient.createPullRequest(
                    branchName, defaultBranch,
                    buildPrTitle(issue), buildPrDescription(issue, changes));

            return FixResult.success(commitSha, mr, changes);
        } finally {
            fixContextHolder.clear();
        }
    }
}
```

### 3.2 Agent Fix Prompt

```
You are a code fix expert. Based on the diagnostic findings, implement the fix.

## Original Problem
{userQuery}

## Root Cause
{rootCause}

## Selected Solution
{selectedSolution}

## Instructions
1. Use code_read to examine relevant source files
2. Use file_edit for targeted changes (provide oldString and newString)
3. Use file_write only for new files
4. Keep changes minimal — fix the root cause, don't refactor
5. After making changes, summarize what you changed and why
```

### 3.3 IssueClosureService 集成

```java
public IssueClosure autoFix(String issueId) {
    IssueClosure issue = issueStore.load(issueId);
    // 状态守卫: 必须 FIX_IN_PROGRESS

    FixResult result = fixExecutionService.autoFix(issueId);

    long now = System.currentTimeMillis();
    IssueClosure updated = issue.withFix(
            result.getCommitId(), result.getPrUrl(), result.getPrNumber(),
            IssueStatus.FIX_SUBMITTED, now);
    issueStore.save(updated);

    // 更新外部 tracker 状态
    if (issue.getExternalIssueId() != null) {
        issueTracker.updateStatus(issue.getExternalIssueId(), "in_progress");
    }
    // 推送修复备注
    if (updated.getExternalIssueId() != null) {
        issueTracker.addComment(updated.getExternalIssueId(),
                buildFixComment(updated, result));
    }
    return updated;
}
```

---

## 4. 文件编辑工具 + FixGuard

### 4.1 FixContext

```java
public class FixContext {
    private final String projectRoot;
    private final Map<String, FileChange> changes = new LinkedHashMap<>();

    public void addChange(String filePath, String content, String action);
    public String getFileContent(String filePath);  // 优先返回已修改版本
    public List<FileChange> getChanges();
}
```

### 4.2 FixContextHolder

```java
public class FixContextHolder {
    private final ThreadLocal<FixContext> holder = new ThreadLocal<>();
    public void set(FixContext ctx) { holder.set(ctx); }
    public FixContext get() { return holder.get(); }
    public void clear() { holder.remove(); }
}
```

使用 ThreadLocal 因 Agent 执行是同步的（`agentService.execute()` 阻塞当前线程）。

### 4.3 文件编辑工具

**file_write**: 创建/覆盖文件
- 输入: `{filePath, content}`
- 行为: FixGuard 校验 → 存入 FixContext
- 返回: `"File written: {filePath} ({content.length} chars)"`

**file_edit**: 精确字符串替换
- 输入: `{filePath, oldString, newString}`
- 行为: 读取当前内容（FixContext 优先，否则磁盘）→ 替换 → FixGuard 校验 → 存入 FixContext
- 返回: `"File edited: {filePath} ({summary})"`

Guard 校验失败时返回错误消息（不抛异常），AI 可调整策略。

### 4.4 FixGuard 校验维度

| # | 校验项 | 默认值 |
|---|--------|--------|
| 1 | 路径穿越 | 禁止 `../` 跳出 project-root |
| 2 | 包含路径白名单 | `src/main/java/**`, `src/main/resources/**`, `src/test/java/**` |
| 3 | 排除路径黑名单 | `**/SecurityConfig.java`, `**/application*.yml`, `**/*Config.java`, `**/DataSource*.java` |
| 4 | 扩展名白名单 | `.java`, `.xml`, `.yml`, `.properties`, `.sql`, `.md` |
| 5 | 最大文件数 | 20 |
| 6 | 单文件大小上限 | 500KB |
| 7 | 内容非空校验 | UPDATE 不允许清空文件 |

```yaml
snap-agent:
  fix:
    enabled: true
    max-turns: 20
    timeout-minutes: 10
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
      secret: ${VCS_WEBHOOK_SECRET}
```

webhook 配置属于 `snap-agent.fix` 段，因为 webhook 入口由 FixExecutionService 的 PR 合并触发逻辑消费。VcsClient 本身不需要知道 webhook。

---

## 5. 验证策略 + 验收标准

### 5.1 分层验证模型

| Layer | 时机 | 验证内容 | 执行方 |
|-------|------|---------|--------|
| 1 | Pre-merge (PR 创建时) | 代码变更存在、AI 自评覆盖根因 | SnapAgent |
| 2 | CI/CD (PR pipeline) | 编译通过、单元测试通过 | 宿主 CI |
| 3 | Post-deploy (merged + 部署后) | 逐项执行验收标准、原始症状消失 | SnapAgent |

Layer 2 不由 SnapAgent 执行，但 webhook 可携带 CI pipeline status。

### 5.2 验收标准结构化

```java
public class AcceptanceCriterion {
    private String id;            // "ac-1"
    private String description;   // "SKU SP-001 的调拨计划已生成"
    private String verification;  // "SELECT COUNT(*) FROM t_allocation_plan WHERE ..."
    private String expected;      // "> 0"
    private String tool;          // "mysql_query"
}

// SolutionSuggestion 扩展
public class SolutionSuggestion {
    private List<AcceptanceCriterion> acceptanceCriteria;  // NEW
}
```

### 5.3 验收标准生成

在 `proposeSolution` 阶段，AI 生成方案的同时生成验收标准。`solution-suggest` 技能 prompt 增强：
- 每个方案至少 1 条验收标准，通常 2-3 条
- 每条验收标准必须有可执行的 SQL 或命令
- expected 必须具体（`> 0`, `= true`, `contains xxx`）

### 5.4 验证流程

```java
public IssueClosure verify(String issueId) {
    IssueClosure issue = issueStore.load(issueId);

    // 1. 收集验收标准 (从 issue.getSolution().getAcceptanceCriteria() 提取)
    List<AcceptanceCriterion> criteria = extractAcceptanceCriteria(issue);

    if (criteria.isEmpty()) {
        // 兜底: 用 verify-fix 技能 (现有逻辑)
        return verifyViaSkill(issueId, issue);
    }

    // 2. 逐项执行验收
    List<VerificationDetail> results = new ArrayList<>();
    for (AcceptanceCriterion ac : criteria) {
        VerificationDetail vd = executeVerification(ac);
        results.add(vd);
    }

    // 3. 汇总判断
    boolean allPassed = results.stream().allMatch(VerificationDetail::isPassed);

    // 4. 保存结果
    IssueClosure updated = issue.withVerification(
        new VerificationResult(allPassed, summarize(results), ...), now);
    updated = updated.withStatus(
        allPassed ? IssueStatus.VERIFIED : IssueStatus.FAILED, now);
    issueStore.save(updated);

    // 5. 推送验收结果到外部 issue
    if (updated.getExternalIssueId() != null) {
        issueTracker.addComment(updated.getExternalIssueId(),
                buildVerificationComment(updated, results));
    }
    return updated;
}
```

### 5.5 预期值评估

支持表达式：`> 0`, `>= 1`, `= true`, `!= null`, `contains xxx`。

### 5.6 验收结果展示

```json
{
  "status": "VERIFIED",
  "fixCommitId": "a1b2c3d",
  "fixPrUrl": "https://gitlab.example.com/repo/-/merge_requests/42",
  "verificationResult": {
    "passed": true,
    "summary": "AC-1: 调拨计划已生成 → COUNT=3 (>0) ✓\nAC-2: 评分正常 → score=8.5 (>0) ✓",
    "details": [
      {"id": "ac-1", "description": "调拨计划已生成", "passed": true,
       "actual": "3", "expected": "> 0"},
      {"id": "ac-2", "description": "评分正常", "passed": true,
       "actual": "8.5", "expected": "> 0"}
    ]
  }
}
```

---

## 6. 外部 Issue 备注同步

### 6.1 IssueTracker SPI 扩展

```java
public interface IssueTracker {
    // existing
    String createIssue(String title, String description, String assignee);
    void updateStatus(String externalIssueId, String status);
    String getIssueUrl(String externalIssueId);
    String type();

    /** NEW: 在外部 issue 上添加备注/评论 */
    void addComment(String externalIssueId, String comment);
}
```

### 6.2 备注同步时机

| 生命周期节点 | 推送备注内容 |
|-------------|-------------|
| proposeSolution | 方案选项 + 验收标准 |
| createExternalIssue | 选中方案 + 根因摘要（已包含在 createIssue description） |
| autoFix | 变更文件列表 + commit SHA + PR URL + 验收标准 |
| verify | 逐项验收结果（通过/失败） |
| close | 知识沉淀摘要 + 关闭确认 |

### 6.3 备注格式示例

```
## 🔧 修复方案已提交

**Commit**: a1b2c3d
**PR**: https://gitlab.example.com/repo/-/merge_requests/42

### 变更文件
| 文件 | 操作 | 说明 |
|------|------|------|
| src/.../AllocationService.java | UPDATE | 修复调拨计划生成逻辑 |
| src/.../PlanGenerator.java | UPDATE | 补充缺失的仓库校验 |

### 验收标准
1. [x] AC-1: 调拨计划已生成 (COUNT=3, expected > 0)
2. [x] AC-2: 评分正常 (score=8.5, expected > 0)

---
_由 SnapAgent 自动生成_
```

### 6.4 各 Tracker 的 addComment 实现

| Tracker | API 端点 |
|---------|---------|
| Zentao | `POST /api.php/v1/bugs/{id}/comments` body: `{comment}` |
| GitHub | `POST /repos/{owner}/{repo}/issues/{number}/comments` body: `{body}` |
| Jira | `POST /rest/api/2/issue/{key}/comment` body: `{body}` |
| Noop | 无操作 |

### 6.5 容错

所有 `addComment` 调用包裹在 try/catch 中，失败不影响主流程：
- 外部 tracker 不可达 → warn 日志，继续
- token 过期 → warn 日志，继续
- 权限不足 → warn 日志，继续

---

## 7. PR 合并触发

### 7.1 Webhook 回调（主路径）

```
POST /snap-agent-internal/vcs/webhook
Header: X-Gitlab-Event / X-Bitbucket-Event
Body: { event_type: "merge", pull_request_iid: "...", state: "merged" }
```

处理流程：
1. 验证 webhook 签名（shared secret）
2. 解析 PR number
3. 查找 `fixPrNumber` 匹配的 IssueClosure
4. 如果 state = merged → 触发 `onPrMerged()`

### 7.2 onPrMerged()

```java
public IssueClosure onPrMerged(String prNumber) {
    IssueClosure issue = findByPrNumber(prNumber);
    if (issue == null || issue.getStatus() != IssueStatus.FIX_SUBMITTED) {
        return null;  // 幂等
    }
    IssueClosure verified = verify(issue.getIssueId());
    if (verified != null && verified.getVerificationResult() != null
            && verified.getVerificationResult().isPassed()) {
        return close(issue.getIssueId());
    }
    return verified;
}
```

### 7.3 手动触发（兜底）

已有端点 `POST /issues/{issueId}/verify` + `POST /issues/{issueId}/close`，前端在 FIX_SUBMITTED 状态下展示"验证并关闭"按钮。

---

## 8. 控制器端点

| 方法 | 路径 | 状态要求 | 说明 |
|------|------|---------|------|
| POST | `/runs/{taskId}/solution` | — | 已有：AI 生成方案 + 验收标准 |
| POST | `/runs/{taskId}/issue` | SOLUTION_PROPOSED | 已有：创建外部 Issue |
| POST | `/runs/{taskId}/auto-fix` | FIX_IN_PROGRESS | 新增：触发 AI 自动修复 |
| POST | `/issues/{issueId}/auto-fix` | FIX_IN_PROGRESS | 新增：按 Issue ID 触发修复 |
| POST | `/issues/{issueId}/verify` | FIX_SUBMITTED | 增强：执行验收标准检查 |
| POST | `/issues/{issueId}/close` | VERIFIED | 已有：关闭 + 沉淀 |
| POST | `/snap-agent-internal/vcs/webhook` | — | 新增：Git webhook 回调 |
| GET | `/issues` | — | 增强：返回 fixPrUrl, fixPrNumber, verification details |

---

## 9. 自动装配

```
@ConditionalOnProperty("snap-agent.vcs.enabled=true")
  ├─ GitLabVcsClient    (@ConditionalOnProperty vcs.type=gitlab)
  ├─ BitbucketVcsClient (@ConditionalOnProperty vcs.type=bitbucket)
  └─ FixContextHolder   (@ConditionalOnMissingBean)

@ConditionalOnProperty("snap-agent.fix.enabled=true")
  ├─ FixGuard            (从 props 构建 include/exclude patterns)
  ├─ FileWriteTool       (@Tool, 注入 fixContextHolder + fixGuard)
  ├─ FileEditTool        (@Tool, 注入 fixContextHolder + fixGuard)
  └─ FixExecutionService (注入 agentService + issueStore + vcsClient + fixContextHolder)
```

---

## 10. 文件清单

### core 模块

| 文件 | 类型 | 说明 |
|------|------|------|
| `VcsClient.java` | NEW SPI | Git 操作接口 |
| `FileChange.java` | NEW | 文件变更值对象 |
| `MergeRequestInfo.java` | NEW | PR 信息值对象 |
| `FixResult.java` | NEW | 修复结果值对象 |
| `AcceptanceCriterion.java` | NEW | 验收标准值对象 |
| `VerificationDetail.java` | NEW | 逐项验证结果 |
| `IssueStatus.java` | MODIFY | 新增 `FIX_SUBMITTED` |
| `IssueClosure.java` | MODIFY | 新增 fixPrUrl, fixPrNumber + withFix() |
| `SolutionSuggestion.java` | MODIFY | 新增 acceptanceCriteria 字段 |
| `IssueStore.java` | MODIFY | 新增 findByPrNumber() |
| `IssueTracker.java` | MODIFY | 新增 addComment() |

### starter 模块

| 文件 | 类型 | 说明 |
|------|------|------|
| `AbstractHttpVcsClient.java` | NEW | HTTP 基类 (复用 AbstractHttpIssueTracker 模式) |
| `GitLabVcsClient.java` | NEW | GitLab API v4 实现 |
| `BitbucketVcsClient.java` | NEW | Bitbucket Server API 1.0 实现 |
| `FixGuard.java` | NEW | 文件编辑守卫 |
| `FixContextHolder.java` | NEW | ThreadLocal FixContext 持有者 |
| `FixContext.java` | NEW | 变更追踪上下文 |
| `FileWriteTool.java` | NEW | @Tool file_write |
| `FileEditTool.java` | NEW | @Tool file_edit |
| `FixExecutionService.java` | NEW | 修复编排服务 |
| `IssueClosureService.java` | MODIFY | 新增 autoFix(), onPrMerged(), 增强 verify(), 所有方法增加 addComment |
| `NoopIssueTracker.java` | MODIFY | 新增 addComment no-op |
| `ZentaoIssueTracker.java` | MODIFY | 新增 addComment |
| `GitHubIssueTracker.java` | MODIFY | 新增 addComment |
| `JiraIssueTracker.java` | MODIFY | 新增 addComment |
| `SnapAgentProperties.java` | MODIFY | 新增 Vcs, Fix 配置段 |
| `SnapAgentAutoConfiguration.java` | MODIFY | 装配新 bean |
| `SnapAgentController.java` | MODIFY | 新增 auto-fix + webhook 端点 |
| `FileIssueStore.java` | MODIFY | 新增 findByPrNumber() |

---

## 11. 完整生命周期时序

```
用户发起诊断
  → POST /runs (skillId=allocation-plan-diagnose)
  → Agent 诊断完成, rootCause 写入 task report

  → POST /runs/{taskId}/solution
  → AI 生成方案 + 验收标准 → IssueClosure(SOLUTION_PROPOSED)
  → addComment: 方案 + 验收标准 → 外部 Issue

  → 用户选择方案 → POST /runs/{taskId}/issue
  → IssueTracker.createIssue() → IssueClosure(FIX_IN_PROGRESS)
  → createIssue description 已含方案信息

  → POST /runs/{taskId}/auto-fix
  → FixExecutionService.autoFix():
    → Agent 多轮推理 (code_read → file_edit → ...)
    → VcsClient.createBranch + commitFiles + createPullRequest
  → IssueClosure(FIX_SUBMITTED, fixCommitId, fixPrUrl)
  → addComment: 变更文件 + commit + PR + 验收标准 → 外部 Issue

  → PR merged → Webhook → /snap-agent-internal/vcs/webhook
  → IssueClosureService.onPrMerged():
    → verify(): 逐项执行验收标准 (mysql_query)
    → addComment: 验收结果 → 外部 Issue
    → 全部通过 → IssueClosure(VERIFIED)
    → close(): 沉淀 + 更新外部 tracker 状态 → IssueClosure(CLOSED)
    → addComment: 关闭摘要 → 外部 Issue
```
