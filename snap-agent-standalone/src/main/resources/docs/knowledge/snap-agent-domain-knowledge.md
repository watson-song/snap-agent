---
name: snap-agent-domain-knowledge
description: Domain Knowledge 领域知识管理 — DomainKnowledge、DomainKnowledgeIndex、向量检索
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent Domain Knowledge 领域知识管理

## 1. 架构

```
DomainKnowledgeLoader
  ── 加载 Markdown 文件（YAML frontmatter）
  ── 解析业务概念（表名、服务类）
  ── 存入 DomainKnowledgeIndex
  ── 同时存入 VectorStore（用于 RAG 检索）
```

## 2. DomainKnowledge SPI

```java
public class DomainKnowledge {
    private String name;
    private List<String> tables;
    private List<String> services;
    private String body;
}
```

## 3. 反向查找

通过表名/服务名反查对应的领域知识文档，为 LLM 提供业务上下文。

## 4. 配置

```yaml
snap-agent:
  domain-knowledge:
    enabled: true
    dir: /tmp/snap-agent-skills/domain-knowledge
```
