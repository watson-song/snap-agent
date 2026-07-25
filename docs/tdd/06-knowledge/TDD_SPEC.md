# TDD需求规格说明书 — 业务知识库 (VectorStore + 模块化 RAG)

> 版本: 3.0 | 模块: 06-knowledge | 基于 TEMPLATE.md
> 对应设计: `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` Section 2 (VectorStore + Embedding + Modular RAG) + Section 3 (ETL Pipeline)

---

## 1. 需求元信息

```yaml
需求ID: REQ-06-KNOWLEDGE
需求名称: VectorStore SPI + EmbeddingModel + 模块化 RAG + ETL 管道 + 知识沉淀
优先级: P0
迭代: v2.x
负责人: SnapAgent Team
状态: 开发中
```

### 1.1 背景与目标
- **业务背景**: SnapAgent 2.x 引入 Spring AI 风格 SPI。删除旧 `KnowledgeBase` (关键词搜索)、`KnowledgeInjector`、`KnowledgeSearcher`，替换为 `VectorStore` + `EmbeddingModel` 语义检索 + 模块化 RAG (`QueryTransformer` + `DocumentRetriever` + `QueryAugmenter`) 通过 `RetrievalAugmentationAdvisor` 注入图执行。新增 `KnowledgeETLPipeline` 将 Markdown 自动切块+嵌入+写入向量库；知识沉淀从 IssueClosure 提取 Q&A 经嵌入写入。
- **用户价值**: 提问时自动语义检索相关知识注入 prompt（替代关键词匹配），回答精准度提升 50%+；知识库可热重载；空上下文时 LLM 明确知晓"无相关知识"而非幻觉。
- **成功指标**: 检索 P95 < 50ms (向量库本地) / < 500ms (远程嵌入)；`snap-agent.vectorstore.enabled=false` 时零新增 bean；RAG 上下文为空时回退明确指令。

### 1.2 范围边界
- **包含**: `VectorStore` SPI (add/delete/similaritySearch), `EmbeddingModel` SPI (embed/embedBatch), `Document` 模型, `SearchRequest`, `QueryTransformer`/`DocumentRetriever`/`QueryAugmenter` SPI, `RetrievalAugmentationAdvisor` (order=200), `KnowledgeETLPipeline` (Markdown → DocumentReader → TokenTextSplitter → VectorStore.add), 知识沉淀 (conversation Q&A → extract → embed → VectorStore), filterExpression 元数据过滤, 热重载。
- **不包含**: 具体向量数据库驱动实现 (`RedisVectorStore`/`JdbcVectorStore` 属 starter 层)，外部 API 知识源 (v2.1)，多模态文档 (PDF/Word, v2.1)。

### 1.3 风险与假设

| 风险ID | 描述 | 概率 | 影响 | 缓解 |
|--------|------|------|------|------|
| R1 | EmbeddingModel 远程调用延迟 (500ms-2s) | 高 | 中 | 批量 embedBatch + 缓存查询向量 |
| R2 | 向量数据库不可用时 RAG 失败 | 中 | 高 | allowEmptyContext=false 时返回"无相关知识"指令，不抛异常 |
| R3 | 知识文件 Markdown 格式不规范致分块异常 | 低 | 中 | TokenTextSplitter 兜底按 token 切分 |
| R4 | 知识沉淀 IssueClosure 缺字段 | 中 | 中 | 必填字段缺失时跳过该 issue + WARN 日志 |
| R5 | 热重载高频触发 ETL 重复写入 | 中 | 中 | 文件 hash 比对，未变更跳过 |

**关键假设**: `snap-agent.vectorstore.enabled=true` 时所有 SPI 注入；`EmbeddingModel` 实现可选 (OpenAI/Ollama，由 starter 层提供)；Markdown 文件按 `## ` 分段 + TokenTextSplitter 兜底。

---

## 2. 用户故事 (User Stories)

### US-1: VectorStore 语义检索 — similaritySearch with threshold and topK
```gherkin
作为 Agent 用户
我希望 VectorStore.similaritySearch 根据 query 向量返回 topK 最相似文档（分数 >= similarityThreshold）
以便 检索结果既相关又数量可控
```
**AC:**
```gherkin
AC1: Given VectorStore 含 10 个 Document 且 SearchRequest(query="连接池", topK=4, similarityThreshold=0.75)
  When similaritySearch(request)
  Then 返回最多 4 个 Document 且每个相似度 >= 0.75
AC2: Given 无相似度 >= 0.75 的文档
  When similaritySearch
  Then 返回空列表
AC3: Given SearchRequest.query 为 null 或空
  When similaritySearch
  Then 返回空列表且不抛 NPE
AC4: Given VectorStore 含相同内容不同 metadata 的文档
  When similaritySearch(topK=4)
  Then 返回结果按相似度降序排列
```

### US-2: EmbeddingModel — embed 单文本 + embedBatch 批量
```gherkin
作为 系统开发者
我希望 EmbeddingModel.embed(text) 返回 float[] 向量，embedBatch(texts) 批量返回
以便 单文本用于查询向量，批量用于 ETL 写入
```
**AC:**
```gherkin
AC1: Given EmbeddingModel (dim=1536)
  When embed("hello")
  Then 返回 float[1536]
AC2: Given texts=["a","b","c"]
  When embedBatch(texts)
  Then 返回 List<float[]> 长度=3，每个向量 dim=1536
AC3: Given text=null
  When embed(null)
  Then 抛 IllegalArgumentException
AC4: Given texts 为空列表
  When embedBatch([])
  Then 返回空列表且不抛异常
AC5: Given texts 含 null 元素
  When embedBatch
  Then 跳过 null 元素或抛 IllegalArgumentException（取决于实现约定）
```

### US-3: ETL 管道 — Markdown → split → embed → write to VectorStore
```gherkin
作为 系统管理员
我希望 KnowledgeETLPipeline 将 Markdown 文件 → DocumentReader → TokenTextSplitter → EmbeddingModel.embedBatch → VectorStore.add
以便 无需开发代码即可维护知识库（放置 .md 即可）
```
**AC:**
```gherkin
AC1: Given 文件 "# Title\n## S1\n内容1\n## S2\n内容2"
  When KnowledgeETLPipeline.run(file)
  Then TokenTextSplitter 切分为多个 chunk
  And 每个 chunk 经 embedBatch 生成向量后 VectorStore.add 被调用
  And Document.metadata.source 含文件名
AC2: Given 文件无 ## 标题
  When run(file)
  Then 整文件作为单个 chunk 写入
AC3: Given ETL 执行中 VectorStore.add 抛异常
  When run
  Then 异常被 catch + WARN 日志，其他 chunk 继续写入
AC4: Given 文件含 emoji 和中文
  When run
  Then TokenTextSplitter 按 token 切分，不按字符切分（避免中文截断）
AC5: Given 多个 .md 文件
  When runAll(dir)
  Then 每个文件依次 ETL，全部完成或单文件失败不影响其他
```

### US-4: 模块化 RAG — QueryTransformer 重写 + DocumentRetriever 检索 + QueryAugmenter 注入
```gherkin
作为 系统开发者
我希望 模块化 RAG 三段式 SPI 可独立替换: QueryTransformer 重写 query, DocumentRetriever 检索, QueryAugmenter 注入上下文
以便 不同场景可定制 RAG 行为（如多查询重写、混合检索、上下文压缩）
```
**AC:**
```gherkin
AC1: Given QueryTransformer 实现 rewriteQuery → "扩展后 query"
  When transform("原 query", ctx)
  Then 返回 "扩展后 query"
AC2: Given DocumentRetriever 实现 retrieve(query, topK=4) → 返回 4 个 Document
  When retrieve("query", 4)
  Then 返回 List<Document> 长度=4
AC3: Given QueryAugmenter 实现 augment(originalQuery, docs) → "原 query + 上下文"
  When augment("原 query", docs)
  Then 返回包含 originalQuery 和 docs 内容的合成 prompt
AC4: Given QueryAugmenter.augment(originalQuery, [])
  When augment
  Then 返回 originalQuery（空文档时保持原 query）
AC5: Given 三段式 SPI 各自可独立替换
  When 注入不同实现
  Then RetrievalAugmentationAdvisor 使用新实现，不抛异常
```

### US-5: RetrievalAugmentationAdvisor — before agent_node: transform → retrieve → augment → state
```gherkin
作为 Agent 开发者
我希望 RetrievalAugmentationAdvisor 作为 Advisor (order=200)，在 agent_node 之前执行 RAG 流程并注入 state["rag.context"]
以便 LLM 调用前自动获得相关知识上下文，无需用户手动操作
```
**AC:**
```gherkin
AC1: Given RetrievalAugmentationAdvisor 注册 + state["user.query"]="连接池配置"
  When beforeNode("agent", state, ctx)
  Then 依次调用 transform → retrieve → augment
  And state["rag.context"] 含检索到的知识内容
AC2: Given state 无 user.query
  When beforeNode("agent", state, ctx)
  Then state["rag.context"] 为空字符串，不抛异常
AC3: Given 检索返回空文档
  When beforeNode
  Then state["rag.context"] 含 "无相关知识" 指令（US-6 关联）
AC4: Given afterNode("agent", state, ctx)
  When 调用
  Then 不修改 state（Advisor 仅 before 注入）
AC5: Given Advisor 抛异常
  When beforeNode
  Then 异常被 AdvisorNode catch + WARN 日志，图执行不中断（state["rag.context"] 为空）
```

### US-6: 空上下文处理 — allowEmptyContext=false → "无相关知识" 指令
```gherkin
作为 系统鲁棒性
我希望 allowEmptyContext=false 时检索无结果返回"无相关知识"指令注入 prompt，true 时返回空字符串
以便 LLM 在无相关知识时明确知晓，而非幻觉编造
```
**AC:**
```gherkin
AC1: Given allowEmptyContext=false 且检索返回空
  When QueryAugmenter.augment
  Then 返回 "无相关知识，请基于你自己的知识回答" 指令字符串
AC2: Given allowEmptyContext=true 且检索返回空
  When augment
  Then 返回空字符串 ""
AC3: Given allowEmptyContext=false 且检索返回非空
  When augment
  Then 返回含文档内容的合成 prompt
AC4: Given allowEmptyContext 默认值
  When 构造 RetrievalAugmentationAdvisor
  Then allowEmptyContext=false（默认保守策略，明确告知 LLM）
```

### US-7: 知识沉淀 — conversation Q&A → extract → embed → VectorStore
```gherkin
作为 运维工程师
我希望 IssueClosure 含 userQuery/rootCause/solution，自动提取 Q&A → EmbeddingModel.embed → VectorStore.add
以便 下次同类问题 Agent 可参考历史经验（语义检索替代关键词）
```
**AC:**
```gherkin
AC1: Given IssueClosure(issueId="issue-001", userQuery="为什么订单超时?", rootCause="连接池打满", solution="扩容连接池")
  When KnowledgeSedimentationService.extract(issue)
  Then 生成 Document 含 "## 问题\n...\n## 根因\n...\n## 解决方案\n..."
AC2: Given extract 生成 Document
  When sediment
  Then EmbeddingModel.embed 被调用 + VectorStore.add 被调用
  And metadata.source="sedimentation:issue-001"
  And metadata.category="经验沉淀"
AC3: Given userQuery 长度 > 60 字符
  When extract
  Then title 截断至 60 字符 + "..." 后缀
AC4: Given IssueClosure 缺 rootCause 或 solution
  When extract
  Then 跳过该 issue + WARN 日志 "issue-001 missing required field: rootCause"
AC5: Given suggestion=[方案1,方案2], selectedSolution="方案2: 加索引"
  When extract
  Then content 含 "方案2: 加索引"，不含未选中的列项
```

### US-8: filterExpression — 元数据过滤
```gherkin
作为 系统开发者
我希望 SearchRequest.filterExpression 支持 metadata 过滤（如 source=="handbook"），以便 检索限定特定来源
```
**AC:**
```gherkin
AC1: Given VectorStore 含 Document (metadata.source="handbook") 和 (metadata.source="sedimentation")
  When similaritySearch(filterExpression="source == 'handbook'")
  Then 仅返回 source="handbook" 的文档
AC2: Given filterExpression=null
  When similaritySearch
  Then 返回所有文档（无过滤）
AC3: Given filterExpression 语法错误 (如 "source == ")
  When similaritySearch
  Then 返回空列表 + WARN 日志（不抛异常）
AC4: Given filterExpression="category == '经验沉淀' AND source != 'legacy'"
  When similaritySearch
  Then 返回符合条件文档（复合表达式）
AC5: Given filterExpression="source IN ['handbook', 'docs']"
  When similaritySearch
  Then 返回 source 为 handbook 或 docs 的文档（IN 操作符）
```

### US-9: 热重载 — 知识文件变更时重跑 ETL 管道
```gherkin
作为 系统管理员
我希望 知识文件变更（新增/修改/删除）时自动重跑 KnowledgeETLPipeline
以便 无需重启即可更新知识库
```
**AC:**
```gherkin
AC1: Given 知识目录新增 file2.md
  When 文件变更事件触发
  Then KnowledgeETLPipeline.run(file2.md) 被调用且 VectorStore 含新文档
AC2: Given 知识文件 file1.md 修改
  When 变更事件
  Then VectorStore.delete([file1 旧 chunk id]) 后 add([file1 新 chunk])
AC3: Given 知识文件 file1.md 删除
  When 变更事件
  Then VectorStore.delete([file1 所有 chunk id]) 被调用
AC4: Given 文件内容未变更 (hash 相同)
  When 变更事件
  Then 跳过 ETL（避免重复写入）
AC5: Given snap-agent.vectorstore.enabled=false
  When 文件变更
  Then 不触发 ETL（功能关闭）
```

---

## 2.5 用户故事地图

| 阶段 | 故事 | 价值 | 指标 | 依赖 |
|------|------|------|------|------|
| 嵌入 | US-2 | 单/批量嵌入 | dim 一致 | - |
| 检索 | US-1 | 语义检索 | P95<50ms | US-2 |
| 过滤 | US-8 | 元数据过滤 | 精确匹配 | US-1 |
| 加载 | US-3 | Markdown ETL | 分块正确率 100% | US-2 |
| 模块化 | US-4 | RAG 三段可替换 | SPI 独立 | US-1 |
| 集成 | US-5 | Advisor 注入 | state["rag.context"] | US-4 |
| 降级 | US-6 | 空上下文指令 | 不幻觉 | US-5 |
| 沉淀 | US-7 | 经验复用 | 覆盖率>80% | US-2 |
| 更新 | US-9 | 无需重启 | hash 比对 | US-3 |

---

## 3. 功能规格 (Functional Specs)

### 3.1 用例清单

| 用例ID | 名称 | 优先级 | AC | 类型 |
|--------|------|--------|----|------|
| UC-01 | VectorStore.similaritySearch topK+threshold | P0 | US-1 | 单元 |
| UC-02 | VectorStore.similaritySearch 空查询/null | P0 | US-1 | 单元 |
| UC-03 | VectorStore.similaritySearch 降序排列 | P1 | US-1 | 单元 |
| UC-04 | EmbeddingModel.embed 单文本 | P0 | US-2 | 单元 |
| UC-05 | EmbeddingModel.embedBatch 批量 | P0 | US-2 | 单元 |
| UC-06 | EmbeddingModel null/空列表边界 | P1 | US-2 | 单元 |
| UC-07 | KnowledgeETLPipeline.run ## 分段 | P0 | US-3 | 单元 |
| UC-08 | KnowledgeETLPipeline.run 无 ## 整文件 | P0 | US-3 | 单元 |
| UC-09 | KnowledgeETLPipeline 单 chunk 失败隔离 | P1 | US-3 | 单元 |
| UC-10 | KnowledgeETLPipeline 中文+emoji token 切分 | P1 | US-3 | 单元 |
| UC-11 | QueryTransformer 重写 | P0 | US-4 | 单元 |
| UC-12 | DocumentRetriever 检索 | P0 | US-4 | 单元 |
| UC-13 | QueryAugmenter 注入 | P0 | US-4 | 单元 |
| UC-14 | QueryAugmenter 空文档保持原 query | P1 | US-4 | 单元 |
| UC-15 | RetrievalAugmentationAdvisor beforeNode transform→retrieve→augment | P0 | US-5 | 单元 |
| UC-16 | RetrievalAugmentationAdvisor beforeNode 无 user.query | P1 | US-5 | 单元 |
| UC-17 | RetrievalAugmentationAdvisor 异常隔离 | P1 | US-5 | 单元 |
| UC-18 | allowEmptyContext=false → "无相关知识" | P0 | US-6 | 单元 |
| UC-19 | allowEmptyContext=true → 空字符串 | P1 | US-6 | 单元 |
| UC-20 | KnowledgeSedimentationService.extract 含 ## 问题/根因/解决方案 | P0 | US-7 | 单元 |
| UC-21 | 知识沉淀 extract → embed → VectorStore.add | P0 | US-7 | 单元 |
| UC-22 | 知识沉淀 userQuery 截断 | P1 | US-7 | 单元 |
| UC-23 | 知识沉淀 selectedSolution 优先 | P1 | US-7 | 单元 |
| UC-24 | filterExpression 元数据过滤 | P0 | US-8 | 单元 |
| UC-25 | filterExpression 语法错误降级 | P1 | US-8 | 单元 |
| UC-26 | filterExpression 复合表达式 | P2 | US-8 | 单元 |
| UC-27 | 热重载新增文件触发 ETL | P0 | US-9 | 集成 |
| UC-28 | 热重载修改文件 delete+add | P1 | US-9 | 集成 |
| UC-29 | 热重载删除文件 delete | P1 | US-9 | 集成 |
| UC-30 | 热重载 hash 未变更跳过 | P1 | US-9 | 集成 |
| UC-R1 | GET /knowledge/status 知识库状态 | P1 | - | 集成 |
| UC-R2 | GET /knowledge/search 检索知识片段 | P0 | US-1 | 集成 |
| UC-R3 | POST /knowledge/reload 热重载 | P1 | US-9 | 集成 |

### 3.2 详细用例 (Gherkin)

#### UC-01: VectorStore.similaritySearch topK+threshold
```gherkin
@priority:high @type:unit
功能: VectorStore 语义检索

  场景: topK + similarityThreshold 过滤
    Given VectorStore 含 10 个 Document
    And SearchRequest(query="连接池", topK=4, similarityThreshold=0.75)
    When similaritySearch(request)
    Then 返回最多 4 个 Document
    And 每个文档相似度 >= 0.75

  场景: 无相似度 >= 0.75 的文档返回空
    Given VectorStore 含 10 个文档但相似度均 < 0.75
    When similaritySearch(request)
    Then 返回空列表
```

#### UC-02: VectorStore.similaritySearch 空查询/null
```gherkin
@priority:high @type:unit
功能: 空查询防御

  场景大纲: 空查询返回空列表
    Given SearchRequest.query = <query>
    When similaritySearch
    Then 返回空列表且不抛异常
    例子:
      | query |
      | null  |
      | ""    |
      | "   " |
```

#### UC-03: VectorStore.similaritySearch 降序排列
```gherkin
@priority:medium @type:unit
功能: 检索结果降序

  场景: 结果按相似度降序
    Given VectorStore 含相同内容不同 metadata 的文档，相似度分别为 0.9, 0.7, 0.8
    When similaritySearch(topK=3)
    Then 返回结果按相似度降序排列: 0.9, 0.8, 0.7
```

#### UC-04: EmbeddingModel.embed 单文本
```gherkin
@priority:high @type:unit
功能: 单文本嵌入

  场景: embed 返回固定维度向量
    Given EmbeddingModel (dim=1536)
    When embed("hello")
    Then 返回 float[1536]
    And 每个元素在 [-1, 1] 范围内
```

#### UC-05: EmbeddingModel.embedBatch 批量
```gherkin
@priority:high @type:unit
功能: 批量嵌入

  场景: embedBatch 返回多个向量
    Given texts=["a","b","c"]
    When embedBatch(texts)
    Then 返回 List<float[]> 长度=3
    And 每个向量 dim=1536
```

#### UC-06: EmbeddingModel null/空列表边界
```gherkin
@priority:medium @type:unit
功能: 嵌入边界

  场景大纲: null/空输入
    Given input = <input>
    When <method>
    Then <expected>
    例子:
      | input | method | expected |
      | null | embed(null) | 抛 IllegalArgumentException |
      | []   | embedBatch([]) | 返回空列表不抛异常 |
      | [null, "a"] | embedBatch | 跳过 null 或抛 IllegalArgumentException |
```

#### UC-07: KnowledgeETLPipeline.run ## 分段
```gherkin
@priority:high @type:unit
功能: ETL 管道 ## 分段

  场景: 按 ## 切分多个 chunk
    Given 文件 "# Title\n## S1\n内容1\n## S2\n内容2"
    When KnowledgeETLPipeline.run(file)
    Then TokenTextSplitter 切分为多个 chunk
    And 每个 chunk 经 embedBatch 生成向量后 VectorStore.add 被调用
    And Document.metadata.source 含文件名
    And Document.metadata.category="Title"
```

#### UC-08: KnowledgeETLPipeline.run 无 ## 整文件
```gherkin
@priority:high @type:unit
功能: ETL 管道无 ## 整文件

  场景: 无 ## 标题整文件作为单 chunk
    Given 文件 "# Title\n纯内容无二级标题"
    When run(file)
    Then 整文件作为单个 chunk 写入
    And VectorStore.add 被调用一次
```

#### UC-09: KnowledgeETLPipeline 单 chunk 失败隔离
```gherkin
@priority:medium @type:unit
功能: ETL 单 chunk 失败隔离

  场景: VectorStore.add 抛异常不影响其他 chunk
    Given 文件含 3 个 chunk 且第 2 个 chunk 的 VectorStore.add 抛 RuntimeException
    When run
    Then 第 2 个 chunk 异常被 catch + WARN 日志
    And 第 1 和第 3 个 chunk 正常写入
```

#### UC-10: KnowledgeETLPipeline 中文+emoji token 切分
```gherkin
@priority:medium @type:unit
功能: ETL token 切分

  场景: 中文+emoji 不按字符切分
    Given 文件含 emoji 😀 和中文 "连接池配置"
    When run
    Then TokenTextSplitter 按 token 切分
    And 中文不被截断为半字符
```

#### UC-11: QueryTransformer 重写
```gherkin
@priority:high @type:unit
功能: 查询重写

  场景: transform 返回重写后 query
    Given QueryTransformer 实现 rewriteQuery → "扩展后 query"
    When transform("原 query", ctx)
    Then 返回 "扩展后 query"
```

#### UC-12: DocumentRetriever 检索
```gherkin
@priority:high @type:unit
功能: 文档检索

  场景: retrieve 返回 topK 文档
    Given DocumentRetriever 实现 retrieve(query, topK=4) → 4 个 Document
    When retrieve("query", 4)
    Then 返回 List<Document> 长度=4
```

#### UC-13: QueryAugmenter 注入
```gherkin
@priority:high @type:unit
功能: 查询增强

  场景: augment 返回合成 prompt
    Given QueryAugmenter 实现 augment(originalQuery, docs)
    When augment("原 query", docs)
    Then 返回包含 originalQuery 和 docs 内容的合成 prompt
```

#### UC-14: QueryAugmenter 空文档保持原 query
```gherkin
@priority:medium @type:unit
功能: 空文档增强

  场景: augment 空文档列表返回原 query
    Given QueryAugmenter.allowEmptyContext=true
    When augment("原 query", [])
    Then 返回 "原 query"（保持不变）
```

#### UC-15: RetrievalAugmentationAdvisor beforeNode transform→retrieve→augment
```gherkin
@priority:high @type:unit
功能: RAG Advisor 注入

  场景: beforeNode 依次执行三段式并注入 state
    Given RetrievalAugmentationAdvisor 注册 + state["user.query"]="连接池配置"
    When beforeNode("agent", state, ctx)
    Then 依次调用 transform → retrieve → augment
    And state["rag.context"] 含检索到的知识内容
```

#### UC-16: RetrievalAugmentationAdvisor beforeNode 无 user.query
```gherkin
@priority:medium @type:unit
功能: RAG Advisor 无 query

  场景: state 无 user.query 时不抛异常
    Given state 无 user.query
    When beforeNode("agent", state, ctx)
    Then state["rag.context"] 为空字符串
    And 不抛异常
```

#### UC-17: RetrievalAugmentationAdvisor 异常隔离
```gherkin
@priority:medium @type:unit
功能: RAG Advisor 异常隔离

  场景: Advisor 抛异常被 catch
    Given DocumentRetriever.retrieve 抛 RuntimeException
    When beforeNode("agent", state, ctx)
    Then 异常被 AdvisorNode catch + WARN 日志
    And state["rag.context"] 为空字符串
    And 图执行不中断
```

#### UC-18: allowEmptyContext=false → "无相关知识"
```gherkin
@priority:high @type:unit
功能: 空上下文降级指令

  场景: allowEmptyContext=false 返回指令
    Given allowEmptyContext=false 且检索返回空
    When QueryAugmenter.augment
    Then 返回 "无相关知识，请基于你自己的知识回答" 指令字符串
```

#### UC-19: allowEmptyContext=true → 空字符串
```gherkin
@priority:medium @type:unit
功能: 空上下文宽松策略

  场景: allowEmptyContext=true 返回空字符串
    Given allowEmptyContext=true 且检索返回空
    When augment
    Then 返回空字符串 ""
```

#### UC-20: KnowledgeSedimentationService.extract 含 ## 问题/根因/解决方案
```gherkin
@priority:high @type:unit
功能: 知识沉淀提取

  场景: extract 生成含三章节的 Document
    Given IssueClosure(issueId="issue-001", userQuery="为什么订单超时?", rootCause="连接池打满", solution="扩容连接池")
    When extract(issue)
    Then 生成 Document
    And content 含 "## 问题" 和 "## 根因" 和 "## 解决方案"
    And metadata.source="sedimentation:issue-001"
    And metadata.category="经验沉淀"
```

#### UC-21: 知识沉淀 extract → embed → VectorStore.add
```gherkin
@priority:high @type:unit
功能: 知识沉淀写入

  场景: extract 后嵌入并写入 VectorStore
    Given IssueClosure 含完整字段
    When sediment(issue)
    Then EmbeddingModel.embed 被调用
    And VectorStore.add 被调用
    And Document 含 embedding 字段
```

#### UC-22: 知识沉淀 userQuery 截断
```gherkin
@priority:medium @type:unit
功能: 知识沉淀截断

  场景: userQuery > 60 字符截断
    Given userQuery 长度=80
    When extract
    Then title 截断至 60 字符 + "..." 后缀
    And content 含完整 userQuery
```

#### UC-23: 知识沉淀 selectedSolution 优先
```gherkin
@priority:medium @type:unit
功能: 知识沉淀方案优先

  场景: selectedSolution 优先不列选项
    Given suggestion=[方案1,方案2], selectedSolution="方案2: 加索引"
    When extract
    Then content 含 "方案2: 加索引"
    And content 不含 "- [medium] 方案1"
```

#### UC-24: filterExpression 元数据过滤
```gherkin
@priority:high @type:unit
功能: 元数据过滤

  场景: filterExpression 过滤特定来源
    Given VectorStore 含 Document (metadata.source="handbook") 和 (metadata.source="sedimentation")
    When similaritySearch(filterExpression="source == 'handbook'")
    Then 仅返回 source="handbook" 的文档
```

#### UC-25: filterExpression 语法错误降级
```gherkin
@priority:medium @type:unit
功能: filterExpression 降级

  场景: 语法错误返回空列表
    Given filterExpression="source == "
    When similaritySearch
    Then 返回空列表 + WARN 日志
    And 不抛异常
```

#### UC-26: filterExpression 复合表达式
```gherkin
@priority:low @type:unit
功能: 复合 filterExpression

  场景: AND/IN 复合表达式
    Given filterExpression="category == '经验沉淀' AND source != 'legacy'"
    When similaritySearch
    Then 返回符合条件文档
```

#### UC-27: 热重载新增文件触发 ETL
```gherkin
@priority:high @type:integration
功能: 热重载新增

  场景: 新增 file2.md 触发 ETL
    Given 知识目录新增 file2.md
    When 文件变更事件触发
    Then KnowledgeETLPipeline.run(file2.md) 被调用
    And VectorStore 含新文档
```

#### UC-28: 热重载修改文件 delete+add
```gherkin
@priority:medium @type:integration
功能: 热重载修改

  场景: 修改 file1.md 触发 delete+add
    Given 知识文件 file1.md 修改
    When 变更事件
    Then VectorStore.delete([file1 旧 chunk id]) 被调用
    And VectorStore.add([file1 新 chunk]) 被调用
```

#### UC-29: 热重载删除文件 delete
```gherkin
@priority:medium @type:integration
功能: 热重载删除

  场景: 删除 file1.md 触发 delete
    Given 知识文件 file1.md 删除
    When 变更事件
    Then VectorStore.delete([file1 所有 chunk id]) 被调用
```

#### UC-30: 热重载 hash 未变更跳过
```gherkin
@priority:medium @type:integration
功能: 热重载 hash 比对

  场景: 文件内容未变更跳过 ETL
    Given 文件内容 hash 相同
    When 变更事件
    Then 跳过 ETL（避免重复写入）
    And VectorStore.add 未被调用
```

---

## 4. 接口规格

```java
// VectorStore SPI — 替代旧 KnowledgeBase
public interface VectorStore {
    void add(List<Document> documents);
    void delete(List<String> ids);
    List<Document> similaritySearch(SearchRequest request);
}

// EmbeddingModel SPI — 新增
public interface EmbeddingModel {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}

// Document 模型
public class Document {
    private String id;
    private String content;
    private Map<String, Object> metadata;
    private float[] embedding;
}

// SearchRequest
public class SearchRequest {
    private String query;
    private int topK = 4;
    private double similarityThreshold = 0.75;
    private String filterExpression;
}

// Modular RAG SPI
public interface QueryTransformer {
    String transform(String originalQuery, ExecutionContext ctx);
}
public interface DocumentRetriever {
    List<Document> retrieve(String query, int topK);
}
public interface QueryAugmenter {
    String augment(String originalQuery, List<Document> retrievedDocs);
}

// RetrievalAugmentationAdvisor (Advisor, order=200)
// beforeNode("agent"): transform → retrieve → augment → state["rag.context"]
// afterNode: noop
```

REST 端点:
```yaml
GET /knowledge/status: 200 {sourceCount, documentCount, vectorStoreEnabled}
GET /knowledge/search?q={query}&topK={n}&threshold={t}&filter={expr}: 200 [{id,content,metadata,similarity}]
POST /knowledge/reload: 200 {reloaded: true, documentCount}
```

---

## 5. 数据规格

```yaml
实体: Document
字段: id(String, UUID) | content(String, Markdown) | metadata(Map, 含 source/category/createdAt) | embedding(float[], dim=1536)
约束: id 自动生成 | metadata null→emptyMap | content 非 null
实体: SearchRequest
字段: query(String) | topK(int=4) | similarityThreshold(double=0.75) | filterExpression(String可null)
默认: topK=4, similarityThreshold=0.75, filterExpression=null
实体: CostRecord(沉淀相关)
字段: userId, skillName, taskId, model, inputTokens, outputTokens, cacheReadTokens, cost(BigDecimal), timestamp
缓存: 查询向量缓存(Caffeine, key=SHA256(query), TTL=5min)
```

---

## 6. 错误处理

| 错误码 | 级别 | 描述 |
|--------|------|------|
| SOURCE_LOAD_FAILED | WARN | ETL 单文件加载失败 |
| SOURCE_RELOAD_FAILED | WARN | 热重载单文件失败 |
| EMBEDDING_FAILED | ERROR | EmbeddingModel 调用失败 |
| VECTORSTORE_UNAVAILABLE | ERROR | VectorStore 不可用，RAG 降级 |
| FILTER_EXPRESSION_INVALID | WARN | filterExpression 语法错误，返回空 |
| RAG_ADVISOR_FAILED | WARN | RetrievalAugmentationAdvisor 异常被 catch |

```gherkin
场景: VectorStore 不可用时 RAG 降级
  Given VectorStore.similaritySearch 抛 RuntimeException
  When RetrievalAugmentationAdvisor.beforeNode
  Then 异常被 catch + WARN 日志
  And state["rag.context"] 含 "无相关知识" 指令
  And 图执行不中断
场景: ETL 单 chunk 失败隔离
  Given 第 2 个 chunk VectorStore.add 抛异常
  When run
  Then 第 2 个 chunk 被 catch + WARN，其他 chunk 正常写入
```

---

## 7. 非功能需求

```yaml
性能: 检索P95<50ms(本地)/<500ms(远程嵌入) | ETL<500ms(10文件) | embedBatch<2s(100文本) | 查询向量缓存命中<1ms
可测试性: 核心覆盖>80% | VectorStore/EmbeddingModel/QueryTransformer/DocumentRetriever/QueryAugmenter 全部 SPI 可 Mock | @TempDir隔离文件
```

---

## 8. 测试策略

### 8.1 已有测试覆盖

| 测试文件 | 类型 | 覆盖用例 |
|----------|------|----------|
| (2.x 重构后旧测试已废弃，新测试待编写) | - | - |

**总结**: 2.x 架构重构删除旧 `KnowledgeBase`/`KnowledgeInjector`/`SimpleKeywordSearcher`/`MarkdownKnowledgeSource`/`KnowledgeSedimentationExtractor`，所有 2.x 测试为新增。优先实现 UC-01~30 (单元) 和 UC-R1~3 (集成)。

### 8.2 E2E 关键路径

| 路径ID | 关键路径 | 端点 | 状态 |
|--------|----------|------|------|
| E2E-1 | 知识状态查询: GET /knowledge/status → 200 (sourceCount/documentCount/vectorStoreEnabled) | GET /knowledge/status | ⚠未实现 (GAP-8) |
| E2E-2 | 知识搜索: GET /knowledge/search?q=keyword → 200 (Document 列表含 similarity) | GET /knowledge/search | ⚠未实现 (GAP-9) |
| E2E-3 | 知识重载: POST /knowledge/reload → 200 → GET /knowledge/status 验证刷新 | POST /knowledge/reload | ⚠未实现 (GAP-10) |
| E2E-4 | RAG Advisor 集成: POST /runs → 图执行 → state["rag.context"] 含检索知识 | POST /runs | ⚠未实现 (GAP-5 P0) |
| E2E-5 | ETL 上传: POST /knowledge/upload (.md) → 200 → VectorStore 含新文档 | POST /knowledge/upload | ⚠未实现 (GAP-11) |
| E2E-6 | 热重载文件变更: 修改 .md → 自动 ETL → VectorStore 更新 | 文件系统 | ⚠未实现 (GAP-12) |

### 8.3 测试缺口

| ID | 描述 | 优先级 | 建议 |
|----|------|--------|------|
| GAP-1 | `VectorStore.similaritySearch` topK+threshold+降序 无单测 | P0 | UC-01/03 |
| GAP-2 | `VectorStore.similaritySearch` 空查询/null 防御 无单测 | P0 | UC-02 |
| GAP-3 | `EmbeddingModel.embed`/`embedBatch` 单文本+批量+边界 无单测 | P0 | UC-04~06 |
| GAP-4 | `KnowledgeETLPipeline.run` ## 分段+整文件+失败隔离+token 切分 无单测 | P0/P1 | UC-07~10 |
| GAP-5 | `RetrievalAugmentationAdvisor` 集成图执行 注入 state["rag.context"] 无 E2E | P0 | E2E-4 |
| GAP-6 | `QueryTransformer`/`DocumentRetriever`/`QueryAugmenter` 三段式 SPI 可独立替换 无单测 | P0 | UC-11~14 |
| GAP-7 | `RetrievalAugmentationAdvisor.beforeNode` 三段式调用链 + 异常隔离 无单测 | P0/P1 | UC-15~17 |
| GAP-8 | E2E缺失: GET /knowledge/status REST 端点无 E2E 覆盖 — 见 E2E-1 | P1 | 需 E2E 集成测试 |
| GAP-9 | E2E缺失: GET /knowledge/search REST 端点无 E2E 覆盖 — 见 E2E-2 | P1 | 需 E2E 集成测试 |
| GAP-10 | E2E缺失: POST /knowledge/reload REST 端点无 E2E 覆盖 — 见 E2E-3 | P2 | 需 E2E 集成测试 |
| GAP-11 | E2E缺失: POST /knowledge/upload REST 端点无 E2E 覆盖 — 见 E2E-5 | P2 | 需 E2E 集成测试 |
| GAP-12 | E2E缺失: 热重载文件变更自动触发 ETL 无 E2E — 见 E2E-6 | P1 | 需 E2E + 文件监听 |
| GAP-13 | `allowEmptyContext=false` → "无相关知识" 指令 无单测 | P0 | UC-18 |
| GAP-14 | `KnowledgeSedimentationService.extract` + embed + VectorStore.add 无单测 | P0 | UC-20~21 |
| GAP-15 | `filterExpression` 元数据过滤+语法错误降级+复合表达式 无单测 | P0/P1/P2 | UC-24~26 |
| GAP-16 | `EmbeddingModel` 远程调用延迟测试 (mock 延迟) | P2 | 性能测试 |
| GAP-17 | `Document` 值对象 getter/toString/不可变 无单测 | P3 | 简单 getter |
| GAP-18 | `SearchRequest` 值对象默认值 (topK=4, threshold=0.75) 无单测 | P3 | 默认值 |

### 8.4 Mock 策略
```yaml
Mock: VectorStore(匿名实现), EmbeddingModel(lambda), QueryTransformer/DocumentRetriever/QueryAugmenter(lambda), IssueClosure/AgentTask(真实对象)
文件: @TempDir 创建临时 .md 文件
向量: 使用随机 float[] 模拟嵌入结果，避免真实 EmbeddingModel 依赖
```

---

## 9. 依赖与前置条件

| 依赖 | 状态 | 降级 |
|------|------|------|
| snap-agent-core graph/advisor SPI | 2.x 新增 | 无 |
| EmbeddingModel 实现 (OpenAI/Ollama) | starter 层提供 | 缺失时 RAG 功能禁用 |
| VectorStore 实现 (Redis/Jdbc) | starter 层提供 | 缺失时 vectorstore.enabled=false |
| Spring ResourcePatternResolver | 已完成 | classpath 模式需要 |
| Caffeine cache | 已完成 | 查询向量缓存 |

---

## 10. 可观测性设计

```yaml
日志: INFO "ETL loaded {} documents from {} file(s) in {}" | DEBUG "Search query='{}',results={}" | WARN "source {} load failed" | WARN "RAG advisor failed, falling back to empty context"
指标: rag_retrieve_count / rag_retrieve_latency_seconds / rag_context_empty_total / vectorstore_add_total / vectorstore_search_total / etl_chunk_total / sedimentation_extract_total
追踪: MicrometerObservationAdvisor span "snap-agent.rag.retrieve" / "snap-agent.etl.run"
```

---

## 11. 原型与交互参考

| 状态 | 表现 | 说明 |
|------|------|------|
| 已加载 | 知识库 modal 列文档 | GET /knowledge/status 驱动 |
| 检索中 | 无 UI 变化 | < 50ms |
| RAG 注入成功 | prompt 含知识 section | LLM 回答含上下文 |
| 无匹配 | prompt 含 "无相关知识" 指令 | LLM 基于自身知识回答 |
| 热重载 | 文件变更自动 ETL | VectorStore 含新文档 |

---

## 12. 附录

### 12.1 变更历史
| 版本 | 日期 | 作者 | 内容 |
|------|------|------|------|
| 2.0 | 2026-07-23 | Team | 初始 TDD 规格 (KnowledgeBase 关键词搜索) |
| 3.0 | 2026-07-25 | Team | 2.x 重构: VectorStore SPI + EmbeddingModel + 模块化 RAG + ETL + 知识沉淀 + filterExpression + 热重载，删除旧 KnowledgeBase/KnowledgeInjector |

### 12.2 参考文档
- `docs/superpowers/specs/2026-07-25-architecture-refactor-2x-design.md` (Section 2 VectorStore+Embedding+Modular RAG, Section 3 ETL Pipeline)
- `docs/superpowers/specs/2026-07-16-v0.7-knowledge-base-design.md` (历史设计)
- `docs/tdd/TEMPLATE.md`
- `snap-agent-core/.../vectorstore/` (VectorStore, Document, SearchRequest — 2.x 新增)
- `snap-agent-core/.../embedding/` (EmbeddingModel — 2.x 新增)
- `snap-agent-core/.../rag/` (QueryTransformer, DocumentRetriever, QueryAugmenter — 2.x 新增)
- `snap-agent-core/.../advisor/` (RetrievalAugmentationAdvisor — 2.x 新增)
- `snap-agent-spring-boot-2x-starter/.../knowledge/` (KnowledgeETLPipeline, KnowledgeSedimentationService — 2.x 新增)

### 12.3 术语表
| 术语 | 定义 |
|------|------|
| VectorStore | 向量库 SPI (add/delete/similaritySearch)，替代旧 KnowledgeBase |
| EmbeddingModel | 嵌入模型 SPI (embed/embedBatch)，2.x 新增 |
| Document | 知识文档 (id+content+metadata+embedding)，替代旧 KnowledgeFragment |
| SearchRequest | 检索请求 (query+topK+similarityThreshold+filterExpression) |
| QueryTransformer | RAG 第一段: 重写 query（如多查询、扩展） |
| DocumentRetriever | RAG 第二段: 从 VectorStore 检索文档 |
| QueryAugmenter | RAG 第三段: 将检索文档注入 originalQuery 合成 prompt |
| RetrievalAugmentationAdvisor | 组合三段式的 Advisor (order=200)，before agent_node 注入 state["rag.context"] |
| allowEmptyContext | 空上下文策略: false 返回"无相关知识"指令，true 返回空字符串 |
| KnowledgeETLPipeline | Markdown → DocumentReader → TokenTextSplitter → EmbeddingModel → VectorStore.add |
| filterExpression | 元数据过滤表达式 (如 source=='handbook') |
| 知识沉淀 | IssueClosure Q&A → extract → embed → VectorStore.add |
