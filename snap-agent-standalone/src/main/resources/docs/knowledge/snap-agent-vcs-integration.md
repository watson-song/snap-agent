---
name: snap-agent-vcs-integration
description: VCS 版本控制集成 — GitLab、Bitbucket、HttpExecutor、Bridge 代理
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent VCS 版本控制集成

## 1. 架构

```
IssueClosureService
  └── VcsClient (SPI)
        ├── GitLabVcsClient
        └── BitbucketVcsClient
              └── AbstractHttpVcsClient
                    └── HttpExecutor → BridgeHttpExecutor (可选代理)
```

## 2. VcsClient SPI

```java
public interface VcsClient {
    void createBranch(String branch, String source);
    void createMergeRequest(String title, String source, String target);
    void addComment(String mrId, String comment);
}
```

## 3. Bridge 代理

当容器无法直连内网 GitLab 时，通过浏览器 Bridge 代理 HTTP 请求。

## 4. 配置

```yaml
snap-agent:
  vcs:
    type: gitlab          # gitlab | bitbucket
    base-url: https://gitlab.example.com
    token: ${GITLAB_TOKEN}
    project-id: 42
  bridge:
    enabled: true
    allowed-host-patterns:
      - "*.example.com"
```
