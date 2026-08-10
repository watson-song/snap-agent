---
name: snap-agent-rag-system
description: RAG 检索增强生成 — RetrievalAugmentationAdvisor、QueryTransformer、DocumentRetriever、VectorStore
version: 2.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent RAG 检索增强生成

## 1. 架构

```
RetrievalAugmentationAdvisor (Order=200)
  beforeNode("agent"):
    QueryTransformer → DocumentRetriever → QueryAugmenter → state["rag.context"]
```

## 2. 核心 SPI

| 接口 | 模块 | 职责 |
|------|------|------|
| `QueryTransformer` | core/rag | 优化查询提升检索精度 |
| `DocumentRetriever` | core/rag | 从 VectorStore 检索文档 |
| `QueryAugmenter` | core/rag | 将检索文档注入查询上下文 |
| `DocumentReader` | core/rag | 读取文件返回 Document 列表 |
| `Chunker` | core/rag | 文档分块 |

| 实现 | 模块 | 说明 |
|------|------|------|
| `IdentityQueryTransformer` | boot2x/knowledge | 透传（不转换）|
| `DefaultQueryAugmenter` | core/rag | 默认上下文注入 |
| `MarkdownDocumentReader` | boot2x/knowledge | 读取 .md 文件 |
| `HeadingChunker` | boot2x/knowledge | 按 `##` 标题分块 |
| `VectorStoreDocumentRetriever` | boot2x/knowledge | 基于 VectorStore 检索 |

## 3. VectorStore

```java
// core/vectorstore/VectorStore.java
public interface VectorStore {
    void add(Document document, float[] vector);
    List<Document> similaritySearch(SearchRequest request);
    void delete(String id);
}
```

| 类 | 说明 |
|----|------|
| `Document` | id + content + metadata |
| `SearchRequest` | queryVector + topK + minScore + metadataFilter |
| `InMemoryVectorStore` | 内存实现，余弦相似度 |

## 4. EmbeddingModel

```java
// core/embedding/EmbeddingModel.java
public interface EmbeddingModel {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}
```

## 5. 知识 ETL 管道

```java
// boot2x/knowledge/KnowledgeETLPipeline.java
// 加载 Markdown → Chunk → Embed → 存入 VectorStore
```

| 组件 | 说明 |
|------|------|
| `KnowledgeSedimentationService` | 知识沉淀服务 |
| `AcceptAllSedimentationReviewer` | 沉淀审查（全部接受）|

## 6. 异常隔离

RAG 阶段失败不阻塞 Agent 执行 — RetrievalAugmentationAdvisor 内部 catch 异常后注入空上下文。

## 7. 配置

```yaml
snap-agent:
  vectorstore:
    enabled: true
  knowledge:
    enabled: true
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
```
