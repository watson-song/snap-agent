---
name: 领域知识系统
description: SnapAgent 领域知识系统 — Markdown 解析、frontmatter 提取、DomainKnowledgeLoader、VectorStore 集成
version: 1.0.0
tables: []
services: [DomainKnowledge, DomainKnowledgeLoader, DomainKnowledgeIndex, VectorStore]
entry_points: [DomainKnowledgeLoader.loadFromDirectory(), DomainKnowledgeLoader.parseFile()]
related_concepts: [技能系统，RAG 检索，向量存储]
tags: [domain-knowledge, markdown, frontmatter, rag, vectorstore]
version: 1.0.0
module: snap-agent-core
author: SnapAgent
---

# 领域知识系统

## 1. 模块概述

领域知识系统从 Markdown 文件中提取业务概念，建立 概念→表→类→入口 映射，支持 RAG 检索。

## 2. 核心类

### 2.1 DomainKnowledge (领域知识模型)

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 概念名称（中文） |
| description | String | 一句话描述 |
| content | String | Markdown 正文 |
| tables | List\<String\> | 涉及的表名列表 |
| services | List\<String\> | 涉及的服务类列表 |
| entryPoints | List\<String\> | 入口方法或 API 端点 |
| relatedConcepts | List\<String\> | 关联概念 |
| tags | List\<String\> | 标签列表 |
| version | String | 版本号 |
| module | String | 所属模块 |
| author | String | 作者 |

### 2.2 DomainKnowledgeLoader (加载器)

| 方法 | 说明 |
|------|------|
| loadFromDirectory(Path dir) | 从目录加载所有 *.md 文件 |
| parseFile(Path file) | 解析单个 Markdown 文件 |
| parseContent(String content) | 解析 Markdown 内容 |
| parseFrontmatter(String frontmatter) | 解析 YAML frontmatter |

### 2.3 DomainKnowledgeIndex (索引)

| 方法 | 说明 |
|------|------|
| put(DomainKnowledge dk) | 添加领域知识 |
| findByTable(String tableName) | 按表名查询 |
| findByService(String serviceName) | 按服务类查询 |
| findByEntryPoint(String entryPoint) | 按入口查询 |
| findByTag(String tag) | 按标签查询 |
| search(String keyword) | 语义检索 |

## 3. Markdown 格式

### 3.1 标准格式

```markdown
---
name: 业务概念名称（中文）
description: 一句话描述（50 字以内）
tables: [table1, table2]
services: [ServiceA, ServiceB]
entry_points: [POST /api/xxx, XxxTask.run()]
related_concepts: [概念 A, 概念 B]
tags: [tag1, tag2]
version: 1.0.0
module: 所属模块名
author: SnapAgent
---

# 业务概念名称

## 业务描述
...

## 核心服务
...

## 数据表
...

## 数据流向
...
```

### 3.2 Frontmatter 字段用途

| 字段 | RAG 检索用途 |
|------|------------|
| tables | 按表名检索（如查询涉及 `drp_allocation_plan` 表的知识） |
| services | 按类名检索（如查询 `AllocationPlanService` 相关的知识） |
| entry_points | 按入口方法检索（如查询 `POST /api/allocation` 相关的知识） |
| tags | 按标签检索（如查询 `replenishment` 标签的知识） |

## 4. 加载流程

```
1. DomainKnowledgeLoader.loadFromDirectory(dir)
2. 扫描 dir 下所有 *.md 文件
3. 对每个文件：
   a. 读取文件内容
   b. 解析 frontmatter（--- 之间的 YAML）
   c. 提取 name, description, tables, services, entry_points 等字段
   d. 创建 DomainKnowledge 对象
   e. 存入 DomainKnowledgeIndex
   f. 同时存入 VectorStore 用于语义检索
```

## 5. 查询方式

### 5.1 按表名查询
```java
List<DomainKnowledge> results = index.findByTable("drp_allocation_plan");
```

### 5.2 按服务类查询
```java
List<DomainKnowledge> results = index.findByService("AllocationPlanService");
```

### 5.3 按入口查询
```java
List<DomainKnowledge> results = index.findByEntryPoint("POST /api/allocation");
```

### 5.4 按标签查询
```java
List<DomainKnowledge> results = index.findByTag("replenishment");
```

### 5.5 语义检索
```java
List<DomainKnowledge> results = index.search("如何生成补货计划？");
```

## 6. 与 VectorStore 集成

```java
// 加载时同时存入 VectorStore
public void loadFromDirectory(Path dir) {
    for (Path file : files) {
        DomainKnowledge dk = parseFile(file);
        index.put(dk);
        
        // 存入 VectorStore
        Document doc = new Document(
            dk.toSummary(),  // 摘要文本
            Map.of("title", dk.getName(), "tags", String.join(",", dk.getTags()))
        );
        vectorStore.add(List.of(doc));
    }
}
```

## 7. 配置

```yaml
snap-agent:
  domain-knowledge:
    enabled: true
    directory: ./knowledge
    vectorstore:
      enabled: true
      type: in-memory  # in-memory | milvus | qdrant
```

---

**文档版本**: 1.0.0
**最后更新**: 2026-08-12
