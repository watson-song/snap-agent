---
name: snap-agent-rag-system
description: RAG 检索增强生成详解 — RetrievalAugmentationAdvisor、DocumentReader、Chunker、VectorStore
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent RAG 检索增强生成系统

## 1. 架构概述

RAG (Retrieval-Augmented Generation) 系统在 Agent 调用 LLM 前注入相关文档上下文，提升回答准确性。

```
┌─────────────────────────────────────────────────────────┐
│              RetrievalAugmentationAdvisor (Order=200)    │
│                                                         │
│  beforeNode("agent"):                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │  Query       │→ │  Document    │→ │  Query       │ │
│  │  Transformer │  │  Retriever   │  │  Augmenter   │ │
│  └──────────────┘  ──────────────┘  └──────────────┘ │
│         │                   │                   │       │
│         ▼                   ▼                   ▼       │
│  优化查询             检索文档           注入上下文       │
│                                                         │
│  state["rag.context"] = augmented context               │
─────────────────────────────────────────────────────────┘
```

## 2. 核心 SPI

### 2.1 QueryTransformer

```java
public interface QueryTransformer {
    /** 优化用户查询，提升检索精度 */
    String transform(String rawQuery);
}
```

### 2.2 DocumentRetriever

```java
@FunctionalInterface
public interface DocumentRetriever {
    /** 从 VectorStore 检索相关文档 */
    List<Document> retrieve(String query, int topK);
}
```

### 2.3 QueryAugmenter

```java
public interface QueryAugmenter {
    /** 将检索到的文档注入到查询上下文中 */
    String augment(String query, List<Document> documents);
}
```

### 2.4 DocumentReader

```java
public interface DocumentReader {
    /** 读取文件并返回原始文档 */
    List<Document> read(Path file);
}
```

**已知实现**:
- `MarkdownDocumentReader` — 读取 .md 文件

### 2.5 Chunker

```java
public interface Chunker {
    /** 将文档分割为小块 */
    List<Document> chunk(Document document);
}
```

**已知实现**:
- `HeadingChunker` — 按 `## ` 标题分割

## 3. 执行流程

```
1. 用户提问: "SnapAgent 如何组装 memory？"
                    ↓
2. QueryTransformer.transform()
   → "SnapAgent memory assembly architecture ChatMemory"
                    ↓
3. DocumentRetriever.retrieve(query, topK=4)
   → 检索到 4 篇相关文档
                    ↓
4. QueryAugmenter.augment(query, documents)
   → 注入文档内容到上下文
                    ↓
5. state["rag.context"] = "..."
                    ↓
6. AgentNode 使用 rag.context 构建 LLM 请求
```

## 4. 自动配置

```java
@Bean
public RetrievalAugmentationAdvisor ragAdvisor(
        QueryTransformer queryTransformer,
        DocumentRetriever documentRetriever,
        QueryAugmenter queryAugmenter) {
    return new RetrievalAugmentationAdvisor(
        queryTransformer, documentRetriever, queryAugmenter, 4);
}
```

## 5. 异常隔离

RAG 阶段失败不阻塞 Agent 执行：

```java
try {
    // RAG 流程
    state = state.with("rag.context", augmentedContext);
} catch (Exception e) {
    log.warn("RAG failed: {}", e.getMessage());
    state = state.with("rag.context", "");  // 空上下文继续
}
```

## 6. 配置属性

```yaml
snap-agent:
  rag:
    enabled: true
    top-k: 4
    min-score: 0.7
    query-transformer: default
    document-retriever: vector-store
    query-augmenter: default
```
