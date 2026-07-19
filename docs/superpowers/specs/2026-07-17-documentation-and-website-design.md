# SnapAgent Documentation & Website Design

> **Status:** Design phase
> **Date:** 2026-07-17
> **Goal:** Supplement missing technical documentation and build an official website for SnapAgent

## Decision Summary

| Item | Decision |
|------|----------|
| Order | Docs first, then website |
| Language | Bilingual (Chinese + English) |
| Doc organization | New `docs/site/` directory, bilingual subdirs |
| Website tech | Static HTML+CSS+JS, GitHub Pages hosting |
| User manual audience | Both ops users and host developers |

## Sub-project 1: Technical Documentation

### Structure

```
docs/site/
├── architecture/
│   ├── en/system-architecture.md
│   └── zh/system-architecture.md
├── search/
│   ├── en/knowledge-search.md
│   └── zh/knowledge-search.md
├── integration/
│   ├── en/host-integration-guide.md
│   └── zh/host-integration-guide.md
├── deployment/
│   ├── en/multi-cluster-architecture.md
│   └── zh/multi-cluster-architecture.md
├── plugins/
│   ├── en/tool-plugin-architecture.md
│   └── zh/tool-plugin-architecture.md
├── workflow/
│   ├── en/workflow-engine-architecture.md
│   └── zh/workflow-engine-architecture.md
├── issue/
│   ├── en/issue-closure-architecture.md
│   └── zh/issue-closure-architecture.md
├── proactive/
│   ├── en/proactive-monitoring-architecture.md
│   └── zh/proactive-monitoring-architecture.md
├── manual/
│   ├── en/user-manual.md
│   └── zh/user-manual.md
└── README.md   (index page)
```

### Document 1: System Architecture

**File:** `docs/site/architecture/{en,zh}/system-architecture.md`

Covers the complete v1.0 system architecture:

1. Module split (core + starter + client)
   - `snap-agent-core`: SPI + execution loop, servlet-agnostic
   - `snap-agent-spring-boot-2x-starter`: javax.servlet + autoconfig + web + tools
   - `snap-agent-client`: REST SDK for external callers
   - Why split: javax/jakarta binary incompatibility
2. Component inventory (all v0.1-v1.0 components):
   - Core: AgentExecutor, SkillRegistry/SkillLoader/SkillMeta, ToolDispatcher/ToolProvider, LlmClient/LlmRequest/LlmEventSink, SecurityGateway/PrincipalResolver, RateLimiter, TaskStore/AgentTask
   - Starter: AnthropicLlmClient, SnapAgentController/SnapAgentFilter, SpringSecurityAdapter/ShiroAdapter, JdbcQueryToolProvider/RedisReadToolProvider, all v0.3-v1.0 tool providers, KnowledgeBase/KnowledgeInjector, CodeGraph, IssueClosure, CostTracker, WorkflowEngine, ToolPluginRegistry, PeerRouter/PeerSseRelay
3. Data flow: user input → skill selection → LLM loop → tool dispatch → SSE streaming → report
4. AutoConfiguration conditional assembly (enabled=false zero-impact proof)
5. SPI extension points (all interfaces with their purpose)
6. Knowledge orchestration layer (pre-injection + per-turn injection + tool-based search)
7. Dependency management (optional deps, BOM control, no version conflicts)
8. Two-tier skill system (builtin classpath + uploadable filesystem)

### Document 2: Knowledge Search Algorithm

**File:** `docs/site/search/{en,zh}/knowledge-search.md`

Covers the search engine design and algorithm:

1. Architecture: KnowledgeBase → KnowledgeSource → KnowledgeSearcher SPI
2. SimpleKeywordSearcher tokenization:
   - Latin: split on whitespace/punctuation, lowercase, drop <2 char tokens
   - CJK: overlapping 2-char bigrams (e.g. "数据库" → ["数据", "据库", "库"])
   - Mixed text handling (flush Latin word on CJK boundary)
3. Scoring formula: `score = (titleHits×2 + contentHits) / (queryTokenCount×2)`, clamped [0.0, 1.0]
   - Title weight 2x (title directly describes topic)
   - Example walkthroughs: "数据库" → 1.0, "snapagent" → 1.0 + 0.50
4. minScore threshold:
   - Config: `snap-agent.knowledge.min-score` (default 0.1)
   - KnowledgeBase.search() filters `score >= minScore`
   - KnowledgeController.search() uses config minScore (was hardcoded 0.0 — bug fixed)
5. searchWithScores() — returns SearchResult(fragment, score) for UI display
6. KnowledgeInjector auto-injection flow:
   - SystemPromptExtender SPI → KnowledgeInjector implementation
   - Per-skill-query: extract keywords from user input → search top-K → inject to system prompt
   - AgentExecutor multi-extender: List<SystemPromptExtender>, ordered stream
7. Known limitations:
   - Case-sensitive English matching (SnapAgent ≠ snapagent)
   - No semantic search (keyword overlap only)
   - No vector embeddings (planned for v0.7.2)
   - Single-token queries now supported (was blocked by 2-token minimum — bug fixed)
8. Extension points: implement KnowledgeSearcher for custom scoring, KnowledgeSource for custom sources

### Document 3: Host Integration Guide

**File:** `docs/site/integration/{en,zh}/host-integration-guide.md`

Covers everything a host developer needs to integrate SnapAgent:

1. 5-step quick start:
   - Step 1: Add Maven dependency
   - Step 2: Configure application.yml
   - Step 3: Provide read-only DataSource (if JDBC enabled)
   - Step 4: Configure security (permit /snap-agent/** path)
   - Step 5: (Optional) Custom PrincipalResolver
2. Full configuration reference — complete `snap-agent.*` tree:
   - Master switch, base-path, skill dirs
   - LLM config (api-type, base-url, auth, model, streaming, timeout)
   - Agent config (max-turns, timeout, rate limits, thread pool)
   - JDBC/Redis config
   - Security config (framework, audit, required-permission, auth-token)
   - Code config (project-root, extensions, limits)
   - Knowledge config (sources, max-fragments, min-score)
   - Code-graph config (scan-packages, max-depth)
   - Issue-closure config
   - Cost config (pricing, budgets, storage)
   - Workflow config
   - Routing config (mode, discovery-cache-ttl)
3. Security adaptation:
   - Auto-detect Spring Security / Shiro
   - Custom SecurityGateway bean (override hasPermission)
   - PrincipalResolver SPI
   - Common pitfall: permissions in principal vs GrantedAuthority
4. Custom extensions:
   - ToolProvider: @Component, name/schema/execute
   - SystemPromptExtender: extend(skillMeta, agentTask) → String
   - KnowledgeSearcher: custom scoring algorithm
   - CodeGraphBuilder: AST-based code analysis
   - IssueTracker: Jira/GitHub integration
   - CostStore: DB-backed cost storage
   - ConversationStore: DB-backed conversation storage
5. Multi-environment datasource configuration
6. Skill authoring guide (frontmatter, body, inputs, tools contract, availability)
7. Troubleshooting (common issues and solutions)

### Document 4: Multi-Cluster Deployment Architecture

**File:** `docs/site/deployment/{en,zh}/multi-cluster-architecture.md`

Covers K8s multi-instance deployment and cross-pod routing:

1. Deployment topology:
   - Multiple pods, each running SnapAgent embedded in host app
   - In-memory state (TaskStore), no shared session store
   - Any pod can receive any request → need cross-pod routing
2. Cross-pod SSE relay architecture:
   - SnapAgentController receives run request → creates task in local TaskStore
   - SSE stream request → local TaskStore lookup → if hit, stream locally
   - If miss → PeerSseRelay.tryRelay() → probe peer pods → relay SSE
   - Internal endpoint: /skills-agent-internal/ (outside basePath, internal token only)
3. PeerRouter pod discovery degradation chain:
   - k8s-api: read Endpoints API (needs SA token + RBAC)
   - headless-dns: DNS A record resolution
   - static: configured peer list
   - none: local only + 404
4. Self-exclusion: MY_POD_IP env var (K8s downward API)
5. Discovery cache: TTL configurable (default 10s), stale-OK on failure
6. Internal token security: X-Skills-Agent-Internal-Token header, separate from user auth
7. SSE relay implementation:
   - Probe: HTTP GET to peer internal endpoint, 200/404/401 handling
   - Stream: line-by-line SSE parsing, forward via local SseEmitter
   - Heartbeat: 15s polling comment to prevent browser timeout
8. Production best practices:
   - Replica count (2-3 minimum for HA)
   - Resource limits (memory for in-memory task store)
   - RBAC for K8s API mode (minimal: read endpoints)
   - SSE proxy compatibility (disable buffering)
   - Docker/standalone deployment notes

### Document 5: Tool Plugin Architecture

**File:** `docs/site/plugins/{en,zh}/tool-plugin-architecture.md`

Covers the v1.0 tool plugin ecosystem design:

1. SPI layer (`core/tool/ToolPlugin.java`):
   - Interface: name() / version() / description() / toolNames()
   - Default methods for backward compatibility
   - Purpose: metadata layer on top of existing ToolProvider auto-discovery
2. Registry (`boot2x/tool/ToolPluginRegistry.java`):
   - Collects all ToolPlugin beans via Spring auto-injection
   - Unconditional assembly (always registered)
   - Exposes plugin metadata via REST endpoint: GET /tools/plugins
3. Relationship with ToolProvider:
   - ToolProvider + @Component = auto-discovered by ToolDispatcher (since v0.1)
   - ToolPlugin = optional metadata wrapper (name/version/description for display)
   - ToolProvider can exist without ToolPlugin (no metadata); ToolPlugin without ToolProvider is metadata-only
4. Built-in tool providers inventory (v0.1-v1.0):
   - v0.1: JdbcQueryToolProvider, RedisReadToolProvider
   - v0.3: CodeReaderToolProvider, ProjectStructureToolProvider, GitLogToolProvider
   - v0.4: MetricsToolProvider, LogSearchToolProvider, TraceSearchToolProvider, ConfigReadToolProvider
   - v0.8: CodeGraphToolProvider (4 sub-tools: call_chain/reverse_chain/impact_analysis/find)
5. Extension guide: how to write and register a custom ToolPlugin
6. Future: independent plugin JAR, MCP protocol plugins (v1.0.1+)

### Document 6: Workflow Engine Architecture

**File:** `docs/site/workflow/{en,zh}/workflow-engine-architecture.md`

Covers the v1.0 workflow engine design:

1. Core SPI (`core/workflow/`):
   - WorkflowStep: name/skill/condition/inputs/onFailure[STOP|SKIP|RETRY]
   - WorkflowDefinition: name/description/steps (immutable)
   - WorkflowResult: success/failure factory methods, stepResults map
   - WorkflowEngine: execute(definition, triggerInputs) → WorkflowResult, type()
2. Starter implementation (`boot2x/workflow/`):
   - YamlWorkflowLoader: SnakeYAML parsing, loadAll() + load(name), reads filesystem dir
   - SimpleWorkflowEngine: sequential execution, condition evaluation, input reference resolution
3. Condition expression language:
   - `${step.result}` — reference previous step result
   - `${step.result != null}` — null check
   - `${step.result.contains('text')}` — string contains
   - `${step.result.size > 0}` — size check
   - `${trigger.xxx}` — trigger input reference
4. Input reference resolution: `${step.result}` and `${trigger.field}` substitution
5. Failure handling: STOP (halt), SKIP (continue next), RETRY (retry step)
6. REST endpoints: GET /workflows, GET /workflows/{name}, POST /workflows/{name}/run
7. Built-in workflow: full-diagnose.yml (health-check → error-spike → code-analysis → solution-suggest, 4-step conditional)
8. Configuration: `snap-agent.workflows.{enabled,dir}`
9. Future: loops, human approval, cron/event triggers (v1.0.1+)

### Document 7: Issue Closure Architecture

**File:** `docs/site/issue/{en,zh}/issue-closure-architecture.md`

Covers the v0.9 issue closure loop design:

1. Core SPI (`core/issue/`):
   - IssueStatus: DIAGNOSED → SOLUTION_PROPOSED → FIX_IN_PROGRESS → VERIFIED → CLOSED (state machine)
   - IssueClosure: immutable value object (issueId/externalIssueId/taskId/conversationId/userQuery/rootCause/solutions/selectedSolution/status/fixCommitId/verificationResult/knowledgeEntryId/createdAt/updatedAt; withStatus/withExternalIssue/withVerification/withKnowledgeEntry return new instances)
   - IssueStore: save/load/findByTaskId/list/listByStatus/delete (persistence SPI)
   - IssueTracker: createIssue/updateStatus/getIssueUrl/type (external tracker SPI)
2. Starter implementation (`boot2x/issue/`):
   - FileIssueStore: JSON file storage in {upload-skills-dir}/issues/{issueId}.json
   - NoopIssueTracker: default empty implementation
   - KnowledgeSedimentationExtractor: extracts KnowledgeFragment from IssueClosure (title="问题: "+truncate(query,60), content=##问题/##根因/##解决方案/##验证结果, source="sedimentation:"+issueId, metadata={category:"经验沉淀"})
   - IssueClosureService: orchestration service
     - proposeSolution(taskId): run solution-suggest skill → extract solutions
     - createExternalIssue(taskId): call IssueTracker.createIssue()
     - verify(issueId): run verify-fix skill → check if issue resolved
     - close(issueId): extract knowledge → store in KnowledgeBase → set status CLOSED
3. REST endpoints: POST /runs/{id}/solution, POST /runs/{id}/issue, GET /issues/{id}, POST /issues/{id}/verify, POST /issues/{id}/close
4. Knowledge sedimentation flow: diagnosis → solution → fix → verify → sediment to knowledge base → feedback loop
5. Configuration: `snap-agent.issue-closure.{enabled,system-user-id,storage-dir,tracker-type}`
6. Built-in skills: solution-suggest.md, verify-fix.md
7. Extension: implement IssueTracker for Jira/GitHub (v0.9.1+), auto PR creation (v0.9.1+)

### Document 8: Proactive Monitoring Architecture

**File:** `docs/site/proactive/{en,zh}/proactive-monitoring-architecture.md`

Covers the v0.5 proactive monitoring & alert push design, including patrol tasks and alerts:

1. Core SPI (`core/proactive/`):
   - EventSource: start(listener)/stop()/type() — event source (MQ, scheduled, etc.)
   - EventConsumer: onEvent(AnomalyEvent) — event handler
   - AnomalyEvent: immutable (type/service/message/stackTrace/timestamp/metadata)
   - PushChannel: push(DiagnosticReport)/type() — notification channel
   - DiagnosticReport: immutable (title/severity[INFO/WARN/CRITICAL]/summary/rootCause/recommendations/evidence/timestamp/metadata)
2. Starter implementation (`boot2x/proactive/`):
   - **AlertConverger**: ConcurrentHashMap sliding window, key=service:type, windowMinutes(5) dedup, maxAlertsPerWindow(3) storm prevention
   - **TrendPredictor**: least-squares linear regression, predict(double[])→Double (returns null if <3 data points), exceedsThreshold check
   - **AnomalyEventListener**: implements EventConsumer — convergence check → find auto-diagnose skill → construct AgentTask(userId=system) → snapAgentExecutor async execute → extract task.getReport() → DiagnosticReport → push all channels
   - **ScheduledHealthChecker** (patrol): standalone daemon ScheduledExecutor, periodic ops-health-check skill execution, anomaly keyword detection → push
   - **NoopEventSource**: default event source (when no MQ), type="noop"
   - **WebhookPushChannel**: extends ObservabilityHttpClient, httpPost JSON to webhook URL
3. Lifecycle: SmartLifecycle bean (ProactiveLifecycle) — start() calls eventSource.start(listener) + checker.start(), stop() reverses
4. Alert convergence design:
   - Sliding window per service:type key
   - Dedup: same key within window → increment count, don't create new alert
   - Storm prevention: max N alerts per window, excess dropped + logged
   - Converged alert includes: first/last occurrence, count, root cause (if diagnosed)
5. Patrol task design:
   - Scheduled execution: cron-like scheduling via ScheduledExecutorService
   - Skill-driven: runs ops-health-check skill with configured inputs
   - Anomaly detection: keyword scanning in skill output (e.g. "CRITICAL", "异常", "超时")
   - Report generation: DiagnosticReport with severity classification
   - Auto-push: detected anomaly → push to all configured channels
6. Trend prediction design:
   - Collects metric data points over time
   - Least-squares linear regression on data points
   - Predicts future value, compares against configured threshold
   - Triggers early warning before threshold breach (e.g. "disk 80% in 3 days")
7. Configuration: `snap-agent.proactive.{enabled,event-source-type,system-user-id,health-check.*,alert-convergence.*,trend-prediction.*,push.webhook.*}`
8. Built-in skill: auto-diagnose.md (6-phase: understand anomaly → logs → metrics → trace → code → diagnosis report)
9. REST endpoints: GET /patrol/tasks, GET /patrol/reports, GET /alerts (active alerts with resolve action)
10. Extension: KafkaEventSource/RabbitMqEventSource (v0.5.1), DingTalkPushChannel/JiraPushChannel (v0.5.1)

### Document 9: User Manual

**File:** `docs/site/manual/{en,zh}/user-manual.md`

Two-part manual covering both user types:

**Part 1 — Ops User Guide:**
1. Accessing SnapAgent (URL, authentication)
2. UI walkthrough:
   - Sidebar: skill list (host/builtin sections), disabled skills toggle, detail button
   - Top bar: model selector, user info, active profiles
   - Chat area: welcome screen, messages, timestamps
   - Input area: shortcut bar, input form, send/cancel buttons
   - Feature nav bar: tools, workflows, cost, issues, patrol, alerts, knowledge
3. Selecting and running a skill
4. Viewing agent thinking process (SSE streaming)
5. Tool calls and results in transcript
6. Canceling a running task
7. Conversation history (save, load, download, delete)
8. Feature panels — detailed usage guide for each:
   - **Tools & Plugins (🔧)**: view registered tools and their schemas; view installed plugins with name/version/description; understand which tools each skill uses
   - **Workflows (📋)**: list available workflows; view step details (skill, condition, inputs, onFailure); run a workflow manually with trigger inputs; monitor step-by-step execution and results; understand condition evaluation and failure handling
   - **Cost Dashboard (💰)**: view 7-day cost summary; check total cost, token usage, request count; per-user and per-skill breakdown; budget utilization and warning threshold; understand pricing model (input/output/cache-read token rates)
   - **Issue Closure (🐛)**: view recent diagnostic runs; propose solution for a completed diagnosis; create external issue (Jira/工单); verify fix effectiveness; close issue and sediment knowledge; track issue lifecycle (DIAGNOSED → SOLUTION_PROPOSED → FIX_IN_PROGRESS → VERIFIED → CLOSED)
   - **Patrol Tasks (🛡️)**: view scheduled health check tasks; view patrol reports with severity classification; understand scheduled execution and anomaly keyword detection; configure patrol schedule and skill
   - **Alerts (🔔)**: view active alerts with convergence info (count, first/last occurrence); understand alert convergence (dedup window, storm prevention); resolve alerts; configure webhook push channel
   - **Knowledge Base (📚)**: view knowledge status (fragment count, injection limit, min score); view data sources; live keyword search with relevance score; understand how knowledge is auto-injected into agent conversations
9. Uploading skills (file, folder, zip)
10. Model selection and switching
11. Environment profile awareness

**Part 2 — Developer Guide:**
1. Writing custom ToolProvider (full example with @Component)
2. Writing custom SystemPromptExtender
3. Implementing IssueTracker for Jira/GitHub (createIssue, updateStatus, getIssueUrl)
4. Implementing CostStore with DB-backed storage
5. Writing workflow YAML — step-by-step guide:
   - Step definition (name, skill, condition, inputs, onFailure)
   - Condition expression syntax (${step.result}, .contains(), .size, ${trigger.xxx})
   - Input reference resolution
   - Failure handling strategies (STOP/SKIP/RETRY)
   - Example: full-diagnose.yml walkthrough
6. MCP integration (external tool servers)
7. Knowledge source extension (custom KnowledgeSource implementation)
8. ConversationStore replacement (DB-backed)
9. Implementing custom EventSource (Kafka/RabbitMQ anomaly events)
10. Implementing custom PushChannel (DingTalk, Jira, email)
11. Configuring proactive monitoring (patrol schedule, alert convergence, trend prediction, webhook)
12. Implementing custom CodeGraphBuilder (AST-based)
13. Skill markdown authoring best practices

## Sub-project 2: Official Website (after docs)

### Tech Stack
- Static HTML + CSS + JS (vanilla, no build tools)
- Hosted on GitHub Pages
- Same visual style as SnapAgent embedded UI

### Pages
1. **Homepage** (`docs/site/index.html`) — Hero section, key features, quick start, architecture diagram
2. **User Manual** — Rendered from `docs/site/manual/` Markdown
3. **Technical Docs** — Architecture, search algorithm, deployment, tool plugins, workflows, issue closure, proactive monitoring
4. **Integration Guide** — Host integration step-by-step
5. **API Reference** — REST endpoints from existing `10-rest-api.md`
6. **Feature Guide** — How to use each feature: tools, workflows, cost, issues, patrol, alerts, knowledge

### Layout
- Single-page navigation (sidebar + content area, like docs sites)
- Dark/light theme toggle
- Code blocks with syntax highlighting (vanilla JS highlighter)
- Responsive layout

## Scope Check

This spec covers two independent sub-projects:
1. Technical documentation (18 Markdown files, bilingual — 9 topics × 2 languages)
2. Official website (static HTML site)

Sub-project 1 should be completed first as it provides content for sub-project 2.

The 9 documentation topics:
1. System architecture (overall v0.1-v1.0)
2. Knowledge search algorithm
3. Host integration guide
4. Multi-cluster deployment
5. Tool plugin architecture (v1.0)
6. Workflow engine architecture (v1.0)
7. Issue closure architecture (v0.9)
8. Proactive monitoring architecture — patrol + alerts (v0.5)
9. User manual (ops + developer, covers all features)

## Non-Goals

- Migrating/replacing existing `docs/embeed-skills-agent/` design docs
- Video tutorials
- Interactive playground
- Blog/CMS functionality
- Search functionality for the website
