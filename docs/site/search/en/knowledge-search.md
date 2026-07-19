# SnapAgent Knowledge Search Algorithm Design

> Version: v1.0 | Updated: 2026-07-17

## 1. Architecture Overview

SnapAgent knowledge base uses a three-layer SPI architecture, achieving full decoupling of knowledge source loading, scoring, and injection:

```
┌──────────────────────────────────────────────────────────┐
│                    KnowledgeBase                         │
│   (manages all KnowledgeSource, delegates to Searcher)   │
│   - search(query, topK, minScore) → List<KnowledgeFragment>     │
│   - searchWithScores(query, topK, minScore) → List<SearchResult>│
│   - reload() / size()                                   │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   KnowledgeSource      │   │  KnowledgeSearcher   │
   │   (knowledge source SPI)│   │  (search algorithm SPI)│
   │   - load() → List<KF>  │   │  - score(query, KF)  │
   │   - reload() / type()  │   │    → double [0,1]     │
   └───────────────────────┘   └──────────────────────┘
```

### Core Interfaces

**`KnowledgeSearcher`** (core SPI):
```java
public interface KnowledgeSearcher {
    double score(String query, KnowledgeFragment fragment);
    // Returns [0.0, 1.0]: 0.0 = no relevance, 1.0 = perfect match
}
```

**`KnowledgeSource`** (core SPI):
```java
public interface KnowledgeSource {
    List<KnowledgeFragment> load();  // Load knowledge fragments
    void reload();                    // Hot reload
    String type();                    // Source type identifier
}
```

**`KnowledgeFragment`** (core, immutable):
```java
public final class KnowledgeFragment {
    private final String title;
    private final String content;
    private final String source;     // e.g. "business-overview.md:section-2"
    private final Map<String, Object> metadata; // defensive copy
}
```

**`SearchResult`** (core, immutable):
```java
public final class SearchResult {
    private final KnowledgeFragment fragment;
    private final double score;
}
```

### KnowledgeBase Search Flow

1. Boundary check: null/empty query or no fragments → return empty list
2. Score each fragment via `searcher.score(query, fragment)`
3. Filter: keep only fragments where `score >= minScore`
4. Sort by score descending
5. Return top-K results

---

## 2. Tokenization (SimpleKeywordSearcher)

`SimpleKeywordSearcher` is the default `KnowledgeSearcher` implementation, using a **hybrid tokenization strategy** for mixed Chinese/English text.

### 2.1 Tokenization Rules

**English/Latin text:**
- Split on whitespace and punctuation
- Lowercase
- Drop tokens shorter than 2 characters (stopword filter)

**Chinese/CJK text:**
- 2-character overlapping bigrams (step by 1)
- e.g. "补货策略" → `["补货", "货策", "策略"]`
- Standalone CJK characters (no adjacent CJK) kept as single-char tokens

**Mixed text handling:**
- When CJK appears mid-Latin-word, flush the Latin word first, then process CJK
- e.g. "SnapAgent 是嵌入式" → `["snapagent", "是嵌", "嵌式"]`

### 2.2 Tokenization Code

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
                tokens.add(text.substring(i, i + 2));  // add directly, no filter
            } else {
                tokens.add(String.valueOf(c));  // standalone CJK char
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

### 2.3 Tokenization Examples

| Input | Tokens | Notes |
|-------|--------|-------|
| `snapagent` | `["snapagent"]` | Single English word → 1 token |
| `数据库` | `["数据", "据库", "库"]` | 3 CJK chars → 3 tokens (2 bigrams + 1 standalone) |
| `补货策略` | `["补货", "货策", "策略"]` | 4 CJK chars → 3 overlapping bigrams |
| `系统` | `["系统", "统"]` | 2 CJK chars → 1 bigram + 1 standalone |
| `SnapAgent 数据库诊断` | `["snapagent", "数据", "据库", "库", "诊", "诊断", "断"]` | Mixed text |

---

## 3. Scoring Formula

### 3.1 Formula

```
score = (titleHits × 2 + contentHits) / (queryTokenCount × 2)
```

- `titleHits`: number of query tokens found in the fragment title
- `contentHits`: number of query tokens found in the fragment content
- `queryTokenCount`: distinct query token count
- Title hits weighted 2× (title directly describes topic)
- Result clamped to `[0.0, 1.0]`

### 3.2 Scoring Code

```java
public double score(String query, KnowledgeFragment fragment) {
    if (query == null || query.isEmpty() || fragment == null) {
        return 0.0;
    }
    List<String> queryTokens = tokenize(query);
    if (queryTokens.isEmpty()) {
        return 0.0;  // at least 1 token required
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

### 3.3 Scoring Examples

**Query: "数据库" (database)**

Fragment "数据库诊断" (title contains "数据库"):
- Title hits: "数据"✓, "据库"✓ → 2
- Content hits: "数据"✓, "据库"✓, "库"✓ → 3
- Score: `(2×2 + 3) / (3×2) = 7/6` → clamp → **1.0**

Fragment "系统概述" (content mentions "数据库查询"):
- Title hits: 0
- Content hits: "数据"✓, "据库"✓ → 2 (no "库" in content as bigram)
- Score: `(0 + 2) / (3×2) = 2/6` → **0.33**

**Query: "snapagent"**

Fragment "SnapAgent 业务知识示例" (title contains SnapAgent):
- Title hits: "snapagent"✓ → 1
- Content hits: 0
- Score: `(1×2 + 0) / (1×2) = 1.0` → **1.0**

---

## 4. minScore Threshold

### 4.1 Configuration

```yaml
snap-agent:
  knowledge:
    enabled: true
    min-score: 0.1   # Default 0.1; fragments below this score are excluded
```

### 4.2 Filter Mechanism

`KnowledgeBase.searchWithScores()` filters `score >= minScore` after scoring:
- Fragments with score=0.0 (no match) are excluded
- Only fragments reaching the threshold appear in results
- Results sorted by score descending

### 4.3 Historical Bug Fixes

**Bug 1: minScore hardcoded to 0.0**

`KnowledgeController.search()` hardcoded `0.0` instead of using configured `minScore`, causing all fragments (including zero-score non-matches) to be returned for any query.

```java
// Bug: hardcoded 0.0
List<KnowledgeFragment> fragments = knowledgeBase.search(q, searchTopK, 0.0);
// Fix: use configured minScore
List<SearchResult> results = knowledgeBase.searchWithScores(q, searchTopK, minScore);
```

**Bug 2: 2-token minimum restriction**

`SimpleKeywordSearcher` previously required `queryTokens.size() >= 2`, blocking single-word English queries like "snapagent" (1 token). Fixed to only reject empty token lists.

---

## 5. KnowledgeInjector Auto-Injection

### 5.1 Injection Mechanism

`KnowledgeInjector` implements `SystemPromptExtender` SPI, automatically injecting business knowledge before LLM reasoning:

1. Extract user query from task inputs
2. Call `knowledgeBase.search(query, maxFragments, minScore)`
3. Format matched fragments into context block
4. Return text to be appended to system prompt

### 5.2 Configuration

```yaml
snap-agent:
  knowledge:
    enabled: true
    max-fragments: 3     # Max fragments injected into system prompt
    min-score: 0.1       # Minimum relevance score for injection
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
```

### 5.3 AgentExecutor Multi-Extender

`AgentExecutor` supports `List<SystemPromptExtender>` (v0.7), ordered by Spring `@Order`:
1. `ProjectContextExtender` (v0.3): injects project structure summary
2. `KnowledgeInjector` (v0.7): injects business knowledge fragments

---

## 6. REST API

### GET /knowledge/status

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

Returns matching fragments with relevance scores:

```json
{
    "query": "数据库",
    "totalFragments": 5,
    "matched": 2,
    "fragments": [
        {
            "title": "数据库诊断",
            "content": "...",
            "source": "business-overview.md:section-2",
            "metadata": { "category": "..." },
            "score": 1.0
        }
    ]
}
```

---

## 7. Knowledge Sources

### 7.1 MarkdownKnowledgeSource (built-in)

- Splits Markdown by `##` headings; each section becomes a `KnowledgeFragment`
- H1 title becomes `metadata.category`
- Supports `classpath:/` and filesystem paths

### 7.2 Custom Knowledge Source

Implement `KnowledgeSource` + `@Component` for auto-discovery (database, external API, Confluence, etc.).

---

## 8. Known Limitations

| Limitation | Description | Plan |
|------------|-------------|------|
| Case-sensitive English | `SnapAgent` ≠ `snapagent` in HashSet matching | Fix planned |
| No semantic search | Pure keyword overlap, no synonym/understanding | v0.7.2 vector embeddings |
| No vector embeddings | No embedding similarity search | v0.7.2 |
| Coarse Chinese segmentation | 2-gram bigrams can't handle domain terms | Custom KnowledgeSearcher |
| No relevance feedback | Users can't mark results as useful/useless | v0.7.1 planned |

---

## 9. Extension Guide

### Custom Search Algorithm

Implement `KnowledgeSearcher` interface and register as Spring Bean:

```java
@Component
public class SemanticSearcher implements KnowledgeSearcher {
    @Override
    public double score(String query, KnowledgeFragment fragment) {
        // Use vector embeddings for cosine similarity
        double[] queryVec = embed(query);
        double[] fragVec = embed(fragment.getContent());
        return cosineSimilarity(queryVec, fragVec);
    }
}
```

Registered via `@ConditionalOnMissingBean` — replaces default `SimpleKeywordSearcher`.

### Custom Knowledge Source

Implement `KnowledgeSource` interface for any data source (database, Confluence, API, etc.).
