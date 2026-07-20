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
│  │  SnapAgentAutoConfiguration (conditional assembly entry)     │   │
│  │  - @ConditionalOnProperty / @ConditionalOnClass              │   │
│  │  - @ConditionalOnMissingBean (SPI replacement point)          │   │
│  └───────────────────────────┬───────────────────────────────────┘   │
│                              │                                        │
│  ┌───────────┐  ┌───────────┐  │  ┌────────────┐  ┌──────────────┐  │
│  │ Web Layer  │  │ LLM Impl  │  │  │ Built-in    │  │ Routing      │  │
│  │ Controller │  │ Anthropic │  │  │ Tools       │  │ Subsystem    │  │
│  │ Filter     │  │ OpenAI    │  │  │ Jdbc/Redis  │  │ PeerRouter   │  │
│  │ SSE        │  │ LlmClient │  │  │ Code/Metrics│  │ PeerSseRelay │  │
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
│  │ Agent       │ │ LLM SPI      │ │ Skill SPI    │ │ Tool SPI    │ │
│  │ Executor    │ │ LlmClient    │ │ SkillRegistry│ │ ToolDispatch│ │
│  │ TaskStore   │ │ LlmEventSink │ │ SkillLoader  │ │ ToolProvider│ │
│  │ RateLimiter │ │ LlmRequest   │ │ SkillMeta    │ │ ToolPlugin  │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ Security    │ │ Knowledge   │ │ CodeGraph    │ │ Issue       │ │
│  │ Gateway     │ │ KnowledgeBase│ │ CodeGraph    │ │ IssueStore  │ │
│  │ Principal   │ │ KnowledgeSrc │ │ Builder/Index│ │ IssueTracker│ │
│  │ Resolver    │ │ Searcher     │ │              │ │             │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐ │
│  │ Cost        │ │ Workflow     │ │ Conversation │ │ Patrol      │ │
│  │ CostTracker │ │ WorkflowEng │ │ ConversationSt│ │ AlertConverg│ │
│  │ CostStore   │ │ WorkflowDef │ │              │ │ PatrolSched │ │
│  └─────────────┘ └──────────────┘ └──────────────┘ └─────────────┘ │
│  ┌──────────────────────────────────────────────────────────────────┐│
│  │ SystemPromptExtender (Context Injection SPI)                     ││
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

### snap-agent-core

Pure logic layer containing all SPI interface definitions and core execution logic:

```
cn.watsontech.snapagent.core/
├── agent/        AgentExecutor, AgentTask, TaskStore, RateLimiter, SystemPromptExtender, TranscriptEvent
├── llm/          LlmClient, LlmEventSink, LlmRequest, Message, ToolDef, ToolUseBlock
├── skill/        SkillRegistry, SkillLoader, SkillMeta, InputSpec, SkillAvailability, Shortcut
├── tool/         ToolDispatcher, ToolProvider, ToolContext, ToolResult, ToolPlugin, AuditCallback
├── security/     SecurityGateway, PrincipalResolver, UserInfo, AuditStore, SecurityAuditLogger
├── knowledge/     KnowledgeBase, KnowledgeSource, KnowledgeSearcher, KnowledgeFragment, SearchResult
├── codegraph/    CodeGraph, CodeGraphBuilder, CodeGraphIndex, CodeGraphNode, CodeGraphEdge
├── issue/        IssueStore, IssueTracker, IssueClosure, IssueStatus, SolutionSuggester, VerificationRunner
├── cost/         CostTracker, CostStore, CostRecord, CostSummary
├── workflow/     WorkflowEngine, WorkflowDefinition, WorkflowStep, WorkflowResult, WorkflowStatus
├── conversation/ ConversationStore, Conversation, ConversationMessage, ConversationSummary
└── patrol/       AlertConverger, AnomalyEvent, AnomalyEventListener, PatrolScheduler, PatrolTask, BugfixSuggester
```

All third-party dependencies (Spring, Jackson, SnakeYAML) are marked as `optional`, allowing the core module to be reused in non-Spring environments.

### snap-agent-spring-boot-2x-starter

Spring Boot 2.x auto-configuration layer providing all built-in implementations:

```
cn.watsontech.snapagent.boot2x/
├── autoconfig/   SnapAgentAutoConfiguration, SnapAgentProperties
├── web/          SnapAgentController, SnapAgentFilter, AgentRequestContext, KnowledgeController, InternalTaskController
├── llm/          AnthropicLlmClient, OpenAiLlmClient
├── security/     SpringSecurityAdapter, ShiroAdapter, DefaultPrincipalResolver, InMemoryAuditStore
├── skill/        ClasspathSkillScanner, SkillHotReloader
├── tool/         JdbcQueryToolProvider, RedisReadToolProvider, CodeReaderToolProvider, ProjectStructureToolProvider,
│                 GitLogToolProvider, MetricsToolProvider, LogSearchToolProvider, TraceSearchToolProvider,
│                 ConfigReadToolProvider, CodePathGuard, SqlGuard, DataSourceRegistry, ObservabilityHttpClient,
│                 TimeRangeParser, ToolPluginRegistry, mcp/McpBootstrap, mcp/McpToolProvider
├── context/      ProjectContextExtender
├── knowledge/    MarkdownKnowledgeSource, SimpleKeywordSearcher, KnowledgeInjector
├── codegraph/    SimpleCodeGraphBuilder, InMemoryCodeGraphIndex, CodeGraphToolProvider
├── issue/        FileIssueStore, NoopIssueTracker, IssueClosureService, KnowledgeSedimentationExtractor,
│                 TemplateSolutionSuggester, SimpleVerificationRunner
├── cost/         FileCostStore, BudgetEnforcer, DefaultCostTracker, CostTrackingLlmClient, CostSummaryService, CostCalculator
├── workflow/     YamlWorkflowLoader, SimpleWorkflowEngine
├── conversation/ FileConversationStore
├── patrol/       DefaultAnomalyEventListener, InMemoryAlertConverger, ScheduledPatrolScheduler, TemplateBugfixSuggester
└── routing/      PeerRouter, NoopPeerRouter, StaticPeerRouter, K8sApiPeerRouter, HeadlessDnsPeerRouter, PeerSseRelay
```

Registered via `META-INF/spring.factories`:

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentAutoConfiguration
```

### snap-agent-client

Standalone REST API client SDK based on JDK HttpURLConnection, with no Spring dependency. Provides the `SnapAgentClient` class and DTOs (`SkillDto`, `RunRequest`, `RunResponse`, `RunStatus`, `TranscriptEvent`, `UserInfo`), with Basic Auth support.

### snap-agent-demo

Standalone Spring Boot demo application containing `DemoApplication`, `SecurityConfig`, and `EchoToolProvider`, used for E2E testing (`e2e-local.sh` host-direct, `e2e-docker.sh` containerized).

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

`LlmEventSink` is the event callback interface through which AgentExecutor receives LLM streaming output:

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

- **Default implementations**: `AnthropicLlmClient` (Anthropic Messages API + SSE parsing), `OpenAiLlmClient` (OpenAI-compatible API)
- **Extension point**: Implement `LlmClient` + register as a Bean to integrate Tongyi/Wenxin/Zhipu and other compatible APIs

### 3.2 SkillRegistry / SkillLoader — Two-Layer Skill System

`SkillRegistry` manages a merged cache of built-in skills (classpath, read-only) and custom skills (filesystem, read-write):

```java
public class SkillRegistry {
    SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills, ToolDispatcher dispatcher);

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
- **Contract validation**: `SkillRegistry` validates at startup whether the tools declared by a skill are registered in `ToolDispatcher`; missing tools degrade the skill to UNAVAILABLE

### 3.3 ToolDispatcher / ToolProvider — Tool Plugin Architecture

```java
public interface ToolProvider {
    String name();                                         // Unique tool name
    String schema();                                       // JSON Schema (Anthropic tool format)
    ToolResult execute(Map<String, Object> args, ToolContext ctx); // Execute tool call
}
```

`ToolDispatcher` routes tool calls by name, collecting all `ToolProvider` Beans:

```java
public class ToolDispatcher {
    ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars);

    Set<String> availableToolNames();               // Registered tool names
    ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx); // Route execution
    String buildToolDefinitions();                   // Tool definition list (for system prompt)
}
```

- **Auto-discovery**: Any `ToolProvider` + `@Component` Bean is automatically collected by `ToolDispatcher`
- **Truncation protection**: Results exceeding `maxToolResultChars` are auto-truncated with a `[truncated, total N rows]` annotation
- **Audit callback**: `ToolContext` carries an `AuditCallback`; audit records are written asynchronously after each tool execution
- **ToolPlugin metadata SPI** (v1.0): Provides name/version/description/toolNames metadata, exposed via `GET /tools/plugins`, does not affect tool discovery

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

### 3.5 KnowledgeBase / KnowledgeSource / KnowledgeSearcher — Business Knowledge

```java
public interface KnowledgeSource {
    List<KnowledgeFragment> load();  // Load knowledge fragments
    void reload();                    // Hot reload
    String type();                    // Source type identifier
}

public interface KnowledgeSearcher {
    double score(String query, KnowledgeFragment fragment); // [0.0, 1.0] relevance score
}

public class KnowledgeBase {
    KnowledgeBase(List<KnowledgeSource> sources, KnowledgeSearcher searcher);

    List<KnowledgeFragment> search(String query, int topK, double minScore);
    List<SearchResult> searchWithScores(String query, int topK, double minScore);
    void reload();  // Reload all sources
    int size();     // Total cached fragments
}
```

- **Default implementations**: `MarkdownKnowledgeSource` (segments by `##` headings), `SimpleKeywordSearcher` (mixed Chinese/English tokenization + keyword overlap scoring)
- **Extension point**: Custom `KnowledgeSource` (database/Confluence/API) or `KnowledgeSearcher` (vector embedding semantic search)

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
}
```

- **Default implementations**: `SimpleCodeGraphBuilder` (regex-based Java source parsing), `InMemoryCodeGraphIndex` (bidirectional adjacency list)
- **Node types**: CLASS / METHOD / FIELD
- **Edge types**: CALLS / IMPLEMENTS / EXTENDS / DEPENDS_ON / OVERRIDES / REFERENCES
- **Extension point**: Implement `CodeGraphBuilder` with JavaParser AST; implement `CodeGraphIndex` with SQLite/H2 persistence

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

### 3.9 WorkflowEngine / WorkflowDefinition — Workflow Orchestration

```java
public interface WorkflowEngine {
    WorkflowResult execute(WorkflowDefinition workflow, Map<String, String> triggerInputs);
    String type();
}

public final class WorkflowDefinition {
    String getName();                          // Workflow name
    String getDescription();                    // Description
    List<WorkflowStep> getSteps();             // Step list (immutable)
}
```

- **Default implementations**: `SimpleWorkflowEngine` (sequential execution + conditional branching) + `YamlWorkflowLoader` (SnakeYAML parsing)
- **Condition syntax**: `${step.result != null}`, `${step.result.contains('text')}`, `${step.result.size > 0}`
- **Variable references**: `${trigger.xxx}` (trigger inputs), `${step.result}` (preceding step result)
- **Extension point**: Implement `WorkflowEngine` to support DAG parallelism/human approval

### 3.10 ConversationStore — Session History

```java
public interface ConversationStore {
    Conversation save(Conversation conversation);              // Save/update (auto-generates ID)
    Conversation load(String conversationId, String userId);  // Load (with ownership check)
    List<ConversationSummary> list(String userId, String skillId); // List (optional skill filter)
    boolean delete(String conversationId, String userId);      // Delete (with ownership check)
    String exportMarkdown(String conversationId, String userId); // Export as Markdown
}
```

- **Default implementation**: `FileConversationStore` (JSON storage in `{upload-skills-dir}/conversations/{userId}/`)
- **Ownership isolation**: All methods take a `userId` parameter to prevent cross-user access
- **Extension point**: Implement `ConversationStore` to store conversations in a database

### 3.11 SystemPromptExtender — Context Injection

```java
public interface SystemPromptExtender {
    String extend(SkillMeta skill, AgentTask task); // Returns context text to append to system prompt
}
```

AgentExecutor supports `List<SystemPromptExtender>` (v0.7), ordered by Spring `@Order`:

1. `ProjectContextExtender` (v0.3): Scans project structure at startup, injects module/Java file count/key directory summary
2. `KnowledgeInjector` (v0.7): Retrieves knowledge fragments from the knowledge base based on user query, injects matching business knowledge

Both work independently, each retrieving and injecting, then concatenated into the complete system prompt context.

---

## 4. Agent Execution Loop

`AgentExecutor` is SnapAgent's core execution engine, driving the interaction loop between the LLM and tools.

### Execution Flow

```
User request → POST /runs
    │
    ▼
AgentExecutor.execute(task, skill)
    │
    ├─ 1. Build system prompt
    │   ├─ READ_ONLY_PREFIX (read-only constraints + Phase troubleshooting instructions)
    │   ├─ skill.getName() + skill.getDescription()
    │   ├─ skill.getBody() (Phase troubleshooting body, {key} placeholders kept as references)
    │   ├─ INPUT_REF_INSTRUCTION (explains how {key} references are resolved)
    │   ├─ userId
    │   └─ Iterate List<SystemPromptExtender>.extend() → append context
    │
    ├─ 2. Build tools array (parse each ToolProvider.schema() JSON)
    │
    ├─ 3. Build messages (history messages + user input message)
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
│  6. llmClient.stream(req, collector, taskId)                          │
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
│  9. Tool calls                                                        │
│     ├─ messages.add(Message.assistant(thoughts, toolUseBlocks))      │
│     ├─ for each toolUse:                                              │
│     │   ├─ ToolDispatcher.dispatch(name, input, ctx)                │
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
| `/tools/plugins` | GET | List tool plugin metadata |
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

```java
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
public class SnapAgentAutoConfiguration { ... }
```

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
    private CodeGraph codeGraph; // Code graph (enabled, scan-packages, max-depth)
    private IssueClosure issueClosure; // Issue closure (enabled, system-user-id)
    private Cost cost;          // Cost accounting (enabled, pricing, budgets)
    private Workflows workflows; // Workflows (enabled, dir)
    private Skill skill;        // Skill hot reload
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
| Code Graph | `snap-agent.code-graph` | false | `@ConditionalOnProperty` + `@ConditionalOnBean(CodePathGuard)` |
| Issue Closure | `snap-agent.issue-closure` | false | `@ConditionalOnProperty` |
| Cost Accounting | `snap-agent.cost` | false | `@ConditionalOnProperty` |
| Workflows | `snap-agent.workflows` | false | `@ConditionalOnProperty` |
| MCP Tools | `snap-agent.mcp` | false | `@ConditionalOnProperty` |

### @ConditionalOnMissingBean Pattern

Nearly all default implementation Beans are annotated with `@ConditionalOnMissingBean`; hosts can declare a same-type Bean to replace them:

```java
// Default implementation — host can replace
@Bean
@ConditionalOnMissingBean
public ConversationStore conversationStore(SnapAgentProperties props) {
    return new FileConversationStore(props.getUploadSkillsDir());
}

// Host replaces with a database implementation
@Bean
public ConversationStore conversationStore(DataSource dataSource) {
    return new JdbcConversationStore(dataSource);
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
        return new OpenAiLlmClient(...);  // Tongyi/Wenxin/Zhipu and other compatible APIs
    }
    return new AnthropicLlmClient(...);    // Default: Anthropic
}
```

### AgentExecutor Assembly

```java
@Bean
@ConditionalOnMissingBean
public AgentExecutor agentExecutor(
        ObjectProvider<LlmClient> llmClientProvider,
        ToolDispatcher toolDispatcher,
        TaskStore taskStore,
        SnapAgentProperties props,
        ObjectProvider<SystemPromptExtender> extenderProvider,    // Collects all extenders
        ObjectProvider<CostTracker> costTrackerProvider,
        ObjectProvider<CostCalculator> costCalculatorProvider) {

    LlmClient llmClient = llmClientProvider.getIfAvailable();
    // When cost accounting is enabled, wrap original client with CostTrackingLlmClient
    if (llmClient != null && costTracker != null && props.getCost().isEnabled()) {
        llmClient = new CostTrackingLlmClient(llmClient, costTracker, costCalculator, ...);
    }
    // Collect all SystemPromptExtenders (ordered by @Order)
    List<SystemPromptExtender> extenders = extenderProvider.orderedStream().collect(...);
    return new AgentExecutor(llmClient, toolDispatcher, taskStore, maxTurns, maxTokens, extenders);
}
```

---

## 8. Version Roadmap

| Version | Deliverable | Key SPI/Components |
|---------|------------|-------------------|
| v0.1-alpha | Core SPI + LLM + basic tools | AgentExecutor, LlmClient, SkillRegistry, ToolDispatcher, JdbcQueryToolProvider, RedisReadToolProvider |
| v0.2 | Framework enhancements | SnapAgentFilter, AgentRequestContext, cross-pod routing subsystem (PeerRouter/PeerSseRelay) |
| v0.3 | Code understanding | SystemPromptExtender, CodePathGuard, code_read/project_structure/git_log tools, ProjectContextExtender |
| v0.4 | Ops diagnostics | ObservabilityHttpClient, TimeRangeParser, metrics_query/log_search/trace_search/config_read tools |
| v0.5 | Proactive monitoring & push | AlertConverger, AnomalyEventListener, ScheduledPatrolScheduler, patrol skills |
| v0.6 | Platform | DataSourceRegistry (multi-env), SkillMeta.requiredPermission (skill-level permissions), snap-agent-client REST SDK |
| v0.7 | Embedded knowledge base | KnowledgeBase, KnowledgeSource, KnowledgeSearcher, KnowledgeInjector (multi-SystemPromptExtender) |
| v0.8 | Code knowledge graph | CodeGraph, CodeGraphBuilder, CodeGraphIndex, CodeGraphToolProvider |
| v0.9 | Issue closure loop | IssueStore, IssueTracker, IssueClosureService, KnowledgeSedimentationExtractor |
| v1.0 | Plugins + workflows + cost | ToolPlugin, WorkflowEngine, CostTracker, CostTrackingLlmClient, LlmEventSink.onUsage() |
| v1.1 | Proactive monitoring SPI | PatrolReportStore interface (InMemoryPatrolReportStore), PatrolLockProvider (multi-Pod), AlertPushChannel (Webhook+Email defaults), KnowledgeBase.listAll()/`GET /knowledge/fragments`, ObservabilityHttpClient.httpPost() |

---

## 9. Known Limitations & Extension

### Known Limitations

| Limitation | Description |
|------------|-------------|
| In-memory only | TaskStore is backed by ConcurrentHashMap; all tasks and transcripts are lost on process restart |
| Java 8 + Spring Boot 2.x | Uses javax.servlet; does not support Spring Boot 3.x (jakarta.servlet) |
| No vector search | Knowledge base uses keyword overlap scoring by default; no embedding-based semantic search |
| Regex-based code graph | SimpleCodeGraphBuilder uses regex; comments may cause false positives, overloads are not distinguished, lambdas may be missed |
| No Spring Cloud dependency | Cross-pod routing self-implements K8s API/DNS discovery; does not depend on a service discovery framework |
| SSE limitation | EventSource does not support custom headers; SSE endpoint requires permitAll + token query param |
| Exact permission matching | SpringSecurityAdapter.hasPermission() matches authorities exactly; no wildcard/role inheritance support |
| Sequential workflows | SimpleWorkflowEngine executes sequentially; no parallelism/DAG/human approval support |

### SPI Extension Guide

| SPI | Default Implementation | How to Extend |
|-----|----------------------|---------------|
| `LlmClient` | AnthropicLlmClient / OpenAiLlmClient | Implement `LlmClient` + `@Component`; configure `api-type` to select |
| `ToolProvider` | JdbcQueryToolProvider etc. | Implement `ToolProvider` + `@Component`; auto-collected by ToolDispatcher |
| `SecurityGateway` | SpringSecurityAdapter / ShiroAdapter | Implement and declare as Bean; `@ConditionalOnMissingBean` takes effect |
| `PrincipalResolver` | DefaultPrincipalResolver | Implement `PrincipalResolver` + `@Component` |
| `KnowledgeSource` | MarkdownKnowledgeSource | Implement `KnowledgeSource` + `@Component` (database/API/Confluence) |
| `KnowledgeSearcher` | SimpleKeywordSearcher | Implement `KnowledgeSearcher` + `@Component` (vector embedding semantic search) |
| `CodeGraphBuilder` | SimpleCodeGraphBuilder | Implement `CodeGraphBuilder` + `@Component` (JavaParser AST) |
| `CodeGraphIndex` | InMemoryCodeGraphIndex | Implement `CodeGraphIndex` + `@Component` (SQLite/H2 persistence) |
| `IssueStore` | FileIssueStore | Implement `IssueStore` + `@Component` (database storage) |
| `IssueTracker` | NoopIssueTracker | Implement `IssueTracker` + `@Component` (Jira/GitHub Issues) |
| `CostStore` | FileCostStore | Implement `CostStore` + `@Component` (database storage) |
| `WorkflowEngine` | SimpleWorkflowEngine | Implement `WorkflowEngine` + `@Component` (DAG parallelism/human approval) |
| `ConversationStore` | FileConversationStore | Implement `ConversationStore` + `@Component` (database storage) |
| `SystemPromptExtender` | ProjectContextExtender / KnowledgeInjector | Implement `SystemPromptExtender` + `@Component` (custom context injection) |

All extensions take effect via the Spring `@ConditionalOnMissingBean` mechanism: Beans declared by the host take precedence over default implementations, with no need to modify SnapAgent source code.
