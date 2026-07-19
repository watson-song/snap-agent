# SnapAgent 知识搜索算法设计

> 版本：v1.0 | 更新日期：2026-07-17

## 1. 架构概览

SnapAgent 知识库采用三层 SPI 架构，实现知识源的加载、评分和注入的完全解耦：

```
┌──────────────────────────────────────────────────────────┐
│                    KnowledgeBase                         │
│   (管理所有 KnowledgeSource, 委托 KnowledgeSearcher 检索) │
│   - search(query, topK, minScore) → List<KnowledgeFragment>     │
│   - searchWithScores(query, topK, minScore) → List<SearchResult>│
│   - reload() / size()                                   │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   KnowledgeSource      │   │  KnowledgeSearcher   │
   │   (知识源 SPI)          │   │  (检索算法 SPI)       │
   │   - load() → List<KF>  │   │  - score(query, KF)  │
   │   - reload() / type()  │   │    → double [0,1]     │
   └───────────────────────┘   └──────────────────────┘
```

### 核心接口

**`KnowledgeSearcher`** (core SPI):
```java
public interface KnowledgeSearcher {
    /**
     * 评分：查询与知识片段的相关度
     * @return [0.0, 1.0]，0.0=无相关，1.0=完美匹配
     */
    double score(String query, KnowledgeFragment fragment);
}
```

**`KnowledgeSource`** (core SPI):
```java
public interface KnowledgeSource {
    List<KnowledgeFragment> load();  // 加载知识片段
    void reload();                    // 重新加载（热重载）
    String type();                    // 源类型标识
}
```

**`KnowledgeFragment`** (core, 不可变值对象):
```java
public final class KnowledgeFragment {
    private final String title;
    private final String content;
    private final String source;     // 来源标识，如 "business-overview.md:section-2"
    private final Map<String, Object> metadata; // 防御拷贝
}
```

**`SearchResult`** (core, 不可变值对象):
```java
public final class SearchResult {
    private final KnowledgeFragment fragment;
    private final double score;
}
```

### KnowledgeBase 检索流程

```java
public List<SearchResult> searchWithScores(String query, int topK, double minScore) {
    // 1. 边界检查
    if (query == null || query.isEmpty() || allFragments.isEmpty()) {
        return new ArrayList<>();
    }
    // 2. 对每个片段评分 + 过滤
    List<ScoredFragment> scored = new ArrayList<>();
    for (KnowledgeFragment f : allFragments) {
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

`SimpleKeywordSearcher` 是 `KnowledgeSearcher` 的默认实现，采用**混合分词策略**处理中英文文本。

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
public double score(String query, KnowledgeFragment fragment) {
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

`KnowledgeBase.searchWithScores()` 在评分后执行 `score >= minScore` 过滤：
- score=0.0 的片段（无任何匹配）被排除
- 只有分数达到阈值的片段才出现在结果中
- 结果按分数降序排列

### 4.3 历史 Bug 修复

**Bug 1：minScore 硬编码为 0.0**

`KnowledgeController.search()` 中硬编码了 `0.0` 而非使用配置值：

```java
// Bug: 硬编码 0.0
List<KnowledgeFragment> fragments = knowledgeBase.search(q, searchTopK, 0.0);
// Fix: 使用配置的 minScore
List<SearchResult> results = knowledgeBase.searchWithScores(q, searchTopK, minScore);
```

这导致所有片段（包括 score=0 无匹配的）都被返回，任何查询都返回全部 5 个片段。

**Bug 2：2-token 最低限制**

`SimpleKeywordSearcher` 曾有 `if (queryTokens.size() < 2) return 0.0;` 限制，导致单词英文查询（如 "snapagent"，只有 1 个 token）直接返回 0 分，搜不到任何结果。

修复：改为 `if (queryTokens.isEmpty()) return 0.0;`，允许单 token 查询正常打分。

---

## 5. KnowledgeInjector 自动注入

### 5.1 注入机制

`KnowledgeInjector` 实现 `SystemPromptExtender` SPI，在 LLM 开始思考前自动注入业务知识：

```
用户输入 "SKU-001 为什么没生成补货策略？"
    │
    ▼
KnowledgeInjector.extend(skillMeta, agentTask)
    │
    ├─ 从 task.inputs 提取用户查询文本
    ├─ 调用 knowledgeBase.search(query, maxFragments, minScore)
    ├─ 格式化匹配的知识片段为上下文段落
    └─ 返回注入到 system prompt 的知识文本
    │
    ▼
AgentExecutor 组装 system prompt:
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

### 5.3 AgentExecutor 多 Extender

`AgentExecutor` 支持 `List<SystemPromptExtender>`（v0.7 改造），按 Spring `@Order` 排序：

1. `ProjectContextExtender`（v0.3）：注入项目结构摘要
2. `KnowledgeInjector`（v0.7）：注入业务知识片段

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

---

## 7. 知识源

### 7.1 MarkdownKnowledgeSource（内置实现）

从 Markdown 文件加载知识，自动分段：

- 按 `##` 标题分段，每个 `##` 标题下的内容作为一个 `KnowledgeFragment`
- H1 标题作为 `metadata.category`
- 无 `##` 标题的文件，整文件作为一个 fragment
- 支持解析 `classpath:/` 和文件系统路径

### 7.2 扩展知识源

实现 `KnowledgeSource` 接口 + `@Component` 注解即可被自动发现：

```java
@Component
public class DatabaseKnowledgeSource implements KnowledgeSource {
    @Override
    public List<KnowledgeFragment> load() {
        // 从数据库加载业务知识
        return jdbcTemplate.query("SELECT title, content FROM knowledge_base",
            (rs, i) -> new KnowledgeFragment(
                rs.getString("title"),
                rs.getString("content"),
                "db:" + rs.getString("id"),
                null
            ));
    }
    @Override
    public String type() { return "database"; }
}
```

---

## 8. 已知限制

| 限制 | 说明 | 计划 |
|------|------|------|
| 英文大小写敏感 | `SnapAgent` ≠ `snapagent`（Latin 分词转小写，但内容匹配是大小写敏感的 HashSet） | 未来修复 |
| 无语义搜索 | 纯关键词重叠，不理解同义词/上下文 | v0.7.2 向量嵌入 |
| 无向量嵌入 | 不支持 embedding 相似度检索 | v0.7.2 引入 |
| 中文分词粗糙 | 2-gram bigram 无法处理专业术语/实体名 | 可自定义 KnowledgeSearcher |
| 无相关性反馈 | 用户无法标记结果是否有用 | v0.7.1 计划 |

---

## 9. 扩展指南

### 自定义检索算法

实现 `KnowledgeSearcher` 接口，替换默认的 `SimpleKeywordSearcher`：

```java
@Component
public class SemanticSearcher implements KnowledgeSearcher {
    @Override
    public double score(String query, KnowledgeFragment fragment) {
        // 使用向量嵌入计算余弦相似度
        double[] queryVec = embed(query);
        double[] fragVec = embed(fragment.getContent());
        return cosineSimilarity(queryVec, fragVec);
    }
}
```

注册为 Spring Bean 后，`KnowledgeBase` 会自动使用它替代默认实现（`@ConditionalOnMissingBean`）。

### 自定义知识源

实现 `KnowledgeSource` 接口（见 7.2 节），支持从任意数据源加载知识：数据库、外部 API、Confluence/语雀等。
