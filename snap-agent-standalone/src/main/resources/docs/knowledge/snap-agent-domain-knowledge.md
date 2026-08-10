---
name: snap-agent-domain-knowledge
description: 领域知识管理 — DomainKnowledge、DomainKnowledgeIndex、DomainKnowledgeLoader、DomainKnowledgeTools
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 领域知识管理

## 1. 架构

```
DomainKnowledgeLoader → 加载 Markdown（YAML frontmatter）→ DomainKnowledgeIndex
                                                              ↓
                                          VectorStore（RAG 检索用）
                                          DomainKnowledgeTools（LLM 查询工具）
```

## 2. 核心 SPI (core/domain/)

| 类 | 职责 |
|----|------|
| `DomainKnowledge` | 领域知识数据（name, tables, services, body）|
| `DomainKnowledgeIndex` | 索引 + 反向查找（按表名/服务名查知识）|

## 3. 实现 (boot2x/domain/)

| 类 | 说明 |
|----|------|
| `DomainKnowledgeLoader` | 从 Markdown 文件加载，解析 YAML frontmatter |
| `InMemoryDomainKnowledgeIndex` | 内存索引实现 |
| `DomainKnowledgeTools` | @Tool 注解，提供 LLM 查询接口 |

## 4. 配置

```yaml
snap-agent:
  domain-knowledge:
    enabled: true
    dir: /tmp/snap-agent-skills/domain-knowledge
```
