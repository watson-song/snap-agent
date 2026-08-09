---
name: snap-agent-vectorstore
description: VectorStore 向量存储详解 — Document、向量相似度搜索、InMemoryVectorStore
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent VectorStore 向量存储

## 1. VectorStore SPI

```java
public interface VectorStore {
    void add(Document document, float[] vector);
    List<Document> similaritySearch(SearchRequest request);
    void delete(String id);
}
```

## 2. Document

```java
public class Document {
    String id;
    String content;
    Map<String, String> metadata;  // source, chunkIndex, etc.
}
```

## 3. SearchRequest

```java
public class SearchRequest {
    float[] queryVector;
    int topK;
    double minScore;
    Map<String, String> metadataFilter;
}
```

## 4. InMemoryVectorStore（默认）

- 内存存储，适合开发/测试
- 余弦相似度计算
- 支持元数据过滤

## 5. 可扩展实现

- Redis VectorStore（生产环境）
- Milvus VectorStore（大规模数据）
- pgvector（PostgreSQL 扩展）

## 6. 配置

```yaml
snap-agent:
  vectorstore:
    type: in-memory      # in-memory | redis | milvus
    dimension: 1536
    default-top-k: 4
    default-min-score: 0.7
```
