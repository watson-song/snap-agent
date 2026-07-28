# SnapAgent 2.x Architecture Refactor Design

> **Date:** 2026-07-25
> **Branch:** 2.x
> **Status:** Approved
> **Scope:** Full architecture refactor — graph runtime + Spring AI-inspired SPI building blocks

---

## Goal

将 SnapAgent 从线性 ReAct 循环重构为**统一图架构**，同时引入 Spring AI 风格的构建块 SPI（Advisor、@Tool、VectorStore、Embedding、RAG、StructuredOutput、ChatMemory）。

图作为运行时，SPI 作为构建块。一个抽象覆盖三个现有实现（ReAct 循环、Workflow 引擎、插件路由），消除概念冗余。无向后兼容层 — 删除旧抽象，一步到位。

## Constraint

- Spring Boot 2.5.x ~ 2.7.x / Java 8 / javax.servlet — 硬约束
- 不依赖 Spring AI（要求 Spring Boot 3.x），只借鉴模式
- core 层零新外部依赖 — 所有 SPI 是纯 Java 接口
- starter 层可选依赖（SQLite、Micrometer、Redis 复用宿主）

## Architecture Overview

```
snap-agent-core (无 Spring, 无 servlet)
├── graph/              StateGraph, Node, Edge, ConditionalEdge, CompiledGraph
├── execution/          GraphExecutor, InterruptException
├── checkpoint/         CheckpointStore SPI
├── advisor/            Advisor, AdvisorNode
├── tool/               @Tool, @ToolParam, ToolCallback, ToolCallbackRegistry, ToolCallbacks
├── vectorstore/        VectorStore, Document, SearchRequest
├── embedding/          EmbeddingModel
├── converter/          StructuredOutputConverter, BeanOutputConverter, HtmlOutputConverter
├── rag/                QueryTransformer, DocumentRetriever, QueryAugmenter
├── memory/             ChatMemory, ChatMemoryRepository, MessageWindowChatMemory
├── llm/                LlmClient (保留不变)
├── skill/              SkillRegistry, SkillLoader, SkillMeta (保留不变)
├── agent/              AgentTask, TaskStatus, TaskStore (增强 checkpoint)
├── security/           RateLimiter, SecurityGateway (保留不变)
├── patrol/             PatrolScheduler (保留)
├── anchor/             AnchorOrchestrator (保留)
├── issue/              IssueClosureService (保留)
├── codegraph/          CodeGraph (保留)
└── routing/            PeerRouter SPI (保留)
```

## Deleted Abstractions (No Backward Compatibility)

| Deleted | Replaced By |
|---------|-------------|
| AgentExecutor (for loop) | GraphExecutor + ReActGraphFactory |
| SystemPromptExtender | Advisor |
| ToolProvider SPI | @Tool + ToolCallback |
| ToolDispatcher | ToolsNode (graph node) |
| SimpleWorkflowEngine | StateGraph (YAML → graph) |
| WorkflowDefinition / WorkflowStep | StateGraph nodes/edges |
| ConversationStore | ChatMemoryRepository |
| KnowledgeInjector | RetrievalAugmentationAdvisor |
| KnowledgeBase (keyword search) | VectorStore + EmbeddingModel |
| KnowledgeSearcher | DocumentRetriever |
| ProjectContextExtender | Advisor |
| TurnCollector | Graph execution state |

## Section 1: Graph Runtime

### GraphState (不可变)

```java
public class GraphState {
    private final Map<String, Object> values;
    private final String threadId;
    private final String checkpointId;
    private final int turn;

    public <T> T get(String key);
    public <T> T get(String key, T defaultValue);
    public GraphState with(String key, Object value);  // 返回新实例
    public GraphState nextTurn();
    public byte[] serialize();
    public static GraphState deserialize(byte[] data);
}
```

### Node

```java
public interface Node {
    String getName();
    GraphState execute(GraphState state, ExecutionContext ctx) throws InterruptException;
}

public interface ExecutionContext {
    LlmClient getLlmClient();
    ToolCallbackRegistry getTools();
    ChatMemory getMemory();
    String getTaskId();
    String getUserId();
    void emit(TranscriptEvent event);
    boolean isCancelled();
}
```

### Edge + ConditionalEdge

```java
public class Edge {
    private final String from;
    private final String to;
}

public class ConditionalEdge {
    private final String from;
    private final EdgeCondition condition;
    private final Map<String, String> routing;
}

@FunctionalInterface
public interface EdgeCondition {
    String route(GraphState state);
}
```

### StateGraph + CompiledGraph

```java
public class StateGraph {
    public StateGraph addNode(String name, Node node);
    public StateGraph addEdge(String from, String to);
    public StateGraph addConditionalEdges(String from, EdgeCondition condition, Map<String, String> routing);
    public StateGraph setEntryPoint(String entry);
    public CompiledGraph compile();  // 验证图完整性
}

public class CompiledGraph {
    public String getEntryPoint();
    public List<EdgeTarget> getEdgesFrom(String node);
    public Map<String, Node> getNodes();
}
```

### GraphExecutor

```java
public class GraphExecutor {
    private final CheckpointStore checkpointStore;
    private final int maxTurns;

    public TaskResult execute(CompiledGraph graph, GraphState state, ExecutionContext ctx);
    public TaskResult resume(CompiledGraph graph, String checkpointId, ExecutionContext ctx);
}
```

执行循环:
1. 执行当前 Node
2. catch InterruptException → PAUSED + checkpoint
3. catch RuntimeException → FAILED + checkpoint
4. 保存 checkpoint（失败不阻塞）
5. 检查 cancel 信号 → CANCELLED
6. 条件路由到下一 Node
7. 无下一 Node → SUCCEEDED
8. 超过 maxTurns → TIMEOUT

### CheckpointStore

```java
public interface CheckpointStore {
    String save(String threadId, GraphState state);
    GraphState load(String checkpointId);
    List<CheckpointMetadata> list(String threadId);
    void delete(String checkpointId);
    void deleteByThread(String threadId);
}
```

### ReAct 图形态

```
entry → agent ──conditional──→ tools → agent (循环)
                  └──conditional──→ END
```

- entry: 构建 system prompt + 准备 messages
- agent: 调用 LLM（流式）+ 处理结构化输出
- tools: 执行工具 + 结果截断 + SSE 事件
- conditional: end_turn/no tools → END; tool_use → tools

## Section 2: SPI Building Blocks

### Advisor

```java
public interface Advisor {
    String getName();
    int getOrder();
    GraphState beforeNode(String nodeName, GraphState state, ExecutionContext ctx);
    GraphState afterNode(String nodeName, GraphState state, ExecutionContext ctx);
}

// AdvisorNode 包裹 Node
public class AdvisorNode implements Node {
    private final Node delegate;
    private final List<Advisor> advisors;  // 按 order 排序

    // before chain (正序) → delegate.execute() → after chain (逆序)
    // Advisor 异常不阻塞主流程
}
```

内置 Advisor:
- MessageChatMemoryAdvisor (order=100)
- RetrievalAugmentationAdvisor (order=200)
- SafeGuardAdvisor (order=50)
- CostBudgetAdvisor (order=300)
- MicrometerObservationAdvisor (order=10)

### Tool (@Tool + ToolCallback)

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name() default "";
    String description();
    boolean returnDirect() default false;
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String description();
    boolean required() default true;
}

public interface ToolCallback {
    String getName();
    String getDescription();
    String getJsonSchema();
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}

public interface ToolCallbackRegistry {
    void register(ToolCallback callback);
    void unregister(String toolName);
    List<ToolCallback> getAll();
    ToolCallback find(String toolName);
    String toToolDefinitionsJson();
}

// 自动发现 @Tool 方法
public class ToolCallbacks {
    public static ToolCallback[] from(Object target);
    public static ToolCallback[] from(Class<?> clazz);
}
```

### VectorStore + Embedding

```java
public interface VectorStore {
    void add(List<Document> documents);
    void delete(List<String> ids);
    List<Document> similaritySearch(SearchRequest request);
}

public interface EmbeddingModel {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}

public class Document {
    private String id;
    private String content;
    private Map<String, Object> metadata;
    private float[] embedding;
}

public class SearchRequest {
    private String query;
    private int topK = 4;
    private double similarityThreshold = 0.75;
    private String filterExpression;
}
```

### Modular RAG

```java
public interface QueryTransformer {
    String transform(String originalQuery, ExecutionContext ctx);
}

public interface DocumentRetriever {
    List<Document> retrieve(String query, int topK);
}

public interface QueryAugmenter {
    String augment(String originalQuery, List<Document> retrievedDocs);
}

// RetrievalAugmentationAdvisor 组合三者
// beforeNode("agent"): transform → retrieve → augment → 注入 state["rag.context"]
```

### StructuredOutputConverter (多格式)

```java
public interface StructuredOutputConverter<T> {
    String getFormat();
    T convert(String llmOutput);
    String getContentType();
}

// JSON 输出
public class BeanOutputConverter<T> implements StructuredOutputConverter<T> {
    // Java class → JSON Schema → Jackson 反序列化
}

// HTML 输出 (anchor injection)
public class HtmlOutputConverter implements StructuredOutputConverter<String> {
    private final String containerClass;     // "snap-inject"
    private final String template;           // 可选 HTML 骨架
    private final boolean stripThinking;      // 过滤 LLM 思考前缀
    private final boolean sanitize;           // XSS 防护

    // getFormat() → HTML 结构指令
    // convert() → stripThinking + sanitize + 确保根 div 包裹
}

// Markdown 输出
public class MarkdownOutputConverter implements StructuredOutputConverter<String> { ... }
```

### ChatMemory

```java
public interface ChatMemory {
    void add(String conversationId, Message message);
    List<Message> get(String conversationId, int lastN);
    void clear(String conversationId);
}

public interface ChatMemoryRepository {
    void save(String conversationId, List<Message> messages);
    List<Message> load(String conversationId);
    void delete(String conversationId);
    List<String> listConversations(String userId);
}

// 滑动窗口 — 按 turn 边界驱逐，保留 SystemMessage
public class MessageWindowChatMemory implements ChatMemory {
    private final ChatMemoryRepository repository;
    private final int maxMessages = 20;
}
```

## Section 3: Starter Implementations

### Graph Nodes

- **EntryNode**: 构建 system prompt (只读前缀 + skill body) + 准备 messages + prompt 注入防御
- **AgentNode**: LLM 流式调用 + 工具定义 + RAG 上下文 + 结构化输出解析
- **ToolsNode**: 工具执行 + 结果截断 + SSE 事件 + 错误回传 LLM
- **ShouldContinue**: 条件路由 (end_turn → END, tool_use → tools)

### ReActGraphFactory

skill + task + advisors → CompiledGraph (entry → agent ↔ tools → END)

### Checkpoint Implementations

- SqliteCheckpointStore: 零依赖，开发环境
- RedisCheckpointStore: 生产环境，TTL 7 天，复用宿主 Redis

### VectorStore + Embedding Implementations

- RedisVectorStore: 复用宿主 Redis
- JdbcVectorStore: 复用宿主 MySQL
- OpenAIEmbeddingModel: text-embedding-3-small
- OllamaEmbeddingModel: 本地部署

### ETL Pipeline

KnowledgeETLPipeline: Markdown → DocumentReader → TokenTextSplitter → VectorStore.add

### Controller API

- POST /runs — 创建并执行 (构建图 + 初始化 state + 异步执行)
- GET /runs/{id}/stream — SSE 流式 (不变)
- POST /runs/{id}/resume — 从 checkpoint 恢复 (HITL)
- POST /runs/{id}/interrupt — 请求暂停 (HITL)
- GET /runs/{id}/checkpoints — 历史 checkpoint 列表
- POST /runs/{id}/replay — 从指定 checkpoint 回放

### TaskStatus

PENDING → RUNNING → { SUCCEEDED | FAILED | TIMEOUT | CANCELLED | PAUSED }

### HITL Flow

1. ToolsNode 检测 @ToolApproval(required=true) → 抛出 InterruptException
2. GraphExecutor 存 checkpoint → TaskStatus.PAUSED → SSE 推送 "paused" 事件
3. 人工 POST /runs/{id}/resume + { humanInput: "approved" }
4. GraphExecutor.resume() → 注入 human.input → 继续执行

### AutoConfiguration

模块化开关，所有新功能默认关闭:
- snap-agent.checkpoint.type (sqlite|redis, 默认 sqlite)
- snap-agent.vectorstore.enabled (默认 false)
- snap-agent.rag.enabled (默认 false)
- snap-agent.memory.type (in-memory|jdbc, 默认 in-memory)
- snap-agent.cost.enabled (默认 false)

## Section 4: Differentiated Features Adaptation

### Patrol — 定时触发图执行

PatrolScheduler 内部用 ReActGraphFactory + GraphExecutor。告警收敛和 issue 闭环作为图执行后处理。

### Anchor — 三种图模式

- auto: 复用 ReAct 图，anchor 上下文作为 skill body
- off: 线性图 (summarize → answer)，用 HtmlOutputConverter
- inject: 线性图 (preprocess → generate → cache)，用 HtmlOutputConverter

### Issue Closure — 图终态节点

IssueClosureNode 作为可选终态节点，agent end_turn 后路由到此。

### Plugin Hot-Load — 产出 ToolCallback

PluginRegistry 保留 JAR 上传 + ClassLoader 隔离，产出 ToolCallback[] via ToolCallbacks.from()。

### Unchanged

PeerSseRelay, PeerRouter, RateLimiter, SecurityGateway, CodeGraph, LlmClient, SkillRegistry — 不变。

## Error Handling

| Scenario | Handling | TaskStatus |
|----------|----------|------------|
| LLM exception | catch → checkpoint → FAILED | FAILED |
| LLM error message | AgentNode writes to state | FAILED |
| Tool exception | catch → ToolResult.error → LLM self-correct | RUNNING |
| max_tokens truncation | mark truncated → continue | RUNNING |
| Max turns exceeded | loop exit | TIMEOUT |
| External cancel | check per turn | CANCELLED |
| InterruptException | catch → checkpoint → PAUSED | PAUSED |
| Advisor exception | AdvisorNode catch → continue | RUNNING |
| Checkpoint save failure | log + continue (degraded) | RUNNING |

## Testing Strategy

- core: 纯单元测试 (Mockito, 无 Spring)
- starter: 单元 + 集成 (MockMvc + H2)
- E2E: Testcontainers (完整图执行 + checkpoint 恢复 + HITL)

## Module Dependencies

- core: 零新外部依赖 (graph/advisor/tool SPI 纯接口)
- starter: 可选 sqlite-jdbc, micrometer-observation (复用现有 Redis/JDBC)

## TDD Spec Updates

7 个模块重写: 01-agent-engine, 03-tool-dispatcher, 06-knowledge, 07-workflow, 10-cost-security, 04-anchor-qa (partial), 11-host-integration (partial)
5 个微调: 02-skill-system, 05-anchor-inject, 08-patrol-alert, 09-plugin-mcp, 12-codegraph

## Architecture Deepening (Implemented 2026-07-28)

### Strong candidates (completed)
1. **Type-Safe GraphState Keys** — `StateKey<T>` typed constants replace raw string keys; compile-time type safety across all advisors and nodes.
2. **Extract AbstractStreamingLlmClient** — Template method pattern; shared OkHttp infrastructure in base class. `AnthropicLlmClient` and `OpenAiLlmClient` reduced to hook implementations.
3. **Decompose SnapAgentAutoConfiguration** — 1443-line god-class → 8 domain-specific `@Configuration` classes + thin main class (~210 lines, 83% reduction).

### Worth exploring candidates (completed)
4. **Consolidate RAG Pipeline** — Extracted `VectorStoreDocumentRetriever` and `IdentityQueryTransformer` named classes replacing inline lambdas. Similarity threshold now configurable.
5. **Extract MessagePartitioner** — `MessagePartitioner` interface in `core.memory`; `LastNMessagePartitioner` default (current behavior). `AgentNode` uses partitioner instead of inline assembly. `ReActGraphFactory` accepts optional custom partitioner.
7. **Move READ_ONLY_PREFIX Out of Core** — `SkillMode` enum (`READ_ONLY` / `READ_WRITE`); `SkillMeta.mode` field parsed from frontmatter. `EntryNode` only prepends guardrail when `mode=READ_ONLY`. Enables write-capable skills (auto-fix) without contradiction.

## References

- LangGraph vs SnapAgent comparison: `docs/research/langgraph-vs-snapagent-comparison.md`
- Spring AI vs SnapAgent comparison: `docs/research/spring-ai-vs-snapagent-comparison.md`
- LangGraph docs: https://langchain-ai.github.io/langgraph/
- Spring AI docs: https://docs.spring.io/spring-ai/reference/
