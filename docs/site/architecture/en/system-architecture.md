# SnapAgent System Architecture

> Version: v1.1 | Updated: 2026-07-20

## 1. Architecture Overview

SnapAgent is an **embeddable LLM diagnostic Agent library** that gives Spring Boot 2.x applications built-in read-only diagnostic capabilities. It is not a standalone Agent service, but runs as part of the host application, reusing the host's security framework, data sources, and configuration system to provide LLM-driven troubleshooting, code understanding, and operational diagnostics.

### Design Principles

- **Read-only safety**: All built-in tools perform read-only operations (SELECT queries, get/keys, log reads); the Agent system prompt enforces a strict ban on any write operations
- **SPI decoupling**: The core logic layer is pure interface definitions with no servlet/Spring dependencies; hosts can replace any SPI implementation
- **Zero intrusion**: Integrated via Spring Boot auto-configuration; the host only needs to add the dependency and set `snap-agent.enabled=true`
- **In-memory state**: TaskStore is backed by ConcurrentHashMap, process-level state that is lost on restart, suitable for stateless multi-instance deployment

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Host Spring Boot Application                      │
│  (Spring Security / Shiro auth, DataSource, RedisTemplate, ...)      │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ Auto-configuration (@ConditionalOnProperty)
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│              snap-agent-spring-boot-2x-starter                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │  8 Domain @Configuration Classes (conditional assembly)      │   │
│  │  Security / Tool / Web / Patrol / Knowledge /                │   │
│  │  Issue / Cost / Workflow                                       │   │
│  │  - @ConditionalOnProperty / @ConditionalOnClass              │   │
│  │  - @ConditionalOnMissingBean (SPI replacement point)          │   │
│  └───────────────────────────┬───────────────────────────────────┘   │
│                              │                                        │
│  ┌───────────┐  ┌───────────┐  │  ┌────────────┐  ┌──────────────┐  │
│  │ Web Layer  │  │ LLM Impl  │  │  │ Built-in    │  │ Routing      │  │
│  │ Controller │  │ AbstractSt │  │  │ @Tool       │  │ Subsystem    │  │
│  │ Filter     │  │ reaming   │  │  │ ToolCallbac │  │ PeerRouter   │  │
│  │ SSE        │  │ LlmClient  │  │  │ kRegistry   │  │ PeerSseRelay │  │
│  └─────┬─────┘  └─────┬─────┘  │  └──────┬─────┘  └──────┬───────┘  │
│        │              │        │         │               │           │
│        └──────────────┴────────┴─────────┴───────────────┘           │
│                     depends on ↓                                     │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                     snap-agent-core (Pure SPI Layer)                 │
│                                                                     │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ Graph       │ │ LLM SPI      │ │ Skill SPI    │ │ Tool SPI    │ │
│  │ Runtime     │ │ LlmClient    │ │ SkillRegistry│ │ @Tool       │ │
│  │ GraphExecut │ │ LlmEventSink │ │ SkillLoader  │ │ @ToolParam  │ │
│  │ StateGraph  │ │ LlmRequest   │ │ SkillMeta    │ │ ToolCallback│ │
│  │ ReActGraph  │ │ Message      │ │              │ │ ToolCallback│ │
│  │ Factory     │ │ Partitioner  │ │              │ │ Registry    │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ Security    │ │ RAG /        │ │ CodeGraph    │ │ Issue       │ │
│  │ Gateway     │ │ VectorStore  │ │ CodeGraph    │ │ IssueStore  │ │
│  │ Principal   │ │ VectorStoreD │ │ Builder/Index│ │ IssueTracker│ │
│  │ Resolver    │ │ ocRetriever  │ │              │ │             │ │
│  │             │ │ IdentityQry  │ │              │ │             │ │
│  │             │ │ Transformer  │ │              │ │             │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ Cost        │ │ Workflow     │ │ Memory       │ │ Patrol      │ │
│  │ CostTracker │ │ WorkflowEng  │ │ ChatMemory   │ │ AlertConverg│ │
│  │ CostStore   │ │ WorkflowDef  │ │ ChatMemoryRep│ │ PatrolSched │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │ Advisor SPI (context injection + interception, replaces SystemP.  ││
│  └──────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Module Structure

SnapAgent uses a multi-module Maven structure. The core principle is **separation of SPI layer from implementation layer**.

| Module | artifactId | Responsibility | Key Dependencies |
|--------|-----------|----------------|-----------------|
| Core | `snap-agent-core` | Pure SPI + execution loop, no servlet dependency | SLF4J, Jackson, SnakeYAML (all optional) |
| Implementation | `snap-agent-spring-boot-2x-starter` | Spring Boot auto-configuration + built-in implementations | `snap-agent-core`, Spring Web, javax.servlet, OkHttp, Spring Security (optional) |
| SDK | `snap-agent-client` | REST API client SDK (no Spring dependency) | HttpURLConnection |
| Demo | `snap-agent-demo` | Standalone Spring Boot demo app (E2E testing) | Spring Boot Starter Web + snap-agent-starter |
| Anchor Demo | `snap-agent-anchor-demo` | Anchor Q&A feature demo app (Thymeleaf SKU pages) | Spring Boot Starter Web + snap-agent-starter |

### snap-agent-core

Pure logic layer containing all SPI interface definitions and core execution logic:

```
cn.watsontech.snapagent.core/
├── graph/        GraphExecutor, StateGraph, ReActGraphFactory, EntryNode, AgentNode, ToolsNode, StateKey<T>, StateKeys
├── execution/    AgentTask, TaskStore, RateLimiter, TranscriptEvent
├── llm/          LlmClient, LlmEventSink, LlmRequest, Message, ToolDef, ToolUseBlock, MessagePartitioner
├── skill/        SkillRegistry, SkillLoader, SkillMeta, InputSpec, SkillAvailability, Shortcut, SkillMode
├── tool/         @Tool, @ToolParam, ToolCallback, ToolCallbackRegistry, ToolContext, ToolResult, AuditCallback
├── security/     SecurityGateway, PrincipalResolver, UserInfo, AuditStore, SecurityAuditLogger
├── advisor/      Advisor (replaces SystemPromptExtender, provides context injection + interception)
├── memory/       ChatMemory, ChatMemoryRepository (session history SPI)
├── rag/          VectorStoreDocumentRetriever, IdentityQueryTransformer, DocumentReader, Chunker, QueryAugmenter (RAG pipeline + ETL SPI)
├── vectorstore/  VectorStore, EmbeddingModel, Document (vector store SPI + value object)
├── codegraph/    CodeGraph, CodeGraphBuilder, CodeGraphIndex, CodeGraphNode, CodeGraphEdge
├── issue/        IssueStore, IssueTracker, IssueClosure, IssueStatus, SolutionSuggester, VerificationRunner, SedimentationReviewer
├── cost/         CostTracker, CostStore, CostRecord, CostSummary
├── workflow/     WorkflowDefinition, WorkflowStep, WorkflowResult, WorkflowStatus (engine impl pluggable)
└── patrol/       AlertConverger, AnomalyEvent, AnomalyEventListener, PatrolScheduler, PatrolTask, BugfixSuggester
```

All third-party dependencies (Spring, Jackson, SnakeYAML) are marked as `optional`, allowing the core module to be reused in non-Spring environments.

### snap-agent-spring-boot-2x-starter

Spring Boot 2.x auto-configuration layer providing all built-in implementations:

```
cn.watsontech.snapagent.boot2x/
├── autoconfig/   8 domain @Configuration: SecurityConfig, ToolConfig, WebConfig, PatrolConfig,
│                 KnowledgeConfig, IssueConfig, CostConfig, WorkflowConfig + SnapAgentProperties
├── web/          SnapAgentController, SnapAgentFilter, AgentRequestContext, KnowledgeController, InternalTaskController
├── llm/          AnthropicLlmClient, OpenAiLlmClient (both extend AbstractStreamingLlmClient template method)
├── security/     SpringSecurityAdapter, ShiroAdapter, DefaultPrincipalResolver, InMemoryAuditStore
├── skill/        ClasspathSkillScanner, SkillHotReloader
├── tool/         @Tool-annotated built-in tools: JdbcQueryTool, RedisReadTool, CodeReaderTool, ProjectStructureTool,
│                 GitLogTool, MetricsTool, LogSearchTool, TraceSearchTool, ConfigReadTool,
│                 CodePathGuard, SqlGuard, DataSourceRegistry, ObservabilityHttpClient,
│                 TimeRangeParser, ToolCallbackRegistry, mcp/McpBootstrap, mcp/McpToolCallback
├── advisor/      ProjectContextAdvisor, KnowledgeAdvisor (replacing ProjectContextExtender / KnowledgeInjector)
├── knowledge/    MarkdownDocumentReader, HeadingChunker, KnowledgeETLPipeline, KnowledgeSedimentationService, VectorStoreDocumentRetriever, IdentityQueryTransformer
├── codegraph/    SimpleCodeGraphBuilder, InMemoryCodeGraphIndex, H2CodeGraphIndex, AsyncCodeGraphIndex, CodeGraphHotReloader, CodeGraphTools, CodeGraphCli
├── issue/        FileIssueStore, NoopIssueTracker, IssueClosureService, KnowledgeSedimentationService, AcceptAllSedimentationReviewer,
│                 TemplateSolutionSuggester, SimpleVerificationRunner
├── cost/         FileCostStore, BudgetEnforcer, DefaultCostTracker, CostTrackingLlmClient, CostSummaryService, CostCalculator
├── workflow/     YamlWorkflowLoader, SimpleWorkflowEngine
├── memory/       FileChatMemoryRepository (ChatMemory default implementation)
├── patrol/       DefaultAnomalyEventListener, InMemoryAlertConverger, ScheduledPatrolScheduler, TemplateBugfixSuggester,
│                 InMemoryPatrolReportStore, NoopPatrolLockProvider, WebhookAlertPushChannel, EmailAlertPushChannel
├── anchor/       AnchorOrchestrator, AnchorContext, AnchorContextSummarizer, AnchorSkillClassifier, AnchorSummaryCache
└── routing/      PeerRouter, NoopPeerRouter, StaticPeerRouter, K8sApiPeerRouter, HeadlessDnsPeerRouter, PeerSseRelay
```

Registered via `META-INF/spring.factories` (8 domain `@Configuration` classes):

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  cn.watsontech.snapagent.boot2x.autoconfig.SecurityConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.ToolConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.WebConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.PatrolConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.KnowledgeConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.IssueConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.CostConfig,\
  cn.watsontech.snapagent.boot2x.autoconfig.WorkflowConfig
```

### snap-agent-client

Standalone REST API client SDK based on JDK HttpURLConnection, with no Spring dependency. Provides the `SnapAgentClient` class and DTOs (`SkillDto`, `RunRequest`, `RunResponse`, `RunStatus`, `TranscriptEvent`, `UserInfo`), with Basic Auth support.

### snap-agent-demo

Standalone Spring Boot demo application containing `DemoApplication`, `SecurityConfig`, and `EchoTool` (using `@Tool` annotation), used for E2E testing (`e2e-local.sh` host-direct, `e2e-docker.sh` containerized).

---

## 3. Core SPI Layer

SnapAgent's core design is a set of decoupled SPI interfaces. Each SPI has a default implementation (in the starter module); hosts can replace them with custom implementations via `@ConditionalOnMissingBean`.

### 3.1 LlmClient — LLM Streaming Client

```java
public interface LlmClient {
    // Stream completion: send request to LLM gateway, events returned via sink
    void stream(LlmRequest req, LlmEventSink events, String taskId);

    // Cancel an in-progress streaming call (default no-op for backward compatibility)
    default void cancel(String taskId) {}

    // List available models (default returns empty list)
    default List<String> listModels() { return Collections.emptyList(); }
}
```

`LlmEventSink` is the event callback interface through which `GraphExecutor` receives LLM streaming output:

```java
public interface LlmEventSink {
    void onThought(String text);                                    // Assistant text fragment (thinking process)
    void onToolUse(String id, String name, Map<String, Object> input); // LLM requests a tool call
    void onToolResult(String toolUseId, String result);             // Tool result has been produced
    void onStop(String stopReason);                                // Generation stopped (end_turn / max_tokens)
    void onError(String message);                                   // Streaming error
    default void onUsage(long inputTokens, long outputTokens, long cacheReadTokens) {} // v1.0 token usage
}
```

- **Default implementations**: `AnthropicLlmClient` (Anthropic Messages API + SSE parsing), `OpenAiLlmClient` (OpenAI-compatible API) — both extend `AbstractStreamingLlmClient`, reusing the template method for streaming connect, retry, and cancel
- **Extension point**: Extend `AbstractStreamingLlmClient` + register as a Bean to integrate Tongyi/Wenxin/Zhipu and other compatible APIs

### 3.2 SkillRegistry / SkillLoader — Two-Layer Skill System

`SkillRegistry` manages a merged cache of built-in skills (classpath, read-only) and custom skills (filesystem, read-write):

```java
public class SkillRegistry {
    SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills, ToolCallbackRegistry registry);

    List<SkillMeta> all();           // All merged skills (custom overrides same-name builtin)
    SkillMeta get(String name);      // Lookup by name
    boolean isBuiltin(String name);  // Whether it is a built-in skill
    RefreshResult refresh();         // Re-scan upload directory, atomically replace cache
}
```

`SkillLoader` parses Markdown frontmatter into `SkillMeta`, using SnakeYAML `SafeConstructor` to prevent deserialization attacks:

```java
public class SkillLoader {
    SkillMeta parse(String content);  // --- YAML frontmatter --- + body → SkillMeta
}
```

`SkillMeta` key fields:

```java
public final class SkillMeta {
    private final String name;              // Skill name (unique identifier)
    private final String description;       // Description
    private final List<String> tools;       // List of dependent tool names
    private final List<InputSpec> inputs;   // Input parameter definitions
    private final List<Shortcut> shortcuts; // Shortcut messages
    private final String body;              // Skill body (Phase troubleshooting steps)
    private final SkillAvailability availability; // AVAILABLE / UNAVAILABLE / INVALID
    private final String source;           // "builtin" / "custom" / "host"
    private final boolean overridesBuiltin; // Whether custom skill overrides a same-name builtin
    private final String requiredPermission; // Skill-level permission code (empty = use global)
}
```

- **Merge logic**: Custom skills override built-in by name; deleting a custom skill automatically restores the builtin
- **Directory skills**: A subdirectory containing `SKILL.md` → the entire directory is one skill, only `SKILL.md` is parsed
- **Contract validation**: `SkillRegistry` validates at startup whether the tools declared by a skill are registered in `ToolCallbackRegistry`; missing tools degrade the skill to UNAVAILABLE

### 3.3 @Tool / @ToolParam + ToolCallback — Tool Plugin Architecture

Tools are declared via the `@Tool` annotation; parameters are described via `@ToolParam`. `ToolCallback` encapsulates tool metadata and execution logic; `ToolCallbackRegistry` handles registration and routing:

```java
@Target(ElementType.METHOD)
public @interface Tool {
    String name();                  // Unique tool name
    String description() default ""; // Description (for LLM to decide when to call)
}

@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String value();                 // Parameter name
    String description() default "";
    boolean required() default true;
}
```

`ToolCallbackRegistry` routes tool calls by name, scanning all methods annotated with `@Tool` and constructing `ToolCallback` instances:

```java
public class ToolCallbackRegistry {
    ToolCallbackRegistry(List<ToolCallback> callbacks, int maxToolResultChars);

    Set<String> availableToolNames();               // Registered tool names
    ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx); // Route execution
    String buildToolDefinitions();                   // Tool definition list (for system prompt)
}
```

- **Auto-discovery**: Any Bean method annotated with `@Tool` is automatically scanned and registered by `ToolCallbackRegistry`
- **Truncation protection**: Results exceeding `maxToolResultChars` are auto-truncated with a `[truncated, total N rows]` annotation
- **Audit callback**: `ToolContext` carries an `AuditCallback`; audit records are written asynchronously after each tool execution
- **StructuredOutputConverter** (new SPI): Used to convert tool return values into structured JSON Schema

### 3.4 SecurityGateway / PrincipalResolver — Permission Model

```java
public interface SecurityGateway {
    String currentUserId();             // Read authenticated user ID from security context
    boolean hasPermission(String code);  // Permission code check (empty code returns true)
}

public interface PrincipalResolver {
    String resolve(Object principal);    // Resolve security framework principal to userId
}
```

- **Default implementations**: `SpringSecurityAdapter` (`@ConditionalOnClass(SecurityContextHolder)`) and `ShiroAdapter` (`@ConditionalOnClass(SecurityUtils)`)
- **Authentication delegation**: SecurityGateway does not perform authentication; it only reads the already-authenticated principal
- **Permission check**: `hasPermission` iterates `GrantedAuthority` for exact matching (no wildcards)
- **Extension point**: Host declares a custom `SecurityGateway` Bean to replace (`@ConditionalOnMissingBean`)

### 3.5 RAG Pipeline — VectorStore / DocumentReader / Chunker / VectorStoreDocumentRetriever

Knowledge retrieval is composed of `VectorStore` + `EmbeddingModel` SPIs and `DocumentReader`, `Chunker`, `VectorStoreDocumentRetriever`, `IdentityQueryTransformer` forming the RAG pipeline:

```java
public interface VectorStore {
    List<Document> search(String query, int topK);  // Vector similarity search
    void add(List<Document> documents);             // Write vectors (formerly KnowledgeFragment)
    void reload();                                            // Reload
    int size();                                               // Total cached documents
}

public interface EmbeddingModel {
    float[] embed(String text);  // Text → embedding vector
}

public interface DocumentReader {
    List<Document> read(Path file);     // Read raw documents from file
    String supportedExtension();         // Supported file extension (e.g. "md")
}

public interface Chunker {
    List<Document> chunk(Document document);  // Split document into chunks
    String strategy();                         // Chunking strategy name (e.g. "heading")
}
```

- **ETL Pipeline**: `KnowledgeETLPipeline` orchestrates `DocumentReader → Chunker → EmbeddingModel → VectorStore` flow, supporting single-file and directory batch processing
- **Default implementations**: `MarkdownDocumentReader` (reads .md files), `HeadingChunker` (splits by `##` headings), `VectorStoreDocumentRetriever` (keyword search), `IdentityQueryTransformer` (passes query through unchanged)
- **Retrieval flow**: `IdentityQueryTransformer` transforms query → `EmbeddingModel` embeds → `VectorStoreDocumentRetriever` retrieves → returns `Document` list (formerly `KnowledgeFragment` in v0.7)
- **Token estimation**: `DefaultQueryAugmenter.estimateTokens()` provides CJK-aware token estimation (CJK chars 1:1, English words 1:1), replacing the crude `chars / 3.5` heuristic
- **Extension points**: Custom `VectorStore` (Pinecone/Milvus/PGVector), `EmbeddingModel` (OpenAI/Zhipu Embedding), `DocumentReader` (PDF/HTML), `Chunker` (fixed-size/sentence-based)

### 3.6 CodeGraph / CodeGraphBuilder / CodeGraphIndex — Code Knowledge Graph

```java
public interface CodeGraphBuilder {
    CodeGraph build();  // Build code graph from source code
    String type();       // Parser type ("regex", "javaparser")
}

public interface CodeGraphIndex {
    List<CodeGraphNode> findByName(String namePattern);        // Fuzzy-find nodes
    List<CodeGraphEdge> getOutgoingEdges(String nodeId);        // Outgoing edges
    List<CodeGraphEdge> getIncomingEdges(String nodeId);        // Incoming edges
    List<CodeGraphNode> findCallChain(String methodId, int maxDepth);      // Forward call chain (BFS)
    List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth); // Reverse call chain
    List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);      // Impact scope analysis
    CodeGraphNode getNode(String id);
    int nodeCount();
    void rebuild(CodeGraphBuilder builder);  // Hot rebuild (triggered by WatchService)
}
```

- **Default implementations**:
  - `SimpleCodeGraphBuilder` (regex-based Java source parsing)
  - `InMemoryCodeGraphIndex` (bidirectional adjacency list, in-memory, default)
  - `H2CodeGraphIndex` (H2 file persistence, survives process restarts, for K8s/CI deployment)
  - `AsyncCodeGraphIndex` (async build wrapper, daemon thread build does not block startup)
  - `CodeGraphHotReloader` (WatchService watches `.java` changes, auto-triggers `rebuild()`)
- **Node types**: CLASS / METHOD / FIELD
- **Edge types**: CALLS / IMPLEMENTS / EXTENDS / DEPENDS_ON / OVERRIDES / REFERENCES
- **Persistence mode**: `persistence=memory` (default, in-memory) or `persistence=h2` (H2 file persistence)
- **CI/CD integration**: `CodeGraphCli` provides a CLI entry point for pre-building H2 files in CI pipelines; K8s loads directly from disk on startup (see Integration Guide)

### 3.7 IssueStore / IssueTracker — Issue Closure Loop

```java
public interface IssueStore {
    void save(IssueClosure issue);                    // Save (upsert)
    IssueClosure load(String issueId);                // Load
    IssueClosure findByTaskId(String taskId);          // Find by task ID
    List<IssueClosure> list();                        // List all
    List<IssueClosure> listByStatus(IssueStatus status); // Filter by status
    void delete(String issueId);
}

public interface IssueTracker {
    String createIssue(String title, String description, String assignee); // Create external issue
    void updateStatus(String externalIssueId, String status);              // Update status
    String getIssueUrl(String externalIssueId);                             // Issue URL
    String type();                                                         // Tracker type
}
```

- **Default implementations**: `FileIssueStore` (JSON file storage), `NoopIssueTracker` (no-op implementation)
- **Extension point**: Implement `IssueTracker` to integrate with Jira/GitHub Issues

### 3.8 CostTracker / CostStore — Cost Accounting

```java
public interface CostTracker {
    void record(CostRecord record);                                    // Record LLM call cost
    boolean isWithinBudget(String userId, String skillName);           // Budget check
    CostSummary getSummary(String dimension, String dimensionValue, long from, long to); // Cost summary
    String type();
}

public interface CostStore {
    void save(CostRecord record);                                      // Append write
    List<CostRecord> list(long from, long to);                         // List by time range
    List<CostRecord> listByUser(String userId, long from, long to);
    List<CostRecord> listBySkill(String skillName, long from, long to);
    BigDecimal sumCostByUser(String userId, long from, long to);
    BigDecimal sumCostBySkill(String skillName, long from, long to);
    BigDecimal sumCost(long from, long to);
    int countByUser(String userId, long from, long to);
    int countBySkill(String skillName, long from, long to);
    void deleteBefore(long timestamp);                                  // Purge expired records
}
```

- **Default implementations**: `DefaultCostTracker` + `FileCostStore` (JSON partitioned by date directory) + `BudgetEnforcer` (per-user/per-skill/global daily budgets)
- **Cost capture**: `CostTrackingLlmClient` decorates the original `LlmClient`, capturing token usage via `LlmEventSink.onUsage()`
- **Extension point**: Implement `CostStore` to store cost records in a database

### 3.9 WorkflowDefinition — Workflow Orchestration (engine impl pluggable)

```java
public final class WorkflowDefinition {
    String getName();                          // Workflow name
    String getDescription();                    // Description
    List<WorkflowStep> getSteps();             // Step list (immutable)
}
```

- **Default implementations**: `SimpleWorkflowEngine` (sequential execution + conditional branching, in starter module) + `YamlWorkflowLoader` (SnakeYAML parsing)
- **Condition syntax**: `${step.result != null}`, `${step.result.contains('text')}`, `${step.result.size > 0}`
- **Variable references**: `${trigger.xxx}` (trigger inputs), `${step.result}` (preceding step result)
- **Extension point**: Use graph runtime's `StateGraph` for DAG parallelism/human approval

### 3.10 ChatMemory / ChatMemoryRepository — Session History (new SPI, replaces ConversationStore)

```java
public interface ChatMemory {
    void add(String conversationId, Message message);              // Append message
    List<Message> get(String conversationId);                      // Load all messages
    void clear(String conversationId);                             // Clear
}

public interface ChatMemoryRepository {
    String save(Conversation conversation);                        // Save (auto-generates ID)
    Conversation load(String conversationId, String userId);      // Load (with ownership check)
    List<ConversationSummary> list(String userId, String skillId); // List
    boolean delete(String conversationId, String userId);          // Delete (with ownership check)
    String exportMarkdown(String conversationId, String userId);  // Export as Markdown
}
```

- **Default implementation**: `FileChatMemoryRepository` (JSON storage in `{upload-skills-dir}/conversations/{userId}/`)
- **Ownership isolation**: All methods take a `userId` parameter to prevent cross-user access
- **Extension point**: Implement `ChatMemoryRepository` to store conversations in a database

### 3.11 Advisor — Context Injection & Interception (replaces SystemPromptExtender)

```java
public interface Advisor {
    String advise(SkillMeta skill, AgentTask task); // Returns context text to append to system prompt
}
```

`GraphExecutor` supports `List<Advisor>`, ordered by Spring `@Order`:

1. `ProjectContextAdvisor` (replacing `ProjectContextExtender`): Scans project structure at startup, injects module/Java file count/key directory summary
2. `KnowledgeAdvisor` (replacing `KnowledgeInjector`): Retrieves knowledge fragments via the RAG pipeline based on user query, injects matching business knowledge

Both work independently, each retrieving and injecting, then concatenated into the complete system prompt context.

---

## 4. Agent Execution Loop

`GraphExecutor` is SnapAgent's core execution engine, based on `StateGraph` to drive the interaction loop between the LLM and tools. `ReActGraphFactory` constructs the graph structure; nodes include `EntryNode`, `AgentNode`, `ToolsNode`.

### Execution Flow

```
User request → POST /runs
    │
    ▼
GraphExecutor.execute(task, skill)  ← StateGraph built by ReActGraphFactory
    │
    ├─ 1. Build system prompt
    │   ├─ READ_ONLY_PREFIX or READ_WRITE_PREFIX (determined by SkillMode enum: READ_ONLY / READ_WRITE)
    │   ├─ skill.getName() + skill.getDescription()
    │   ├─ skill.getBody() (Phase troubleshooting body, {key} placeholders kept as references)
    │   ├─ INPUT_REF_INSTRUCTION (explains how {key} references are resolved)
    │   ├─ userId
    │   └─ Iterate List<Advisor>.advise() → append context
    │
    ├─ 2. Build tools array (parse each ToolCallback's JSON Schema from ToolCallbackRegistry)
    │
    ├─ 3. Build messages (via MessagePartitioner: partitioned history + user input message)
    │   └─ buildInputMessage(): <user_inputs>key=value</user_inputs> (injection-proof isolation)
    │
    ▼
┌─ for (turn = 0; turn < maxTurns; turn++) ─────────────────────────────┐
│                                                                       │
│  4. Check cancellation status (task.getStatus() == CANCELLED)         │
│                                                                       │
│  5. Create TurnCollector (implements LlmEventSink)                    │
│     └─ onThought → real-time push to transcript (token-by-token)      │
│     └─ onToolUse → collect tool_use blocks                           │
│     └─ onStop → record stopReason                                     │
│     └─ onError → record errorMessage                                  │
│                                                                       │
│  6. AgentNode → llmClient.stream(req, collector, taskId)             │
│                                                                       │
│  7. Error handling                                                    │
│     ├─ CANCELLED → record "task cancelled", return                    │
│     ├─ errorMessage != null → record error, FAILED, return            │
│     └─ max_tokens truncation → append partial thoughts, execute       │
│        tools, continue to next turn                                   │
│                                                                       │
│  8. Termination check                                                 │
│     └─ stopReason == "end_turn" || toolUses.isEmpty()                │
│        → task.setReport(thoughts), SUCCEEDED, done event, return      │
│                                                                       │
│  9. ToolsNode → Tool calls                                           │
│     ├─ messages.add(Message.assistant(thoughts, toolUseBlocks))      │
│     ├─ for each toolUse:                                              │
│     │   ├─ ToolCallbackRegistry.dispatch(name, input, ctx)         │
│     │   ├─ transcript.add(toolCall event)                            │
│     │   ├─ transcript.add(toolResult event)                          │
│     │   └─ messages.add(Message.toolResult(id, serializedResult))   │
│     └─ Continue to next turn                                         │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
    │
    ▼ (maxTurns reached)
  task.setStatus(TIMEOUT), done event
```

### Key Design Decisions

**Prompt injection prevention**: User input values are not substituted into the system prompt. Instead, they are placed inside `<user_inputs>` tags in the first user message. The system prompt instructs the LLM to treat `<user_inputs>` tag content as data, not instructions.

```java
String buildInputMessage(Map<String, String> inputs) {
    StringBuilder sb = new StringBuilder();
    sb.append("<user_inputs>\n");
    for (Map.Entry<String, String> entry : inputs.entrySet()) {
        sb.append(entry.getKey()).append("=").append(sanitizeInput(entry.getValue())).append("\n");
    }
    sb.append("</user_inputs>\n");
    sb.append("Please begin the diagnosis.");
    return sb.toString();
}
```

**Real-time streaming**: `TurnCollector.onThought()` pushes each token delta immediately to `task.getTranscript()`, and the SSE polling thread forwards it to the browser, enabling token-by-token thinking process display.

**max_tokens recovery**: When `stopReason == "max_tokens"`, the partial thoughts and collected tool_use blocks are appended to messages, and the next turn allows the LLM to resume from where it was interrupted.

**Cancellation support**: Before each loop iteration, `task.getStatus() == CANCELLED` is checked; cancellation status is also checked during LLM streaming errors to ensure timely cancellation.

---

## 5. Web Layer Architecture

### SnapAgentController

The main controller, mounted under `${snap-agent.base-path:/snap-agent}`, provides the full REST API:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth-config` | GET | Returns authentication configuration info |
| `/user-info` | GET | Current user info + active profiles |
| `/skills` | GET | List all skills |
| `/skills/refresh` | POST | Re-scan skill directory |
| `/skills/{name}` | DELETE | Delete custom skill (built-in cannot be deleted) |
| `/skills/upload` | POST | Upload a single skill file |
| `/skills/upload-folder` | POST | Upload skill directory (multiple files) |
| `/tools` | GET | List registered tools |
| `/tools/plugins` | GET | List tool callback metadata (`@Tool` annotation info) |
| `/models` | GET | List available LLM models |
| `/runs` | POST | Create and start a diagnostic task |
| `/runs` | GET | List tasks |
| `/runs/{id}` | GET | Get task details |
| `/runs/{id}/transcript` | GET | Get full transcript |
| `/runs/{id}/report` | GET | Get diagnostic report |
| `/runs/{id}/stream` | GET (SSE) | Real-time streaming subscription to transcript |
| `/runs/{id}/cancel` | POST | Cancel a running task |
| `/audit` | GET | Query audit records |
| `/conversations` | POST | Save conversation |
| `/conversations` | GET | List conversations (optional skill filter) |
| `/conversations/{id}` | GET | Load conversation |
| `/conversations/{id}/download` | GET | Download conversation as Markdown |
| `/conversations/{id}` | DELETE | Delete conversation |
| `/runs/{taskId}/solution` | POST | Submit solution suggestion |
| `/runs/{taskId}/issue` | POST | Create external issue |
| `/issues/{issueId}` | GET | Get issue details |
| `/issues/{issueId}/verify` | POST | Verify fix |
| `/issues/{issueId}/close` | POST | Close issue (sediment knowledge) |
| `/cost/summary` | GET | Global cost summary |
| `/cost/users/{userId}/summary` | GET | Cost summary by user |
| `/cost/skills/{skillName}/summary` | GET | Cost summary by skill |
| `/workflows` | GET | List workflow definitions |
| `/workflows/{name}` | GET | Get workflow details |
| `/workflows/{name}/run` | POST | Execute workflow |
| `/patrol/tasks` | POST/GET | Patrol task management |
| `/patrol/tasks/{id}` | DELETE | Delete patrol task |
| `/patrol/reports` | GET | Patrol report list |
| `/patrol/reports/{id}` | GET | Patrol report details |
| `/alerts` | GET | Alert list |
| `/alerts/{id}/resolve` | POST | Resolve alert |
| `/runs/{id}/bugfix-suggestion` | POST | Bugfix suggestion |
| `/anchor.js` | GET | Anchor Q&A client script (static asset) |
| `/anchor/config` | GET | Anchor feature config (public) |
| `/anchor/preprocess` | POST | Pre-summarize + pre-classify on anchor click |

### KnowledgeController

Standalone controller (`@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled")`):

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/knowledge/status` | GET | Knowledge base status (fragment count/config) |
| `/knowledge/search?q={query}` | GET | Keyword search for knowledge fragments |

### SnapAgentFilter

javax.servlet Filter that injects the authenticated user into a ThreadLocal:

```java
public class SnapAgentFilter implements Filter {
    // Intercepts ${basePath}/** requests
    // Reads user via SecurityGateway.currentUserId()
    // Sets into AgentRequestContext ThreadLocal
    // clear() in finally block (prevent thread pool leakage)
}
```

- Does not perform authentication — authentication is delegated to the host security framework
- Registration order `Ordered.LOWEST_PRECEDENCE - 10`, running after the host security filter chain

### AgentRequestContext

ThreadLocal context, valid only on the HTTP thread:

```java
public final class AgentRequestContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<String>();

    public static String getUserId();
    public static void setUserId(String userId);
    public static void clear();  // Must be called at the end of each request
}
```

> Note: The Agent execution loop runs on the `taskExecutor` thread pool, where ThreadLocal does not propagate. Tool providers and the executor must obtain user identity from `ToolContext`, not from `AgentRequestContext`.

### SSE Streaming

The `GET /runs/{id}/stream` endpoint implements real-time transcript push:

```
Browser EventSource → GET /runs/{id}/stream?token=base64(user:pass)
    │
    ▼
SnapAgentController.streamRun()
    ├─ Auth check (token query param or SecurityGateway)
    ├─ Task lookup (local taskStore)
    │   └─ Not found → PeerSseRelay.tryRelay() cross-pod relay
    ├─ Ownership check (IDOR protection, token auth skips)
    └─ taskExecutor async:
        ├─ 1. Replay existing transcript (last 200 events, skip done/error types)
        ├─ 2. Poll task.getTranscript() for new events
        │   ├─ Per-event SSE event().name(type).data(payload)
        │   ├─ Skip "done"/"error" as SSE event names (prevents EventSource built-in error handling)
        │   ├─ "error" sent as "task_error" SSE event instead
        │   └─ Send comment("heartbeat") every 15s
        └─ 3. Terminal state → send "done" SSE event(data=status), complete()
```

### InternalTaskController

Internal inter-pod endpoints, mounted outside basePath at `${snap-agent.routing.internal-path:/snap-agent-internal}`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/tasks/{id}/probe` | GET | Probe whether this pod owns the task (200/404/401) |
| `/tasks/{id}/stream` | GET (SSE) | Internal SSE stream (only validates internal token) |

Only validates the `X-Skills-Agent-Internal-Token` header; no user authentication/ownership check (the originating pod has already done this).

---

## 6. Security & Permissions

### Security Architecture

```
Browser → Host security filter chain (Spring Security / Shiro)
    │
    │ Authentication complete, principal set in SecurityContextHolder
    ▼
SnapAgentFilter (LOWEST_PRECEDENCE - 10)
    │
    ├─ SecurityGateway.currentUserId()
    │   └─ SpringSecurityAdapter: SecurityContextHolder → principal → PrincipalResolver.resolve()
    ├─ AgentRequestContext.setUserId(userId)
    ▼
SnapAgentController
    ├─ Global permission: securityGateway.hasPermission(globalRequiredPermission)
    ├─ Skill-level permission: securityGateway.hasPermission(skill.getRequiredPermission())
    ├─ SSE: token query param = base64(user:pass) → skip ownership check
    └─ Tool execution: ToolContext carries userId
```

### SecurityGateway SPI

```java
public interface SecurityGateway {
    String currentUserId();             // Read authenticated user from security context
    boolean hasPermission(String code);  // Exact match against authorities
}
```

Two built-in adapters, auto-selected via `@ConditionalOnClass`:

- `SpringSecurityAdapter`: `SecurityContextHolder.getContext().getAuthentication()` → `PrincipalResolver.resolve(principal)`
- `ShiroAdapter`: `SecurityUtils.getSubject()` → principal resolution

### Permission Model

**Two-tier permissions**:

1. **Global permission**: Configured via `snap-agent.security.required-permission`, required by all skills by default
2. **Skill-level permission**: `SkillMeta.requiredPermission` (frontmatter `required-permission: code`); when non-empty, overrides the global permission

```yaml
snap-agent:
  security:
    required-permission: snap-agent:use  # Global permission code
```

```markdown
---
name: database-query
required-permission: snap-agent:db       # Skill-level permission (overrides global)
tools: [jdbc_query]
---
```

When `requiredPermission` is empty, the global permission is inherited (backward compatible).

### SSE Authentication

The `EventSource` API does not support custom headers, so the SSE endpoint is handled specially:

- `permitAll` on the SSE path
- Credentials passed via `?token=base64(user:pass)` query param
- Controller decodes to extract userId, skips ownership check (task ID itself is an unguessable random ID)

### Common Permission Issues

Enterprise projects may store permissions in a principal's custom field (e.g., `LoginUser.permissionList`) rather than in `GrantedAuthority`, causing `hasPermission()` to return false. Solution: the host declares a custom `SecurityGateway` Bean (extending `SpringSecurityAdapter`, overriding `hasPermission`).

---

## 7. Auto-Configuration

### Master Switch

The former `SnapAgentAutoConfiguration` god-class has been decomposed into 8 domain `@Configuration` classes, each responsible for one functional domain's Bean assembly. The master switch is held by `SecurityConfig`:

```java
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
public class SecurityConfig { ... }
```

The other 7: `ToolConfig`, `WebConfig`, `PatrolConfig`, `KnowledgeConfig`, `IssueConfig`, `CostConfig`, `WorkflowConfig`.

Default `snap-agent.enabled = false`; the starter has zero impact when on the classpath but not activated.

### Configuration Namespace

```java
@ConfigurationProperties(prefix = "snap-agent")
public class SnapAgentProperties {
    private boolean enabled = false;          // Master switch
    private String basePath = "/snap-agent";  // Controller path prefix
    private String builtinSkillsDir;          // Built-in skill classpath directory
    private String uploadSkillsDir;           // Upload skill filesystem directory
    // Feature config groups:
    private Llm llm;            // LLM connection (base-url, api-key, api-type, max-tokens)
    private Agent agent;        // Execution params (max-turns, max-concurrent-runs, max-tool-result-chars)
    private Jdbc jdbc;          // Data sources (datasources map, default-env)
    private Redis redis;        // Redis connection
    private Logs logs;          // Log reading
    private Security security;  // Security (required-permission, audit-log)
    private Routing routing;    // Cross-pod routing (mode, port, k8s-service-name)
    private Code code;          // Code understanding (enabled, project-root, allowed-extensions)
    private Metrics metrics;    // Prometheus metrics query
    private LogSearch logSearch; // Loki log search
    private Trace trace;        // Jaeger trace search
    private ConfigRead configRead; // Config reading
    private Patrol patrol;      // Patrol (enabled, schedule)
    private Knowledge knowledge; // Knowledge base (enabled, sources, max-fragments, min-score)
    private CodeGraph codeGraph; // Code graph (enabled, scan-packages, max-depth, persistence, h2-url, hot-reload-enabled)
    private IssueClosure issueClosure; // Issue closure (enabled, system-user-id)
    private Cost cost;          // Cost accounting (enabled, pricing, budgets)
    private Workflows workflows; // Workflows (enabled, dir)
    private Skill skill;        // Skill hot reload
    private Anchor anchor;      // Anchor Q&A (enabled, disabled-paths, max-context-chars, preprocess)
}
```

### Feature Toggles & Conditional Assembly

Each feature is independently controlled via `@ConditionalOnProperty`:

| Feature | Config Prefix | Default | Conditional Annotation |
|---------|--------------|---------|----------------------|
| JDBC Query | `snap-agent.jdbc` | false | `@ConditionalOnProperty(name = "enabled")` |
| Redis Read | `snap-agent.redis` | false | `@ConditionalOnProperty` + `@ConditionalOnClass(RedisTemplate)` |
| Log Read | `snap-agent.logs` | false | `@ConditionalOnProperty` |
| Code Understanding | `snap-agent.code` | false | `@ConditionalOnExpression(enabled=true AND project-root non-empty)` |
| Project Context Injection | `snap-agent.code` | true (matchIfMissing) | `@ConditionalOnBean(CodePathGuard)` + `@ConditionalOnProperty(context-injection)` |
| Metrics Query | `snap-agent.metrics` | false | `@ConditionalOnProperty` |
| Log Search | `snap-agent.log-search` | false | `@ConditionalOnProperty` |
| Trace Search | `snap-agent.trace` | false | `@ConditionalOnProperty` |
| Config Read | `snap-agent.config-read` | false | `@ConditionalOnProperty` |
| Patrol | `snap-agent.patrol` | false | `@ConditionalOnProperty` |
| Knowledge Base | `snap-agent.knowledge` | false | `@ConditionalOnProperty` |
| Code Graph | `snap-agent.code-graph` | false | `@ConditionalOnProperty` + `@ConditionalOnBean(CodePathGuard)`, `persistence=h2` enables H2 persistence, `hot-reload-enabled=true` enables hot reload |
| Issue Closure | `snap-agent.issue-closure` | false | `@ConditionalOnProperty` |
| Cost Accounting | `snap-agent.cost` | false | `@ConditionalOnProperty` |
| Workflows | `snap-agent.workflows` | false | `@ConditionalOnProperty` |
| MCP Tools | `snap-agent.mcp` | false | `@ConditionalOnProperty` |
| Anchor Q&A | `snap-agent.anchor` | true (matchIfMissing) | `@ConditionalOnProperty` |

### @ConditionalOnMissingBean Pattern

Nearly all default implementation Beans are annotated with `@ConditionalOnMissingBean`; hosts can declare a same-type Bean to replace them:

```java
// Default implementation — host can replace
@Bean
@ConditionalOnMissingBean
public ChatMemoryRepository chatMemoryRepository(SnapAgentProperties props) {
    return new FileChatMemoryRepository(props.getUploadSkillsDir());
}

// Host replaces with a database implementation
@Bean
public ChatMemoryRepository chatMemoryRepository(DataSource dataSource) {
    return new JdbcChatMemoryRepository(dataSource);
}
```

### LlmClient Selection

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnExpression("'${snap-agent.llm.api-key:}' != '' or '${snap-agent.llm.auth-token:}' != ''")
public LlmClient llmClient(SnapAgentProperties props) {
    String apiType = props.getLlm().getApiType();
    if ("openai".equalsIgnoreCase(apiType)) {
        return new OpenAiLlmClient(...);  // Tongyi/Wenxin/Zhipu, extends AbstractStreamingLlmClient
    }
    return new AnthropicLlmClient(...);    // Default: Anthropic, extends AbstractStreamingLlmClient
}
```

### GraphExecutor Assembly

```java
@Bean
@ConditionalOnMissingBean
public GraphExecutor graphExecutor(
        ObjectProvider<LlmClient> llmClientProvider,
        ToolCallbackRegistry toolCallbackRegistry,
        TaskStore taskStore,
        SnapAgentProperties props,
        ObjectProvider<Advisor> advisorProvider,    // Collects all advisors
        ObjectProvider<CostTracker> costTrackerProvider,
        ObjectProvider<CostCalculator> costCalculatorProvider) {

    LlmClient llmClient = llmClientProvider.getIfAvailable();
    // When cost accounting is enabled, wrap original client with CostTrackingLlmClient
    if (llmClient != null && costTracker != null && props.getCost().isEnabled()) {
        llmClient = new CostTrackingLlmClient(llmClient, costTracker, costCalculator, ...);
    }
    // Collect all Advisors (ordered by @Order, replacing SystemPromptExtender)
    List<Advisor> advisors = advisorProvider.orderedStream().collect(...);
    // ReActGraphFactory constructs StateGraph, injecting EntryNode / AgentNode / ToolsNode
    ReActGraphFactory factory = new ReActGraphFactory(llmClient, toolCallbackRegistry, maxTurns, maxTokens, advisors);
    return new GraphExecutor(factory, taskStore);
}
```

---

## 8. Version Roadmap

| Version | Deliverable | Key SPI/Components |
|---------|------------|-------------------|
| v0.1-alpha | Core SPI + LLM + basic tools | GraphExecutor, StateGraph, LlmClient, SkillRegistry, ToolCallbackRegistry, JdbcQueryTool, RedisReadTool |
| v0.2 | Framework enhancements | SnapAgentFilter, AgentRequestContext, cross-pod routing subsystem (PeerRouter/PeerSseRelay) |
| v0.3 | Code understanding | Advisor SPI, CodePathGuard, code_read/project_structure/git_log tools, ProjectContextAdvisor |
| v0.4 | Ops diagnostics | ObservabilityHttpClient, TimeRangeParser, metrics_query/log_search/trace_search/config_read tools |
| v0.5 | Proactive monitoring & push | AlertConverger, AnomalyEventListener, ScheduledPatrolScheduler, patrol skills |
| v0.6 | Platform | DataSourceRegistry (multi-env), SkillMeta.requiredPermission (skill-level permissions), snap-agent-client REST SDK |
| v0.7 | Embedded knowledge base | VectorStore, EmbeddingModel, VectorStoreDocumentRetriever, IdentityQueryTransformer, KnowledgeAdvisor (multi-Advisor) |
| v0.8 | Code knowledge graph | CodeGraph, CodeGraphBuilder, CodeGraphIndex, CodeGraphTool |
| v0.9 | Issue closure loop | IssueStore, IssueTracker, IssueClosureService, KnowledgeSedimentationService, SedimentationReviewer |
| v1.0 | Workflows + cost + annotated tools | WorkflowDefinition, CostTracker, CostTrackingLlmClient, LlmEventSink.onUsage(), @Tool/@ToolParam, ToolCallback |
| v1.1 | Proactive monitoring SPI + Anchor Q&A | PatrolReportStore interface (InMemoryPatrolReportStore), PatrolLockProvider (multi-Pod), AlertPushChannel (Webhook+Email defaults), VectorStore.listAll()/`GET /knowledge/fragments`, ObservabilityHttpClient.httpPost(), AnchorOrchestrator (page-section anchor Q&A + smart skill routing + pre-summary cache) |
| v1.2 | Graph runtime + architecture refactor | StateGraph, ReActGraphFactory, EntryNode, AgentNode, ToolsNode, StateKey<T>, StateKeys, MessagePartitioner, AbstractStreamingLlmClient, 8 domain @Configuration split, ChatMemory/ChatMemoryRepository, CheckpointStore, StructuredOutputConverter |

---

## 9. Known Limitations & Extension

### Known Limitations

| Limitation | Description |
|------------|-------------|
| In-memory only | TaskStore is backed by ConcurrentHashMap; all tasks and transcripts are lost on process restart |
| Java 8 + Spring Boot 2.x | Uses javax.servlet; does not support Spring Boot 3.x (jakarta.servlet) |
| Vector search requires EmbeddingModel | Default RAG pipeline uses keyword search; vector semantic search only available after plugging in an EmbeddingModel |
| Regex-based code graph | SimpleCodeGraphBuilder uses regex; comments may cause false positives, overloads are not distinguished, lambdas may be missed; H2 persistence + CI pre-build solves K8s no-source-code issue |
| No Spring Cloud dependency | Cross-pod routing self-implements K8s API/DNS discovery; does not depend on a service discovery framework |
| SSE limitation | EventSource does not support custom headers; SSE endpoint requires permitAll + token query param |
| Exact permission matching | SpringSecurityAdapter.hasPermission() matches authorities exactly; no wildcard/role inheritance support |
| Sequential workflows | SimpleWorkflowEngine executes sequentially; no parallelism/DAG/human approval support (use StateGraph for custom) |

### SPI Extension Guide

| SPI | Default Implementation | How to Extend |
|-----|----------------------|---------------|
| `LlmClient` | AnthropicLlmClient / OpenAiLlmClient (extend AbstractStreamingLlmClient) | Extend `AbstractStreamingLlmClient` + `@Component`; configure `api-type` to select |
| `ToolCallback` | Built-in `@Tool`-annotated tools | Annotate a Bean method with `@Tool` + `@ToolParam`; auto-scanned by `ToolCallbackRegistry` |
| `SecurityGateway` | SpringSecurityAdapter / ShiroAdapter | Implement and declare as Bean; `@ConditionalOnMissingBean` takes effect |
| `PrincipalResolver` | DefaultPrincipalResolver | Implement `PrincipalResolver` + `@Component` |
| `Advisor` | ProjectContextAdvisor / KnowledgeAdvisor | Implement `Advisor` + `@Component` (custom context injection and interception) |
| `VectorStore` | VectorStoreDocumentRetriever | Implement `VectorStore` + `@Component` (Pinecone/Milvus/PGVector) |
| `EmbeddingModel` | Built-in keyword matching (no vectors) | Implement `EmbeddingModel` + `@Component` (OpenAI/Zhipu Embedding) |
| `DocumentReader` | MarkdownDocumentReader | Implement `DocumentReader` + `@Component` (PDF/HTML formats) |
| `Chunker` | HeadingChunker | Implement `Chunker` + `@Component` (fixed-size/sentence chunking strategies) |
| `SedimentationReviewer` | AcceptAllSedimentationReviewer | Implement `SedimentationReviewer` (LLM quality check / human review gate) |
| `ChatMemoryRepository` | FileChatMemoryRepository | Implement `ChatMemoryRepository` + `@Component` (database storage) |
| `CodeGraphBuilder` | SimpleCodeGraphBuilder | Implement `CodeGraphBuilder` + `@Component` (JavaParser AST) |
| `CodeGraphIndex` | InMemoryCodeGraphIndex / H2CodeGraphIndex | Implement `CodeGraphIndex` + `@Component` (custom persistence backend) |
| `IssueStore` | FileIssueStore | Implement `IssueStore` + `@Component` (database storage) |
| `IssueTracker` | NoopIssueTracker | Implement `IssueTracker` + `@Component` (Jira/GitHub Issues) |
| `CostStore` | FileCostStore | Implement `CostStore` + `@Component` (database storage) |
| `CheckpointStore` | In-memory implementation | Implement `CheckpointStore` + `@Component` (StateGraph checkpoint persistence) |
| `StructuredOutputConverter` | Default JSON converter | Implement `StructuredOutputConverter` + `@Component` (custom tool return value structuring) |

All extensions take effect via the Spring `@ConditionalOnMissingBean` mechanism: Beans declared by the host take precedence over default implementations, with no need to modify SnapAgent source code.
