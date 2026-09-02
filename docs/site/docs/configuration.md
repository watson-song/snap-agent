# Configuration Reference

All configuration properties under the `snap-agent` prefix.

## Core

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Master switch. When false, no beans are created. |
| `base-path` | string | `/snap-agent` | REST API path prefix |
| `builtin-skills-dir` | string | `classpath*:/docs/skills/` | Classpath directory for built-in skills |
| `upload-skills-dir` | string | `/tmp/snap-agent-skills` | Filesystem directory for uploaded skills |
| `default-skill` | string | `""` | Default skill when no skillId is provided |
| `app-profiles` | string | `""` | Active Spring profiles (auto-resolved) |
| `conversation-store` | string | `file` | Conversation backend: `file`, `jdbc`, or `redis` |

## LLM

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `llm.provider` | string | `anthropic` | LLM provider: `anthropic`, `openai`, `bridge`, `fallback` |
| `llm.api-key` | string | — | API key for the provider |
| `llm.model` | string | `claude-sonnet-4-20250514` | Model ID |
| `llm.base-url` | string | — | Custom API base URL |
| `llm.max-tokens` | int | `4096` | Max response tokens |
| `llm.temperature` | double | `0.7` | Sampling temperature |
| `llm.timeout-seconds` | int | `120` | Request timeout |

### Fallback LLM

```yaml
snap-agent:
  llm:
    provider: fallback
    fallback:
      primary:
        provider: anthropic
        api-key: ${ANTHROPIC_KEY}
        model: claude-sonnet-4-20250514
      secondary:
        provider: openai
        api-key: ${OPENAI_KEY}
        model: gpt-4o
```

### Bridge LLM

Route LLM calls through another SnapAgent instance:

```yaml
snap-agent:
  llm:
    provider: bridge
    bridge:
      url: http://snap-agent-host:8080/snap-agent
```

## Agent

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agent.max-iterations` | int | `20` | Max ReAct loop iterations per run |
| `agent.system-prompt` | string | — | Global system prompt prefix |
| `agent.temperature` | double | — | Override LLM temperature (null = use llm.temperature) |

## Checkpoint (Cluster Support)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `checkpoint.store` | string | `memory` | Backend: `memory`, `file`, `jdbc`, `redis` |

### JDBC Checkpoint

```yaml
snap-agent:
  checkpoint:
    store: jdbc
```

Auto-creates `snap_agent_checkpoints` table. Uses your existing `DataSource`.

### Redis Checkpoint

```yaml
snap-agent:
  checkpoint:
    store: redis
```

Uses your existing `StringRedisTemplate`.

## JDBC Tool

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `jdbc.read-only` | boolean | `true` | Restrict SQL tool to read-only |
| `jdbc.max-rows` | int | `100` | Maximum rows returned |
| `jdbc.query-timeout` | int | `30` | Query timeout in seconds |
| `jdbc.allowed-tables` | list | — | Whitelist of accessible tables |
| `jdbc.blocked-tables` | list | — | Blacklist of blocked tables |

## Redis Tool

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `redis.enabled` | boolean | `true` | Enable Redis tool |
| `redis.read-only` | boolean | `true` | Restrict to read operations |
| `redis.max-keys` | int | `50` | Max keys returned per SCAN |

## Log Tool

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `logs.enabled` | boolean | `true` | Enable log reading tool |
| `logs.max-lines` | int | `500` | Max lines per read |
| `logs.allowed-paths` | list | — | Allowed log directories |

## Code Tool

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `code.enabled` | boolean | `true` | Enable code reading tool |
| `code.allowed-paths` | list | — | Allowed source directories |
| `code.max-lines` | int | `200` | Max lines per file read |

## Knowledge Base

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `knowledge.enabled` | boolean | `false` | Enable knowledge base |
| `knowledge.embedding-model` | string | — | Embedding model ID |
| `knowledge.chunk-size` | int | `500` | Document chunk size (tokens) |
| `knowledge.chunk-overlap` | int | `50` | Overlap between chunks |
| `knowledge.top-k` | int | `5` | Number of results for similarity search |

### Vector Store

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `vector-store.type` | string | `memory` | Backend: `memory` or `jdbc` |

### Embedding

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `embedding.provider` | string | — | Embedding provider |
| `embedding.api-key` | string | — | API key |
| `embedding.model` | string | — | Model ID |

## Security

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `security.enabled` | boolean | `true` | Enable security integration |
| `security.required-permission` | string | `snap-agent:access` | Permission required for API access |
| `security.audit-enabled` | boolean | `true` | Enable audit logging |

## Patrol & Alert

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `patrol.enabled` | boolean | `false` | Enable patrol scheduler |
| `patrol.thread-pool-size` | int | `2` | Patrol execution thread pool |
| `alert.enabled` | boolean | `true` | Enable alert system |
| `alert.auto-resolve` | boolean | `false` | Auto-resolve alerts on next successful patrol |

## Issue Closure

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `issue-closure.enabled` | boolean | `false` | Enable automatic issue creation |
| `issue-closure.tracker` | string | — | Tracker type: `github`, `jira`, `chandao` |
| `issue-closure.repo` | string | — | Repository path (GitHub) |
| `issue-closure.project-key` | string | — | Project key (Jira) |

## Cost Tracking

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `cost.enabled` | boolean | `true` | Enable cost tracking |
| `cost.budget.daily-limit` | double | `0` | Daily budget limit (0 = unlimited) |
| `cost.budget.per-run-limit` | double | `0` | Per-run budget limit (0 = unlimited) |

## Metrics (Observability)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `metrics.enabled` | boolean | `true` | Enable Micrometer metrics |
| `metrics.prefix` | string | `snap-agent` | Metric name prefix |

Emitted metrics:

- `snap-agent.graph.node.duration` — Timer per graph node execution
- `snap-agent.llm.tokens` — Counter for token usage (tagged: `type=input/output`)
- `snap-agent.tool.calls` — Counter for tool invocations
- `snap-agent.errors` — Counter for errors (tagged: `errorType`)

## Memory

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `memory.max-messages` | int | `20` | Max messages in conversation window |
| `memory.summarize-enabled` | boolean | `false` | Enable message summarization |
| `memory.summarize-threshold` | int | `10` | Messages before triggering summary |

## Routing

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `routing.enabled` | boolean | `false` | Enable skill auto-routing |
| `routing.mode` | string | `llm` | Routing strategy: `llm` or `keyword` |

## Anchor (Context Injection)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `anchor.enabled` | boolean | `true` | Enable anchor injection system |
| `anchor.auto-detect` | boolean | `true` | Auto-detect anchor placeholders |

## Complete Example

```yaml
snap-agent:
  enabled: true
  base-path: /snap-agent
  default-skill: general-chat

  llm:
    provider: anthropic
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-20250514
    max-tokens: 4096
    temperature: 0.7

  agent:
    max-iterations: 20

  checkpoint:
    store: jdbc            # cluster-safe

  conversation-store: jdbc  # cluster-safe

  cost:
    enabled: true
    budget:
      daily-limit: 50.00
      per-run-limit: 5.00

  patrol:
    enabled: true

  security:
    enabled: true
    required-permission: snap-agent:access

  metrics:
    enabled: true           # Micrometer integration
```
