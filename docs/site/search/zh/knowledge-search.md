# SnapAgent 知识搜索算法设计

> 版本：v1.3 | 更新日期：2026-08-04

## 1. 架构概览

SnapAgent 知识库采用三层 SPI 架构，实现知识源的加载、评分和注入的完全解耦：

```
┌──────────────────────────────────────────────────────────┐
│                    VectorStore                           │
│   (管理所有文档, 委托 VectorStoreDocumentRetriever 检索)  │
│   - search(query, topK, minScore) → List<Document>       │
│   - searchWithScores(query, topK, minScore) → List<SearchResult>│
│   - reload() / size()                                   │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   DocumentReader       │   │  DocumentRetriever  │
   │   (文件读取 SPI,        │   │  (检索算法 SPI,     │
   │    旧名 KnowledgeSource)│   │   旧名 KnowledgeSearcher)│
   │   - read(Path) → Docs  │   │  - retrieve(query,  │
   │   - supportedExtension()│   │      topK) → Docs  │
   └───────────┬───────────┘   └──────────────────────┘
               │
   ┌───────────▼───────────┐
   │   Chunker              │
   │   (文档分块 SPI)        │
   │   - chunk(Document)    │
   │     → List<Document>   │
   │   - strategy()         │
   └───────────────────────┘
```

> **命名说明**：v2.x 中 `KnowledgeSource` 拆分为 `DocumentReader` + `KnowledgeSourceConfig`，`KnowledgeSearcher` 改为 `DocumentRetriever`，`KnowledgeFragment` 改为 `Document`。详见 `docs/glossary.md`。

### 核心接口

**`DocumentRetriever`** (core SPI, 旧名 `KnowledgeSearcher`):
```java
public interface DocumentRetriever {
    List<Document> retrieve(String query, int topK);
    // 替代 KnowledgeSearcher.score(query, fragment)，现返回排序后的 Document 列表
}
```

**`DocumentReader`** (core SPI, 旧名 `KnowledgeSource`):
```java
public interface DocumentReader {
    List<Document> read(Path file);     // 从文件读取原始文档
    String supportedExtension();         // 支持的文件扩展名（如 "md"）
}
```

**`Chunker`** (core SPI, ETL 分块):
```java
public interface Chunker {
    List<Document> chunk(Document document);  // 文档分块
    String strategy();                         // 分块策略名（如 "heading"）
}
```

**`Document`** (core, 不可变值对象, 旧名 `KnowledgeFragment`):
```java
public final class Document {
    private final String title;
    private final String content;
    private final String source;     // 来源标识，如 "business-overview.md:section-2"
    private final Map<String, Object> metadata; // 防御拷贝
}
```

**`SearchResult`** (core, 不可变值对象):
```java
public final class SearchResult {
    private final Document fragment;
    private final double score;
}
```

### VectorStore 检索流程

```java
public List<SearchResult> searchWithScores(String query, int topK, double minScore) {
    // 1. 边界检查
    if (query == null || query.isEmpty() || allFragments.isEmpty()) {
        return new ArrayList<>();
    }
    // 2. 对每个片段评分 + 过滤
    List<ScoredFragment> scored = new ArrayList<>();
    for (Document f : allFragments) {
        double s = searcher.score(query, f);
        if (s >= minScore) {        // 低于阈值的被排除
            scored.add(new ScoredFragment(f, s));
        }
    }
    // 3. 按分数降序排序
    Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));
    // 4. 取 topK
    List<SearchResult> result = new ArrayList<>();
    for (int i = 0; i < Math.min(topK, scored.size()); i++) {
        result.add(new SearchResult(scored.get(i).fragment, scored.get(i).score));
    }
    return result;
}
```

---

## 2. 分词算法 (SimpleKeywordSearcher)

`SimpleKeywordSearcher` 是 `DocumentRetriever`（旧名 `KnowledgeSearcher`）的默认实现，采用**混合分词策略**处理中英文文本。

### 2.1 分词规则

**英文/Latin 文本：**
- 按空格和标点分割
- 转为小写
- 丢弃长度 < 2 的 token（过滤 "a", "of" 等停用词）

**中文/CJK 文本：**
- 2 字符滑动窗口 bigram（重叠，步进为 1）
- 例如："补货策略" → `["补货", "货策", "策略"]`
- 独立 CJK 字符（无相邻 CJK 字符）保留为单字符 token

**混合文本处理：**
- CJK 字符出现在 Latin 单词中间时，先 flush 当前 Latin 单词，再处理 CJK
- 例如："SnapAgent 是嵌入式" → `["snapagent", "是嵌", "嵌式"]`

### 2.2 分词代码

```java
List<String> tokenize(String text) {
    List<String> tokens = new ArrayList<>();
    StringBuilder currentWord = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (isCjk(c)) {
            // Flush pending Latin word
            if (currentWord.length() > 0) {
                addToken(tokens, currentWord.toString());
                currentWord = new StringBuilder();
            }
            // CJK bigram: combine with next char if also CJK
            if (i + 1 < text.length() && isCjk(text.charAt(i + 1))) {
                tokens.add(text.substring(i, i + 2));  // 直接添加，不过滤
            } else {
                tokens.add(String.valueOf(c));  // 独立 CJK 字符
            }
        } else if (Character.isLetterOrDigit(c)) {
            currentWord.append(Character.toLowerCase(c));
        } else {
            // Whitespace/punctuation — flush Latin word
            if (currentWord.length() > 0) {
                addToken(tokens, currentWord.toString());
                currentWord = new StringBuilder();
            }
        }
    }
    if (currentWord.length() > 0) {
        addToken(tokens, currentWord.toString());
    }
    return tokens;
}

private void addToken(List<String> tokens, String token) {
    // Latin tokens: drop < 2 chars (stopword filter)
    // CJK bigrams: always >= 2 chars, standalone CJK: 1 char
    if (token.length() >= 2) {
        tokens.add(token);
    }
}
```

### 2.3 分词示例

| 输入 | 分词结果 | 说明 |
|------|---------|------|
| `snapagent` | `["snapagent"]` | 单个英文单词 → 1 token |
| `数据库` | `["数据", "据库", "库"]` | 3 CJK 字符 → 3 tokens（2 bigram + 1 standalone） |
| `补货策略` | `["补货", "货策", "策略"]` | 4 CJK 字符 → 3 overlapping bigrams |
| `系统` | `["系统", "统"]` | 2 CJK 字符 → 1 bigram + 1 standalone |
| `SnapAgent 数据库诊断` | `["snapagent", "数据", "据库", "库", "诊", "诊断", "断"]` | 混合文本 |

---

## 3. 打分公式

### 3.1 公式

```
score = (titleHits × 2 + contentHits) / (queryTokenCount × 2)
```

- `titleHits`：query 的 token 中命中标题的数量
- `contentHits`：query 的 token 中命中内容的数量
- `queryTokenCount`：query 去重后的 token 数量
- 标题命中权重 ×2（标题直接描述主题，相关性更高）
- 结果 clamp 到 `[0.0, 1.0]`

### 3.2 打分代码

```java
public double score(String query, Document fragment) {
    if (query == null || query.isEmpty() || fragment == null) {
        return 0.0;
    }
    List<String> queryTokens = tokenize(query);
    if (queryTokens.isEmpty()) {
        return 0.0;  // 至少需要 1 个 token
    }
    Set<String> querySet = new HashSet<>(queryTokens);
    Set<String> titleSet = new HashSet<>(tokenize(fragment.getTitle()));
    Set<String> contentSet = new HashSet<>(tokenize(fragment.getContent()));

    int titleHits = 0, contentHits = 0;
    for (String token : querySet) {
        if (titleSet.contains(token)) titleHits++;
        if (contentSet.contains(token)) contentHits++;
    }

    int queryTokenCount = querySet.size();
    double raw = (double) (titleHits * 2 + contentHits) / (queryTokenCount * 2.0);
    return Math.max(0.0, Math.min(1.0, raw));
}
```

### 3.3 打分示例

**示例 1：查询 "数据库"**

知识片段 "数据库诊断"（标题="数据库诊断"，内容含"数据库"）

| 步骤 | 计算 |
|------|------|
| query tokens | `{"数据", "据库", "库"}` → 3 个 token |
| 标题 tokens | `{"数据", "据库", "库诊", "诊断", "诊", "断"}` |
| 标题命中 | "数据"✓, "据库"✓, "库"✗(标题中是"库诊") → titleHits=2 |
| 内容命中 | "数据"✓, "据库"✓, "库"✓ → contentHits=3 |
| 计算 | `(2×2 + 3) / (3×2) = 7/6 = 1.17` → clamp → **1.0** |

知识片段 "系统概述"（内容含"数据库查询"）

| 步骤 | 计算 |
|------|------|
| 标题命中 | 无 → titleHits=0 |
| 内容命中 | "数据"✓, "据库"✓, "库"✗(内容中是"库查") → contentHits=2 |
| 计算 | `(0×2 + 2) / (3×2) = 2/6 = 0.33` → **0.33** |

**示例 2：查询 "snapagent"**

知识片段 "SnapAgent 业务知识示例"（标题含 SnapAgent）

| 步骤 | 计算 |
|------|------|
| query tokens | `{"snapagent"}` → 1 个 token |
| 标题命中 | "snapagent"✓（标题 tokenize 后为 "snapagent"）→ titleHits=1 |
| 内容命中 | 无 → contentHits=0 |
| 计算 | `(1×2 + 0) / (1×2) = 2/2 = 1.0` → **1.0** |

---

## 4. minScore 阈值机制

### 4.1 配置

```yaml
snap-agent:
  knowledge:
    enabled: true
    min-score: 0.1   # 默认 0.1，低于此分数的片段不返回
```

### 4.2 过滤机制

`VectorStore.searchWithScores()` 在评分后执行 `score >= minScore` 过滤：
- score=0.0 的片段（无任何匹配）被排除
- 只有分数达到阈值的片段才出现在结果中
- 结果按分数降序排列

### 4.3 历史 Bug 修复

**Bug 1：minScore 硬编码为 0.0**

`KnowledgeController.search()` 中硬编码了 `0.0` 而非使用配置值：

```java
// Bug: 硬编码 0.0
List<Document> fragments = vectorStore.search(q, searchTopK, 0.0);
// Fix: 使用配置的 minScore
List<SearchResult> results = vectorStore.searchWithScores(q, searchTopK, minScore);
```

这导致所有片段（包括 score=0 无匹配的）都被返回，任何查询都返回全部 5 个片段。

**Bug 2：2-token 最低限制**

`SimpleKeywordSearcher` 曾有 `if (queryTokens.size() < 2) return 0.0;` 限制，导致单词英文查询（如 "snapagent"，只有 1 个 token）直接返回 0 分，搜不到任何结果。

修复：改为 `if (queryTokens.isEmpty()) return 0.0;`，允许单 token 查询正常打分。

---

## 5. VectorStoreDocumentRetriever + Advisor 自动注入

### 5.1 注入机制

`VectorStoreDocumentRetriever` 通过 `VectorStore` 检索相关知识，经 `Advisor` SPI 在 LLM 开始思考前自动注入业务知识：

```
用户输入 "SKU-001 为什么没生成补货策略？"
    │
    ▼
VectorStoreDocumentRetriever.retrieve(query)
    │
    ├─ 从 task.inputs 提取用户查询文本
    ├─ 调用 vectorStore.search(query, maxFragments, minScore)
    ├─ 格式化匹配的知识片段为上下文段落
    └─ 返回注入到 system prompt 的知识文本
    │
    ▼
GraphExecutor 组装 system prompt:
    "你是诊断 Agent...
     ## 业务知识上下文
     补货策略依赖 replm_inv_param_sku_wh_input 表...
     常见原因: init_replenishment_param 任务未执行..."
```

### 5.2 配置

```yaml
snap-agent:
  knowledge:
    enabled: true
    max-fragments: 3     # 注入到 system prompt 的最大片段数
    min-score: 0.1       # 最低相关度分数
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
```

- `max-fragments`：注入上限（默认 3），控制 system prompt 的 token 消耗
- `min-score`：注入阈值，低于此分数的知识不会被注入

### 5.3 GraphExecutor 多 Advisor

`GraphExecutor` 支持 `List<Advisor>`（v0.7 改造），按 Spring `@Order` 排序：

1. `ProjectContextExtender`（v0.3）：注入项目结构摘要
2. `VectorStoreDocumentRetriever + Advisor`（v0.7）：注入业务知识片段

两者独立工作，各自检索和注入，最后拼接为完整的 system prompt 上下文。

---

## 6. REST API

### GET /knowledge/status

返回知识库状态：

```json
{
    "enabled": true,
    "fragmentCount": 5,
    "maxFragments": 3,
    "minScore": 0.1,
    "sources": [
        { "type": "markdown", "dir": "classpath:/docs/knowledge/" }
    ]
}
```

### GET /knowledge/search?q={query}

关键词搜索，返回匹配片段及相关度分数：

```json
{
    "query": "数据库",
    "totalFragments": 5,
    "matched": 2,
    "fragments": [
        {
            "title": "数据库诊断",
            "content": "数据库诊断基于独立只读数据源连接...",
            "source": "business-overview.md:section-2",
            "metadata": { "category": "SnapAgent 业务知识示例" },
            "score": 1.0
        },
        {
            "title": "系统概述",
            "content": "SnapAgent 是嵌入式 LLM 诊断 Agent...",
            "source": "business-overview.md:section-1",
            "metadata": { "category": "SnapAgent 业务知识示例" },
            "score": 0.33
        }
    ]
}
```

### GET /knowledge/fragments（v1.1 新增）

返回所有知识片段（无评分），用于前端"知识点"统计卡片的点击展开查看：

```json
{
    "total": 5,
    "fragments": [
        {
            "title": "系统概述",
            "content": "SnapAgent 是嵌入式 LLM 诊断 Agent...",
            "source": "business-overview.md:section-1",
            "metadata": { "category": "SnapAgent 业务知识示例" }
        },
        ...
    ]
}
```

与 `/knowledge/search` 的差异：

| 端点 | 用途 | 是否评分 | 入参 |
|------|------|---------|------|
| `GET /knowledge/search` | 按查询关键词检索 | 是（`score` 字段） | `q`（query） |
| `GET /knowledge/fragments` | 列出所有片段供浏览 | 否 | 无 |

底层实现：`VectorStore.listAll()` 返回 `Collections.unmodifiableList(allFragments)`，
保持片段加载顺序。

---

## 7. 知识源与 ETL 管道

### 7.1 ETL 管道 (KnowledgeETLPipeline)

`KnowledgeETLPipeline` 编排完整的知识导入流程：

```
DocumentReader.read(file) → Chunker.chunk(doc) → EmbeddingModel.embed(text) → VectorStore.add(docs)
```

- **DocumentReader**：读取文件内容为原始 `Document` 列表（含 metadata）
- **Chunker**：将原始文档分块为更小的语义单元
- **EmbeddingModel**：为每个分块生成向量嵌入（可选，无 EmbeddingModel 时跳过）
- **VectorStore**：持久化分块到向量存储
- **失败隔离**：单个分块写入失败只记 WARN 日志，不影响其他分块

### 7.2 内置实现

| SPI | 默认实现 | 说明 |
|-----|---------|------|
| `DocumentReader` | `MarkdownDocumentReader` | 读取 .md 文件，提取 H1 标题作为 `metadata.category` |
| `Chunker` | `HeadingChunker` | 按 `##` 标题分块，无标题的整文件作为一个分块 |

### 7.3 扩展知识源

实现 `DocumentReader` 接口 + `@Component` 注解即可被自动发现：

```java
@Component
public class PdfDocumentReader implements DocumentReader {
    @Override
    public List<Document> read(Path file) {
        // 从 PDF 文件提取文本
        String content = pdfExtractor.extract(file);
        return Collections.singletonList(new Document(content, metadata));
    }
    @Override
    public String supportedExtension() { return "pdf"; }
}
```

实现 `Chunker` 接口自定义分块策略：

```java
@Component
public class FixedSizeChunker implements Chunker {
    @Override
    public List<Document> chunk(Document document) {
        // 按固定字符数分块（如每 500 字符一个分块）
        return splitBySize(document, 500);
    }
    @Override
    public String strategy() { return "fixed-size"; }
}
```

---

## 8. 已知限制

| 限制 | 说明 | 计划 |
|------|------|------|
| 英文大小写敏感 | `SnapAgent` ≠ `snapagent`（Latin 分词转小写，但内容匹配是大小写敏感的 HashSet） | 未来修复 |
| 无语义搜索 | 纯关键词重叠，不理解同义词/上下文 | v0.7.2 向量嵌入 |
| 无向量嵌入 | 不支持 embedding 相似度检索 | v0.7.2 引入 |
| 中文分词粗糙 | 2-gram bigram 无法处理专业术语/实体名 | 可自定义 DocumentRetriever（旧名 KnowledgeSearcher） |
| 无相关性反馈 | 用户无法标记结果是否有用 | v0.7.1 计划 |

---

## 9. 扩展指南

### 自定义检索算法

实现 `DocumentRetriever` 接口（旧名 `KnowledgeSearcher`），替换默认的 `VectorStoreDocumentRetriever`：

```java
@Component
public class SemanticSearcher implements DocumentRetriever {
    @Override
    public List<Document> retrieve(String query, int topK) {
        // 使用向量嵌入计算余弦相似度
        double[] queryVec = embed(query);
        // ...
        return topKDocuments;
    }
}
```

注册为 Spring Bean 后，`VectorStore` 会自动使用它替代默认实现（`@ConditionalOnMissingBean`）。

### 自定义知识源

实现 `DocumentReader` 接口（见 7.3 节）读取任意格式文件，或实现 `Chunker` 接口自定义分块策略，支持从任意数据源加载知识：数据库、外部 API、Confluence/语雀等。

---

## 命名约定

> v2.x 重命名了多个知识子系统 SPI/类。完整映射见 `docs/glossary.md`。

| 旧名 (v0.7) | 当前名 (v2.x) | 说明 |
|--------------|---------------|------|
| KnowledgeBase | VectorStore | 2.x 重构重命名 |
| KnowledgeFragment | Document | 统一向量库模型 |
| KnowledgeSearcher | DocumentRetriever | RAG 管道集成 |
| KnowledgeInjector | RetrievalAugmentationAdvisor | Advisor 模式 |
| KnowledgeSource | DocumentReader | 拆分为文件读取 SPI（`read(Path)`, `supportedExtension()`）|
| MarkdownKnowledgeSource | MarkdownDocumentReader + HeadingChunker | ETL 管道拆分为 Reader + Chunker |
| SimpleKeywordSearcher | VectorStoreDocumentRetriever | 默认 DocumentRetriever |
| SystemPromptExtender | Advisor | 泛化 |
