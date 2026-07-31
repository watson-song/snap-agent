# Spring AI vs SnapAgent Engine — 架构差异分析

> 研究日期: 2026-07-25
> 研究范围: Spring AI 2.0+ 核心架构与 SnapAgent AgentEngine 的对比，识别能力差距
> 参考资料: [Spring AI 2.0 官方文档](https://docs.spring.io/spring-ai/reference/)

---

## 一、架构对比总览

| 维度 | Spring AI 2.0 | SnapAgent | 差距等级 |
|------|---------------|-----------|----------|
| **API 风格** | ChatClient fluent API (.prompt/.user/.call/.stream/.entity) | `GraphExecutor` 直接调用 | P1 |
| **拦截器链** | Advisor chain（类似 Filter，所有横切关注点都是 Advisor） | `Advisor` SPI | P0 |
| **工具调用** | ToolCallingAdvisor 自动管理生命周期，@Tool 声明式 | `@Tool`/`@ToolParam` 注解 + `ToolCallbackRegistry` 自动发现 | P1 |
| **Agent 循环** | Recursive Advisors（自递归实现 ReAct/self-refinement） | 硬编码 for 循环 ReAct | P1 |
| **记忆管理** | ChatMemory + 滑动窗口 + JDBC/Cassandra 持久化 | ConversationStore（文件 JSON），无滑动窗口 | P1 |
| **向量存储** | VectorStore 抽象，20+ 实现（PgVector/Redis/Milvus 等） | KnowledgeBase（内存关键词搜索）(now VectorStore) | P0 |
| **Embedding** | EmbeddingModel SPI（OpenAI/Ollama/ONNX 等） | 无 | P0 |
| **ETL 管道** | DocumentReader → Transformer → Writer | 无 | P1 |
| **结构化输出** | BeanOutputConverter + .entity(MyType.class) + JSON Schema | 无 | P1 |
| **RAG** | QuestionAnswerAdvisor + RetrievalAugmentationAdvisor（模块化） | KnowledgeInjector（简单关键词匹配 + 注入）(now RetrievalAugmentationAdvisor) | P0 |
| **可观测性** | Micrometer 深度集成（metrics + tracing） | AuditStore（自定义审计） | P1 |
| **评估框架** | RelevancyEvaluator + FactCheckingEvaluator + LLM-as-Judge | 无 | P2 |
| **多模态** | 图片/音频/视频 + 多模态 Embedding | 无 | P2 |
| **MCP 支持** | 完整（Client + Server + 注解 + 传输层） | McpToolProvider（仅 Client） | P2 |
| **工作流模式** | 5 种 Anthropic 模式（Chain/Parallel/Routing/Orchestrator/Evaluator） | SimpleWorkflowEngine（串行 + 正则条件） | P1 |
| **Prompt 模板** | PromptTemplate + delimiters + 参数化 | 无（直接字符串拼接） | P2 |

---

## 二、我们缺少的核心能力（按权重和优先级排序）

### P0 — 基础设施级缺陷（影响核心 AI 能力）

#### 1. Advisor 拦截器链 — 权重: 10/10

**Spring AI 实现:**
- Advisor 是 Spring AI 的核心扩展机制，类似 Spring Filter/HandlerInterceptor 但用于 LLM 调用
- 所有横切关注点都是 Advisor：记忆注入、RAG 检索、工具执行、安全过滤、可观测性
- `CallAdvisor` 和 `StreamAdvisor` 分别处理同步和流式调用
- 链式调用：`chain.nextCall(request)` 传递到下一个 advisor
- 支持自定义排序（`Ordered` 接口）
- **Recursive Advisors**（2.0 新增）：advisor 可自递归实现 agent 循环、self-refinement、迭代评估

```java
// Spring AI 的 Advisor 链
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 记忆注入
        QuestionAnswerAdvisor.builder(vectorStore).build(),     // RAG 检索
        SafeGuardAdvisor.builder().build()                      // 安全过滤
    )
    .build();
```

**SnapAgent 现状:**
- 只有 `Advisor` SPI（`Advisor.java`），且仅能扩展 system prompt
- 没有请求/响应拦截器链
- 工具执行在 `GraphExecutor` 的 `ToolsNode` 中，通过 `ToolCallbackRegistry` 自动发现
- 无法在不修改 `GraphExecutor` 源码的情况下添加新的横切关注点

**缺少的组件:**
- `Advisor` / `CallAdvisor` / `StreamAdvisor` 接口
- `AdvisorChain` — 链式调用机制
- `RecursiveAdvisor` — 自递归实现循环和迭代
- 将现有 `Advisor` SPI、KnowledgeInjector (now RetrievalAugmentationAdvisor)、AuditCallback 重构为 Advisor

---

#### 2. 向量存储 + Embedding — 权重: 10/10

**Spring AI 实现:**
- `VectorStore` 抽象，20+ 实现：
  - PgVector, Redis, Milvus, Chroma, Pinecone, Qdrant, Weaviate, Elasticsearch, MongoDB Atlas, Cassandra 等
- `EmbeddingModel` SPI：
  - OpenAI, Azure OpenAI, Ollama, ONNX Transformers, Google VertexAI, Mistral AI 等
- 完整 ETL 管道：
  - `DocumentReader`（PDF/Markdown/Tika/JSON）
  - `DocumentTransformer`（TokenTextSplitter/ContentFormatTransformer）
  - `DocumentWriter`（VectorStore.add）

```java
// Spring AI 的 ETL + RAG
vectorStore.accept(tokenTextSplitter.apply(pdfReader.get()));  // ETL: 读 → 分块 → 写入

// RAG 检索
List<Document> docs = vectorStore.similaritySearch(
    SearchRequest.builder().query("how to configure").topK(5).build()
);
```

**SnapAgent 现状:**
- `KnowledgeBase`（now VectorStore） 是内存 Map，用 `SimpleKeywordSearcher` 做简单关键词匹配
- 无向量数据库支持
- 无 Embedding 模型抽象
- 无 ETL 管道（知识文件直接加载到内存）
- `KnowledgeInjector`（now RetrievalAugmentationAdvisor） 做的是关键词匹配 + 文本注入，不是语义检索

**缺少的组件:**
- `VectorStore` SPI + 至少一个实现（Redis/PgVector）
- `EmbeddingModel` SPI + 至少一个实现（OpenAI/Ollama）
- `DocumentReader` / `DocumentTransformer` / `DocumentWriter` ETL 管道
- 将 KnowledgeBase（now VectorStore） 从关键词搜索升级为向量语义搜索

---

#### 3. 模块化 RAG — 权重: 9/10

**Spring AI 实现:**
两种 RAG 模式：

**简单 RAG（QuestionAnswerAdvisor）:**
- 查询 VectorStore → 检索文档 → 拼接到 user prompt
- 支持 similarityThreshold、topK、filterExpression

**模块化 RAG（RetrievalAugmentationAdvisor）:**
- `QueryTransformer` — LLM 重写查询 / 翻译 / 扩展为多变体
- `DocumentRetriever` — 向量检索 + 过滤
- `QueryAugmenter` — 将检索结果合并到查询

```java
// Spring AI 模块化 RAG
Advisor advisor = RetrievalAugmentationAdvisor.builder()
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .similarityThreshold(0.50)
        .vectorStore(vectorStore)
        .build())
    .queryAugmenter(ContextualQueryAugmenter.builder()
        .allowEmptyContext(true)
        .build())
    .build();
```

**SnapAgent 现状:**
- `KnowledgeInjector`（now RetrievalAugmentationAdvisor） 是唯一的 RAG 机制
- 流程：构建查询 → 关键词搜索 → 取 top-K → 拼接到 system prompt
- 无查询重写、无多变体扩展、无空上下文处理策略

**缺少的组件:**
- `QueryTransformer` — LLM 重写查询
- `DocumentRetriever` SPI — 可插拔检索策略
- `QueryAugmenter` — 上下文注入策略
- 空结果处理策略

---

### P1 — 重要能力差距（影响开发体验和生产质量）

#### 4. 结构化输出 — 权重: 8/10

**Spring AI 实现:**
- `BeanOutputConverter<T>` — 从 Java class 生成 JSON Schema，反序列化 LLM 输出到 POJO
- `.entity(MyType.class)` — ChatClient fluent API 一行调用
- 原生结构化输出（兼容模型直接用 JSON Schema）
- `MapOutputConverter` / `ListOutputConverter` 等变体

```java
// Spring AI 一行获取结构化输出
Person person = chatClient.prompt()
    .user("Describe a person named John")
    .call()
    .entity(Person.class);
```

**SnapAgent 现状:**
- LLM 返回纯文本，工具结果也是 `ToolResult(text)`
- 无 JSON Schema 生成
- 无自动反序列化
- 调用方需要手动解析 LLM 输出

**缺少的组件:**
- `StructuredOutputConverter<T>` SPI
- `BeanOutputConverter` — Java class → JSON Schema → 反序列化
- `.entity(Class)` API
- 原生结构化输出支持

---

#### 5. 工具调用抽象 — 权重: 8/10

**Spring AI 实现:**
- `@Tool` 注解 — 声明式，标注方法即为工具
- `@ToolParam` — 参数描述和必填标记
- `ToolCallback` — 工具回调接口
- `ToolCallingAdvisor` — 自动注册到 ChatClient，管理完整工具调用生命周期
- 工具结果是 `ToolResponseMessage`，自动 fed back to model
- 支持运行时工具注册和动态解析

```java
// Spring AI 声明式工具
class DateTimeTools {
    @Tool(description = "Get current date and time")
    String getCurrentDateTime() { return LocalDateTime.now().toString(); }
}

ChatClient.create(chatModel)
    .prompt("What time is it?")
    .tools(new DateTimeTools())  // 直接传入 @Tool 标注的 POJO
    .call().content();
```

**SnapAgent 现状:**
- `@Tool`/`@ToolParam` 注解 + `ToolCallback` 接口 — 通过 `ToolCallbackRegistry` 自动发现
- 工具定义需要手动构建 JSON Schema
- 工具分发通过 `ToolCallbackRegistry` 自动发现，在 `GraphExecutor` 的 `ToolsNode` 中执行
- 没有声明式注解，没有自动 JSON Schema 生成
- `PluginRegistry`（now ToolCallbackRegistry） 做插件管理（热加载、默认插件），但这是插件层而非工具声明层

**缺少的组件:**
- `@Tool` / `@ToolParam` 注解
- `ToolCallbacks.from(Object)` — 自动发现 @Tool 方法
- `ToolCallingAdvisor` — 自动管理工具调用生命周期
- `ToolCallbackResolver` — 动态工具解析
- `ToolCallbackProvider` — 批量工具供应（MCP 已有类似概念）

---

#### 6. ChatMemory 持久化 — 权重: 7/10

**Spring AI 实现:**
- `ChatMemory` 接口 + `MessageWindowChatMemory`（滑动窗口，默认 20 条）
- `ChatMemoryRepository` SPI：
  - `InMemoryChatMemoryRepository`（开发）
  - `JdbcChatMemoryRepository`（JDBC 持久化）
  - Cassandra / Neo4j / Azure Cosmos DB
- `MessageChatMemoryAdvisor` — 自动注入历史消息到 prompt
- 按 turn 边界驱逐（不会截断对话中间）

```java
// Spring AI ChatMemory
MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(10)
    .build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    .build();
```

**SnapAgent 现状:**
- `ConversationStore` SPI — 文件 JSON 存储
- `Conversation` model — userId/skillId/title/messages
- 无滑动窗口策略（历史全量传递或手动截断）
- 无 JDBC/Cassandra 持久化
- 无 turn 边界驱逐

**缺少的组件:**
- 滑动窗口策略（`MessageWindowChatMemory`）
- JDBC 持久化实现
- Turn 边界驱逐算法
- `ChatMemoryAdvisor` — 自动注入历史

---

#### 7. 可观测性 — 权重: 7/10

**Spring AI 实现:**
- 深度集成 Micrometer Observation API
- 指标（metrics）：ChatClient、ChatModel、Advisor、EmbeddingModel、VectorStore
- 链路追踪（tracing）：Zipkin / Jaeger / OpenTelemetry
- 低基数键（`gen_ai.operation.name`、`spring.ai.kind`）
- 高基数键（`spring.ai.chat.client.advisors`、`spring.ai.chat.client.conversation.id`）
- Prompt/Completion 日志（默认关闭，可开启）

**SnapAgent 现状:**
- `AuditStore` — 自定义审计记录
- `TranscriptEvent` — 事件日志
- 无 Micrometer 集成
- 无分布式链路追踪
- 无标准指标（QPS/延迟/错误率/Token 用量）

**缺少的组件:**
- Micrometer Observation 集成
- 标准 OTel/OpenTelemetry 标签
- Token 用量指标
- Prompt/Completion 日志（可开关）

---

#### 8. 工作流模式 — 权重: 6/10

**Spring AI 实现:**
实现 Anthropic 的 5 种 Agent 模式：
1. **Chain** — 串行步骤，每步基于上一步输出
2. **Parallelization** — 并行 LLM 调用，结果聚合
3. **Routing** — 分类器路由到不同处理路径
4. **Orchestrator-Workers** — 编排器分析任务，分发子任务给 worker
5. **Evaluator-Optimizer** — 迭代优化（生成 → 评估 → 改进）

**SnapAgent 现状:**
- `SimpleWorkflowEngine` — 串行步骤 + 基础正则条件
- 不支持并行、路由分类、编排器-工作者、迭代优化

**缺少的组件:**
- Parallelization workflow（并行 + 聚合）
- Routing workflow（分类器 + 动态路由）
- Orchestrator-Workers（子任务分发 + 并行执行）
- Evaluator-Optimizer（迭代优化循环）

---

### P2 — 增强能力差距（影响扩展性和高级场景）

#### 9. 评估框架 — 权重: 5/10

**Spring AI 实现:**
- `RelevancyEvaluator` — 评估响应是否与查询相关
- `FactCheckingEvaluator` — 事实准确性验证（Bespoke-Minicheck）
- LLM-as-a-Judge — 通过 Recursive Advisor 实现 1-4 分评分 + 自优化
- 直接 Assessment + Pairwise Comparison 两种模式

**SnapAgent 现状:** 无任何评估框架

---

#### 10. 多模态 — 权重: 4/10

**Spring AI 实现:**
- 图片（PNG/JPEG/GIF/WebP）— OpenAI GPT-4o, Anthropic Claude 3, Google Gemini
- 音频（MP3/WAV）— OpenAI, Gemini
- 视频（MP4）— Gemini
- 多模态 Embedding — VertexAI
- 图片生成 — DALL-E, Stability AI
- 语音转文字 / 文字转语音

**SnapAgent 现状:** 纯文本，无多模态支持

---

#### 11. Prompt 模板 — 权重: 3/10

**Spring AI 实现:**
- `PromptTemplate` + 可配置 delimiter（`<` `>` 等）
- 参数化模板（`.param("tone", "professional")`）
- `StTemplateRenderer` 渲染引擎

**SnapAgent 现状:**
- Skill body 是 Markdown，直接作为 system prompt
- 无参数化模板，无 delimiter 配置

---

## 三、我们有而 Spring AI 没有的（差异化优势）

| 能力 | 文件 | 说明 |
|------|------|------|
| **嵌入式 Skill 系统** | `SkillRegistry.java` | Markdown skill 文件热加载，skill body 即 prompt 模板 |
| **插件热加载** | `PluginRegistry.java` (now ToolCallbackRegistry), `PluginUploader.java` | 运行时 JAR 上传 + 隔离 classloader |
| **SSE 跨 Pod 中继** | `PeerSseRelay.java`, `PeerRouter.java` | K8s/DNS peer discovery + 代理流 |
| **Anchor 注入** | `AnchorOrchestrator.java` | 页面上下文摘要 + 分类 + RAG 注入 |
| **Patrol 调度** | `PatrolScheduler.java` | cron 自主巡检 + 告警收敛 |
| **限流** | `RateLimiter.java` | per-user 并发 + 小时配额 + CAS |
| **Issue 闭环** | `IssueClosureService.java` | 自动创建外部 tracker 工单 |
| **提示注入防御** | `GraphExecutor.java` | `<user_inputs>` XML 隔离 + 长度截断 |
| **审批门** | `SnapAgentController.java:1499` | cancel 机制（虽弱于 Spring AI 的 HITL） |
| **成本追踪** | Cost tracking decorator | Token + 费用追踪 |

---

## 四、与 LangGraph 差距对比

| 维度 | LangGraph | Spring AI | SnapAgent |
|------|-----------|-----------|-----------|
| **图模型** | StateGraph + 条件边 + 环 | Recursive Advisor（自递归） | 无 |
| **持久化** | SqliteSaver/RedisSaver | JDBC ChatMemoryRepository | 文件 JSON |
| **HITL** | interrupt() + resume() | 无原生支持 | cancel only |
| **多 Agent** | Supervisor-Worker + Subgraph | Orchestrator-Workers pattern | 无 |
| **RAG** | 需自行实现 | QuestionAnswerAdvisor + RetrievalAugmentationAdvisor | KnowledgeInjector（简单）(now RetrievalAugmentationAdvisor) |
| **向量存储** | 需自行实现 | 20+ VectorStore 实现 | 无 |
| **工具调用** | 需自行实现 | @Tool + ToolCallingAdvisor | `@Tool`/`@ToolParam` + `ToolCallbackRegistry` |
| **可观测性** | 需自行实现 | Micrometer 深度集成 | AuditStore |

**关键洞察:** LangGraph 在图编排和 HITL 上领先，Spring AI 在基础设施（向量存储、RAG、工具调用、可观测性）上领先。SnapAgent 的短板同时覆盖两个维度 — 既缺 LangGraph 的图模型，也缺 Spring AI 的 AI 基础设施。

---

## 五、补齐路线图（综合 LangGraph + Spring AI 差距）

### Phase 1 (P0): AI 基础设施 + 拦截器链

```
1.1 Advisor 拦截器链
    ├── Advisor / CallAdvisor / StreamAdvisor 接口
    ├── AdvisorChain 链式调用
    ├── 将 `Advisor` SPI / KnowledgeInjector (now RetrievalAugmentationAdvisor) / AuditCallback 重构为 Advisor
    └── RecursiveAdvisor 支持自递归（agent 循环基础）

1.2 向量存储 + Embedding
    ├── VectorStore SPI + Redis 实现
    ├── EmbeddingModel SPI + OpenAI 实现
    └── 将 KnowledgeBase（now VectorStore） 从关键词升级为语义搜索

1.3 模块化 RAG
    ├── QueryTransformer — LLM 重写查询
    ├── DocumentRetriever SPI
    ├── QueryAugmenter — 上下文注入
    └── 替换 KnowledgeInjector（now RetrievalAugmentationAdvisor） 为 RetrievalAugmentationAdvisor
```

### Phase 2 (P1): 开发体验 + 生产质量

```
2.1 结构化输出
    ├── BeanOutputConverter + JSON Schema 生成
    ├── .entity(Class) API
    └── 原生结构化输出支持

2.2 工具调用增强
    ├── @Tool / @ToolParam 注解
    ├── ToolCallbacks.from(Object) 自动发现
    ├── ToolCallingAdvisor — 工具生命周期管理
    └── ToolCallbackResolver 动态解析

2.3 ChatMemory 持久化
    ├── MessageWindowChatMemory 滑动窗口
    ├── JDBC ChatMemoryRepository
    └── Turn 边界驱逐

2.4 可观测性
    ├── Micrometer Observation 集成
    ├── 标准指标（QPS/延迟/Token）
    └── 分布式链路追踪

2.5 工作流模式
    ├── Parallelization workflow
    ├── Routing workflow
    ├── Orchestrator-Workers
    └── Evaluator-Optimizer
```

### Phase 3 (P0 from LangGraph): 图模型 + 持久化 + HITL

```
3.1 StateGraph（参考 LangGraph 路线图）
    ├── Node / Edge / ConditionalEdge
    ├── Graph compile + 执行引擎
    └── `GraphExecutor` 基于 `StateGraph` 的 ReAct 图（`EntryNode` → `AgentNode` → `ToolsNode` → `ShouldContinue`）

3.2 Checkpoint 持久化
    ├── CheckpointStore SPI
    ├── SQLite / Redis 实现
    └── 从 checkpoint 恢复

3.3 Interrupt / Resume
    ├── 节点边界暂停
    ├── resume 恢复
    └── 审批队列
```

### Phase 4 (P2): 高级能力

```
4.1 评估框架
    ├── RelevancyEvaluator
    ├── FactCheckingEvaluator
    └── LLM-as-a-Judge

4.2 多模态
    ├── 图片支持
    ├── 音频支持
    └── 多模态 Embedding

4.3 Prompt 模板
    ├── PromptTemplate + 参数化
    └── 可配置 delimiter
```

---

## 六、关键文件引用

| SnapAgent 组件 | 文件路径 | 对应 Spring AI 概念 |
|----------------|----------|---------------------|
| GraphExecutor | `snap-agent-core/.../agent/GraphExecutor.java` | ChatClient + ToolCallingAdvisor |
| Advisor SPI | `snap-agent-core/.../agent/Advisor.java` | Advisor |
| KnowledgeBase (now VectorStore) | `snap-agent-core/.../knowledge/KnowledgeBase.java` | VectorStore + EmbeddingModel |
| KnowledgeInjector (now RetrievalAugmentationAdvisor) | `snap-agent-core/.../knowledge/KnowledgeInjector.java` | RetrievalAugmentationAdvisor |
| KnowledgeSearcher (now DocumentRetriever) | `snap-agent-core/.../knowledge/KnowledgeSearcher.java` | DocumentRetriever |
| `@Tool`/`@ToolParam`/`ToolCallback` | `snap-agent-core/.../tool/ToolCallback.java` | @Tool / ToolCallback |
| ToolCallbackRegistry | `snap-agent-core/.../tool/ToolCallbackRegistry.java` | ToolCallingAdvisor |
| PluginRegistry (now ToolCallbackRegistry) | `snap-agent-core/.../tool/PluginRegistry.java` | ToolCallbackProvider |
| ConversationStore (now ChatMemoryRepository) | `snap-agent-core/.../conversation/ConversationStore.java` | ChatMemoryRepository |
| SimpleWorkflowEngine (now StateGraph) | `snap-agent-spring-boot-2x-starter/.../workflow/SimpleWorkflowEngine.java` | Agentic Patterns |
| AuditStore | `snap-agent-core/.../audit/AuditStore.java` | Micrometer Observation |
| LlmClient | `snap-agent-core/.../llm/LlmClient.java` | ChatModel |

> **Naming note**: The 2.x refactor renamed several components. Old names map to new names as follows: `KnowledgeBase` → `VectorStore`, `KnowledgeInjector` → `RetrievalAugmentationAdvisor`, `KnowledgeSearcher` → `DocumentRetriever`, `PluginRegistry` → `ToolCallbackRegistry`, `ConversationStore` → `ChatMemoryRepository`, `SimpleWorkflowEngine` → `StateGraph`. See `docs/glossary.md`.

---

## 七、参考资料

- [Spring AI 2.0 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Spring AI ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring AI Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)
- [Spring AI Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [Spring AI Evaluation/Testing](https://docs.spring.io/spring-ai/reference/api/testing.html)
- [Spring AI Multimodality](https://docs.spring.io/spring-ai/reference/api/multimodality.html)
