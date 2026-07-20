# SnapAgent Host Integration Guide

> Version: v1.0 | Updated: 2026-07-17

## 1. Integration Overview

SnapAgent is an embeddable LLM diagnostic Agent library that gives Spring Boot 2.x applications read-only diagnostic capabilities (database queries, log analysis, metrics inspection, code understanding, etc.). It ships as two Maven modules:

- **`snap-agent-core`** — Pure logic layer (servlet-agnostic): Skill parsing, Agent execution loop, LLM SPI, Tool SPI.
- **`snap-agent-spring-boot-2x-starter`** — Spring Boot 2.x auto-configuration layer: AutoConfiguration, Controller, Filter, Security adapters, built-in Tool implementations, Anthropic/OpenAI LLM clients.

The host application only needs to add the Starter dependency and set `snap-agent.enabled=true` to get a full Agent Web UI and REST API under `{base-path}/**`. The host provides business data sources (DataSource / RedisTemplate), a security framework (Spring Security / Shiro), and LLM Provider credentials; SnapAgent handles skill parsing, tool dispatch, streaming execution, and permission checks.

```
┌──────────────────────────────────────────────────────────────┐
│                     Host App                                 │
│  ┌────────────┐  ┌─────────────┐  ┌────────────────────┐    │
│  │ Business    │  │ DataSource  │  │ RedisTemplate      │    │
│  │ Controllers │  │ (read-only) │  │ / log dirs / code   │    │
│  └────────────┘  └──────┬──────┘  └─────────┬──────────┘    │
│                         │                   │                │
│  ┌──────────────────────▼───────────────────▼──────────┐     │
│  │        SnapAgent Starter (auto-discovered, @Component) │     │
│  │  ┌──────────────┐ ┌────────────┐ ┌───────────────┐  │     │
│  │  │ ToolProvider │ │ SkillLoader│ │ SecurityGateway│  │     │
│  │  │ (Jdbc/Redis/ │ │ (builtin+  │ │ (Spring/Shiro) │  │     │
│  │  │  Code/Ops…)  │ │  uploaded) │ │                │  │     │
│  │  └──────────────┘ └────────────┘ └───────────────┘  │     │
│  │         AgentExecutor + SSE Controller              │     │
│  └──────────────────────┬──────────────────────────────┘     │
│                         │ /v1/messages (SSE)                  │
└─────────────────────────┼────────────────────────────────────┘
                          ▼
              ┌───────────────────────┐
              │   LLM Provider        │
              │  (Anthropic / Tongyi /│
              │   Wenxin / Zhipu /    │
              │   self-hosted)        │
              └───────────────────────┘
```

### Design Principles

- **Zero intrusion**: When `snap-agent.enabled=false` (default), the Starter creates zero beans — no Filter, no thread pool, no routes (TDD_SPEC §AC15).
- **Read-only diagnostics**: All built-in tools are read-only (SELECT queries, GET requests, file reads); they never mutate host state.
- **Delegated authentication**: SnapAgent does not implement authentication itself; it only reads the host's already-authenticated Principal and checks permissions.
- **Auto-discovery**: A custom tool only needs to implement `ToolProvider` + `@Component` to be collected by `ToolDispatcher`.

---

## 2. Maven Dependencies

### 2.1 Core Dependency

The Maven coordinates for both modules (groupId is `cn.watsontech.snapagent`, matching the Java package):

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

> `snap-agent-core` is a transitive dependency of the Starter — no need to declare it separately. The Starter marks `spring-web`, `javax.servlet-api`, `spring-security-core`, `spring-jdbc`, `spring-data-redis`, and `okhttp` as `<optional>`, so the host brings in only what it needs without polluting the classpath.

### 2.2 Optional Dependencies the Host Must Provide

Built-in tools in the Starter are conditionally assembled via `@ConditionalOnClass` / `@ConditionalOnBean`; they activate only when the corresponding dependency is on the classpath:

| Built-in tool | Required dependency | Notes |
|---------------|---------------------|-------|
| `mysql_query` (JdbcQueryToolProvider) | `spring-jdbc` + a `DataSource` bean + JDBC driver | Default `snap-agent.jdbc.enabled=true` |
| `redis_read` (RedisReadToolProvider) | `spring-data-redis` + a `RedisTemplate` bean | Default `snap-agent.redis.enabled=true` |
| `log_read` (LogReadToolProvider) | No extra dependency | Default `snap-agent.logs.enabled=true` |
| `metrics_query` / `log_search` / `trace_search` | No extra dependency (JDK HttpURLConnection) | Each `enabled` defaults to false; needs `base-url` |
| LLM streaming (AnthropicLlmClient / OpenAiLlmClient) | `com.squareup.okhttp3:okhttp` | **Required** — LLM calls won't work without it |

A typical host `pom.xml` dependency snippet (mirrors the `snap-agent-demo` module):

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!-- SnapAgent Starter (transitively depends on core) -->
    <dependency>
        <groupId>cn.watsontech.snapagent</groupId>
        <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
    <!-- OkHttp (optional in Starter; host must declare explicitly) -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
    </dependency>
    <!-- MySQL driver (choose per your actual database) -->
    <dependency>
        <groupId>mysql</groupId>
        <artifactId>mysql-connector-java</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 2.3 Best Practice: Excluding SnapAgent's Built-in Skills

SnapAgent's built-in skill Markdown files are packaged inside the Starter JAR at `classpath:/docs/skills/`. The `ClasspathSkillScanner` uses a two-pass scan: **SnapAgent JAR resources first** (URL contains `snap-agent-spring-boot` or `snap-agent-core`), then host classpath resources; when names collide, the SnapAgent version wins and the host version is skipped with a WARN log.

If the host wants to **completely remove** SnapAgent's built-in skills (e.g., to ship an entirely custom skill set), use a Maven `<exclusion>` to drop the Starter's `docs/skills/` resources:

```xml
<dependency>
    <groupId>cn.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
    <exclusions>
        <exclusion>
            <groupId>cn.watsontech.snapagent</groupId>
            <artifactId>snap-agent-core</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> Caution: Excluding `snap-agent-core` also removes the core SPI — not recommended. The more common approach is to keep the built-in skills and override individual ones by name via the upload directory (`snap-agent.upload-skills-dir`): custom skills take precedence over builtins with the same name, and deleting the custom version restores the builtin automatically. See Section 6.

If the host project's own `src/main/resources/docs/skills/` should not be scanned, exclude that directory in the Maven `<build><resources>` section, or place host skills in the upload directory rather than on the classpath.

---

## 3. Configuration Reference

All properties are prefixed with `snap-agent` and bound by `SnapAgentProperties` (`@ConfigurationProperties(prefix = "snap-agent")`). The tables below list every property, its default, and description, grouped by feature module.

### 3.1 Core Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `snap-agent.enabled` | `false` | Master switch. When false, zero beans are created |
| `snap-agent.base-path` | `/snap-agent` | Controller path prefix; all endpoints mount under `{base-path}/**` |
| `snap-agent.builtin-skills-dir` | `classpath*:/docs/skills/` | Classpath directory for built-in skills (read-only, in JAR). `classpath*:` ensures all classpath roots are scanned |
| `snap-agent.upload-skills-dir` | `/tmp/snap-agent-skills` | Filesystem directory for uploaded skills (read-write, persists across restarts). Conversation history and issues are also stored here |
| `snap-agent.app-profiles` | `""` (auto-resolved) | Host Spring active profiles, comma-joined. Auto-resolved from `environment.getActiveProfiles()` at startup; skills can reference `{_app_profile}` |

### 3.2 LLM Configuration (`snap-agent.llm.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `api-type` | `anthropic` | LLM API type: `anthropic` (default) or `openai` (OpenAI-compatible) |
| `base-url` | `https://api.anthropic.com` | LLM service root URL |
| `api-key` | `""` | API Key (Anthropic uses `x-api-key` header) |
| `auth-token` | `""` | Bearer token (use with api-key, or standalone) |
| `proxy-url` | `""` | HTTP proxy URL (for proxy gateways like cc-switch) |
| `model` | `claude-sonnet-4-6` | Default model name |
| `allowed-models` | `[]` | Model whitelist (empty = no restriction) |
| `max-tokens` | `8192` | Max tokens per response |
| `timeout-seconds` | `120` | HTTP connect + read timeout (shares this budget) |
| `streaming` | `true` | Whether SSE streaming is enabled |

> The `llmClient` bean is created only when `api-key` or `auth-token` is non-empty (`@ConditionalOnExpression`). If both are empty, AgentExecutor logs a WARN and cannot function.

### 3.3 Agent Execution Configuration (`snap-agent.agent.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `max-turns` | `20` | Max LLM turns per task |
| `task-timeout-minutes` | `30` | Task timeout in minutes |
| `executor` | `snapAgentExecutor` | Async executor bean name |
| `max-concurrent-runs-per-user` | `1` | Max concurrent tasks per user |
| `max-runs-per-hour` | `20` | Max tasks per user per hour |
| `max-result-rows` | `1000` | Max rows returned by SQL queries |
| `max-tool-result-chars` | `50000` | Max characters in a single tool result |
| `transcript-event-limit` | `500` | Transcript event count limit |

### 3.4 JDBC Configuration (`snap-agent.jdbc.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Whether to assemble JdbcQueryToolProvider |
| `datasource-bean-name` | `snapAgentReadOnlyDataSource` | DataSource bean name in single-DS mode |
| `datasources` | `{}` | Multi-environment datasource map (v0.6). key=env name, value=`{url,username,password,driver-class-name}` |
| `default-env` | `""` | Default env name (empty = first entry) |

> Without `datasources`, single-DataSource mode is used (bean fetched from host context); with `datasources`, `DataSourceRegistry` multi-env mode is used and the skill schema gains an `env` parameter.

### 3.5 Redis Configuration (`snap-agent.redis.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Whether to assemble RedisReadToolProvider (also requires `RedisTemplate` on classpath) |
| `redis-template-bean-name` | `redisTemplate` | RedisTemplate bean name |
| `max-key-count` | `100` | Max keys returned per call |

### 3.6 Logs Configuration (`snap-agent.logs.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Whether to assemble LogReadToolProvider |
| `allowed-paths` | `[]` | List of log directories allowed to read |
| `max-lines` | `500` | Max log lines returned per call |
| `max-file-bytes` | `10485760` (10MB) | Max single file size (prevents OOM) |
| `app-log-file` | `""` (auto-resolved) | App's own log file path, auto-resolved from `logging.file.name` |

### 3.7 Security Configuration (`snap-agent.security.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `framework` | `auto` | Security framework: `auto` (pick Spring/Shiro by classpath) / `spring` / `shiro` |
| `required-permission` | `snap-agent:access` | Permission code required to access SnapAgent. **Setting to an empty string is equivalent to allowing anonymous access** (`hasPermission("")` returns true) |
| `filter-order` | `Integer.MAX_VALUE - 10` | SnapAgentFilter order in the filter chain (ensures it runs after host security filters) |
| `principal-resolver-class` | `""` | Custom PrincipalResolver fully-qualified class name |
| `audit-log` | `true` | Whether audit logging is enabled |
| `auth-token-header` | `""` | Frontend auth token header name |
| `auth-token-cookie` | `""` | Frontend auth token cookie name |
| `auth-token-local-storage-key` | `""` | Frontend localStorage key (for the Web UI to read) |

> Note: SnapAgent has no `allow-anonymous` switch. Anonymous access is achieved by setting `required-permission` to empty (the permission code is empty so `hasPermission` returns true), but authentication is still performed by the host framework. See Section 7.

### 3.8 Code Understanding Configuration (`snap-agent.code.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch for code tools |
| `project-root` | `""` | Host project root directory (absolute path). **Non-empty and a real directory is required** to assemble CodePathGuard |
| `allowed-extensions` | `.java,.xml,.yml,.yaml,.properties,.sql,.md,.txt,.json,.csv` | File extension whitelist |
| `max-lines` | `500` | Max lines returned by a single code_read call |
| `max-file-bytes` | `524288` (512KB) | Max single file size |
| `structure-depth` | `3` | Default scan depth for project_structure tool |
| `context-injection` | `true` | Whether to inject project structure summary into the system prompt |

> `CodePathGuard` uses `@ConditionalOnExpression` to ensure `enabled=true` and `project-root` is non-empty; the three tools (`code_read`/`project_structure`/`git_log`) use `@ConditionalOnBean(CodePathGuard.class)`.

### 3.9 Operations Diagnostics Configuration

**Metrics (`snap-agent.metrics.*`)** — Prometheus:

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | metrics_query tool switch |
| `base-url` | `""` | Prometheus URL, e.g. `http://prometheus:9090` |
| `auth-header` / `auth-header-value` | `""` | Auth header name and value (e.g. `Authorization` / `Bearer xxx`) |
| `timeout-seconds` | `15` | HTTP timeout |
| `max-points` | `200` | Max data points per series |

**Log search (`snap-agent.log-search.*`)** — Loki: same structure as metrics; `max-lines` defaults to 500.

**Trace search (`snap-agent.trace.*`)** — Jaeger: same structure as metrics; `max-traces` defaults to 20.

**Config read (`snap-agent.config-read.*`)**:

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | config_read tool switch |
| `nacos-base-url` | `""` | Nacos remote config URL (empty = local Spring Environment only) |
| `nacos-namespace` | `""` | Nacos namespace ID |
| `nacos-auth-token` | `""` | Nacos auth token |
| `max-keys` | `100` | Max local properties returned per call |
| `sensitive-key-patterns` | `password,secret,token,credential,key` | Sensitive key patterns (values masked to `****`) |

### 3.10 Knowledge Base Configuration (`snap-agent.knowledge.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Knowledge base switch |
| `sources` | `[]` | Knowledge source list; each entry `{type: markdown, dir: classpath:/docs/knowledge/}` |
| `max-fragments` | `3` | Max fragments injected into the system prompt per query |
| `min-score` | `0.1` | Injection threshold; fragments below this score are not injected |

### 3.11 Code Graph Configuration (`snap-agent.code-graph.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Code graph switch (also requires `code.enabled=true` to share CodePathGuard) |
| `scan-packages` | `[]` | Package prefixes to scan (empty = scan all .java under project-root) |
| `max-depth` | `5` | Max depth for call chain queries (forward and reverse) |
| `max-impact-depth` | `3` | Max depth for impact analysis queries |

### 3.12 Proactive Monitoring Configuration

**Patrol (`snap-agent.patrol.*`)**:

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Scheduled patrol switch |
| `scheduler-pool-size` | `2` | Patrol scheduler thread pool size |
| `report-buffer-size` | `500` | Patrol report ring buffer size |
| `lock-ttl-seconds` | `300` | Multi-Pod coordination lock TTL (seconds, default 5 min, new in v1.1) |

**Alert convergence (`snap-agent.alert.*`)**:

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Alert convergence switch |
| `buffer-size` | `1000` | Alert buffer size |
| `auto-resolve-minutes` | `30` | Alert auto-resolve minutes |
| `push.email.enabled` | `false` | Email push switch (new in v1.1) |
| `push.email.from` | `snap-agent@local` | Email sender |
| `push.email.to` | `[]` | Email recipient list |
| `push.email.subject-prefix` | `[SnapAgent Alert]` | Email subject prefix |
| `push.webhook.enabled` | `false` | Webhook push switch (new in v1.1) |
| `push.webhook.url` | _empty_ | Webhook push URL; bean only wired when non-empty |
| `push.webhook.auth-header` | `Authorization` | Webhook auth header name |
| `push.webhook.auth-token` | _empty_ | Webhook auth token |
| `push.webhook.connect-timeout-ms` | `5000` | Webhook connect timeout |
| `push.webhook.read-timeout-ms` | `10000` | Webhook read timeout |

### 3.13 Issue Closure Configuration (`snap-agent.issue-closure.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Issue closure switch |
| `system-user-id` | `system` | System user ID used when running solution-suggest/verify-fix skills |
| `storage-dir` | `""` | Issue JSON storage directory (empty = `{upload-skills-dir}/issues/`) |
| `tracker-type` | `noop` | IssueTracker type (`noop`/`jira`/`github`) |

### 3.14 Cost Accounting Configuration (`snap-agent.cost.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Cost accounting switch |
| `pricing.input` | `3.00` | Price per 1M input tokens |
| `pricing.output` | `15.00` | Price per 1M output tokens |
| `pricing.cache-read` | `0.30` | Price per 1M cache-read tokens |
| `pricing.currency` | `CNY` | Currency code |
| `budgets.per-user-daily` | `null` | Per-user daily budget (null = unlimited) |
| `budgets.per-skill-daily` | `null` | Per-skill daily budget |
| `budgets.global-daily` | `null` | Global daily budget |
| `storage-dir` | `""` | Cost record storage directory (empty = `{upload-skills-dir}/cost/`) |
| `warn-threshold` | `0.8` | Budget utilization ratio at which a warning is emitted |

### 3.15 Workflows Configuration (`snap-agent.workflows.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Workflow engine switch |
| `dir` | `""` | Workflow `.yml` file directory (empty = `{upload-skills-dir}/workflows/`) |

### 3.16 Other Configuration

- **Skill hot-reload (`snap-agent.skill.hot-reload`)**: default `true`; watches the upload directory for file changes and auto-refreshes SkillRegistry.
- **MCP (`snap-agent.mcp.*`)**: `enabled` defaults to false; `servers` is a map, each entry `{transport, url, auth-header, auth-header-value}`.
- **Cross-pod routing (`snap-agent.routing.*`)**: `mode` defaults to `none`; degradation chain `k8s-api` → `headless-dns` → `static` → `none`.

### 3.17 Minimal Runnable Configuration Example

```yaml
snap-agent:
  enabled: true
  llm:
    base-url: https://api.anthropic.com
    auth-token: ${ANTHROPIC_AUTH_TOKEN}   # recommended: inject via env var
    model: claude-sonnet-4-6
  security:
    required-permission: snap-agent:access  # set to empty string for anonymous
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

---

## 4. Startup Flow

When the host application starts, Spring Boot auto-configures `SnapAgentAutoConfiguration` via `META-INF/spring.factories`. The class carries `@ConditionalOnProperty(prefix = "snap-agent", name = "enabled", havingValue = "true")`, so it activates only when `snap-agent.enabled=true`. The assembly order after activation:

```
Host startup
  │
  ▼
SnapAgentAutoConfiguration activates (snap-agent.enabled=true)
  │
  ├─ 1. Infrastructure beans
  │     SqlGuard(maxResultRows) → TaskStore → RateLimiter
  │     → PrincipalResolver → AuditStore + SecurityAuditLogger
  │
  ├─ 2. SecurityGateway (one of two, by classpath)
  │     @ConditionalOnClass(SecurityContextHolder) → SpringSecurityAdapter
  │     @ConditionalOnClass(ShiroUtils)            → ShiroAdapter
  │     (host can declare a custom SecurityGateway bean to override, @ConditionalOnMissingBean)
  │
  ├─ 3. LlmClient (routed by api-type)
  │     api-key or auth-token non-empty → create LlmClient:
  │       api-type=openai  → OpenAiLlmClient  (POST {base-url}/v1/chat/completions)
  │       api-type=anthropic(default) → AnthropicLlmClient (POST {base-url}/v1/messages)
  │
  ├─ 4. Tool layer (conditionally assembled)
  │     DataSourceRegistry(multi-env) / JdbcQueryToolProvider(@ConditionalOnBean DataSource)
  │     RedisReadToolProvider(@ConditionalOnClass RedisTemplate)
  │     LogPathGuard + LogReadToolProvider
  │     CodePathGuard(@ConditionalOnExpression code.enabled + project-root non-empty)
  │       → ProjectContextExtender + CodeReader/ProjectStructure/GitLog ToolProvider
  │     Metrics/LogSearch/Trace/ConfigRead ToolProvider (each enabled+base-url)
  │
  ├─ 5. ToolDispatcher (collects all ToolProvider beans)
  │     ObjectProvider<ToolProvider>.orderedStream() → List
  │     + McpBootstrap.getProviders() (if enabled)
  │     custom @Component ToolProvider beans are also collected
  │
  ├─ 6. Skill layer
  │     ClasspathSkillScanner.scan(builtin-skills-dir)
  │       two-pass scan: SnapAgent JAR resources first → host classpath resources second
  │     SkillRegistry(uploadDir, builtinSkills, toolDispatcher)
  │       merge: custom overrides builtin by name; deleting custom restores builtin
  │     SkillHotReloader (watches upload-skills-dir, on by default)
  │
  ├─ 7. SystemPromptExtender collection (ObjectProvider.orderedStream)
  │     ProjectContextExtender (v0.3, project structure summary)
  │     KnowledgeInjector (v0.7, business knowledge fragments) — if knowledge.enabled
  │
  ├─ 8. AgentExecutor
  │     if cost.enabled → wrap original LlmClient with CostTrackingLlmClient
  │     new AgentExecutor(llmClient, toolDispatcher, taskStore, maxTurns, maxTokens, extenders)
  │
  ├─ 9. Thread pool + routing
  │     snapAgentExecutor (ThreadPoolTaskExecutor: core=2, max=4, queue=10)
  │     PeerRouter (mode: k8s-api/headless-dns/static/none)
  │     PeerSseRelay + InternalTaskController (internal pod-to-pod endpoints)
  │
  └─ 10. Web layer
        SnapAgentFilter (FilterRegistrationBean, url-pattern={base-path}/*)
        SnapAgentController (mounted at {base-path}/**, injects all ObjectProvider optional deps)
        auto-resolves app-profiles + app-log-file
```

### Key Assembly Points

1. **`@ConditionalOnMissingBean` precedence**: Nearly all built-in beans are annotated with `@ConditionalOnMissingBean`; the host can declare a same-named bean to replace it (e.g., a custom `SecurityGateway`, `ConversationStore`, `LlmClient`, `PrincipalResolver`).
2. **Conditional tool activation**: Without `spring-jdbc`, `JdbcQueryToolProvider` is never created (`@ConditionalOnBean(DataSource.class)`); without Redis, `RedisReadToolProvider` is skipped.
3. **`ClasspathSkillScanner` two-pass scan**: SnapAgent JAR resources (URL contains `snap-agent-spring-boot` or `snap-agent-core`) are processed first, then host classpath resources; on name collision the host version is skipped with a WARN, preventing accidental shadowing of built-in skills.
4. **`ToolDispatcher` collects all `ToolProvider` beans**: both built-in (Jdbc/Redis/Code/Ops) and host-custom (`@Component` implementing `ToolProvider`), dispatched uniformly.

---

## 5. Custom Tools

### 5.1 ToolProvider SPI

A custom tool only needs to implement the `ToolProvider` interface (in `snap-agent-core`) and be annotated with `@Component` to be auto-discovered by `ToolDispatcher`:

```java
package cn.watsontech.snapagent.core.tool;

import java.util.Map;

public interface ToolProvider {
    /** Unique tool name; referenced by the tools field in skill frontmatter */
    String name();

    /** JSON Schema string injected into the LLM tool definition (Anthropic tool format) */
    String schema();

    /**
     * Execute the tool call.
     * @param args arguments parsed from the LLM tool_use block
     * @param ctx  request-scoped context (taskId, userId, audit)
     * @return immutable result; never null
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
```

### 5.2 Full Example: Custom HTTP Health Check Tool

```java
package com.example.myapp.tools;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

@Component
public class HttpHealthCheckToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "http_health_check";
    }

    @Override
    public String schema() {
        return "{\"name\":\"http_health_check\","
            + "\"description\":\"Check HTTP endpoint health (read-only GET).\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{\"url\":{\"type\":\"string\","
            + "\"description\":\"Absolute HTTP(S) URL to check\"}},"
            + "\"required\":[\"url\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String url = args.get("url") != null ? args.get("url").toString() : null;
        if (url == null || url.isEmpty()) {
            return ToolResult.error("missing required parameter: url", 0);
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            String content = "HTTP " + code + " from " + url;
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.error("request failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }
}
```

No extra registration is needed — `@Component` lets Spring scan it, and `ToolDispatcher` collects it via `ObjectProvider<ToolProvider>.orderedStream()`. Reference it in a skill's frontmatter with `tools: [http_health_check]`.

### 5.3 Context and Result

**`ToolContext`** (immutable, request-scoped):
```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;  // may be null
}
```

**`ToolResult`** (immutable, built via factory methods):
```java
ToolResult.success(content, rowCount, durationMs);   // success
ToolResult.truncated(content, rowCount, durationMs); // success but truncated
ToolResult.error(message, durationMs);               // failure (content is null)
```
Fields: `content`, `rowCount`, `truncated`, `durationMs`, `error`.

**`AuditCallback`**: invoked by `ToolDispatcher` after a tool returns, for audit recording:
```java
void onToolExecuted(String toolName, Map<String, Object> args, ToolResult result);
```

### 5.4 JSON Schema Conventions

`schema()` returns a JSON string (not a JSON object — a String) in the Anthropic tool-use format. Key fields:

- `name`: must match `name()`
- `description`: the LLM uses this to decide when to call the tool
- `input_schema`: a JSON Schema, `type: object` + `properties` + `required`

Refer to the built-in `JdbcQueryToolProvider` schema (multi-env mode additionally exposes an `env` parameter):

```json
{
  "name": "mysql_query",
  "description": "Execute a read-only SQL query.",
  "input_schema": {
    "type": "object",
    "properties": {
      "sql": {"type": "string", "description": "SELECT/SHOW/DESCRIBE/EXPLAIN/WITH query"},
      "env": {"type": "string", "description": "Environment name (e.g. sit/uat). Empty=use default."}
    },
    "required": ["sql"]
  }
}
```

---

## 6. Custom Skills

### 6.1 Skill Markdown Format

A skill is a Markdown file consisting of YAML frontmatter + body. `SkillLoader` parses the frontmatter with SnakeYAML's `SafeConstructor` (prevents deserialization attacks).

**Required frontmatter fields**: `name`, `description`.
**Optional frontmatter fields**: `tools` (list of tool names), `inputs` (input parameters), `shortcuts` (quick-action buttons), `required-permission` (skill-level permission code).

```markdown
---
name: my-diagnose
description: "Custom diagnostic skill: locate root cause from symptoms. Trigger when user says 'investigate XXX issue'."
tools: [mysql_query, log_read, http_health_check]
inputs:
  - key: symptom
    label: "Symptom description"
    required: true
    type: string
    default: ""
  - key: env
    label: "Environment"
    required: false
    type: enum
    options: [sit, uat, prod]
    default: sit
shortcuts:
  - label: "🔍 Slow queries"
    message: "Investigate slow queries from the last hour"
  - label: "⚠️ Error logs"
    message: "Show ERROR logs from the last hour"
required-permission: ops:diagnose
---

# Custom Diagnostics

## Step 1: Understand the Symptom
Read the user-provided `{symptom}` and identify:
- The affected entity (orders / users / products)
- The time range
- The anomaly

## Step 2: Check Logs
Call `log_read` to read application logs, filtering ERROR level...
```

### 6.2 Frontmatter Field Reference

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `name` | yes | string | Unique skill identifier (independent of filename) |
| `description` | yes | string | Description (including trigger context, for LLM/frontend display) |
| `tools` | no | list&lt;string&gt; | Dependent tool names; empty means a pure-LLM skill |
| `inputs` | no | list | Input parameter definitions |
| `inputs[].key` | yes | string | Parameter key (referenced as `{key}` in templates) |
| `inputs[].label` | no | string | Frontend display label (defaults to key) |
| `inputs[].required` | no | boolean | Whether required (default false) |
| `inputs[].type` | no | string | `string`/`enum`/`date`/`number`/`boolean` (default string) |
| `inputs[].options` | no | list&lt;string&gt; | Options for enum type |
| `inputs[].default` | no | string | Default value |
| `shortcuts` | no | list | Frontend quick-action buttons |
| `shortcuts[].label` | yes | string | Button text |
| `shortcuts[].message` | yes | string | Message sent on click |
| `required-permission` | no | string | Skill-level permission code (empty = inherit global `security.required-permission`) |

### 6.3 Skill Loading

SnapAgent uses a two-layer skill system:

- **Built-in skills (builtin)**: loaded from `snap-agent.builtin-skills-dir` (default `classpath*:/docs/skills/`), read-only, packaged in the JAR. `source="builtin"`.
- **Uploaded skills (custom)**: loaded from `snap-agent.upload-skills-dir` (default `/tmp/snap-agent-skills`), read-write, persists across restarts. `source="custom"`.

**Merge logic**: custom overrides builtin by `name` (`overridesBuiltin=true`); deleting the custom version restores the builtin automatically.

**Directory skill rules**:
- A subdirectory containing `SKILL.md` → the whole directory is one skill; only `SKILL.md` is parsed, other files (`.md`/non-`.md`) are auxiliary and skipped.
- A subdirectory without `SKILL.md` → an organizational directory; recurse into its files/subdirectories.

### 6.4 Packaging vs Runtime Upload

**Option 1: Package with the host JAR** (recommended for stable built-in business skills)

Place `.md` files under the host's `src/main/resources/docs/skills/`; they enter the classpath after build and are scanned by `ClasspathSkillScanner` as `source="host"` (note: a same-named SnapAgent JAR built-in wins; the host version is skipped + WARN).

```text
my-app/
└─ src/main/resources/docs/skills/
   ├─ order-diagnose.md          # standalone skill
   └─ inventory-check/
      ├─ SKILL.md                 # directory skill (only this is parsed)
      └─ reference.md            # auxiliary file (skipped)
```

**Option 2: Upload at runtime** (for frequently iterated skills)

Upload to `upload-skills-dir` via the REST API:

```bash
# Upload a single skill file
curl -X POST http://localhost:8080/snap-agent/skills/upload \
  -F "file=@order-diagnose.md"

# Upload a directory skill (multiple files + dirName)
curl -X POST http://localhost:8080/snap-agent/skills/upload-folder \
  -F "dirName=inventory-check" \
  -F "files=@SKILL.md" \
  -F "files=@reference.md"
```

After upload, `SkillHotReloader` (on by default) watches the directory for changes and triggers `SkillRegistry.refresh()` automatically — no restart needed.

**Option 3: Drop files directly**

Copy `.md` files directly into the `upload-skills-dir`; the hot reloader discovers them automatically.

### 6.5 Full Example: Reference the Built-in health-check Skill

```markdown
---
name: health-check
description: "Performs a basic health check of the application — verifies database connectivity and key configuration. Use when the user asks 'is the system healthy?' or 'check health'."
tools: [mysql_query]
---

# Health Check

## Step 1: Verify Database Connectivity
Use the `mysql_query` tool to run a simple query:
```sql
SELECT 1 AS ok
```
If this fails, report that the database is unreachable.

## Step 2: Check Table Counts
Check if key tables exist and have data:
```sql
SELECT TABLE_NAME, TABLE_ROWS
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME
LIMIT 20
```

## Step 3: Report Summary
Summarize the health status:
- Database connectivity: OK / FAILED
- Table count summary
- Any anomalies detected
```

---

## 7. Security Integration

### 7.1 SecurityGateway SPI

SnapAgent does not implement authentication itself; it only reads the host's already-authenticated Principal and checks permissions via the `SecurityGateway` SPI:

```java
public interface SecurityGateway {
    /** Returns the current authenticated user id, or null if not authenticated */
    String currentUserId();
    /** Checks the permission code; an empty code returns true (equivalent to no check) */
    boolean hasPermission(String code);
}
```

The default implementation is auto-selected by classpath (`@ConditionalOnClass` + `@ConditionalOnMissingBean`):

- **`SpringSecurityAdapter`**: assembled when `SecurityContextHolder` is on the classpath. `currentUserId()` reads the Authentication principal from `SecurityContextHolder` and resolves it via `PrincipalResolver`; `hasPermission()` iterates `Authentication.getAuthorities()` and does an **exact match** (not wildcard).
- **`ShiroAdapter`**: assembled when `org.apache.shiro.SecurityUtils` is on the classpath (fallback).

### 7.2 Common Pitfall: Enterprise Apps Store Permissions Outside GrantedAuthority

**Problem**: `SpringSecurityAdapter.hasPermission()` iterates `Authentication.getAuthorities()` for an exact match. Many enterprise apps (e.g., scpdrp-saas) store permissions in a custom field of the Principal (e.g., `LoginUser.permissionList`) rather than in `GrantedAuthority` → `hasPermission()` returns false → `authorized: false` → all requests return 403.

**Solution**: the host declares a custom `SecurityGateway` bean (extends `SpringSecurityAdapter`, overrides `hasPermission`). Thanks to `@ConditionalOnMissingBean`, the host bean takes precedence:

```java
package com.example.myapp.security;

import cn.watsontech.snapagent.boot2x.security.SpringSecurityAdapter;
import cn.watsontech.snapagent.core.security.PrincipalResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CustomSecurityGateway extends SpringSecurityAdapter {

    public CustomSecurityGateway(PrincipalResolver principalResolver) {
        super(principalResolver);
    }

    @Override
    public boolean hasPermission(String code) {
        if (code == null || code.isEmpty()) {
            return true;  // empty permission code = no check
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser)) {
            return false;
        }
        LoginUser user = (LoginUser) auth.getPrincipal();
        List<String> permissions = user.getPermissionList();  // read from custom field
        return permissions != null && permissions.contains(code);
    }
}
```

### 7.3 Permission Check Flow

The Controller's `requireAuth()` method uniformly checks all non-SSE endpoints:

1. `securityGateway.currentUserId()` is null → 401 UNAUTHORIZED.
2. `securityGateway.hasPermission(required-permission)` is false → 403 FORBIDDEN.
3. Otherwise, proceed.

**Anonymous access**: SnapAgent has no `allow-anonymous` switch. Set `snap-agent.security.required-permission` to an empty string (`hasPermission("")` returns true), but authentication is still performed by the host framework.

**Skill-level permissions**: set `required-permission: ops:diagnose` in frontmatter; the Controller checks that skill's permission on POST `/runs`; when empty, the global `security.required-permission` is inherited.

### 7.4 SSE Streaming Endpoint Authentication

The browser `EventSource` API does not support custom headers, so the SSE endpoint `GET /runs/{id}/stream` authenticates differently:

- The endpoint is set to `permitAll` in the host security filter chain (no forced auth).
- The frontend passes credentials via `?token=base64(user:pass)` query param.
- The Controller decodes the token, extracts the `user` part, and skips the ownership check.
- Without a token, it falls back to `SecurityGateway.currentUserId()`.

```javascript
// Frontend example
const token = btoa(`${username}:${password}`);
const es = new EventSource(`/snap-agent/runs/${taskId}/stream?token=${token}`);
```

### 7.5 Filter and Path

`SnapAgentFilter` is registered as a `FilterRegistrationBean` with URL pattern `{base-path}/*` and order `Integer.MAX_VALUE - 10` (ensures it runs after the host security filter chain). It only injects the authenticated principal into `AgentRequestContext`; it does not perform authentication — that is the host framework's job.

---

## 8. Multi-LLM Integration

SnapAgent switches LLM client implementations via `snap-agent.llm.api-type`. The `SnapAgentAutoConfiguration.llmClient()` bean method routes by `api-type`:

```java
if ("openai".equalsIgnoreCase(apiType)) {
    return new OpenAiLlmClient(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds);
}
return new AnthropicLlmClient(baseUrl, apiKey, authToken, proxyUrl, timeoutSeconds);
```

### 8.1 Anthropic (Default)

The Anthropic client POSTs to `{base-url}/v1/messages` using the Anthropic streaming SSE protocol (`message_start`/`content_block_delta`/`message_delta` events), authenticating with the `x-api-key` header.

```yaml
snap-agent:
  llm:
    api-type: anthropic          # or omit (default)
    base-url: https://api.anthropic.com
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-6
    max-tokens: 8192
    timeout-seconds: 120
```

### 8.2 OpenAI-Compatible (Tongyi / Wenxin / Zhipu / vLLM / Ollama)

The OpenAI client POSTs to `{base-url}/v1/chat/completions` using the OpenAI streaming chunk protocol, authenticating with `Authorization: Bearer {token}`. It is compatible with OpenAI, Azure OpenAI, and all OpenAI-compatible gateways.

**Tongyi Qianwen (Alibaba DashScope)**:
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: https://dashscope.aliyuncs.com/compatible-mode
    auth-token: ${DASHSCOPE_API_KEY}
    model: qwen-max
```

**Zhipu GLM**:
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: https://open.bigmodel.cn/api/paas/v4
    auth-token: ${ZHIPU_API_KEY}
    model: glm-4
```

**Self-hosted OpenAI-compatible gateway (e.g., vLLM/Ollama)**:
```yaml
snap-agent:
  llm:
    api-type: openai
    base-url: http://llm-gateway.internal:8000
    auth-token: ${INTERNAL_LLM_TOKEN}
    model: qwen2.5-72b-instruct
```

### 8.3 Proxy Gateway

To reach the LLM through an HTTP proxy (e.g., cc-switch or similar gateways), set `proxy-url`:

```yaml
snap-agent:
  llm:
    proxy-url: http://proxy.internal:8080
```

Both `AnthropicLlmClient` and `OpenAiLlmClient` accept a `proxyUrl` constructor argument and configure OkHttp's `Proxy(Type.HTTP, ...)`.

### 8.4 Fully Custom LlmClient

To integrate an LLM that speaks neither Anthropic nor OpenAI protocol (e.g., native Wenxin/Tongyi non-compatible mode), implement the `LlmClient` SPI and declare it as a Spring bean; `@ConditionalOnMissingBean` gives the custom implementation precedence:

```java
@Component
public class MyCustomLlmClient implements LlmClient {
    // implement stream(LlmRequest, LlmEventSink) + cancel(taskId) etc.
}
```

---

## 9. Deployment Checklist

Confirm each item before going to production:

- [ ] **Maven dependencies**: `snap-agent-spring-boot-2x-starter` added, and `okhttp` declared explicitly (optional in Starter)
- [ ] **`application.yml`**: `snap-agent.enabled=true`; LLM `base-url`/`api-key` or `auth-token` configured (recommended: inject via `${ENV}` environment variables)
- [ ] **Skills ready**: built-in skills suffice, or custom skills uploaded/packaged under `docs/skills/`
- [ ] **SecurityGateway configured**: if the enterprise app stores permissions outside `GrantedAuthority`, a custom `SecurityGateway` bean overriding `hasPermission` is declared
- [ ] **Permission code**: `snap-agent.security.required-permission` matches the host's permission system; set to empty for anonymous access
- [ ] **Knowledge base directory**: if using business knowledge injection, `snap-agent.knowledge.sources[].dir` points to a Markdown directory
- [ ] **Code project root**: if using code tools, `snap-agent.code.project-root` points to the host project root (absolute path), `enabled=true`
- [ ] **Observability endpoints**: if using ops diagnostics, `metrics.base-url` / `log-search.base-url` / `trace.base-url` and auth headers are configured
- [ ] **Cost budgets**: if using cost accounting, `snap-agent.cost.budgets.*` and `pricing.*` are set
- [ ] **Proactive monitoring**: if using patrol/alert, `snap-agent.patrol.*` / `snap-agent.alert.*` are configured
- [ ] **Alert push**: if using anomaly report push, configure `snap-agent.alert.push.email.*` or `snap-agent.alert.push.webhook.*` (new in v1.1)
- [ ] **Multi-Pod coordination lock**: in multi-Pod deployments, implement a custom `PatrolLockProvider` bean (Redis / k8s lease / DB row lock) — see §10 (new in v1.1)
- [ ] **Persistent storage**: to persist patrol reports / conversation history / Issues, implement custom SPI beans such as `PatrolReportStore` (new in v1.1)
- [ ] **basePath does not conflict**: `snap-agent.base-path` (default `/snap-agent`) does not collide with existing host routes
- [ ] **JDBC driver**: the host has the JDBC driver for its database (e.g., `mysql-connector-java`)
- [ ] **Log paths**: `snap-agent.logs.allowed-paths` lists allowed log directories (or confirm `logging.file.name` is auto-resolvable)
- [ ] **Smoke test**: after startup, `GET {base-path}/skills` returns the skill list, and a sample skill runs a full conversation end-to-end

---

## 10. Replaceable SPI Catalog (updated in v1.1)

All core SnapAgent components are exposed as SPI interfaces; hosts can replace any of
them. Implement the interface + `@Component` (or `@Bean`); `@ConditionalOnMissingBean`
yields to your custom bean.

### 10.1 SPI overview

| SPI interface | Default impl | Wiring condition | Purpose |
|---------------|-------------|-------------------|---------|
| `LlmClient` | `AnthropicLlmClient` | `snap-agent.llm.api-type=anthropic` | LLM streaming |
| `ToolProvider` | multiple built-in | `@Component` discovery | Tools |
| `SecurityGateway` | `SpringSecurityAdapter` | `@ConditionalOnMissingBean` | Permission check |
| `PrincipalResolver` | `SpringPrincipalResolver` | `@ConditionalOnMissingBean` | User identity resolution |
| `SystemPromptExtender` | `ProjectContextExtender` / `KnowledgeInjector` | `@ConditionalOnMissingBean` | system prompt injection |
| `ConversationStore` | `FileConversationStore` | `@ConditionalOnMissingBean` | Conversation history |
| `IssueStore` | `FileIssueStore` | `@ConditionalOnMissingBean` | Issue persistence |
| `IssueTracker` | `NoopIssueTracker` | `@ConditionalOnMissingBean` | External issue tracker |
| `KnowledgeSource` | `MarkdownKnowledgeSource` | `@ConditionalOnMissingBean` | Knowledge sources |
| `KnowledgeSearcher` | `SimpleKeywordSearcher` | `@ConditionalOnMissingBean` | Knowledge search |
| `CostStore` | `FileCostStore` | `@ConditionalOnMissingBean` | Cost record persistence |
| `CostTracker` | `DefaultCostTracker` | `@ConditionalOnMissingBean` | Cost tracking |
| `WorkflowEngine` | `SimpleWorkflowEngine` | `@ConditionalOnMissingBean` | Workflow engine |
| `PatrolScheduler` | `ScheduledPatrolScheduler` | `snap-agent.patrol.enabled=true` | Patrol scheduling |
| `PatrolReportStore` | `InMemoryPatrolReportStore` | `snap-agent.patrol.enabled=true` | Patrol report storage (v1.1 SPI) |
| `PatrolLockProvider` | `NoopPatrolLockProvider` | `snap-agent.patrol.enabled=true` | Multi-Pod patrol lock (new in v1.1) |
| `AlertConverger` | `InMemoryAlertConverger` | `snap-agent.alert.enabled=true` | Alert convergence |
| `AnomalyEventListener` | `DefaultAnomalyEventListener` | `snap-agent.patrol.enabled=true` | Anomaly event handling |
| `BugfixSuggester` | `TemplateBugfixSuggester` | `@ConditionalOnMissingBean` | Fix suggestion generation |
| `AlertPushChannel` | `WebhookAlertPushChannel` + `EmailAlertPushChannel` | `snap-agent.alert.push.*` | Anomaly report push (new in v1.1, supports multiple beans) |

### 10.2 PatrolLockProvider — multi-Pod coordination

In multi-Pod deployments (e.g., K8s replicas), the same patrol task fires on every Pod,
causing duplicate execution and duplicate alerts. Implement `PatrolLockProvider` to fix:

```java
@Component
public class RedisPatrolLockProvider implements PatrolLockProvider {
    private final StringRedisTemplate redis;
    private final String podName = System.getenv().getOrDefault("MY_POD_NAME", "pod-0");

    @Override
    public boolean tryAcquire(String patrolId, long ttlSeconds) {
        String key = "patrol:lock:" + patrolId;
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, podName, Duration.ofSeconds(ttlSeconds)));
    }

    @Override
    public void release(String patrolId) {
        // Use a Lua script to verify owner before deleting, to avoid releasing someone else's lock
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        redis.execute(new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList("patrol:lock:" + patrolId), podName);
    }

    @Override
    public String type() { return "redis"; }
}
```

> TTL defaults to 300 seconds (`snap-agent.patrol.lock-ttl-seconds`), covering the
> longest single patrol. If a Pod crashes, the lock auto-expires after TTL and other
> Pods take over on the next cron tick.

### 10.3 AlertPushChannel — custom push channels

Implement `AlertPushChannel` + `@Component` to work alongside the default
Webhook/Email channels (all `AlertPushChannel` beans are collected into a
`List<AlertPushChannel>`; anomaly reports fan out to all of them):

```java
@Component
public class DingTalkAlertPushChannel implements AlertPushChannel {
    @Override
    public void push(PatrolReport report, AnomalyEvent event) {
        if (report == null || !report.isAnomalyDetected()) return;
        // Build DingTalk markdown message, POST to webhook URL
    }
    @Override
    public String type() { return "dingtalk"; }
}
```

### 10.4 PatrolReportStore — persistent patrol reports

The default `InMemoryPatrolReportStore` has limited capacity and loses data on restart;
production deployments can swap in a DB implementation:

```java
@Component
public class JdbcPatrolReportStore implements PatrolReportStore {
    private final JdbcTemplate jdbc;
    // CREATE TABLE patrol_report (id VARCHAR PRIMARY KEY, patrol_id VARCHAR,
    //   task_id VARCHAR, user_id VARCHAR, skill_name VARCHAR,
    //   triggered_at BIGINT, status VARCHAR, summary TEXT,
    //   anomaly_detected BOOLEAN)
    // ...
}
```

### 10.5 Optional dependencies for email push

`EmailAlertPushChannel` depends on `spring-context-support` + `javax.mail`, both marked
`<optional>true</optional>` in the starter pom. To enable:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

Then set `snap-agent.alert.push.email.enabled=true` + `to[]=ops@example.com`. Without
these deps, `@ConditionalOnClass` skips the bean and the host is unaffected.
