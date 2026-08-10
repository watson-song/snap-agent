---
name: snap-agent-issue-tracker
description: Issue 闭环 + Auto-fix — IssueStore、IssueTracker、SolutionSuggester、VerificationRunner、VcsClient、FixExecutionService
version: 3.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Issue 闭环 + Auto-fix

## 1. Issue 闭环架构

```
Agent 诊断完成 → IssueClosureNode → IssueClosureHandler
    ├── SolutionSuggester → SolutionSuggestion
    ├── IssueTracker → 创建外部 Issue（禅道/GitHub/Jira）
    ├── VerificationRunner → 验证修复
    └── SedimentationReviewer → 知识沉淀
```

## 2. 核心 SPI (core/issue/)

| 接口 | 方法数 | 说明 |
|------|--------|------|
| `IssueStore` | 7 | save / load / findByTaskId / findByPrNumber / list / listByStatus / delete |
| `IssueTracker` | 5 | createIssue / updateStatus / getIssueUrl / type / addComment(default) |
| `SolutionSuggester` | 1 | suggest → SolutionSuggestion |
| `VerificationRunner` | 1 | verify → VerificationResult |
| `IssueClosureHandler` | 1 | handle → 闭环处理 |
| `HttpExecutor` | 1 | HTTP 执行 SPI |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStore.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueTracker.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/SolutionSuggester.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/VerificationRunner.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueClosureHandler.java -->

### 2.1 IssueStore（7 方法）

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStore.java -->
```java
public interface IssueStore {
    void save(IssueClosure issue);
    IssueClosure load(String issueId);
    IssueClosure findByTaskId(String taskId);
    IssueClosure findByPrNumber(String prNumber);
    List<IssueClosure> list();
    List<IssueClosure> listByStatus(IssueStatus status);
    void delete(String issueId);
}
```

## 3. 数据模型

| 类 | 说明 |
|----|------|
| `IssueClosure` | 不可变闭环数据（issueId, taskId, rootCause, solution, status...）|
| `IssueStatus` | 7 态: DIAGNOSED → SOLUTION_PROPOSED → ISSUE_CREATED → FIX_IN_PROGRESS → VERIFIED → CLOSED / FAILED |
| `SolutionSuggestion` | List\<SolutionOption\> + recommendedOptionId |
| `SolutionOption` | id / title / description / effort / temporary |
| `VerificationResult` | passed + summary + before/afterStatus |
| `VerificationDetail` | 验证详情 |
| `AcceptanceCriterion` | 验收标准 |

<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueClosure.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/IssueStatus.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/SolutionSuggestion.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/SolutionOption.java -->
<!-- source: snap-agent-core/src/main/java/cn/watsontech/snapagent/core/issue/VerificationResult.java -->

## 4. IssueTracker 实现

| 类 | 模块 | 说明 |
|----|------|------|
| `NoopIssueTracker` | boot2x | 默认空实现 |
| `ZentaoIssueTracker` | boot2x | 禅道（含 18.x 兼容）|
| `GitHubIssueTracker` | boot2x | GitHub Issues |
| `JiraIssueTracker` | boot2x | Jira |
| `AbstractHttpIssueTracker` | boot2x | HTTP 基类，统一 header 构建 |

## 5. Auto-fix 工作流 (core/vcs/ + boot2x/fix/)

```
PR Merged webhook → FixExecutionService
    ├── FixContext / FixContextHolder → 修复上下文
    ├── FixGuard → 安全检查
    ├── FileWriteTool / FileEditTool → 文件修改
    └── VcsClient → createBranch / commit / push / createMergeRequest
```

| 接口/类 | 说明 |
|---------|------|
| `VcsClient` (core/vcs) | createBranch / commitFiles / createPullRequest / getMergeStatus / type |
| `GitLabVcsClient` | GitLab 实现 |
| `BitbucketVcsClient` | Bitbucket 实现 |
| `AbstractHttpVcsClient` | HTTP 基类 |
| `FixExecutionService` | 编排服务 |
| `FixGuard` | 修复安全守卫 |

## 6. 配置

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: noop      # noop | zentao | github | jira
    system-user-id: system
    storage-dir: ${upload-skills-dir}/issues
  vcs:
    type: gitlab
    base-url: https://gitlab.example.com
    token: ${GITLAB_TOKEN}
```
