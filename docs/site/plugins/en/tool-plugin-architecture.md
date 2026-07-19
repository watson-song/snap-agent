# SnapAgent Tool Plugin Architecture

> Version: v1.0 | Updated: 2026-07-17

## 1. Architecture Overview

SnapAgent uses a **two-layer tool architecture** that separates tool execution capability from metadata declaration:

```
┌──────────────────────────────────────────────────────────┐
│                      AgentExecutor                        │
│   (execution loop: LLM → tool_use → dispatch → result)    │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   ToolDispatcher       │   │  ToolPluginRegistry  │
   │   (name routing +      │   │  (metadata collector) │
   │    truncation)          │   │  - getPlugins()      │
   │   - dispatch(name,args) │   │  - unconditional      │
   │   - availableToolNames()│   │    assembly           │
   └───────────┬───────────┘   └──────────┬──────────┘
               │                           │
   ┌───────────▼───────────┐   ┌──────────▼──────────┐
   │   ToolProvider (SPI)   │   │  ToolPlugin (SPI)    │
   │   Layer 1: exec + def   │   │  Layer 2: metadata   │
   │   - name()             │   │  - name()            │
   │   - schema()           │   │  - version()         │
   │   - execute() → Result │   │  - description()      │
   └───────────────────────┘   │  - toolNames()       │
                                └──────────────────────┘
```

### Two-Layer Responsibility Separation

**Layer 1: `ToolProvider` SPI (v0.1)**

The core SPI for tool execution. Each `ToolProvider` declares a unique `name()` and JSON Schema (Anthropic tool format), and provides an `execute()` method to process tool calls. Auto-discovered via `@Component` — any `ToolProvider` bean on the classpath is collected by `ToolDispatcher`.

**Layer 2: `ToolPlugin` SPI (v1.0)**

The tool plugin metadata layer. Declares plugin name, version, description, and the list of tool names contributed. Collected unconditionally by `ToolPluginRegistry` and exposed via the `GET /tools/plugins` endpoint. **The metadata layer does not affect tool discovery** — even without implementing `ToolPlugin`, a `ToolProvider` is still auto-discovered and executable.

### Call Chain

```
LLM → tool_use event → AgentExecutor → ToolDispatcher.dispatch(name, args, ctx)
    → ToolProvider.execute(args, ctx) → ToolResult → back to LLM → continue reasoning
```

---

## 2. Core SPI

### ToolProvider

The tool provider SPI, defining tool name, JSON Schema, and execution logic:

```java
public interface ToolProvider {
    /** Unique tool name referenced by skill frontmatter {@code tools} field. */
    String name();

    /** JSON Schema string injected into the LLM tools definition (Anthropic format). */
    String schema();

    /**
     * Execute the tool with the given arguments and context.
     *
     * @param args arguments parsed from the LLM tool_use block
     * @param ctx  request-scoped context (userId, audit)
     * @return immutable result; never {@code null}
     */
    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
```

### ToolContext

Request-scoped context carrying task ID, user ID, and an audit callback:

```java
public final class ToolContext {
    private final String taskId;
    private final String userId;
    private final AuditCallback auditCallback;

    public ToolContext(String taskId, String userId, AuditCallback auditCallback) { ... }

    public String getTaskId() { ... }
    public String getUserId() { ... }
    public AuditCallback getAuditCallback() { ... }
}
```

### ToolResult

Immutable tool execution result, Java 8 compatible (final class + explicit getters), with three factory methods:

```java
public final class ToolResult {
    private final String content;
    private final int rowCount;
    private final boolean truncated;
    private final long durationMs;
    private final String error;

    /** Successful non-truncated result. */
    public static ToolResult success(String content, int rowCount, long durationMs) { ... }

    /** Successful but truncated result (content exceeds limits). */
    public static ToolResult truncated(String content, int rowCount, long durationMs) { ... }

    /** Error result — content is null, error message is set. */
    public static ToolResult error(String message, long durationMs) { ... }

    public String getContent() { ... }
    public int getRowCount() { ... }
    public boolean isTruncated() { ... }
    public long getDurationMs() { ... }
    public String getError() { ... }
    public boolean isSuccess() { ... }   // error == null
    public boolean isError() { ... }     // error != null
}
```

### AuditCallback

Audit callback invoked after tool execution, called by `ToolDispatcher` inside `dispatch()`:

```java
public interface AuditCallback {
    /**
     * Called by ToolDispatcher after a tool provider returns a result.
     *
     * @param toolName the registered name of the tool
     * @param args     the arguments passed to the tool
     * @param result   the result returned by the tool
     */
    void onToolExecuted(String toolName, Map<String, Object> args, ToolResult result);
}
```

Defined in the `tool` package so that `ToolContext` can carry it without depending on the `agent` package. The agent layer provides an implementation that constructs `AuditRecord` objects. Audit failures never break the agent loop.

### ToolDispatcher

Routes `tool_use` calls to the matching `ToolProvider` by name:

```java
public class ToolDispatcher {
    public ToolDispatcher(Collection<ToolProvider> providerList, int maxToolResultChars) { ... }

    /** Returns the set of registered tool names. */
    public Set<String> availableToolNames() { ... }

    /** Returns the registered providers (for schema extraction by the executor). */
    public Collection<ToolProvider> providers() { ... }

    /**
     * Dispatch a tool call to the registered provider.
     * Unknown tool names return an error Result so the LLM can self-correct.
     * Successful results exceeding maxToolResultChars are auto-truncated.
     */
    public ToolResult dispatch(String name, Map<String, Object> args, ToolContext ctx) { ... }

    /** Builds a human-readable tool definition listing for system prompt injection. */
    public String buildToolDefinitions() { ... }
}
```

Key behaviors:
- At construction, builds an immutable Map<name, ToolProvider> indexed by `ToolProvider.name()`
- `dispatch()` catches `RuntimeException`, returns `ToolResult.error()` instead of throwing
- Successful results exceeding `maxToolResultChars` are truncated with a `[truncated, total N rows]` suffix
- Calls `AuditCallback.onToolExecuted()` after each execution (if present in ctx); audit exceptions are silently swallowed

### ToolPlugin (v1.0 Metadata SPI)

```java
public interface ToolPlugin {
    /** Plugin name (unique identifier). */
    String name();

    /** Version. */
    String version();

    /** Description. Empty string by default. */
    default String description() { return ""; }

    /** List of tool names contributed. Empty list by default. */
    default List<String> toolNames() { return Collections.emptyList(); }
}
```

`ToolPlugin` is a pure metadata layer — it does not affect tool discovery. Implementers are encouraged to also implement `ToolProvider` (or register a separate `ToolProvider` bean) so their tools are discoverable. The default methods allow minimal implementations to only declare `name()` and `version()`.

---

## 3. Built-in Tools

SnapAgent provides the following built-in `ToolProvider` implementations, grouped by functional domain:

### Data Diagnostics Tools

| Provider | Tool Name | Description | Enable Config |
|----------|-----------|-------------|---------------|
| `JdbcQueryToolProvider` | `mysql_query` | Multi-environment read-only SQL queries, pipe-delimited table output, validated by `SqlGuard` | `snap-agent.jdbc.*` (configure DataSource) |
| `RedisReadToolProvider` | `redis_get` | Read-only Redis operations, supports `get`/`exists` commands, rejects all write/scan commands | `snap-agent.redis.*` (configure RedisTemplate) |

### Code Understanding Tools

| Provider | Tool Name | Description | Enable Config |
|----------|-----------|-------------|---------------|
| `CodeReaderToolProvider` | `code_read` | Read source files, supports line-range selection and keyword filtering (±2 context lines), validated by `CodePathGuard` | `snap-agent.code.enabled=true` + `project-root` |
| `ProjectStructureToolProvider` | `project_structure` | Scan project directory tree, excludes target/.git/node_modules, returns tree-formatted layout | `snap-agent.code.enabled=true` + `project-root` |
| `GitLogToolProvider` | `git_log` | View git history, supports log/blame/show modes, commit_hash regex validation | `snap-agent.code.enabled=true` + `project-root` |

### Log Analysis Tools

| Provider | Tool Name | Description | Enable Config |
|----------|-----------|-------------|---------------|
| `LogReadToolProvider` | `log_read` | Read application log files, supports keyword search, log-level filtering, and tail mode, validated by `LogPathGuard` | `snap-agent.logs.allowed-paths` |

### Operations Diagnostics Tools

| Provider | Tool Name | Description | Enable Config |
|----------|-----------|-------------|---------------|
| `MetricsToolProvider` | `metrics_query` | Prometheus PromQL queries, supports instant queries and range queries (time series) | `snap-agent.metrics.enabled=true` + `base-url` |
| `LogSearchToolProvider` | `log_search` | Loki LogQL log search, nanosecond timestamps, supports stream labels and direction control | `snap-agent.log-search.enabled=true` + `base-url` |
| `TraceSearchToolProvider` | `trace_search` | Jaeger distributed trace search, microsecond timestamps, span tree rendering, slow span marking | `snap-agent.trace.enabled=true` + `base-url` |
| `ConfigReadToolProvider` | `config_read` | Read application config, supports local Spring Environment (sensitive field masking) and Nacos HTTP API | `snap-agent.config-read.enabled=true` |

### Code Graph Tools

| Provider | Tool Name | Description | Enable Config |
|----------|-----------|-------------|---------------|
| `CodeGraphToolProvider` | `code_graph_tools` | Code relationship graph queries, single tool with 4 sub-tools (see below) | `snap-agent.code-graph.enabled=true` |

The 4 sub-tools of `code_graph_tools`:

| Sub-tool | Parameters | Description |
|----------|------------|-------------|
| `call_chain` | `query` + `max_depth` (default 5) | Forward call chain (what methods does this method call, expanded layer by layer) |
| `reverse_chain` | `query` + `max_depth` (default 5) | Reverse call chain (who calls this method) |
| `impact_analysis` | `query` + `max_depth` (default 3) | Change impact analysis (which downstream code is affected if this node changes) |
| `find` | `pattern` | Fuzzy name lookup for code nodes (class/method/field) |

---

## 4. ToolPluginRegistry

### How It Works

`ToolPluginRegistry` is the collector for `ToolPlugin` beans, assembled **unconditionally** (`@ConditionalOnMissingBean`, but created whenever the Spring container exists):

```java
@Bean
@ConditionalOnMissingBean
public ToolPluginRegistry toolPluginRegistry(ObjectProvider<ToolPlugin> toolPluginProvider) {
    List<ToolPlugin> plugins = toolPluginProvider.orderedStream()
            .collect(Collectors.toList());
    log.info("ToolPluginRegistry assembled with {} plugin(s)", plugins.size());
    return new ToolPluginRegistry(plugins);
}
```

- When no `ToolPlugin` beans exist, the plugin list is empty — the Registry bean is still created
- `ToolPlugin` beans are collected in Spring `@Order` sequence
- `getPlugins()` returns an unmodifiable list view

### ToolProvider vs ToolPlugin Relationship

| Dimension | `ToolProvider` | `ToolPlugin` |
|-----------|----------------|---------------|
| Since | v0.1 | v1.0 |
| Responsibility | Provides tool definitions + executes tool calls | Declares plugin metadata (name/version/description/tool names) |
| Discovery | `@Component` → collected by `ToolDispatcher` | `@Component` → collected by `ToolPluginRegistry` |
| Affects execution | Yes — tools without a `ToolProvider` cannot be called | No — pure metadata layer |
| REST exposure | `GET /tools` (tool names only) | `GET /tools/plugins` (full metadata) |

A single plugin can contain multiple tools (e.g., `CodeGraphToolProvider` exposes one tool name `code_graph_tools` with 4 internal sub-tools). `ToolPlugin.toolNames()` declares the list of tool names a plugin contributes, for operational visibility.

### Registered Plugin Examples

| Plugin Name | Version | Description | Tool Names |
|-------------|---------|-------------|------------|
| `weather` | 1.0.0 | Weather query plugin | `["weather_query"]` |
| `inventory` | 2.1.0 | Inventory diagnostics plugin | `["inventory_check", "stock_alert"]` |

> The table above shows custom plugin examples. Built-in tools currently do not mandate `ToolPlugin` metadata; hosts can optionally implement `ToolPlugin` for their tools to gain operational visibility.

---

## 5. Tool Execution Flow

### Execution Sequence

```
  LLM                 AgentExecutor          ToolDispatcher        ToolProvider
   │                       │                      │                     │
   │── tool_use ──────────▶│                      │                     │
   │   (name, args)        │                      │                     │
   │                       │── record assistant    │                     │
   │                       │   message + tool_use  │                     │
   │                       │                      │                     │
   │                       │── build ToolContext ─│                     │
   │                       │   (taskId,userId,    │                     │
   │                       │    auditCallback)     │                     │
   │                       │                      │                     │
   │                       │── record tool_call ─▶│                     │
   │                       │   transcript event   │                     │
   │                       │                      │                     │
   │                       │── dispatch(name, ───▶│                     │
   │                       │   args, ctx)          │                     │
   │                       │                      │── lookup by name ─▶│
   │                       │                      │   ToolProvider       │
   │                       │                      │                      │── execute(args, ctx)
   │                       │                      │                      │
   │                       │                      │                      │── ToolResult ──▶│
   │                       │                      │◀────────────────── │
   │                       │                      │                      │
   │                       │                      │── truncate (if needed)│
   │                       │                      │── invoke audit        │
   │                       │                      │   callback           │
   │                       │◀── ToolResult ───────│                     │
   │                       │                      │                     │
   │                       │── record tool_result▶│                     │
   │                       │   transcript event   │                     │
   │                       │                      │                     │
   │                       │── add tool_result     │                     │
   │                       │   message to convo   │                     │
   │                       │                      │                     │
   │◀── tool_result ───────│                      │                     │
   │   (content/error)     │                      │                     │
   │                       │                      │                     │
   │── continue reasoning ▶│                      │                     │
   │   (next LLM turn)     │                      │                     │
```

### Detailed Steps

1. **LLM emits tool_use blocks**: The LLM's assistant message contains `tool_use` blocks (tool name + arguments JSON), collected by `TurnCollector` via `LlmEventSink`
2. **AgentExecutor checks stop_reason**: If `tool_use`, the LLM needs tool results before continuing
3. **Record assistant message**: The assistant message with `tool_use` blocks is added to the conversation list (so the next request can match `tool_result` to `tool_use` IDs)
4. **Build ToolContext**: `buildToolContext(task)` creates a context carrying taskId, userId, and AuditCallback
5. **Dispatch each tool**: For each `ToolUseBlock`:
   - Record `tool_call` transcript event (toolId, toolName, input)
   - Call `toolDispatcher.dispatch(name, input, ctx)`
   - Dispatcher looks up ToolProvider by name, executes `execute(args, ctx)`
   - Truncates overlong results, invokes audit callback
   - Record `tool_result` transcript event (content preview 500 chars, rowCount, truncated, durationMs, error)
   - Add `tool_result` message to conversation list
6. **Continue next turn**: Call LLM again with tool_result messages; the LLM continues reasoning based on tool results

---

## 6. Security Constraints

SnapAgent's tool system is designed for read-only safety. All tools **do not provide write, delete, or execute operations**.

### SqlGuard — SQL Read-Only Policy

The first syntactic defense line for `JdbcQueryToolProvider`, defending against prompt-injection-driven write operations:

| Step | Rule |
|------|------|
| 1. Strip comments | Remove line comments `--`, block comments `/* */`, hash comments `#` |
| 2. Strip trailing semicolons | Remove trailing `;` and MySQL `\g`/`\G` terminators |
| 3. Reject multi-statement | If `;` or `\g`/`\G` remains after trailing trim, reject |
| 4. First-keyword whitelist | `WITH` / `SELECT` / `SHOW` / `DESCRIBE` / `EXPLAIN` / `DESC` |
| 5. Dangerous-keyword blacklist | `INSERT` / `UPDATE` / `DELETE` / `DROP` / `CREATE` / `ALTER` / `TRUNCATE` / `GRANT` / `REVOKE` / `LOAD` / `SLEEP`, etc. |
| 6. LIMIT injection/rewrite | Append `LIMIT {max}` when absent; rewrite when over max |

> SqlGuard is a syntactic analyzer, not a semantic one. The secondary defense is the read-only DB user granted to the agent's DataSource.

### LogPathGuard — Log Path Safety

The path safety defense for `LogReadToolProvider`:

- **Allowed directory list**: Only reads within configured `snap-agent.logs.allowed-paths` directories
- **Reject directory traversal**: Paths containing `..` are rejected immediately (before any filesystem access)
- **File validation**: Must exist, must be a regular file, must not exceed `maxFileBytes` limit
- Defends against LLM reading `/etc/passwd`, `.env`, and other sensitive files

### CodePathGuard — Code Path Safety

The path safety defense for `CodeReaderToolProvider`, `ProjectStructureToolProvider`, and `GitLogToolProvider`:

- **Single project root**: All paths must be under `snap-agent.code.project-root`
- **Reject directory traversal**: Paths containing `..` are rejected
- **Extension whitelist**: Only allows configured extensions like `.java`, `.xml`, `.yml`
- **Size limit**: Files must not exceed `maxFileBytes` limit
- `resolveWithinProject()` is used for directory scanning, without checking existence or extension

### Other Security Measures

| Measure | Description |
|---------|-------------|
| Redis read-only | `RedisReadToolProvider` only allows `get`/`exists` commands, rejects `set`/`del`/`incr`/`keys`, etc. |
| Git safety | `GitLogToolProvider` uses `ProcessBuilder` argument lists (never shell), `commit_hash` regex `^[0-9a-f]{7,40}$` validation, 10s timeout |
| Timeout enforcement | Operations tools (Metrics/LogSearch/TraceSearch) use `config.timeoutSeconds * 1000` ms timeout (default 15s), Nacos fixed 15s |
| Sensitive masking | `ConfigReadToolProvider` masks values with keys containing `password`/`secret`/`token`/`credential`/`key` as `****` |
| Result truncation | `ToolDispatcher` truncates results exceeding `maxToolResultChars` to prevent LLM context overflow |
| Read-only HTTP | Operations tools only use HTTP GET requests, no POST/PUT/DELETE |

---

## 7. Custom Tool Plugin

### Complete Example: WeatherToolProvider

A custom tool plugin that calls a weather API, demonstrating the combination of `ToolProvider` (execution layer) and `ToolPlugin` (metadata layer):

```java
package com.example.snapagent.tools;

import cn.watsontech.snapagent.core.tool.ToolContext;
import cn.watsontech.snapagent.core.tool.ToolPlugin;
import cn.watsontech.snapagent.core.tool.ToolProvider;
import cn.watsontech.snapagent.core.tool.ToolResult;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;

/**
 * Custom weather query tool — calls an external weather API.
 */
@Component
public class WeatherToolProvider implements ToolProvider {

    private static final String SCHEMA = "{\"name\":\"weather_query\","
            + "\"description\":\"Query current weather information for a given city.\","
            + "\"input_schema\":{\"type\":\"object\","
            + "\"properties\":{"
            + "\"city\":{\"type\":\"string\",\"description\":\"City name, e.g. 'Beijing' or 'Shanghai'\"}"
            + "},"
            + "\"required\":[\"city\"]}}";

    private final String apiBaseUrl;
    private final String apiKey;

    public WeatherToolProvider(@Value("${weather.api-base-url}") String apiBaseUrl,
                                @Value("${weather.api-key}") String apiKey) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "weather_query";
    }

    @Override
    public String schema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        long start = System.currentTimeMillis();

        // 1. Extract parameters
        String city = args != null ? (String) args.get("city") : null;
        if (city == null || city.isEmpty()) {
            return ToolResult.error("missing required parameter: city",
                    System.currentTimeMillis() - start);
        }

        // 2. Call weather API (read-only GET)
        try {
            URL url = new URL(apiBaseUrl + "/v1/current?q=" + city + "&key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code != 200) {
                return ToolResult.error("weather API returned HTTP " + code,
                        System.currentTimeMillis() - start);
            }

            // 3. Parse response (simplified example)
            String body = readAll(conn.getInputStream());
            String content = "# Weather: " + city + "\n" + body;
            return ToolResult.success(content, 1, System.currentTimeMillis() - start);

        } catch (IOException e) {
            return ToolResult.error("weather API call failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private String readAll(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) != -1) {
            buf.write(b, 0, n);
        }
        return buf.toString("UTF-8");
    }
}
```

### Corresponding Metadata Declaration

```java
package com.example.snapagent.tools;

import cn.watsontech.snapagent.core.tool.ToolPlugin;

import java.util.Collections;
import java.util.List;

/**
 * Metadata declaration for the weather query plugin.
 * Once registered, plugin info is visible via GET /tools/plugins.
 */
@Component
public class WeatherToolPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "Weather query plugin — provides city weather information lookup";
    }

    @Override
    public List<String> toolNames() {
        return Collections.singletonList("weather_query");
    }
}
```

### Registration

**No configuration needed** — both classes are annotated with `@Component`, and Spring component scanning auto-discovers them:

- `WeatherToolProvider` → collected by `ToolDispatcher`'s `ObjectProvider<ToolProvider>` → LLM can call `weather_query`
- `WeatherToolPlugin` → collected by `ToolPluginRegistry`'s `ObjectProvider<ToolPlugin>` → `GET /tools/plugins` returns this plugin's metadata

### JSON Schema Notes

The tool schema follows the Anthropic tool format:

```json
{
    "name": "weather_query",
    "description": "Query current weather information for a given city.",
    "input_schema": {
        "type": "object",
        "properties": {
            "city": {
                "type": "string",
                "description": "City name, e.g. 'Beijing' or 'Shanghai'"
            }
        },
        "required": ["city"]
    }
}
```

- `name`: Must match `ToolProvider.name()`
- `description`: The LLM uses this to decide when to call this tool; describe its purpose clearly
- `input_schema`: JSON Schema format, defining parameter types, descriptions, and required fields
- `required`: Marks which parameters are mandatory; the LLM will self-correct if it omits required parameters

---

## 8. REST API

### GET /tools

Returns the name list of all registered tools:

```json
{
    "tools": [
        { "name": "mysql_query" },
        { "name": "redis_get" },
        { "name": "log_read" },
        { "name": "code_read" },
        { "name": "project_structure" },
        { "name": "git_log" },
        { "name": "metrics_query" },
        { "name": "log_search" },
        { "name": "trace_search" },
        { "name": "config_read" },
        { "name": "code_graph_tools" },
        { "name": "weather_query" }
    ]
}
```

> The actual tool list depends on the enabled configuration items. For example, `mysql_query` requires DataSource configuration, `metrics_query` requires `snap-agent.metrics.enabled=true`. The list above shows all built-in tools plus one custom tool.

### GET /tools/plugins

Returns metadata for all registered plugins (v1.0):

```json
[
    {
        "name": "weather",
        "version": "1.0.0",
        "description": "Weather query plugin — provides city weather information lookup",
        "toolNames": ["weather_query"]
    },
    {
        "name": "inventory",
        "version": "2.1.0",
        "description": "Inventory diagnostics plugin — inventory check and stock alerts",
        "toolNames": ["inventory_check", "stock_alert"]
    }
]
```

When no `ToolPlugin` beans exist, an empty array `[]` is returned.

> Both endpoints require authentication (`requireAuth()`), validated through the `SecurityGateway`. Audit logs record `LIST_TOOLS` and `LIST_TOOL_PLUGINS` operations.
