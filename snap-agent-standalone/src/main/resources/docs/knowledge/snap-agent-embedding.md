---
name: snap-agent-embedding
description: Embedding 向量嵌入详解 — EmbeddingModel SPI、向量存储、语义搜索
version: 1.0.0
modules:
  - snap-agent-core
author: SnapAgent
---

# SnapAgent Embedding 向量嵌入系统

## 1. 架构概述

Embedding 系统将文本转换为向量表示，用于语义搜索和相似度匹配。

```
文本输入
   ↓
EmbeddingModel.embed(text)
   ↓
float[] 向量 (如 1536 维)
   ↓
VectorStore.add(document, vector)
   ↓
语义搜索: similaritySearch(query, topK)
```

## 2. EmbeddingModel SPI

```java
public interface EmbeddingModel {
    /** 嵌入单个文本 */
    float[] embed(String text);

    /** 批量嵌入 */
    List<float[]> embedBatch(List<String> texts);
}
```

## 3. 使用场景

- **RAG 检索**: 将文档和查询转换为向量，计算相似度
- **去重检测**: 比较文档向量，识别重复内容
- **聚类分析**: 将相似文档分组

## 4. 自动配置

```java
@Bean
public EmbeddingModel embeddingModel(SnapAgentProperties props) {
    String provider = props.getEmbedding().getProvider();
    switch (provider) {
        case "openai":
            return new OpenAiEmbeddingModel(props.getEmbedding().getApiKey());
        case "ollama":
            return new OllamaEmbeddingModel(props.getEmbedding().getBaseUrl());
        default:
            throw new IllegalArgumentException("Unknown embedding provider: " + provider);
    }
}
```

## 5. 配置属性

```yaml
snap-agent:
  embedding:
    provider: openai        # openai | ollama
    api-key: sk-...
    base-url: http://localhost:11434
    model: text-embedding-3-small
    dimensions: 1536
```
