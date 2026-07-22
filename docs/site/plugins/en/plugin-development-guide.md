# SnapAgent Custom Plugin Development Guide

> Version: v0.5 | Updated: 2026-07-22

This guide covers the full lifecycle of developing a custom Plugin for SnapAgent — from project generation through packaging, upload, configuration, and testing.

---

## 1. Core Concepts

### Plugin = 1 ToolProvider + Metadata Declaration

A SnapAgent Plugin is a hot-pluggable tool unit. Each Plugin contains:

- **1 `ToolProvider` implementation** — provides `name()`, `schema()`, `execute()`
- **Metadata declaration** — via `@ToolPluginAnnotation` annotation (preferred) or `plugin-info.yml` (fallback)

### toolType vs pluginId

| Concept | Description | Example |
|---------|-------------|---------|
| `toolType` | Tool name the LLM sees when calling. One toolType can have multiple Plugins | `log_read` |
| `pluginId` | Unique identifier for the Plugin | `remote-log`, `local-log` |

The LLM does not perceive plugins — it only sees `toolType`. `ToolDispatcher` routes to the specific implementation via `pluginOverrides` or the default Plugin.

### 1 plugin = 1 tool

One Plugin corresponds to one LLM-callable tool. A tool can have multiple operations (via schema parameter branching), e.g., `mysql_query` can support `query`, `explain`, and `slow-log` operations.

### system plugin vs custom plugin

| Type | Source | Removable |
|------|--------|-----------|
| system plugin | Built-in `@Component ToolProvider` beans, auto-wrapped at startup | No |
| custom plugin | JAR uploaded via `POST /tools/plugins/upload` | Yes |

Backward compatibility: existing `@Component ToolProvider` beans require no modification — auto-wrapped as system plugins at startup.

---

## 2. Quick Start

### 2.1 Generate a Project with Maven Archetype

```bash
mvn archetype:generate \
  -DarchetypeGroupId=cn.watsontech.snapagent \
  -DarchetypeArtifactId=snap-agent-plugin-archetype \
  -DarchetypeVersion=0.4.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=my-remote-log-plugin \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.example \
  -DpluginId=remote-log \
  -DtoolType=log_read \
  -DdisplayName=RemoteLogQuery \
  -Ddescription="Query remote Loki logs" \
  -DclassName=RemoteLogToolProvider \
  -DinteractiveMode=false
```

Generated project structure:

```
my-remote-log-plugin/
├── pom.xml
├── src/main/java/com/example/
│   └── RemoteLogToolProvider.java
├── src/main/resources/META-INF/snap-agent/
│   └── plugin-info.yml
└── src/test/java/com/example/
│   └── RemoteLogToolProviderTest.java
```

### 2.2 Implement ToolProvider

The generated `RemoteLogToolProvider.java` contains a basic skeleton. The core is three methods:

```java
@ToolPluginAnnotation(
    id = "remote-log",
    toolType = "log_read",
    displayName = "RemoteLogQuery",
    description = "Query remote Loki logs",
    version = "1.0.0"
)
public class RemoteLogToolProvider implements ToolProvider {

    @Override
    public String name() {
        return "remote-log";
    }

    @Override
    public String schema() {
        return "{\"name\":\"log_read\", ... }";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = (String) args.get("query");
        return ToolResult.success(content, rowCount, durationMs);
    }
}
```

### 2.3 Multi-Operation Branching

A tool can support multiple operations via schema parameters:

```java
@Override
public String schema() {
    return "{\"name\":\"mysql_query\","
         + "\"input_schema\":{\"type\":\"object\","
         + "\"properties\":{"
         +   "\"operation\":{\"type\":\"string\",\"enum\":[\"query\",\"explain\",\"slow-log\"]},"
         +   "\"sql\":{\"type\":\"string\"}"
         + "},\"required\":[\"operation\"]}}";
}

@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String operation = (String) args.get("operation");
    switch (operation) {
        case "query":     return executeQuery(args, ctx);
        case "explain":   return executeExplain(args, ctx);
        case "slow-log":  return executeSlowLog(args, ctx);
        default:          return ToolResult.error("unknown operation: " + operation, 0);
    }
}
```

---

## 3. Packaging

```bash
cd my-remote-log-plugin
mvn clean package
```

Packaging notes:

- `snap-agent-core` uses `provided` scope — **not included** in the plugin JAR
- `spring-boot-starter` is also `provided + optional` — not included
- The output is a skinny JAR, small in size
- Do NOT build a fat JAR — the host already has Spring/Jackson etc., duplicating them causes ClassLoader conflicts

Output: `target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar`

---

## 4. Upload

```bash
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -H "Authorization: Basic $(echo -n 'admin:password' | base64)" \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"
```

Response example:

```json
{
    "pluginId": "remote-log",
    "toolType": "log_read",
    "displayName": "RemoteLogQuery",
    "version": "1.0.0",
    "isDefault": false,
    "enabled": true,
    "system": false,
    "jarPath": "/data/snap-agent/plugins/remote-log/plugin.jar"
}
```

Upload flow:
1. JAR saved to `${upload-skills-dir}/plugins/{pluginId}/plugin.jar`
2. `URLClassLoader` created (parent = main application ClassLoader)
3. Metadata scanned (`@ToolPluginAnnotation` preferred, `plugin-info.yml` fallback)
4. `ToolProvider` instantiated (requires no-arg constructor)
5. `PluginDescriptor` constructed + registered in `PluginRegistry`
6. `isDefault` defaults to `false` — call `PUT /tools/plugins/{id}/default` to set as default

---

## 5. Configuration

### YAML Configuration

```yaml
snap-agent:
  tools:
    remote-log:
      base-url: http://loki:3100
      timeout-seconds: 30
      max-lines: 500
```

### Reading Configuration in a Plugin

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    if (ctx.getPluginContext() != null) {
        Map<String, Object> config = ctx.getPluginContext().getConfiguration();
        String baseUrl = (String) config.get("base-url");
        int timeout = (Integer) config.getOrDefault("timeout-seconds", 30);
    }
}
```

Configuration source: all properties under `snap-agent.tools.{pluginId}.*`.

---

## 6. Disable / Enable / Set Default / Unregister

```bash
# Disable a plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/disable \
  -H "Authorization: Basic ..."

# Enable a plugin
curl -X POST http://localhost:8080/skills-agent/tools/plugins/remote-log/enable \
  -H "Authorization: Basic ..."

# Set as default plugin for this toolType
curl -X PUT http://localhost:8080/skills-agent/tools/plugins/remote-log/default \
  -H "Authorization: Basic ..."

# Unregister (system plugins return 403)
curl -X DELETE http://localhost:8080/skills-agent/tools/plugins/remote-log \
  -H "Authorization: Basic ..."
```

| Operation | Endpoint | Permission |
|-----------|----------|------------|
| Disable | `POST /tools/plugins/{id}/disable` | `snap-agent:plugin:manage` |
| Enable | `POST /tools/plugins/{id}/enable` | `snap-agent:plugin:manage` |
| Set default | `PUT /tools/plugins/{id}/default` | `snap-agent:plugin:manage` |
| Unregister | `DELETE /tools/plugins/{id}` | `snap-agent:plugin:manage` |

> In production, prefer **disable** over unregister — ClassLoader GC is not guaranteed to reclaim immediately.

---

## 7. Using a Plugin in a Skill

### Specify plugin via pluginOverrides

```bash
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Authorization: Basic ..." \
  -H "Content-Type: application/json" \
  -d '{
    "skillName": "log-analysis",
    "inputs": {"keyword": "timeout"},
    "pluginOverrides": {
      "log_read": "remote-log"
    }
  }'
```

### How It Works

```
LLM -> tool_use(log_read, args)
    -> ToolDispatcher.dispatch("log_read", args, ctx)
    -> ctx.pluginOverrides["log_read"] = "remote-log"
    -> registry.getPlugin("remote-log")
    -> plugin.provider.execute(args, ctx)
    -> ToolResult
```

- The LLM only sees `toolType` (e.g., `log_read`), not the plugin
- `ToolDispatcher` first checks `ctx.pluginOverrides[toolType]`, then `registry.getDefault(toolType)`
- Without `pluginOverrides`, the default plugin is used — backward compatible

---

## 8. Testing a Plugin

### Unit Test Template

The generated test skeleton includes 4 tests:

```java
class RemoteLogToolProviderTest {

    private RemoteLogToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new RemoteLogToolProvider();
    }

    @Test
    void nameReturnsPluginId() {
        assertThat(provider.name()).isEqualTo("remote-log");
    }

    @Test
    void schemaContainsToolType() {
        assertThat(provider.schema()).contains("\"log_read\"");
    }

    @Test
    void executeWithNullArgsReturnsError() {
        ToolResult result = provider.execute(null, null);
        assertThat(result.isError()).isTrue();
    }

    @Test
    void executeWithValidQueryReturnsSuccess() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        ToolResult result = provider.execute(args, null);
        assertThat(result.isSuccess()).isTrue();
    }
}
```

### Local Verification Flow

```bash
# 1. Compile + run tests
cd my-remote-log-plugin
mvn clean test

# 2. Package
mvn package

# 3. Upload to local SnapAgent instance
curl -X POST http://localhost:8080/skills-agent/tools/plugins/upload \
  -F "jar=@target/my-remote-log-plugin-1.0.0-SNAPSHOT.jar"

# 4. Verify registration
curl http://localhost:8080/skills-agent/tools/plugins

# 5. Execute skill to verify
curl -X POST http://localhost:8080/skills-agent/runs \
  -H "Content-Type: application/json" \
  -d '{"skillName":"log-analysis","inputs":{"keyword":"test"},"pluginOverrides":{"log_read":"remote-log"}}'
```

---

## 9. Limitations & Best Practices

### ClassLoader Unloading

Java ClassLoader GC **does not guarantee immediate reclamation**. After unregistering a plugin, its `URLClassLoader` is closed and awaits GC, but static state, threads, or unclosed resources may leak.

- **Recommended**: use `disable` instead of `unregister` in production
- **Monitor**: watch ClassLoader count; investigate unexpected growth

### ThreadLocal

Plugins execute in a separate `URLClassLoader` and **cannot access the host application's ThreadLocal**. All request-scoped information (userId, taskId) must be obtained from `ToolContext`:

```java
@Override
public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
    String userId = ctx.getUserId();   // correct
    // String userId = (String) ThreadLocalContext.get();  // wrong - unavailable
}
```

### ToolResult.content Must Be String

`ToolResult.getContent()` returns `String`, ensuring cross-ClassLoader safety. Do not attempt to return custom objects.

### Threads

Do NOT start **non-daemon threads** inside a Plugin. Non-daemon threads prevent JVM shutdown.

### Dependency Compatibility

Plugins can access host Spring Beans (e.g., `DataSource`). Ensure host Bean versions are compatible with the plugin's compile-time dependencies. The `snap-agent-core` API stable version guarantees backward compatibility.
