# SnapAgent 系统架构总览

> 版本：v1.1 | 更新日期：2026-07-20

## 1. 架构概览

SnapAgent 是一个**嵌入式 LLM 诊断 Agent 库**，让 Spring Boot 2.x 应用获得内嵌的只读诊断能力。它不是一个独立的 Agent 服务，而是作为宿主应用的一部分运行，复用宿主的安全框架、数据源和配置体系，为应用赋予 LLM 驱动的故障排查、代码理解和运营诊断能力。

### 设计原则

- **只读安全**：所有内置工具均为只读操作（SELECT 查询、get/keys、日志读取），Agent 系统提示强制禁止任何写操作
- **SPI 解耦**：核心逻辑层纯接口定义，无 servlet/Spring 依赖，宿主可替换任意 SPI 实现
- **零侵入**：通过 Spring Boot 自动装配接入，宿主只需添加依赖 + 配置 `snap-agent.enabled=true`
- **内存状态**：TaskStore 基于 ConcurrentHashMap，进程级状态，重启丢失，适合无状态多实例部署

### 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    宿主 Spring Boot 应用                             │
│  (Spring Security / Shiro 认证, DataSource, RedisTemplate, ...)     │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ 自动装配 (@ConditionalOnProperty)
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│              snap-agent-spring-boot-2x-starter                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │  SnapAgentAutoConfiguration (条件装配入口)                     │   │
│  │  - @ConditionalOnProperty / @ConditionalOnClass              │   │
│  │  - @ConditionalOnMissingBean (SPI 替换点)                     │   │
│  └───────────────────────────┬───────────────────────────────────┘   │
│                              │                                        │
│  ┌───────────┐  ┌───────────┐  │  ┌────────────┐  ┌──────────────┐  │
│  │ Web 层    │  │ LLM 实现  │  │  │ 内置工具    │  │ 路由子系统    │  │
│  │ Controller│  │ Anthropic │  │  │ Jdbc/Redis  │  │ PeerRouter   │  │
│  │ Filter    │  │ OpenAI    │  │  │ Code/Metrics│  │ PeerSseRelay │  │
│  │ SSE       │  │ LlmClient │  │  │ LogSearch   │  │              │  │
│  └─────┬─────┘  └─────┬─────┘  │  └──────┬─────┘  └──────┬───────┘  │
│        │              │        │         │               │           │
│        └──────────────┴────────┴─────────┴───────────────┘           │
│                     依赖 ↓                                           │
└─────────────────────────────┬───────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────────┐
│                     snap-agent-core (纯 SPI 层)                      │
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
│  │ SystemPromptExtender (上下文注入 SPI)                            ││
│  └──────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 模块划分

SnapAgent 采用多模块 Maven 结构，核心原则是 **SPI 层与实现层分离**。

| 模块 | artifactId | 职责 | 关键依赖 |
|------|-----------|------|---------|
| 核心层 | `snap-agent-core` | 纯 SPI + 执行循环, 无 servlet 依赖 | SLF4J, Jackson, SnakeYAML (均 optional) |
| 实现层 | `snap-agent-spring-boot-2x-starter` | Spring Boot 自动装配 + 内置实现 | `snap-agent-core`, Spring Web, javax.servlet, OkHttp, Spring Security (optional) |
| SDK | `snap-agent-client` | REST API 客户端 SDK (无 Spring 依赖) | HttpURLConnection |
| 演示 | `snap-agent-demo` | 独立 Spring Boot 演示应用 (E2E 测试) | Spring Boot Starter Web + snap-agent-starter |

### snap-agent-core

纯逻辑层，包含所有 SPI 接口定义和核心执行逻辑：

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

所有第三方依赖（Spring、Jackson、SnakeYAML）均标记为 `optional`，使得 core 模块可被非 Spring 环境复用。

### snap-agent-spring-boot-2x-starter

Spring Boot 2.x 自动装配层，提供所有内置实现：

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

通过 `META-INF/spring.factories` 注册自动装配：

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
cn.watsontech.snapagent.boot2x.autoconfig.SnapAgentAutoConfiguration
```

### snap-agent-client

独立的 REST API 客户端 SDK，基于 JDK HttpURLConnection，无 Spring 依赖。提供 `SnapAgentClient` 类和 DTO（`SkillDto`、`RunRequest`、`RunResponse`、`RunStatus`、`TranscriptEvent`、`UserInfo`），支持 Basic Auth 认证。

### snap-agent-demo

独立的 Spring Boot 演示应用，包含 `DemoApplication`、`SecurityConfig`、`EchoToolProvider`，用于 E2E 测试（`e2e-local.sh` 主机直跑、`e2e-docker.sh` 容器跑）。

---

## 3. 核心 SPI 层

SnapAgent 的核心设计是一组解耦的 SPI 接口。每个 SPI 有一个默认实现（在 starter 中），宿主可通过 `@ConditionalOnMissingBean` 替换为自定义实现。

### 3.1 LlmClient — LLM 流式客户端

```java
public interface LlmClient {
    // 流式补全：将请求发送到 LLM 网关，事件通过 sink 回传
    void stream(LlmRequest req, LlmEventSink events, String taskId);

    // 取消进行中的流式调用（默认空实现，向后兼容）
    default void cancel(String taskId) {}

    // 列出可用模型（默认返回空列表）
    default List<String> listModels() { return Collections.emptyList(); }
}
```

`LlmEventSink` 是事件回调接口，AgentExecutor 通过它接收 LLM 的流式输出：

```java
public interface LlmEventSink {
    void onThought(String text);                                    // 助手文本片段（思考过程）
    void onToolUse(String id, String name, Map<String, Object> input); // LLM 请求工具调用
    void onToolResult(String toolUseId, String result);             // 工具结果已产生
    void onStop(String stopReason);                                // 生成停止 (end_turn / max_tokens)
    void onError(String message);                                   // 流式错误
    default void onUsage(long inputTokens, long outputTokens, long cacheReadTokens) {} // v1.0 token 用量
}
```

- **默认实现**：`AnthropicLlmClient`（Anthropic Messages API + SSE 解析）、`OpenAiLlmClient`（OpenAI 兼容 API）
- **扩展点**：实现 `LlmClient` + 注册为 Bean 即可接入通义千问/文心/智谱等兼容 API

### 3.2 SkillRegistry / SkillLoader — 两层 Skill 系统

`SkillRegistry` 管理内置 skill（classpath，只读）和自定义 skill（文件系统，读写）的合并缓存：

```java
public class SkillRegistry {
    SkillRegistry(Path uploadDir, List<SkillMeta> builtinSkills, ToolDispatcher dispatcher);

    List<SkillMeta> all();           // 合并后的所有 skill（custom 覆盖同名 builtin）
    SkillMeta get(String name);      // 按名查找
    boolean isBuiltin(String name);  // 是否为内置 skill
    RefreshResult refresh();         // 重新扫描上传目录，原子替换缓存
}
```

`SkillLoader` 解析 Markdown frontmatter 为 `SkillMeta`，使用 SnakeYAML `SafeConstructor` 防止反序列化攻击：

```java
public class SkillLoader {
    SkillMeta parse(String content);  // --- YAML frontmatter --- + body → SkillMeta
}
```

`SkillMeta` 关键字段：

```java
public final class SkillMeta {
    private final String name;              // skill 名（唯一标识）
    private final String description;       // 描述
    private final List<String> tools;       // 依赖的工具名列表
    private final List<InputSpec> inputs;   // 输入参数定义
    private final List<Shortcut> shortcuts; // 快捷消息
    private final String body;              // skill 正文（Phase 排查步骤）
    private final SkillAvailability availability; // AVAILABLE / UNAVAILABLE / INVALID
    private final String source;           // "builtin" / "custom" / "host"
    private final boolean overridesBuiltin; // 自定义 skill 是否覆盖同名内置
    private final String requiredPermission; // skill 级权限码（空则用全局权限）
}
```

- **合并逻辑**：custom 按 name 覆盖 builtin；删除 custom 后 builtin 自动恢复
- **目录 skill**：子目录含 `SKILL.md` → 整目录是一个 skill，仅解析 `SKILL.md`
- **契约校验**：`SkillRegistry` 启动时校验 skill 声明的 tools 是否在 `ToolDispatcher` 中注册，缺失则降级为 UNAVAILABLE

### 3.3 ToolDispatcher / ToolProvider — 工具插件架构

```java
public interface ToolProvider {
    String name();                                         // 唯一工具名
    String schema();                                       // JSON Schema (Anthropic tool 格式)
    ToolResult execute(Map<String, Object> args, ToolContext ctx); // 执行工具调用
}
```

`ToolDispatcher` 按 name 路由工具调用，收集所有 `ToolProvider` Bean：

```java
public class ToolDispatcher {
    ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars);

    Set<String> availableToolNames();               // 已注册工具名
    ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx); // 路由执行
    String buildToolDefinitions();                   // 工具定义列表（用于 system prompt）
}
```

- **自动发现**：任何 `ToolProvider` + `@Component` Bean 会被 `ToolDispatcher` 自动收集
- **截断保护**：超过 `maxToolResultChars` 的结果自动截断并标注 `[truncated, total N rows]`
- **审计回调**：`ToolContext` 携带 `AuditCallback`，每次工具执行后异步记录审计
- **ToolPlugin 元数据 SPI**（v1.0）：提供 name/version/description/toolNames 元数据，通过 `GET /tools/plugins` 暴露，不影响工具发现

### 3.4 SecurityGateway / PrincipalResolver — 权限模型

```java
public interface SecurityGateway {
    String currentUserId();             // 当前认证用户 ID
    boolean hasPermission(String code);  // 权限码检查（空码返回 true）
}

public interface PrincipalResolver {
    String resolve(Object principal);    // 将安全框架 principal 解析为 userId
}
```

- **默认实现**：`SpringSecurityAdapter`（`@ConditionalOnClass(SecurityContextHolder)`）和 `ShiroAdapter`（`@ConditionalOnClass(SecurityUtils)`）
- **认证委托**：SecurityGateway 不做认证，只读取已认证的 principal
- **权限检查**：`hasPermission` 遍历 `GrantedAuthority` 做精确匹配（非通配符）
- **扩展点**：宿主声明自定义 `SecurityGateway` Bean 即可替换（`@ConditionalOnMissingBean`）

### 3.5 KnowledgeBase / KnowledgeSource / KnowledgeSearcher — 业务知识

```java
public interface KnowledgeSource {
    List<KnowledgeFragment> load();  // 加载知识片段
    void reload();                    // 热重载
    String type();                    // 源类型标识
}

public interface KnowledgeSearcher {
    double score(String query, KnowledgeFragment fragment); // [0.0, 1.0] 相关度评分
}

public class KnowledgeBase {
    KnowledgeBase(List<KnowledgeSource> sources, KnowledgeSearcher searcher);

    List<KnowledgeFragment> search(String query, int topK, double minScore);
    List<SearchResult> searchWithScores(String query, int topK, double minScore);
    void reload();  // 重新加载所有源
    int size();     // 缓存片段总数
}
```

- **默认实现**：`MarkdownKnowledgeSource`（按 `##` 分段）、`SimpleKeywordSearcher`（中英文混合分词 + 关键词重叠打分）
- **扩展点**：自定义 `KnowledgeSource`（数据库/Confluence/API）或 `KnowledgeSearcher`（向量嵌入语义检索）

### 3.6 CodeGraph / CodeGraphBuilder / CodeGraphIndex — 代码知识图谱

```java
public interface CodeGraphBuilder {
    CodeGraph build();  // 从源码构建代码图谱
    String type();       // 解析器类型 ("regex", "javaparser")
}

public interface CodeGraphIndex {
    List<CodeGraphNode> findByName(String namePattern);        // 模糊查找节点
    List<CodeGraphEdge> getOutgoingEdges(String nodeId);        // 出边
    List<CodeGraphEdge> getIncomingEdges(String nodeId);        // 入边
    List<CodeGraphNode> findCallChain(String methodId, int maxDepth);      // 正向调用链 (BFS)
    List<CodeGraphNode> findReverseCallChain(String methodId, int maxDepth); // 逆向调用链
    List<CodeGraphNode> findImpactScope(String nodeId, int maxDepth);      // 影响范围分析
    CodeGraphNode getNode(String id);
    int nodeCount();
}
```

- **默认实现**：`SimpleCodeGraphBuilder`（正则解析 Java 源码）、`InMemoryCodeGraphIndex`（双向邻接表）
- **节点类型**：CLASS / METHOD / FIELD
- **边类型**：CALLS / IMPLEMENTS / EXTENDS / DEPENDS_ON / OVERRIDES / REFERENCES
- **扩展点**：实现 `CodeGraphBuilder` 用 JavaParser AST 解析；实现 `CodeGraphIndex` 用 SQLite/H2 持久化

### 3.7 IssueStore / IssueTracker — 问题闭环

```java
public interface IssueStore {
    void save(IssueClosure issue);                    // 保存（upsert）
    IssueClosure load(String issueId);                // 加载
    IssueClosure findByTaskId(String taskId);          // 按 task ID 查找
    List<IssueClosure> list();                        // 列出全部
    List<IssueClosure> listByStatus(IssueStatus status); // 按状态过滤
    void delete(String issueId);
}

public interface IssueTracker {
    String createIssue(String title, String description, String assignee); // 创建外部 issue
    void updateStatus(String externalIssueId, String status);              // 更新状态
    String getIssueUrl(String externalIssueId);                             // issue URL
    String type();                                                         // tracker 类型
}
```

- **默认实现**：`FileIssueStore`（JSON 文件存储）、`NoopIssueTracker`（空实现）
- **扩展点**：实现 `IssueTracker` 对接 Jira/GitHub Issues

### 3.8 CostTracker / CostStore — 成本核算

```java
public interface CostTracker {
    void record(CostRecord record);                                    // 记录 LLM 调用成本
    boolean isWithinBudget(String userId, String skillName);           // 预算检查
    CostSummary getSummary(String dimension, String dimensionValue, long from, long to); // 成本汇总
    String type();
}

public interface CostStore {
    void save(CostRecord record);                                      // 追加写入
    List<CostRecord> list(long from, long to);                         // 按时间范围列出
    List<CostRecord> listByUser(String userId, long from, long to);
    List<CostRecord> listBySkill(String skillName, long from, long to);
    BigDecimal sumCostByUser(String userId, long from, long to);
    BigDecimal sumCostBySkill(String skillName, long from, long to);
    BigDecimal sumCost(long from, long to);
    int countByUser(String userId, long from, long to);
    int countBySkill(String skillName, long from, long to);
    void deleteBefore(long timestamp);                                  // 清理过期记录
}
```

- **默认实现**：`DefaultCostTracker` + `FileCostStore`（JSON 按日期分目录）+ `BudgetEnforcer`（per-user/per-skill/global daily 预算）
- **成本捕获**：`CostTrackingLlmClient` 装饰原始 `LlmClient`，通过 `LlmEventSink.onUsage()` 捕获 token 用量
- **扩展点**：实现 `CostStore` 用数据库存储成本记录

### 3.9 WorkflowEngine / WorkflowDefinition — 工作流编排

```java
public interface WorkflowEngine {
    WorkflowResult execute(WorkflowDefinition workflow, Map<String, String> triggerInputs);
    String type();
}

public final class WorkflowDefinition {
    String getName();                          // 工作流名
    String getDescription();                    // 描述
    List<WorkflowStep> getSteps();             // 步骤列表（不可变）
}
```

- **默认实现**：`SimpleWorkflowEngine`（顺序执行 + 条件分支）+ `YamlWorkflowLoader`（SnakeYAML 解析）
- **条件语法**：`${step.result != null}`、`${step.result.contains('text')}`、`${step.result.size > 0}`
- **变量引用**：`${trigger.xxx}`（触发输入）、`${step.result}`（前序步骤结果）
- **扩展点**：实现 `WorkflowEngine` 支持 DAG 并行/人工审批

### 3.10 ConversationStore — 会话历史

```java
public interface ConversationStore {
    Conversation save(Conversation conversation);              // 保存/更新（自动生成 ID）
    Conversation load(String conversationId, String userId);  // 加载（带归属校验）
    List<ConversationSummary> list(String userId, String skillId); // 列出（可选 skill 过滤）
    boolean delete(String conversationId, String userId);      // 删除（带归属校验）
    String exportMarkdown(String conversationId, String userId); // 导出 Markdown
}
```

- **默认实现**：`FileConversationStore`（JSON 存储在 `{upload-skills-dir}/conversations/{userId}/`）
- **归属隔离**：所有方法带 `userId` 参数，防止跨用户访问
- **扩展点**：实现 `ConversationStore` 用数据库存储

### 3.11 SystemPromptExtender — 上下文注入

```java
public interface SystemPromptExtender {
    String extend(SkillMeta skill, AgentTask task); // 返回要追加到 system prompt 的上下文文本
}
```

AgentExecutor 支持 `List<SystemPromptExtender>`（v0.7），按 Spring `@Order` 排序：

1. `ProjectContextExtender`（v0.3）：启动时扫描项目结构，注入模块/Java 文件数/关键目录摘要
2. `KnowledgeInjector`（v0.7）：从用户查询检索知识库，注入匹配的业务知识片段

两者独立工作，各自检索和注入，最后拼接为完整的 system prompt 上下文。

---

## 4. 执行循环

`AgentExecutor` 是 SnapAgent 的核心执行引擎，驱动 LLM 与工具的交互循环。

### 执行流程

```
用户请求 → POST /runs
    │
    ▼
AgentExecutor.execute(task, skill)
    │
    ├─ 1. 构建 system prompt
    │   ├─ READ_ONLY_PREFIX（只读约束 + Phase 排查指令）
    │   ├─ skill.getName() + skill.getDescription()
    │   ├─ skill.getBody()（Phase 排查步骤正文，{key} 占位符保留为引用）
    │   ├─ INPUT_REF_INSTRUCTION（说明 {key} 引用如何解析）
    │   ├─ userId
    │   └─ 遍历 List<SystemPromptExtender>.extend() → 追加上下文
    │
    ├─ 2. 构建 tools 数组（解析每个 ToolProvider.schema() JSON）
    │
    ├─ 3. 构建 messages（历史消息 + 用户输入消息）
    │   └─ buildInputMessage(): <user_inputs>key=value</user_inputs>（防注入隔离）
    │
    ▼
┌─ for (turn = 0; turn < maxTurns; turn++) ─────────────────────────────┐
│                                                                       │
│  4. 检查取消状态 (task.getStatus() == CANCELLED)                      │
│                                                                       │
│  5. 创建 TurnCollector (implements LlmEventSink)                     │
│     └─ onThought → 实时 push 到 transcript（逐 token 流式）            │
│     └─ onToolUse → 收集 tool_use blocks                              │
│     └─ onStop → 记录 stopReason                                      │
│     └─ onError → 记录 errorMessage                                   │
│                                                                       │
│  6. llmClient.stream(req, collector, taskId)                         │
│                                                                       │
│  7. 错误处理                                                           │
│     ├─ CANCELLED → 记录 "任务已取消", return                          │
│     ├─ errorMessage != null → 记录错误, FAILED, return                │
│     └─ max_tokens 截断 → 追加部分思考, 执行工具, continue 下一轮       │
│                                                                       │
│  8. 终止判断                                                           │
│     └─ stopReason == "end_turn" || toolUses.isEmpty()                │
│        → task.setReport(thoughts), SUCCEEDED, done 事件, return       │
│                                                                       │
│  9. 工具调用                                                           │
│     ├─ messages.add(Message.assistant(thoughts, toolUseBlocks))      │
│     ├─ for each toolUse:                                              │
│     │   ├─ ToolDispatcher.dispatch(name, input, ctx)                │
│     │   ├─ transcript.add(toolCall event)                            │
│     │   ├─ transcript.add(toolResult event)                          │
│     │   └─ messages.add(Message.toolResult(id, serializedResult))   │
│     └─ 继续下一轮                                                     │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
    │
    ▼ (maxTurns 达到)
  task.setStatus(TIMEOUT), done 事件
```

### 关键设计决策

**防提示注入**：用户输入值不替换到 system prompt 中，而是放在第一条 user 消息的 `<user_inputs>` 标签内。system prompt 指示 LLM 将 `<user_inputs>` 标签内容视为数据而非指令。

```java
String buildInputMessage(Map<String, String> inputs) {
    StringBuilder sb = new StringBuilder();
    sb.append("<user_inputs>\n");
    for (Map.Entry<String, String> entry : inputs.entrySet()) {
        sb.append(entry.getKey()).append("=").append(sanitizeInput(entry.getValue())).append("\n");
    }
    sb.append("</user_inputs>\n");
    sb.append("请开始诊断。");
    return sb.toString();
}
```

**实时流式**：`TurnCollector.onThought()` 每个 token delta 立即 push 到 `task.getTranscript()`，SSE 轮询线程转发到浏览器，实现逐 token 的思考过程展示。

**max_tokens 恢复**：当 `stopReason == "max_tokens"` 时，将部分思考和已收集的 tool_use 附加到 messages，继续下一轮让 LLM 从中断处恢复。

**取消支持**：每轮循环开始前检查 `task.getStatus() == CANCELLED`；LLM 流式异常时也检查取消状态，确保取消及时生效。

---

## 5. Web 层架构

### SnapAgentController

主控制器，挂载在 `${snap-agent.base-path:/snap-agent}` 下，提供完整的 REST API：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/auth-config` | GET | 返回认证配置信息 |
| `/user-info` | GET | 当前用户信息 + 活跃 profiles |
| `/skills` | GET | 列出所有 skill |
| `/skills/refresh` | POST | 重新扫描 skill 目录 |
| `/skills/{name}` | DELETE | 删除自定义 skill（builtin 不可删） |
| `/skills/upload` | POST | 上传单个 skill 文件 |
| `/skills/upload-folder` | POST | 上传 skill 目录（多文件） |
| `/tools` | GET | 列出已注册工具 |
| `/tools/plugins` | GET | 列出工具插件元数据 |
| `/models` | GET | 列出可用 LLM 模型 |
| `/runs` | POST | 创建并启动诊断任务 |
| `/runs` | GET | 列出任务 |
| `/runs/{id}` | GET | 获取任务详情 |
| `/runs/{id}/transcript` | GET | 获取完整 transcript |
| `/runs/{id}/report` | GET | 获取诊断报告 |
| `/runs/{id}/stream` | GET (SSE) | 实时流式订阅 transcript |
| `/runs/{id}/cancel` | POST | 取消正在运行的任务 |
| `/audit` | GET | 查询审计记录 |
| `/conversations` | POST | 保存会话 |
| `/conversations` | GET | 列出会话（可按 skill 过滤） |
| `/conversations/{id}` | GET | 加载会话 |
| `/conversations/{id}/download` | GET | 下载会话 Markdown |
| `/conversations/{id}` | DELETE | 删除会话 |
| `/runs/{taskId}/solution` | POST | 提交解决方案建议 |
| `/runs/{taskId}/issue` | POST | 创建外部 issue |
| `/issues/{issueId}` | GET | 获取 issue 详情 |
| `/issues/{issueId}/verify` | POST | 验证修复 |
| `/issues/{issueId}/close` | POST | 关闭 issue（沉淀知识） |
| `/cost/summary` | GET | 全局成本汇总 |
| `/cost/users/{userId}/summary` | GET | 按用户成本汇总 |
| `/cost/skills/{skillName}/summary` | GET | 按 skill 成本汇总 |
| `/workflows` | GET | 列出工作流定义 |
| `/workflows/{name}` | GET | 获取工作流详情 |
| `/workflows/{name}/run` | POST | 执行工作流 |
| `/patrol/tasks` | POST/GET | 巡检任务管理 |
| `/patrol/tasks/{id}` | DELETE | 删除巡检任务 |
| `/patrol/reports` | GET | 巡检报告列表 |
| `/patrol/reports/{id}` | GET | 巡检报告详情 |
| `/alerts` | GET | 告警列表 |
| `/alerts/{id}/resolve` | POST | 解决告警 |
| `/runs/{id}/bugfix-suggestion` | POST | 修复建议 |

### KnowledgeController

独立控制器（`@ConditionalOnProperty(prefix = "snap-agent.knowledge", name = "enabled")`）：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/knowledge/status` | GET | 知识库状态（片段数/配置） |
| `/knowledge/search?q={query}` | GET | 关键词搜索知识片段 |

### SnapAgentFilter

javax.servlet Filter，注入已认证用户到 ThreadLocal：

```java
public class SnapAgentFilter implements Filter {
    // 拦截 ${basePath}/** 请求
    // 通过 SecurityGateway.currentUserId() 读取用户
    // 设置到 AgentRequestContext ThreadLocal
    // finally 块中 clear()（防线程池泄漏）
}
```

- 不做认证——认证委托给宿主安全框架
- 注册顺序 `Ordered.LOWEST_PRECEDENCE - 10`，在宿主安全过滤链之后运行

### AgentRequestContext

ThreadLocal 上下文，仅在 HTTP 线程有效：

```java
public final class AgentRequestContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<String>();

    public static String getUserId();
    public static void setUserId(String userId);
    public static void clear();  // 每个请求结束必须调用
}
```

> 注意：Agent 执行循环运行在 `taskExecutor` 线程池上，ThreadLocal 不传播。工具提供者和执行器必须从 `ToolContext` 获取用户身份，不能从 `AgentRequestContext`。

### SSE 流式传输

`GET /runs/{id}/stream` 端点实现实时 transcript 推送：

```
浏览器 EventSource → GET /runs/{id}/stream?token=base64(user:pass)
    │
    ▼
SnapAgentController.streamRun()
    ├─ 认证检查（token query param 或 SecurityGateway）
    ├─ 任务查找（本地 taskStore）
    │   └─ 未命中 → PeerSseRelay.tryRelay() 跨 Pod 中继
    ├─ 归属检查（IDOR 防护，token auth 跳过）
    └─ taskExecutor 异步:
        ├─ 1. 重放已有 transcript（最近 200 条，跳过 done/error 类型）
        ├─ 2. 轮询 task.getTranscript() 新事件
        │   ├─ 逐事件 SSE event().name(type).data(payload)
        │   ├─ 跳过 "done"/"error" 作为 SSE event name（防 EventSource 内置错误处理）
        │   ├─ "error" 改发为 "task_error" SSE event
        │   └─ 每 15s 发 comment("heartbeat") 心跳
        └─ 3. 终端状态 → 发 "done" SSE event(data=status), complete()
```

### InternalTaskController

内部 Pod 间端点，挂载在 basePath 之外的 `${snap-agent.routing.internal-path:/snap-agent-internal}`：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/tasks/{id}/probe` | GET | 探测本 Pod 是否拥有该 task（200/404/401） |
| `/tasks/{id}/stream` | GET (SSE) | 内部 SSE 流（仅校验 internal token） |

仅校验 `X-Skills-Agent-Internal-Token` header，无用户认证/归属检查（发起 Pod 已做过）。

---

## 6. 安全与权限

### 安全架构

```
浏览器 → 宿主安全过滤链 (Spring Security / Shiro)
    │
    │ 认证完成，principal 设置到 SecurityContextHolder
    ▼
SnapAgentFilter (LOWEST_PRECEDENCE - 10)
    │
    ├─ SecurityGateway.currentUserId()
    │   └─ SpringSecurityAdapter: SecurityContextHolder → principal → PrincipalResolver.resolve()
    ├─ AgentRequestContext.setUserId(userId)
    ▼
SnapAgentController
    ├─ 全局权限: securityGateway.hasPermission(globalRequiredPermission)
    ├─ Skill 级权限: securityGateway.hasPermission(skill.getRequiredPermission())
    ├─ SSE: token query param = base64(user:pass) → 跳过 ownership check
    └─ 工具执行: ToolContext 携带 userId
```

### SecurityGateway SPI

```java
public interface SecurityGateway {
    String currentUserId();             // 从安全上下文读取已认证用户
    boolean hasPermission(String code);  // 精确匹配 authorities
}
```

两个内置适配器，通过 `@ConditionalOnClass` 自动选择：

- `SpringSecurityAdapter`：`SecurityContextHolder.getContext().getAuthentication()` → `PrincipalResolver.resolve(principal)`
- `ShiroAdapter`：`SecurityUtils.getSubject()` → principal 解析

### 权限模型

**两层权限**：

1. **全局权限**：`snap-agent.security.required-permission` 配置，所有 skill 默认要求
2. **Skill 级权限**：`SkillMeta.requiredPermission`（frontmatter `required-permission: code`），非空时覆盖全局权限

```yaml
snap-agent:
  security:
    required-permission: snap-agent:use  # 全局权限码
```

```markdown
---
name: database-query
required-permission: snap-agent:db       # skill 级权限（覆盖全局）
tools: [jdbc_query]
---
```

当 `requiredPermission` 为空时继承全局权限（向后兼容）。

### SSE 认证

`EventSource` API 不支持自定义 header，因此 SSE 端点特殊处理：

- `permitAll` 放行 SSE 路径
- 通过 `?token=base64(user:pass)` query param 传递凭证
- controller 解码后提取 userId，跳过 ownership check（task ID 本身是不可猜测的随机 ID）

### 常见权限问题

企业项目可能将权限存在 principal 自定义字段（如 `LoginUser.permissionList`）而非 `GrantedAuthority`，导致 `hasPermission()` 返回 false。解决方案：宿主声明自定义 `SecurityGateway` Bean（继承 `SpringSecurityAdapter`，覆盖 `hasPermission`）。

---

## 7. 自动装配

### 总开关

```java
@Configuration
@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
public class SnapAgentAutoConfiguration { ... }
```

默认 `snap-agent.enabled = false`，starter 在 classpath 但未激活时零影响。

### 配置命名空间

```java
@ConfigurationProperties(prefix = "snap-agent")
public class SnapAgentProperties {
    private boolean enabled = false;          // 总开关
    private String basePath = "/snap-agent";  // 控制器路径前缀
    private String builtinSkillsDir;          // 内置 skill classpath 目录
    private String uploadSkillsDir;           // 上传 skill 文件系统目录
    // 功能配置组:
    private Llm llm;            // LLM 连接 (base-url, api-key, api-type, max-tokens)
    private Agent agent;        // 执行参数 (max-turns, max-concurrent-runs, max-tool-result-chars)
    private Jdbc jdbc;          // 数据源 (datasources map, default-env)
    private Redis redis;        // Redis 连接
    private Logs logs;          // 日志读取
    private Security security;  // 安全 (required-permission, audit-log)
    private Routing routing;    // 跨 Pod 路由 (mode, port, k8s-service-name)
    private Code code;          // 代码理解 (enabled, project-root, allowed-extensions)
    private Metrics metrics;    // Prometheus 指标查询
    private LogSearch logSearch; // Loki 日志搜索
    private Trace trace;        // Jaeger 链路追踪
    private ConfigRead configRead; // 配置读取
    private Patrol patrol;      // 巡检 (enabled, schedule)
    private Knowledge knowledge; // 知识库 (enabled, sources, max-fragments, min-score)
    private CodeGraph codeGraph; // 代码图谱 (enabled, scan-packages, max-depth)
    private IssueClosure issueClosure; // 问题闭环 (enabled, system-user-id)
    private Cost cost;          // 成本核算 (enabled, pricing, budgets)
    private Workflows workflows; // 工作流 (enabled, dir)
    private Skill skill;        // skill 热重载
}
```

### 功能开关与条件装配

每个功能通过 `@ConditionalOnProperty` 独立控制：

| 功能 | 配置前缀 | 默认 | 条件注解 |
|------|---------|------|---------|
| JDBC 查询 | `snap-agent.jdbc` | false | `@ConditionalOnProperty(name = "enabled")` |
| Redis 读取 | `snap-agent.redis` | false | `@ConditionalOnProperty` + `@ConditionalOnClass(RedisTemplate)` |
| 日志读取 | `snap-agent.logs` | false | `@ConditionalOnProperty` |
| 代码理解 | `snap-agent.code` | false | `@ConditionalOnExpression(enabled=true AND project-root 非空)` |
| 项目上下文注入 | `snap-agent.code` | true (matchIfMissing) | `@ConditionalOnBean(CodePathGuard)` + `@ConditionalOnProperty(context-injection)` |
| Metrics 查询 | `snap-agent.metrics` | false | `@ConditionalOnProperty` |
| 日志搜索 | `snap-agent.log-search` | false | `@ConditionalOnProperty` |
| 链路追踪 | `snap-agent.trace` | false | `@ConditionalOnProperty` |
| 配置读取 | `snap-agent.config-read` | false | `@ConditionalOnProperty` |
| 巡检 | `snap-agent.patrol` | false | `@ConditionalOnProperty` |
| 知识库 | `snap-agent.knowledge` | false | `@ConditionalOnProperty` |
| 代码图谱 | `snap-agent.code-graph` | false | `@ConditionalOnProperty` + `@ConditionalOnBean(CodePathGuard)` |
| 问题闭环 | `snap-agent.issue-closure` | false | `@ConditionalOnProperty` |
| 成本核算 | `snap-agent.cost` | false | `@ConditionalOnProperty` |
| 工作流 | `snap-agent.workflows` | false | `@ConditionalOnProperty` |
| MCP 工具 | `snap-agent.mcp` | false | `@ConditionalOnProperty` |

### @ConditionalOnMissingBean 模式

几乎所有默认实现 Bean 都标注 `@ConditionalOnMissingBean`，宿主可声明同类型 Bean 替换：

```java
// 默认实现 — 宿主可替换
@Bean
@ConditionalOnMissingBean
public ConversationStore conversationStore(SnapAgentProperties props) {
    return new FileConversationStore(props.getUploadSkillsDir());
}

// 宿主替换为数据库实现
@Bean
public ConversationStore conversationStore(DataSource dataSource) {
    return new JdbcConversationStore(dataSource);
}
```

### LlmClient 选择

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnExpression("'${snap-agent.llm.api-key:}' != '' or '${snap-agent.llm.auth-token:}' != ''")
public LlmClient llmClient(SnapAgentProperties props) {
    String apiType = props.getLlm().getApiType();
    if ("openai".equalsIgnoreCase(apiType)) {
        return new OpenAiLlmClient(...);  // 通义/文心/智谱等兼容 API
    }
    return new AnthropicLlmClient(...);    // 默认 Anthropic
}
```

### AgentExecutor 装配

```java
@Bean
@ConditionalOnMissingBean
public AgentExecutor agentExecutor(
        ObjectProvider<LlmClient> llmClientProvider,
        ToolDispatcher toolDispatcher,
        TaskStore taskStore,
        SnapAgentProperties props,
        ObjectProvider<SystemPromptExtender> extenderProvider,    // 收集所有 extender
        ObjectProvider<CostTracker> costTrackerProvider,
        ObjectProvider<CostCalculator> costCalculatorProvider) {

    LlmClient llmClient = llmClientProvider.getIfAvailable();
    // 成本核算启用时，用 CostTrackingLlmClient 包装原始 client
    if (llmClient != null && costTracker != null && props.getCost().isEnabled()) {
        llmClient = new CostTrackingLlmClient(llmClient, costTracker, costCalculator, ...);
    }
    // 收集所有 SystemPromptExtender（按 @Order 排序）
    List<SystemPromptExtender> extenders = extenderProvider.orderedStream().collect(...);
    return new AgentExecutor(llmClient, toolDispatcher, taskStore, maxTurns, maxTokens, extenders);
}
```

---

## 8. 版本路线图

| 版本 | 交付内容 | 关键 SPI/组件 |
|------|---------|-------------|
| v0.1-alpha | 核心 SPI + LLM + 基础工具 | AgentExecutor, LlmClient, SkillRegistry, ToolDispatcher, JdbcQueryToolProvider, RedisReadToolProvider |
| v0.2 | 框架增强 | SnapAgentFilter, AgentRequestContext, 跨 Pod 路由子系统 (PeerRouter/PeerSseRelay) |
| v0.3 | 代码理解能力 | SystemPromptExtender, CodePathGuard, code_read/project_structure/git_log 工具, ProjectContextExtender |
| v0.4 | 运营诊断能力 | ObservabilityHttpClient, TimeRangeParser, metrics_query/log_search/trace_search/config_read 工具 |
| v0.5 | 主动监控与推送 | AlertConverger, AnomalyEventListener, ScheduledPatrolScheduler, 巡检 skill |
| v0.6 | 平台化 | DataSourceRegistry (多环境), SkillMeta.requiredPermission (skill 级权限), snap-agent-client REST SDK |
| v0.7 | 嵌入式业务知识库 | KnowledgeBase, KnowledgeSource, KnowledgeSearcher, KnowledgeInjector (SystemPromptExtender 多 extender) |
| v0.8 | 代码知识图谱 | CodeGraph, CodeGraphBuilder, CodeGraphIndex, CodeGraphToolProvider |
| v0.9 | 问题问答闭环 | IssueStore, IssueTracker, IssueClosureService, KnowledgeSedimentationExtractor |
| v1.0 | 工具插件 + 工作流 + 成本核算 | ToolPlugin, WorkflowEngine, CostTracker, CostTrackingLlmClient, LlmEventSink.onUsage() |
| v1.1 | 主动监控 SPI 化 | PatrolReportStore 接口化 (InMemoryPatrolReportStore), PatrolLockProvider (多 Pod 协调), AlertPushChannel (Webhook+Email 默认实现), KnowledgeBase.listAll()/`GET /knowledge/fragments`, ObservabilityHttpClient.httpPost() |

---

## 9. 已知限制与扩展

### 已知限制

| 限制 | 说明 |
|------|------|
| 仅内存状态 | TaskStore 基于 ConcurrentHashMap，进程重启丢失所有任务和 transcript |
| Java 8 + Spring Boot 2.x | 使用 javax.servlet，不支持 Spring Boot 3.x (jakarta.servlet) |
| 无向量搜索 | 知识库默认使用关键词重叠打分，无 embedding 语义检索 |
| 正则解析代码图谱 | SimpleCodeGraphBuilder 基于正则，注释可能假阳性，不区分重载，lambda 可能遗漏 |
| 无 Spring Cloud 依赖 | 跨 Pod 路由自实现 K8s API/DNS 探测，不依赖服务发现框架 |
| SSE 限制 | EventSource 不支持自定义 header，SSE 端点需 permitAll + token query param |
| 精确权限匹配 | SpringSecurityAdapter.hasPermission() 精确匹配 authority，不支持通配符/角色继承 |
| 串行工作流 | SimpleWorkflowEngine 顺序执行，不支持并行/DAG/人工审批 |

### SPI 扩展指南

| SPI | 默认实现 | 扩展方式 |
|-----|---------|---------|
| `LlmClient` | AnthropicLlmClient / OpenAiLlmClient | 实现 `LlmClient` + `@Component`，配置 `api-type` 选择 |
| `ToolProvider` | JdbcQueryToolProvider 等 | 实现 `ToolProvider` + `@Component`，自动被 ToolDispatcher 收集 |
| `SecurityGateway` | SpringSecurityAdapter / ShiroAdapter | 实现并声明 Bean，`@ConditionalOnMissingBean` 生效 |
| `PrincipalResolver` | DefaultPrincipalResolver | 实现 `PrincipalResolver` + `@Component` |
| `KnowledgeSource` | MarkdownKnowledgeSource | 实现 `KnowledgeSource` + `@Component`（数据库/API/Confluence） |
| `KnowledgeSearcher` | SimpleKeywordSearcher | 实现 `KnowledgeSearcher` + `@Component`（向量嵌入语义检索） |
| `CodeGraphBuilder` | SimpleCodeGraphBuilder | 实现 `CodeGraphBuilder` + `@Component`（JavaParser AST） |
| `CodeGraphIndex` | InMemoryCodeGraphIndex | 实现 `CodeGraphIndex` + `@Component`（SQLite/H2 持久化） |
| `IssueStore` | FileIssueStore | 实现 `IssueStore` + `@Component`（数据库存储） |
| `IssueTracker` | NoopIssueTracker | 实现 `IssueTracker` + `@Component`（Jira/GitHub Issues） |
| `CostStore` | FileCostStore | 实现 `CostStore` + `@Component`（数据库存储） |
| `WorkflowEngine` | SimpleWorkflowEngine | 实现 `WorkflowEngine` + `@Component`（DAG 并行/人工审批） |
| `ConversationStore` | FileConversationStore | 实现 `ConversationStore` + `@Component`（数据库存储） |
| `SystemPromptExtender` | ProjectContextExtender / KnowledgeInjector | 实现 `SystemPromptExtender` + `@Component`（自定义上下文注入） |

所有扩展均通过 Spring `@ConditionalOnMissingBean` 机制生效：宿主声明的 Bean 优先于默认实现，无需修改 SnapAgent 源码。
