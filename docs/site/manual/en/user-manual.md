# SnapAgent User Manual

> Version: v1.0 | Updated: 2026-07-17

This manual targets two audiences:

- **Integrators** — developers embedding SnapAgent into a Spring Boot 2.x host application
- **Operators / End Users** — engineers, SREs, and on-call staff who run diagnostic skills via the Web UI or REST API

For technical assembly details (Maven coordinates, auto-configuration order, `SecurityGateway` customization, cross-pod routing, …) refer to the [Host Integration Guide](../integration/en/host-integration-guide.md). For the big-picture architecture refer to [System Architecture Overview](../architecture/en/system-architecture.md). This manual focuses on *how to use*.

---

## 1. Quick Start

### 1.1 Integrator Path (embed SnapAgent in a host app)

Three minimum steps turn a Spring Boot 2.x app into an LLM diagnostic agent:

1. **Add Maven dependency**

   ```xml
   <dependency>
       <groupId>cn.watsontech.snapagent</groupId>
       <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
       <version>1.0-SNAPSHOT</version>
   </dependency>
   <!-- OkHttp is declared optional by the starter; the host MUST bring it explicitly -->
   <dependency>
       <groupId>com.squareup.okhttp3</groupId>
       <artifactId>okhttp</artifactId>
   </dependency>
   ```

2. **Enable and configure the LLM in `application.yml`**

   ```yaml
   snap-agent:
     enabled: true                              # master switch, default false
     base-path: /snap-agent                    # controller path prefix, default /snap-agent
     llm:
       base-url: https://api.anthropic.com
       auth-token: ${ANTHROPIC_AUTH_TOKEN}      # inject via env var
       model: claude-sonnet-4-6
     security:
       required-permission: snap-agent:access   # empty string = allow anonymous
     jdbc:
       enabled: true
       datasources:
         sit:
           url: jdbc:mysql://sit-db:3306/mydb
           username: readonly
           password: ${SIT_DB_PASSWORD}
           driver-class-name: com.mysql.cj.jdbc.Driver
       default-env: sit
   ```

3. **Start the host app**

   Spring Boot auto-configures `SnapAgentAutoConfiguration` via `META-INF/spring.factories`. Once activated, the full Agent Web UI and REST API are mounted under `{base-path}/**` and the host's `DataSource`, `RedisTemplate`, and Spring Security identity are reused automatically.

The complete checklist (dependencies, optional dependencies, auto-configuration order, `SecurityGateway` customization, base-path conflict check, smoke test) is in [Host Integration Guide §2–§4](../integration/en/host-integration-guide.md).

### 1.2 Operator Path (use the Web UI in a browser)

1. Open `http://<host>:<port>/snap-agent/` in a browser (`base-path` may be overridden by the host).
2. The browser reuses the host app's security context (Basic Auth / session cookie / custom token header). When unauthenticated, the page shows "Please log in to the system before accessing SnapAgent."
3. After login the sidebar lists all available skills (builtin + host-authored). Each card shows the skill name, description, and icon.
4. Click any skill → an input form appears in the middle area → fill in the parameters → click "➤ Run" → streaming output starts.

### 1.3 End User Path (integrate via REST API)

All REST endpoints are mounted under `{base-path}` (default `/snap-agent`). The minimal call chain:

```bash
# 1) List all skills
curl -u user:pass http://localhost:8080/snap-agent/skills

# 2) Start a diagnostic run (returns taskId + streamUrl)
curl -u user:pass -X POST http://localhost:8080/snap-agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"health-check","inputs":{},"model":"claude-sonnet-4-6"}'

# 3) Subscribe to the SSE stream (see §10)
curl -N -u user:pass http://localhost:8080/snap-agent/runs/<taskId>/stream
```

The full endpoint table and request/response examples are in [§10 REST API Reference](#10-rest-api-reference).

---

## 2. Web UI Usage

SnapAgent ships an embedded single-page app at `{base-path}/`. The layout is:

```
┌──────────────────────────────────────────────────────────────────────┐
│ ⚡ SnapAgent         [Model ▼]  👤 user                  │
├──────────┬───────────────────────────────────────────────────────────┤
│ 📄 File  │  Current Skill: <name>                                       │
│ 📁 Folder │  🌍 Active env: sit    📄 App log: /var/log/app.log         │
│ 🔄 Refresh│  ─────────────────────────────────────────────────────────  │
│ 🔍 Show  │                                                             │
│          │  ┌─ Streaming thought ───────────────────────────────────┐  │
│ ▾ Host   │  │ Agentic: Let me verify DB connectivity first...      │  │
│   Skills │  └────────────────────────────────────────────────────────┘  │
│  • ...   │  ┌─ Tool call: mysql_query ─────────────────────────────┐  │
│ ▸ Builtin │  │ args: {"sql":"SELECT 1 AS ok"}                         │  │
│   Skills │  │ result: ok=1 (1 row, 12ms)                            │  │
│ [🔧 📋 💰 │  └────────────────────────────────────────────────────────┘  │
│  🐛 🛡️ 🔔 │  ┌─ Final response ─────────────────────────────────────┐  │
│  📚]     │  │ ✅ System healthy: DB reachable, 47 tables...         │  │
│          │  └────────────────────────────────────────────────────────┘  │
│ ‹ Collapse│  📜  [Type a message or params...]                  [➤]   │
└──────────┴───────────────────────────────────────────────────────────┘
```

### 2.1 Main interactive elements

| Element | Location | Purpose |
|---------|----------|---------|
| 📄 File / 📁 Folder | sidebar top | Upload a single `.md` skill or an entire directory to `snap-agent.upload-skills-dir` |
| 🔄 Refresh | sidebar top | Triggers `POST /skills/refresh` to re-scan the upload directory |
| 🔍 Show/Hide | sidebar top | Toggle visibility of unavailable skills (missing tool dependencies) |
| 🌍 Active env | top bar | Shows host Spring `activeProfiles`; every run auto-injects `_app_profile` |
| 📜 History | input bar left | Open the history modal for the current skill (see §4) |
| ➤ Send | input bar right | Submit inputs and start the skill (see §3) |
| ‹ Collapse | sidebar bottom | Collapse the sidebar to an icon-only rail; hover shows full skill name |

### 2.2 Multi-skill parallel streams

The Web UI keeps an independent `skillChatState` per skill. **Switching the sidebar skill does not cancel the previous skill's stream** — background streams keep receiving events and updating the transcript; switching back rebuilds the DOM from memory (including thoughts, tool calls, timestamps). Only when the user clicks "➤ Run" on the same skill again is the previous stream saved (partial) and cancelled via `cancelSkillStream()`.

Skills with a running stream show a "Running" badge in the sidebar, making it easy to find in-flight background streams.

### 2.3 Feature navigation bar (7 buttons at sidebar bottom)

| Button | Title | Endpoint called | Content |
|--------|-------|------------------|---------|
| 🔧 | Tools & Plugins | `GET /tools` + `GET /tools/plugins` | Registered tool names + `ToolPlugin` metadata table |
| 📋 | Workflows | `GET /workflows` + `GET /workflows/{name}` | Workflow list with per-step detail; inline "Run" button calls `POST /workflows/{name}/run` |
| 💰 | Cost Dashboard | `GET /cost/summary?from=&to=` | Total cost, total tokens, total requests, budget utilization (see §9) |
| 🐛 | Issue Closure | `GET /runs` | Recent runs list with inline "Suggest solution" button (see §8) |
| 🛡️ | Patrol Tasks | `GET /patrol/tasks` + `GET /patrol/reports` | Patrol tasks and reports (proactive monitoring subsystem) |
| 🔔 | Alerts | `GET /alerts` | Active alert list with "Resolve" button (proactive monitoring subsystem) |
| 📚 | Knowledge | `GET /knowledge/status` + `GET /knowledge/search?q=` | Knowledge fragment stats + retrieval test (see §5) |

### 2.4 Frontend version

`index.html` marks the frontend version with the `app.js?v=25` query string (v25 = knowledge search uses configured minScore + shows relevance percentage). To verify the live version, view page source and search for `app.js?v=`.

---

## 3. Running Skills

### 3.1 What is a Skill

A Skill is a Markdown file whose YAML frontmatter declares metadata (`name` / `description` / `tools` / `inputs` / `shortcuts` / `required-permission`) and whose body is a phased diagnostic instruction for the LLM. A Skill is not code — it is a diagnostic playbook. The LLM follows the playbook, calls tools, and produces a diagnostic report step by step.

### 3.2 Input form

A skill's `inputs` field is auto-rendered as a form in the Web UI:

```yaml
inputs:
  - key: service
    label: Service name
    required: true
    type: text            # text / number / boolean / enum / date
    options:              # enum only
      - sit
      - uat
      - prod
  - key: time_window
    label: Time window
    type: text
    default: 1h
```

| `type` | HTML control | Validation |
|--------|--------------|------------|
| `text` | `<input type="text">` | Non-empty when `required: true` |
| `number` | `<input type="number">` | `Double.parseDouble()` |
| `boolean` | `<input type="text">` | Must be `true` / `false` |
| `enum` | `<select>` | Value must be in `options` |
| `date` | `<input type="date">` | `LocalDate.parse()` |

The input bar also auto-fills environment-like fields: when `key` matches `profile`/`profiles`/`environment`/`env`, the host's current `activeProfiles` is used as the value.

### 3.3 Streaming output lifecycle

After clicking "➤ Run":

1. The frontend appends a `user` message to the transcript and immediately `POST /conversations` to persist it (so a refresh during streaming does not lose it).
2. `POST /runs` returns `{taskId, status, streamUrl}`.
3. The frontend subscribes to `streamUrl` via `EventSource` (SSE) and renders events by type:

| SSE event name | Meaning |
|----------------|---------|
| `thought` | LLM thinking stream delta (incremental token, prefixed with `+`) |
| `tool_call` | A tool was invoked; carries `tool` + `args` |
| `tool_result` | Tool returned; carries `content` (truncated to 500 chars) + `error` |
| `task_error` | Task-level error (the `error` event name is avoided so `EventSource`'s built-in error handler is not triggered) |
| `done` | Terminal event; `data.status` is the final status, optional `data.report` |
| `comment` | Heartbeat (every 15s) |

4. The user can cancel at any time by clicking "Cancel" (or by calling `POST /runs/{id}/cancel`). The backend sets `task.status = CANCELLED`, calls `LlmClient.cancel(taskId)` to interrupt the in-flight HTTP call, and sends a `done` SSE event.
5. When the task ends (`SUCCEEDED` / `FAILED` / `TIMEOUT` / `CANCELLED`), the final reply is automatically saved as an `assistant` message in the current conversation.

### 3.4 Multi-environment datasources

If the host configures `snap-agent.jdbc.datasources` (a Map), the `mysql_query` tool schema gains an `env` parameter (empty = default env). When a skill frontmatter declares an `env` input, the user can pick a target environment (e.g. `prod`) in the form; the LLM forwards it to `mysql_query`'s `env` parameter. See [Host Integration Guide §3.3](../integration/en/host-integration-guide.md).

### 3.5 ASCII layout of a skill run page

```
┌─ Current Skill: slow-query-analysis ───────────────────────────────┐
│ Description: Slow-query triage — slow log → EXPLAIN → index hint    │
│ ⚠️ Required: service                                                 │
│ 🌍 Active env: sit                                                   │
├───────────────────────────────────────────────────────────────────┤
│ [service: order-service] [time_window: 1h]                          │
│ [Type a message or params...]                                [➤]   │
├─ 14:32:01 user ────────────────────────────────────────────────────┤
│ Any slow queries on order-service recently?                          │
├─ 14:32:02 thought ─────────────────────────────────────────────────┤
│ Let me query the mysql_slow_queries metric...                       │
├─ 14:32:03 tool_call: metrics_query ───────────────────────────────┤
│ args: {"query":"rate(mysql_slow_queries_total[5m])"}               │
├─ 14:32:04 tool_result ─────────────────────────────────────────────┤
│ 0.05 q/s (1 row, 84ms)                                              │
├─ 14:32:10 response ─────────────────────────────────────────────────┤
│ Past 5 minutes averaged 0.05 q/s slow queries; 3 full-scan SQL...   │
└───────────────────────────────────────────────────────────────────┘
```

---

## 4. Conversation History

### 4.1 Auto-save

After every `POST /runs`, the frontend immediately persists the `user` message via `POST /conversations`; when the stream ends, the `assistant` message is appended. The conversation title is derived from the first `user` message (truncated). The ID is assigned by the server. The JSON file lives at `{upload-skills-dir}/conversations/{userId}/{conversationId}.json`.

### 4.2 List and actions

Click the "📜" button on the left of the input bar to open the modal:

```
┌─ 📜 History — slow-query-analysis ──────────────────┐
│                                                     │
│  Any slow queries on order-service recently?         │
│  2026-07-17 14:32  · 5 messages                     │
│  [📂 Load]  [⬇ Download]  [🗑 Delete]               │
│                                                     │
│  Why is the order status inconsistent?              │
│  2026-07-16 09:15  · 8 messages                     │
│  [📂 Load]  [⬇ Download]  [🗑 Delete]               │
└─────────────────────────────────────────────────────┘
```

- **Load**: `GET /conversations/{id}` fetches messages and rebuilds the transcript (user/assistant messages only). **It does not re-execute**; thoughts and tool calls are not restored. To re-run, click "➤" again after loading.
- **Download**: `GET /conversations/{id}/download` returns Markdown (YAML metadata + sections by role). Filename is `{conversationId}.md`.
- **Delete**: `DELETE /conversations/{id}` removes the JSON file after ownership check.

### 4.3 Conversation save on skill switch

When the sidebar skill is switched, the current skill's in-memory transcript is retained (not written back); the new skill loads its own most recent conversation. If the user edits the input bar without clicking "➤", that unsent text is lost — sent messages are always persisted.

---

## 5. Knowledge Base

The knowledge base (v0.7) lets the LLM acquire business context automatically during diagnosis, avoiding "guessing table names" or "not knowing which table the replenishment strategy depends on". Architecture and algorithm details: [Knowledge Search Algorithm](../search/en/knowledge-search.md).

### 5.1 Retrieval test (Web UI)

Click the "📚" button in the sidebar:

```
┌─ Knowledge ───────────────────────────────────────────────┐
│  5      3      0.1                                       │
│ Fragments  Inject cap  Min score                          │
│                                                           │
│ Sources (1)                                              │
│ ┌──────────┬──────────────────────────────────────────┐   │
│ │ markdown │ classpath:/docs/knowledge/                 │   │
│ └──────────┴──────────────────────────────────────────┘   │
│                                                           │
│ Retrieval test                                           │
│ [Type keywords to search fragments...]            [Search]│
│                                                           │
│ ┌─ Database diagnostics ───── Relevance 100% ───────┐     │
│ │ Source: business-overview.md:section-2             │     │
│ │ Database diagnostics uses a read-only DataSource... │     │
│ └────────────────────────────────────────────────────┘     │
└───────────────────────────────────────────────────────────┘
```

The **Relevance N%** badge on the right of each result comes from `SearchResult.score * 100` (rounded down). Fragments below `min-score` are not returned.

### 5.2 Auto-injection (KnowledgeInjector)

When a skill runs, `KnowledgeInjector` (implements `SystemPromptExtender`) builds a query string from `task.inputs` → calls `knowledgeBase.search(query, maxFragments, minScore)` → formats the matching fragments and injects them into the LLM's system prompt. The injection ceiling is `snap-agent.knowledge.max-fragments` (default 3), preventing token bloat. No user action is needed.

### 5.3 REST API

| Method | Endpoint | Description |
|--------|-----------|-------------|
| `GET` | `/snap-agent/knowledge/status` | Knowledge base status: fragment count, injection cap, minScore, source list |
| `GET` | `/snap-agent/knowledge/search?q={query}` | Retrieval test (topK = max-fragments × 3, min 10) |
| `GET` | `/snap-agent/knowledge/fragments` | List all knowledge fragments (no score); used by the "knowledge stat" card click-to-expand view (new in v1.1) |

Example:

```bash
curl -u user:pass 'http://localhost:8080/snap-agent/knowledge/search?q=database'
```

```json
{
  "query": "database",
  "totalFragments": 5,
  "matched": 2,
  "fragments": [
    {
      "title": "Database diagnostics",
      "content": "Database diagnostics uses a read-only DataSource connection...",
      "source": "business-overview.md:section-2",
      "metadata": { "category": "SnapAgent Knowledge Sample" },
      "score": 1.0
    }
  ]
}
```

### 5.4 Adding knowledge

Drop `.md` files into `snap-agent.knowledge.sources[].dir` (default `classpath:/docs/knowledge/`). Each `##` heading's content becomes one `KnowledgeFragment`; the H1 heading becomes `metadata.category`; a file without `##` is treated as one fragment. After editing, call `KnowledgeBase.reload()` or restart.

```yaml
snap-agent:
  knowledge:
    enabled: true
    max-fragments: 3
    min-score: 0.1
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
      - type: markdown
        dir: /opt/myapp/knowledge/
```

---

## 6. Tools & Skills Overview

### 6.1 Builtin skills

SnapAgent ships 13 builtin skills (in `docs/skills/`), grouped by domain:

| Skill | Since | One-line description | Required tools |
|-------|-------|-----------------------|----------------|
| `health-check` | v0.1 | Basic health check: DB connectivity + table count | `mysql_query` |
| `database-query` | v0.1 | Natural-language to read-only SQL | `mysql_query` |
| `redis-query` | v0.1 | Redis key inspection (`exists` / `get`) | `redis_get` |
| `log-analysis` | v0.1 | Application log file analysis (exceptions / user ops) | `log_read` |
| `code-analysis` | v0.3 | Code structure + call-chain analysis | `project_structure`, `code_read`, `git_log` |
| `ops-health-check` | v0.4 | Full ops health check (metrics → anomalies → root cause) | `metrics_query`, `log_search`, `mysql_query` |
| `slow-query-analysis` | v0.4 | Slow query triage (slow log → EXPLAIN → index hint) | `mysql_query`, `log_search`, `metrics_query` |
| `error-spike-investigation` | v0.4 | Error-spike root cause (metrics → logs → changes → traces → code) | `metrics_query`, `log_search`, `trace_search`, `code_read`, `git_log` |
| `config-diff` | v0.4 | Environment config diff (differences + risk assessment) | `config_read`, `metrics_query` |
| `trend-prediction` | v0.5 | 7-day metric trend prediction and capacity warning | `metrics_query` |
| `health-patrol` | v0.5 | Patrol-oriented comprehensive health check (CPU/mem/error/latency) | `metrics_query` |
| `solution-suggest` | v0.9 | Generate 2-3 candidate solutions from root cause with recommendation level | none (pure LLM) |
| `verify-fix` | v0.9 | Post-fix re-verification: rerun diagnostic to confirm resolution | depends on the original skill's tools |

> `solution-suggest` and `verify-fix` are usually invoked automatically by the issue-closure service (§8), not directly by users.

### 6.2 Builtin tools

`ToolProvider` implementations grouped by domain (see [Tool Plugin Architecture §3](../plugins/en/tool-plugin-architecture.md)):

| Tool name | Provider | Enable config |
|-----------|----------|---------------|
| `mysql_query` | `JdbcQueryToolProvider` | `snap-agent.jdbc.*` |
| `redis_get` | `RedisReadToolProvider` | `snap-agent.redis.*` |
| `code_read` | `CodeReaderToolProvider` | `snap-agent.code.enabled=true` + `project-root` |
| `project_structure` | `ProjectStructureToolProvider` | same as above |
| `git_log` | `GitLogToolProvider` | same as above |
| `log_read` | `LogReadToolProvider` | `snap-agent.logs.allowed-paths` |
| `metrics_query` | `MetricsToolProvider` | `snap-agent.metrics.enabled=true` + `base-url` |
| `log_search` | `LogSearchToolProvider` | `snap-agent.log-search.enabled=true` + `base-url` |
| `trace_search` | `TraceSearchToolProvider` | `snap-agent.trace.enabled=true` + `base-url` |
| `config_read` | `ConfigReadToolProvider` | `snap-agent.config-read.enabled=true` |
| `code_graph_tools` | `CodeGraphToolProvider` | `snap-agent.code-graph.enabled=true` |

`code_graph_tools` is a single tool exposing 4 sub-tools: `call_chain` / `reverse_chain` / `impact_analysis` / `find`.

### 6.3 Builtin workflows

| Workflow | Steps | Description |
|----------|-------|-------------|
| `full-diagnose` | 4 | health-check → error-spike (conditional) → code-analysis (conditional) → solution-suggest (conditional) |

See §7 and [Workflow Engine Architecture](../workflow/en/workflow-engine-architecture.md).

### 6.4 Custom skills

Two upload paths:

1. **Web UI**: click "📄 File" (single `.md`) or "📁 Folder" (entire directory including `SKILL.md` + auxiliary files). Files land in `snap-agent.upload-skills-dir` (default `/tmp/snap-agent-skills`).
2. **REST API**: `POST /snap-agent/skills/upload` (multipart single file) or `POST /snap-agent/skills/upload-folder` (multipart multi-file with relative paths).

A custom skill overrides the builtin with the same name; deleting the custom version restores the builtin. See [Host Integration Guide §3.13](../integration/en/host-integration-guide.md).

---

## 7. Workflow Execution

### 7.1 Viewing and running

The sidebar "📋" button opens the workflow modal, listing all loaded `.yml` workflows (from `snap-agent.workflows.dir`). Each row has a "Run" button; clicking it calls `POST /workflows/{name}/run`:

```bash
curl -u user:pass -X POST \
  http://localhost:8080/snap-agent/workflows/full-diagnose/run \
  -H 'Content-Type: application/json' \
  -d '{"service":"order-service"}'
```

### 7.2 Execution semantics

`SimpleWorkflowEngine` runs steps sequentially:

- A step without `condition` always runs; a step with `condition` is gated by expression evaluation (`${step.result != null}` / `.contains('error')` / `.size > 0` / `${trigger.xxx}`)
- `onFailure: STOP` → abort the whole workflow on step failure
- `onFailure: SKIP` → skip this step and continue
- `onFailure: RETRY` → immediately retry this step

The result DTO contains `success`, `status`, `failedStep`, `errorMessage`, `stepResults` (each step's `taskId` / `status` / `report`), and `durationMs`.

### 7.3 Builtin `full-diagnose` example

```yaml
name: full-diagnose
description: "Full-chain diagnostic workflow — health check → error triage → code analysis → solution suggestion"
steps:
  - name: health-check
    skill: health-check
    inputs: { service: "${trigger.service}" }
    onFailure: STOP
  - name: find-root-cause
    skill: error-spike-investigation
    condition: "${health-check.result.contains('error')}"
    inputs: { timeWindow: "1h", service: "${trigger.service}" }
    onFailure: STOP
  - name: code-analysis
    skill: code-analysis
    condition: "${find-root-cause.result != null}"
    inputs: { rootCause: "${find-root-cause.result}" }
    onFailure: SKIP
  - name: solution-suggest
    skill: solution-suggest
    condition: "${code-analysis.result.size > 0}"
    inputs: { rootCause: "${find-root-cause.result}", codeAnalysis: "${code-analysis.result}" }
    onFailure: SKIP
```

Engine internals, YAML loading, condition DSL, and `StepResult` structure: [Workflow Engine Architecture](../workflow/en/workflow-engine-architecture.md).

---

## 8. Issue Closure Usage

Issue closure (v0.9) chains "diagnosis → solution suggestion → external issue → fix verification → knowledge sedimentation" into a complete loop. Architecture: [Issue Closure Architecture](../issue/en/issue-closure-architecture.md).

### 8.1 Operation flow

```
  Diagnosis complete (POST /runs → SSE done)
        │
        ▼
  ① Propose solution: POST /runs/{taskId}/solution
     → IssueClosureService runs the solution-suggest skill
     → Returns 2-3 candidate solutions with recommendation levels
        │
        ▼
  ② Create external issue: POST /runs/{taskId}/issue
     body: {"selected_solution": "Solution 1 description"}
     → (if IssueTracker configured) creates Jira/GitHub issue
     → Returns issue with externalIssueId
        │
        ▼
  ③ Apply the fix in the codebase (human)
        │
        ▼
  ④ Verify fix: POST /issues/{issueId}/verify
     → Runs verify-fix skill to re-check
     → IssueClosure.verificationResult populated
        │
        ▼
  ⑤ Close issue: POST /issues/{issueId}/close
     → KnowledgeSedimentationExtractor extracts a knowledge fragment from IssueClosure
     → Writes it to KnowledgeBase for future diagnosis
     → IssueClosure.status = CLOSED
```

### 8.2 REST endpoints

| Method | Endpoint | Triggered action |
|--------|----------|------------------|
| `POST` | `/snap-agent/runs/{taskId}/solution` | Run `solution-suggest` skill, return candidate solutions |
| `POST` | `/snap-agent/runs/{taskId}/issue` | After a solution is selected, create external issue |
| `GET` | `/snap-agent/issues/{issueId}` | Load issue details |
| `POST` | `/snap-agent/issues/{issueId}/verify` | Run `verify-fix` skill to re-check |
| `POST` | `/snap-agent/issues/{issueId}/close` | Close and sediment knowledge |

When disabled (`snap-agent.issue-closure.enabled=false`), all endpoints return `503 ISSUE_CLOSURE_DISABLED`.

### 8.3 Web UI

The sidebar "🐛 Issue Closure" button opens a modal listing recent runs. Each completed run row has a "Suggest solution" button; clicking it expands the solution list inline. Subsequent create-issue / verify / close actions are done via REST API or a host-authored panel.

---

## 9. Cost & Budgets

Cost tracking (v1.0) decorates the original `LlmClient` with `CostTrackingLlmClient`, capturing token usage from SSE `message_start` / `message_delta` `usage` blocks and persisting it. Architecture: [System Architecture Overview §6 Cost Tracking](../architecture/en/system-architecture.md).

### 9.1 Configuration

```yaml
snap-agent:
  cost:
    enabled: true                          # default false
    pricing:
      input: 3.00                          # per 1M input tokens (CNY)
      output: 15.00                        # per 1M output tokens
      cache-read: 0.30                     # per 1M cache-read tokens
      currency: CNY
    budgets:
      per-user-daily: 50.00                # null = no limit
      per-skill-daily: 20.00
      global-daily: 500.00
    storage-dir: /var/lib/snap-agent/cost  # empty = {upload-skills-dir}/cost/
    warn-threshold: 0.8                    # warn at 80% budget utilization
```

Cost records are JSON files at `{storage-dir}/{yyyy-MM-dd}/{recordId}.json`, bucketed by day.

### 9.2 Budget enforcement

`BudgetEnforcer` checks before every `POST /runs`:

- Has the user's same-day cumulative cost exceeded `per-user-daily`?
- Has this skill's same-day cumulative cost exceeded `per-skill-daily`?
- Has the global same-day cumulative cost exceeded `global-daily`?

When exceeded, the run is rejected (HTTP 403 / equivalent), the LLM is not invoked, and no new cost is incurred. The `warn-threshold` triggers a WARN log but does not block.

### 9.3 Query endpoints

| Method | Endpoint | Dimension |
|--------|----------|-----------|
| `GET` | `/snap-agent/cost/summary?from=&to=&groupBy=` | Global (`groupBy=user`/`skill` also returns the global summary) |
| `GET` | `/snap-agent/cost/users/{userId}/summary?from=&to=` | Per user |
| `GET` | `/snap-agent/cost/skills/{skillName}/summary?from=&to=` | Per skill |

`from` / `to` are epoch seconds. Example response:

```json
{
  "dimension": "global",
  "dimensionValue": "global",
  "from": 1721184000,
  "to": 1721788800,
  "totalCost": 12.34,
  "totalInputTokens": 2340000,
  "totalOutputTokens": 560000,
  "requestCount": 87,
  "budget": 500.00,
  "utilization": 0.0247
}
```

### 9.4 Web UI dashboard

The sidebar "💰 Cost Dashboard" button queries the last 7 days via `GET /cost/summary` and shows three stat cards (total cost / total tokens / total requests) plus a breakdown table with a budget-utilization column.

---

## 9.5 Anchor Q&A

> New in v0.4. Lets host app users click an anchor icon on any page region → a right-side drawer slides out → they ask LLM questions about that section's content, without leaving the current page.

For full integration (SecurityGateway setup, SPA compat, mobile) see the [Anchor Q&A Integration Guide](../integration/en/anchor-feature-guide.md). This section covers usage.

### 9.5.1 Overview

After the host page includes `<script src="/snap-agent/anchor.js" defer></script>`, the script auto-scans the `<main>` region for `data-snap-anchor` annotations and injects a purple 💬 icon (28×28) at the top-right of each region. Clicking the icon opens a right-side drawer where users ask LLM questions about that section's content.

```
┌─ Host page ────────────────────┐    ┌─ Right drawer (rounded) ─┐
│ <section data-snap-anchor=    │    │ 💬 SKU Overview      ✕   │
│   "SKU Overview" data-snap-   │    │ ## SKU Overview / ...    │
│   skill="auto">              │ →  │ ⚡ Smart Routing (Auto)  │
│   ...table content...         │    │ User: How many here?     │
│ </section>                    │    │ AI: Based on the table...│
└───────────────────────────────┘    └──────────────────────────┘
```

### 9.5.2 User Interaction

1. User visits the host page; `anchor.js` loads and scans anchors automatically
2. User clicks an anchor icon → right drawer slides out (Shadow DOM isolation, rounded right corners)
3. **Header** shows the anchor name + content summary subtitle (first 80 chars single-line)
4. **Skill info bar** shows the active skill:
   - `data-snap-skill="auto"` → "Smart Routing (Auto)"
   - Specific skill name → fetches `GET /skills` for displayName + description
5. Background pre-summarize + pre-classify starts (`POST /anchor/preprocess`)
6. User types a question and clicks Send
7. `POST /runs` initiates the run (`skillId: "auto"` + `anchor` field)
8. SSE streams tokens to the drawer in real time

### 9.5.3 Annotating Anchors

Add the `data-snap-anchor` attribute in HTML:

```html
<section data-snap-anchor="SKU Overview" data-snap-skill="auto">
  <h2>SKU Overview</h2>
  <table>
    <tr><th>Category</th><th>SKU Count</th></tr>
    <tr><td>Bakery</td><td>3</td></tr>
  </table>
</section>
```

| Attribute | Required | Value |
|-----------|----------|-------|
| `data-snap-anchor` | Yes | Anchor name (shown in the drawer header) |
| `data-snap-skill` | No | `auto` (default, smart routing) / `<skill-name>` (specific skill) / `off` (show content only, no Q&A) |

If the page has no annotations, the script auto-discovers `<section>` and `<h2>` / `<h3>` with `id` as fallback anchors.

### 9.5.4 REST API Usage

Anchor Q&A extends `POST /snap-agent/runs` with an `anchor` field and `skillId: "auto"`:

```bash
curl -u alice:secret -X POST http://localhost:8080/snap-agent/runs \
  -H 'Content-Type: application/json' \
  -d '{
    "skillId": "auto",
    "inputs": {"message": "How many SKUs are in this category?"},
    "anchor": {
      "name": "SKU Overview",
      "content": "## SKU Overview\n\n| Category | SKU Count |\n|---|---|\n| Bakery | 3 |",
      "truncated": false,
      "originalLength": 0,
      "pageUrl": "/skus"
    },
    "preprocessId": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

Response (202 Accepted):

```json
{
  "taskId": "sa_1784556364221_60c600a7a559",
  "status": "PENDING",
  "streamUrl": "/snap-agent/runs/sa_1784556364221_60c600a7a559/stream"
}
```

Subscribe via `GET /snap-agent/runs/{taskId}/stream` to receive SSE events — the format is identical to a normal skill run (see [§3.3 Streaming Output Lifecycle](#33-streaming-output-lifecycle)). Audit log records `action=RUN_ANCHOR_QA`.

See [§10 REST API Reference](#10-rest-api-reference) for the full endpoint list.

### 9.5.5 Related Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/snap-agent/anchor.js` | Static asset: anchor script (public) |
| `GET` | `/snap-agent/anchor/config` | Returns `{enabled, disabledPaths}` for the client (public) |
| `POST` | `/snap-agent/anchor/preprocess` | Pre-summarize + pre-classify on anchor click (auth required) |
| `POST` | `/snap-agent/runs` | Extended: `skillId="auto"` + `anchor` field triggers anchor Q&A (auth required) |

### 9.5.6 Common Use Cases

| Scenario | HTML |
|----------|------|
| Docs site: auto Q&A on any section | No annotation needed; script auto-scans `<section>` / `<h2[id]>` |
| Business list page: per-section Q&A | `<section data-snap-anchor="SKU Overview">` |
| Ops dashboard: abnormal metric Q&A | `<div data-snap-anchor="QPS Anomaly" data-snap-skill="patrol">` |
| Form: field help | `<div data-snap-anchor="Order ID Field" data-snap-skill="off">` |
| SKU detail: basic info Q&A | `<section data-snap-anchor="Basic Info" data-snap-skill="auto">` |

---

## 9.6 Anchor Content Injection

### Overview

Anchor content injection mode (`data-snap-mode="inject"`) automatically requests backend skill or workflow to generate HTML content on page load, injecting it at the anchor position. Suitable for personalized recommendations, system announcements, daily tips, and other scenarios that don't require user interaction.

### Annotating Inject Anchors

```html
<div data-snap-anchor="announcement"
     data-snap-mode="inject"
     data-snap-skill="announcement"
     data-snap-cache-ttl="3600"
     data-snap-fallback='<p>Unavailable</p>'>
</div>
```

| Attribute | Description |
|-----------|-------------|
| `data-snap-mode="inject"` | Enable injection mode |
| `data-snap-skill` | Skill ID (one of skill/workflow, skill preferred) |
| `data-snap-workflow` | Workflow ID |
| `data-snap-cache-ttl` | Cache TTL (seconds), 0=no cache |
| `data-snap-fallback` | Fallback HTML |

### Per-User Personalization

Cache key format: `userId:sourceId:anchorName:pageUrl`. Different users see different injected content.

### REST API

```bash
curl -X POST /snap-agent/anchor/inject \
  -H "Content-Type: application/json" \
  -d '{"anchorName":"announcement","pageUrl":"/dashboard","skillId":"announcement","cacheTtl":3600}'
```

---

## 10. REST API Reference

All endpoints are mounted under `${snap-agent.base-path:/snap-agent}`. Except for `GET /auth-config` (public), every endpoint requires an authenticated user through the host security framework and passes `SecurityGateway.hasPermission(required-permission)`.

### 10.1 Endpoint quick reference

| # | Method | Path | Purpose |
|---|--------|------|---------|
| 1 | `GET` | `/auth-config` | Frontend reads the token source (header/cookie/localStorage key) |
| 2 | `GET` | `/user-info` | Current user + `activeProfiles` + authorization status |
| 3 | `GET` | `/skills` | List all skills (builtin + custom) |
| 4 | `GET` | `/skills/{name}` | (embedded in `/skills`) Get skill definition |
| 5 | `POST` | `/skills/refresh` | Re-scan the upload directory |
| 6 | `DELETE` | `/skills/{name}` | Delete a custom skill (builtin returns 403) |
| 7 | `POST` | `/skills/upload` | Multipart upload a single file |
| 8 | `POST` | `/skills/upload-folder` | Multipart upload a directory |
| 9 | `GET` | `/tools` | Registered tool-name list |
| 10 | `GET` | `/tools/plugins` | Registered `ToolPlugin` metadata |
| 11 | `GET` | `/models` | LLM allowed models (`default` + `allowed`) |
| 12 | `POST` | `/runs` | Start a skill run |
| 13 | `GET` | `/runs?status=&skillId=&page=&size=` | Paginated runs for the current user |
| 14 | `GET` | `/runs/{id}` | Task detail |
| 15 | `GET` | `/runs/{id}/transcript` | Full transcript (all events) |
| 16 | `GET` | `/runs/{id}/report` | Diagnostic report as plain text |
| 17 | `GET` | `/runs/{id}/stream?token=` | SSE stream (token authenticates EventSource) |
| 18 | `POST` | `/runs/{id}/cancel` | Cancel a running task |
| 19 | `GET` | `/audit?action=&page=&size=` | Audit log (requires AuditStore) |
| 20 | `POST` | `/conversations` | Save/update a conversation |
| 21 | `GET` | `/conversations?skillId=` | List conversations for the current user |
| 22 | `GET` | `/conversations/{id}` | Load full conversation messages |
| 23 | `GET` | `/conversations/{id}/download` | Download conversation as Markdown |
| 24 | `DELETE` | `/conversations/{id}` | Delete a conversation |
| 25 | `GET` | `/knowledge/status` | Knowledge base status |
| 26 | `GET` | `/knowledge/search?q=` | Knowledge retrieval |
| 26.5 | `GET` | `/knowledge/fragments` | List all knowledge fragments (new in v1.1) |
| 27 | `GET` | `/workflows` | Workflow list |
| 28 | `GET` | `/workflows/{name}` | Workflow detail (with steps) |
| 29 | `POST` | `/workflows/{name}/run` | Trigger workflow execution |
| 30 | `POST` | `/runs/{taskId}/solution` | Issue closure: propose solutions |
| 31 | `POST` | `/runs/{taskId}/issue` | Issue closure: create external issue |
| 32 | `GET` | `/issues/{issueId}` | Issue closure: issue detail |
| 33 | `POST` | `/issues/{issueId}/verify` | Issue closure: verify fix |
| 34 | `POST` | `/issues/{issueId}/close` | Issue closure: close and sediment |
| 35 | `POST` | `/runs/{id}/bugfix-suggestion` | Generate template-based bugfix suggestion (if `TemplateBugfixSuggester` is assembled) |
| 36 | `GET` | `/cost/summary?from=&to=&groupBy=` | Global cost summary |
| 37 | `GET` | `/cost/users/{userId}/summary?from=&to=` | User-dimension cost |
| 38 | `GET` | `/cost/skills/{skillName}/summary?from=&to=` | Skill-dimension cost |
| 39 | `POST` | `/patrol/tasks` | Create a patrol task (proactive monitoring) |
| 40 | `GET` | `/patrol/tasks` | Patrol task list |
| 41 | `DELETE` | `/patrol/tasks/{id}` | Delete a patrol task |
| 42 | `GET` | `/patrol/reports` | Patrol report list |
| 43 | `GET` | `/patrol/reports/{id}` | Patrol report detail |
| 44 | `GET` | `/alerts` | Active alert list |
| 45 | `POST` | `/alerts/{id}/resolve` | Resolve an alert |
| 46 | `GET` | `/anchor.js` | Static asset: anchor script (public, no auth) |
| 47 | `GET` | `/anchor/config` | Anchor feature config (public): `{enabled, disabledPaths}` |
| 48 | `POST` | `/anchor/preprocess` | Anchor pre-summarize + pre-classify (auth required, returns `preprocessId`) |
| 49 | `POST` | `/anchor/inject` | Anchor content injection (auth required): skill/workflow generates HTML and caches |

### 10.2 End-to-end example: run health-check with curl

```bash
# (1) List skills, confirm health-check is available
curl -s -u alice:secret http://localhost:8080/snap-agent/skills | jq '.skills[] | select(.name=="health-check")'
# {
#   "name": "health-check",
#   "description": "Performs a basic health check of the application — ...",
#   "availability": "AVAILABLE",
#   "tools": ["mysql_query"],
#   "source": "builtin"
# }

# (2) Start a run
curl -s -u alice:secret -X POST http://localhost:8080/snap-agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"health-check","inputs":{},"model":"claude-sonnet-4-6"}'
# {
#   "taskId": "8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b",
#   "status": "PENDING",
#   "streamUrl": "/snap-agent/runs/8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b/stream"
# }

# (3) Subscribe to the SSE stream (-N disables curl buffering so events show up live)
curl -N -u alice:secret \
  "http://localhost:8080/snap-agent/runs/8f7c2e1a-3b4d-4e5f-9a6b-7c8d9e0f1a2b/stream"
# event: thought
# data: {"text":"Let me verify DB connectivity..."}
#
# event: tool_call
# data: {"tool":"mysql_query","args":{"sql":"SELECT 1 AS ok"}}
#
# event: tool_result
# data: {"content":"ok=1\n1 row (12ms)"}
#
# event: done
# data: {"status":"SUCCEEDED","report":"DB reachable; 47 tables..."}
```

### 10.3 EventSource authentication (SSE token parameter)

Browsers' `EventSource` cannot send custom headers, so the SSE endpoint is `permitAll` and accepts `?token=base64(user:pass)` as a query parameter. The controller decodes it and skips the ownership check. The frontend gets `userId` from `/user-info` at startup, then forms the token as `base64(userId:password)`.

### 10.4 Common error codes

| HTTP | `error` field | Meaning |
|------|---------------|---------|
| 401 | `UNAUTHORIZED` | Not authenticated |
| 403 | `FORBIDDEN` | Lacks `required-permission` |
| 404 | `SKILL_NOT_FOUND` / `TASK_NOT_FOUND` / `CONVERSATION_NOT_FOUND` / `ISSUE_NOT_FOUND` / `WORKFLOW_NOT_FOUND` | Resource does not exist |
| 400 | `INVALID_INPUT` / `INVALID_STATUS` / `MODEL_NOT_ALLOWED` | Bad input |
| 409 | `SKILL_UNAVAILABLE` / `ANCHOR_DISABLED` | Skill's required tool is not assembled / Anchor feature is disabled (`skillId="auto"` + `anchor` field but `snap-agent.anchor.enabled=false`) |
| 429 | `RATE_LIMITED` | Rate-limited (`Retry-After: 30`) |
| 503 | `*_DISABLED` | Corresponding subsystem not enabled (conversation / cost / workflow / issue-closure / audit) |

---

## Related Documentation

- [System Architecture Overview](../architecture/en/system-architecture.md)
- [Host Integration Guide](../integration/en/host-integration-guide.md)
- [Anchor Q&A Integration Guide](../integration/en/anchor-feature-guide.md)
- [Knowledge Search Algorithm](../search/en/knowledge-search.md)
- [Tool Plugin Architecture](../plugins/en/tool-plugin-architecture.md)
- [Workflow Engine Architecture](../workflow/en/workflow-engine-architecture.md)
- [Issue Closure Architecture](../issue/en/issue-closure-architecture.md)
- [Proactive Monitoring Architecture](../proactive/en/proactive-monitoring-architecture.md)
- [Multi-Cluster Deployment Architecture](../deployment/en/multi-cluster-architecture.md)
