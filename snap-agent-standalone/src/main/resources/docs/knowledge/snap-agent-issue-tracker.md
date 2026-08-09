---
name: snap-agent-issue-tracker
description: Issue 跟踪系统详解 — IssueClosure、VerificationRunner、IssueTracker SPI
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Issue 跟踪系统

## 1. 架构

```
Agent 诊断完成
  ── IssueClosureHandler
       ── IssueTracker (SPI)
            ├── ZentaoIssueTracker (禅道)
            ├── GitHubIssueTracker
            ├── JiraIssueTracker
            └── NoopIssueTracker (默认)
  ── VerificationRunner
       ── 验证修复是否生效
  ── SedimentationReviewer
       ── 审查历史沉淀问题
```

## 2. IssueTracker SPI

```java
public interface IssueTracker {
    String createIssue(IssueClosure closure);
    void updateStatus(String issueId, IssueStatus status);
    void addComment(String issueId, String comment);
}
```

## 3. VerificationRunner

自动验证修复效果：
1. 获取原始问题描述
2. 执行相同诊断流程
3. 对比结果，确认问题已修复

## 4. 配置

```yaml
snap-agent:
  issue-closure:
    enabled: true
    tracker-type: noop      # noop | zentao | github | jira
    system-user-id: system
```
