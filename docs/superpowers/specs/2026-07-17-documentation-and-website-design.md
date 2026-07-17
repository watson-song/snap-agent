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

### Document 5: User Manual

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
8. Feature panels:
   - Tools & Plugins: view registered tools
   - Workflows: list, detail, run
   - Cost dashboard: summary, per-user, per-skill
   - Issues: propose solution, create issue, verify, close
   - Patrol: scheduled health checks
   - Alerts: anomaly convergence
   - Knowledge base: status, sources, live search
9. Uploading skills (file, folder, zip)

**Part 2 — Developer Guide:**
1. Writing custom ToolProvider (full example)
2. Writing custom SystemPromptExtender
3. Implementing IssueTracker (Jira/GitHub)
4. Implementing CostStore (DB-backed)
5. Writing workflow YAML
6. MCP integration (external tool servers)
7. Knowledge source extension (custom KnowledgeSource)
8. ConversationStore replacement (DB-backed)
9. Skill markdown authoring best practices

## Sub-project 2: Official Website (after docs)

### Tech Stack
- Static HTML + CSS + JS (vanilla, no build tools)
- Hosted on GitHub Pages
- Same visual style as SnapAgent embedded UI

### Pages
1. **Homepage** (`docs/site/index.html`) — Hero section, key features, quick start, architecture diagram
2. **User Manual** — Rendered from `docs/site/manual/` Markdown
3. **Technical Docs** — Architecture, search algorithm, deployment
4. **Integration Guide** — Host integration step-by-step
5. **API Reference** — REST endpoints from existing `10-rest-api.md`

### Layout
- Single-page navigation (sidebar + content area, like docs sites)
- Dark/light theme toggle
- Code blocks with syntax highlighting (vanilla JS highlighter)
- Responsive layout

## Scope Check

This spec covers two independent sub-projects:
1. Technical documentation (10 Markdown files, bilingual)
2. Official website (static HTML site)

Sub-project 1 should be completed first as it provides content for sub-project 2.

## Non-Goals

- Migrating/replacing existing `docs/embeed-skills-agent/` design docs
- Video tutorials
- Interactive playground
- Blog/CMS functionality
- Search functionality for the website
