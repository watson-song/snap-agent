---
name: snap-agent-issue-tracker
description: Issue 闭环 + Auto-fix — IssueTracker SPI、IssueClosureService、FixExecutionService、VcsClient
version: 2.0.0
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
    └── KnowledgeSedimentation → 知识沉淀
```

## 2. 核心 SPI (core/issue/)

| 接口 | 职责 |
|------|------|
| `IssueTracker` | createIssue / updateStatus / addComment |
| `IssueStore` | save / load / findByTaskId / list / delete |
| `SolutionSuggester` | suggest → SolutionSuggestion |
| `VerificationRunner` | verify → VerificationResult |
| `IssueClosureHandler` | 闭环处理 SPI |

| 数据模型 | 说明 |
|----------|------|
| `IssueClosure` | 不可变闭环数据（issueId, taskId, rootCause, solution, status...）|
| `IssueStatus` | 7 态: DIAGNOSED → SOLUTION_PROPOSED → ISSUE_CREATED → FIX_IN_PROGRESS → VERIFIED → CLOSED / FAILED |
| `SolutionSuggestion` | List<SolutionOption> + recommendedOptionId |
| `VerificationResult` | passed + summary + before/afterStatus |

## 3. IssueTracker 实现

| 类 | 模块 | 说明 |
|----|------|------|
| `NoopIssueTracker` | boot2x | 默认空实现 |
| `ZentaoIssueTracker` | boot2x | 禅道（含 18.x 兼容）|
| `GitHubIssueTracker` | boot2x | GitHub Issues |
| `JiraIssueTracker` | boot2x | Jira |
| `AbstractHttpIssueTracker` | boot2x | HTTP 基类，统一 header 构建 |

## 4. Auto-fix 工作流 (core/vcs/ + boot2x/fix/)

```
PR Merged webhook → FixExecutionService
    ├── FixContext / FixContextHolder → 修复上下文
    ├── FixGuard → 安全检查
    ├── FileWriteTool / FileEditTool → 文件修改
    └── VcsClient → createBranch / commit / push / createMergeRequest
```

| 接口/类 | 说明 |
|---------|------|
| `VcsClient` (core/vcs) | createBranch / createMergeRequest / addComment |
| `GitLabVcsClient` | GitLab 实现 |
| `BitbucketVcsClient` | Bitbucket 实现 |
| `AbstractHttpVcsClient` | HTTP 基类 |
| `FixExecutionService` | 编排服务 |
| `FixContext` / `FixContextHolder` | 修复上下文 |
| `FixGuard` | 修复安全守卫 |
| `FileWriteTool` / `FileEditTool` | 文件修改工具 |

## 5. 配置

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: noop      # noop | zentao | github | jira
    system-user-id: system
  vcs:
    type: gitlab
    base-url: https://gitlab.example.com
    token: ${GITLAB_TOKEN}
```
