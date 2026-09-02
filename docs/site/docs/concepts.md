# Concepts

Core concepts that power SnapAgent's agent framework.

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   Host Application                    │
│  ┌───────────────────────────────────────────────┐  │
│  │           SnapAgent Auto-Configuration         │  │
│  │  ┌─────────┐  ┌────────┐  ┌──────────────┐  │  │
│  │  │  Agent  │  │  LLM   │  │    Tools     │  │  │
│  │  │ Service │──│ Client │  │  (JDBC,Redis │  │  │
│  │  │         │  │  (SPI) │  │   Log,Git…)  │  │  │
│  │  └────┬────┘  └────────┘  └──────────────┘  │  │
│  │       │                                       │  │
│  │  ┌────▼────────────────────────────────────┐  │  │
│  │  │          StateGraph (ReAct)              │  │  │
│  │  │  entry → agent ↔ tools → END            │  │  │
│  │  └─────────────────────────────────────────┘  │  │
│  │  ┌──────────┐  ┌──────────┐  ┌────────────┐  │  │
│  │  │  Skill   │  │  Memory  │  │  Security  │  │  │
│  │  │ Registry │  │          │  │  Gateway   │  │  │
│  │  └──────────┘  └──────────┘  └────────────┘  │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## Skills

A **Skill** is a Markdown file that defines an agent's behavior. Think of it as a job description for the AI.

### Skill Structure

```markdown
---
name: log-analysis
description: "Analyzes application logs to find errors"
tools: [log_read, sql_read]
inputs:
  - key: environment
    label: Environment
    required: true
shortcuts:
  - label: "🔴 Recent Errors"
    message: "Show recent ERROR logs"
---

# Log Analysis

You are a senior SRE. Analyze log files to answer questions.

## Step 1: Determine the Log Path
The log file is at `{_app_log_file}`. Always try this first.

## Step 2: Search for Errors
Use the `log_read` tool to read relevant log segments.
```

### Frontmatter Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique skill identifier |
| `description` | Yes | What this skill does (shown in UI) |
| `tools` | No | Allowed tool names (empty = all tools) |
| `inputs` | No | User input specifications |
| `shortcuts` | No | Quick-action buttons for the UI |
| `mode` | No | `chat` (default) or `once` (single response) |
| `requiredPermission` | No | Permission needed to use this skill |

### Skill Discovery

SnapAgent loads skills from two locations:

1. **Built-in skills** — `classpath*:/docs/skills/` (read-only, packaged in JAR)
2. **Uploaded skills** — `/tmp/snap-agent-skills/` (read-write, survives restarts)

Skills hot-reload when files change. No restart needed.

## Tools

**Tools** are functions the agent can call during execution. SnapAgent provides built-in tools and a plugin SPI for custom ones.

### Built-in Tools

| Tool | Description |
|------|-------------|
| `sql_read` | Execute read-only SQL queries |
| `redis_read` | Read Redis keys |
| `log_read` | Read application log files |
| `code_read` | Read source code files |
| `git_read` | Git operations (log, diff, blame) |
| `metrics_read` | Read application metrics |
| `config_read` | Read configuration properties |

### Custom Tools with @Tool

```java
@Component
public class OrderTools {

    @Tool(name = "order_lookup", description = "Look up an order by ID")
    public String lookupOrder(@Param("orderId") String orderId) {
        return orderService.findById(orderId).toString();
    }
}
```

### ToolPlugin SPI

For more complex tools, implement the `ToolPlugin` interface:

```java
@Component
public class WeatherPlugin implements ToolPlugin {

    @Override
    public PluginDescriptor getDescriptor() {
        return PluginDescriptor.builder()
            .name("weather")
            .description("Get weather information")
            .build();
    }

    @Override
    public List<ToolDef> getToolDefinitions() {
        return Arrays.asList(
            ToolDef.builder()
                .name("get_weather")
                .description("Get current weather for a city")
                .inputSchema("{...}")
                .build()
        );
    }

    @Override
    public String execute(String toolName, String arguments) {
        // implementation
    }
}
```

## The ReAct Loop

SnapAgent uses the **ReAct** (Reasoning + Acting) pattern for agent execution:

```
         ┌──────────┐
         │  Entry   │
         └────┬─────┘
              │
         ┌────▼─────┐
    ┌───▶│  Agent   │◀──── think + decide
    │    └────┬─────┘
    │         │ tool_use?
    │    ┌────▼─────┐
    │    │  Tools   │──── execute
    │    └────┬─────┘
    │         │ result
    └─────────┘
              │
         ┌────▼─────┐
         │   END    │
         └──────────┘
```

1. **Entry** — Prepare context: skill prompt, conversation history, tool definitions
2. **Agent** — LLM reasons about the task, decides to use a tool or respond
3. **Tools** — If tool_use, execute the tool and return results to Agent
4. **Repeat** — Agent sees tool results, continues reasoning
5. **End** — Agent produces final response

## StateGraph

The underlying execution engine is a **StateGraph** — a directed graph with conditional edges:

```java
StateGraph graph = new StateGraph()
    .addNode("entry", entryNode)
    .addNode("agent", agentNode)
    .addNode("tools", toolsNode)
    .addEdge("entry", "agent")
    .addConditionalEdge("agent", state -> {
        if (state.hasToolUse()) return "tools";
        return StateGraph.END;
    })
    .addEdge("tools", "agent");  // loop back

CompiledGraph compiled = graph.compile();
GraphState result = compiled.invoke(initialState);
```

### CheckpointStore

Graph state is persisted via `CheckpointStore` for conversation continuity:

| Backend | Class | Use Case |
|---------|-------|----------|
| In-Memory | `InMemoryCheckpointStore` | Development, single-instance |
| File | `FileCheckpointStore` | Development, persistence across restarts |
| JDBC | `JdbcCheckpointStore` | Production, cluster deployments |
| Redis | `RedisCheckpointStore` | Production, high-throughput |

## Conversation Store

Manages multi-turn conversation persistence:

```yaml
snap-agent:
  conversation-store: file  # file | jdbc | redis
```

| Backend | Description |
|---------|-------------|
| `file` | Local JSON files (default, zero-config) |
| `jdbc` | Database-backed (uses your existing DataSource) |
| `redis` | Redis-backed (fast, distributed) |

## Memory

SnapAgent provides conversation memory with optional summarization:

- **ChatMemory** — Sliding window of recent messages
- **SummarizingChatMemory** — Summarizes older messages to reduce token usage
- **LongTermMemoryAdvisor** — Persistent project facts and user profiles

## Security

### SecurityGateway SPI

Integrates with your existing authentication:

```java
public interface SecurityGateway {
    UserInfo getCurrentUser();
    boolean hasPermission(String permission);
}
```

Implementations for Spring Security and Shiro are auto-configured.

### SQL Safety

The `sql_read` tool enforces:

- **Read-only** — Only SELECT statements allowed
- **SqlGuard** — Blocklist of dangerous keywords (DROP, DELETE, UPDATE, INSERT)
- **Timeout** — Configurable query timeout

### Path Safety

File-reading tools enforce:

- **Path traversal protection** — No `../` escapes
- **Allowlist** — Only configured directories accessible
- **Symlink detection** — Prevents symlink-based escapes

## Patrol & Alerts

**Patrol** schedules automated agent runs:

```yaml
snap-agent:
  patrol:
    enabled: true
    tasks:
      - name: health-check
        skill: ops-health-check
        cron: "0 */6 * * *"    # Every 6 hours
        enabled: true
```

When a patrol detects an anomaly, it creates an **Alert** that can be:

- Viewed in the built-in UI
- Resolved manually or automatically
- Linked to issue trackers (GitHub Issues, Jira, etc.)

## Cost Tracking

```yaml
snap-agent:
  cost:
    enabled: true
    budget:
      daily-limit: 50.00       # USD
      per-run-limit: 5.00
```

Tracks token-level costs per run, per skill, per user. Enforces budget limits.
