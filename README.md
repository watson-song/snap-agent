# SnapAgent

> Embedded AI skill framework — add Agent capabilities to any Spring Boot app with one dependency. Define skills in Markdown, drive them with natural language.

[![Java 8](https://img.shields.io/badge/Java-8-orange.svg)](https://adoptium.net/)
[![Spring Boot 2.5.15](https://img.shields.io/badge/Spring%20Boot-2.5.15-green.svg)](https://spring.io/projects/spring-boot)
[![Version](https://img.shields.io/badge/version-0.1--alpha-blue.svg)]()
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [中文](README_zh.md)

## Why "SnapAgent"

> Snap = snapshot + the sound of a camera shutter

- **Snap** — like a "snap" of a photo: **one click** to embed, **instantly ready**. No scaffolding, no generators, no standalone service. Add one Maven dependency and you're done.
- **Agent** — the core: LLM multi-turn reasoning + tool calling + skill-driven.
- Together: **SnapAgent** = plug-and-play AI skill agent. Crisp name, no domain-specific baggage.

## What Is This

SnapAgent is an **embedded AI skill framework**. Add one Maven dependency + a few lines of config to any existing or new Spring Boot project, and instantly gain:

- **Agent conversational ability** — Users ask questions in natural language; the Agent understands intent, calls tools, streams results
- **Skill system** — Write skill files in Markdown defining Agent behavior for specific scenarios. Drop into a directory and it's live
- **Tool ecosystem** — Built-in read-only database queries, Redis reads; implement `ToolProvider` to extend with any tool
- **Out-of-the-box UI** — Chat-style SPA with SSE real-time streaming, no frontend build step
- **Safety guardrails** — `SqlGuard` enforces read-only, rate limiting, audit; `SecurityGateway` SPI integrates with host authentication

### Beyond Database Queries

"Querying a database" is just one built-in tool. The framework is designed as a **skill-driven + tool-extensible** general-purpose Agent platform:

| Scenario | How |
|----------|-----|
| **Business data diagnostics** | Write a Skill guiding the Agent to query and analyze data, e.g. "Why wasn't a replenishment plan generated for this SKU?" |
| **Operations diagnostics** | Skill defines an ops troubleshooting flow; Agent checks metrics, trends, pinpoints anomalies |
| **Code analysis** | Develop `CodeReaderToolProvider` so the Agent reads project source, answers "where is this interface implemented?" |
| **Online monitoring** | Develop `MetricsToolProvider` for the Agent to query Prometheus/Grafana metrics in real time |
| **Anomaly diagnosis & bugfix** | Develop `LogAnalysisToolProvider` for log pattern analysis, root cause, fix suggestions |
| **Any custom scenario** | Implement `ToolProvider` + write a Skill Markdown — the Agent instantly gains new capabilities |

## Quick Start

### 1. Install

```bash
git clone <repo-url> snap-agent && cd snap-agent && mvn clean install -DskipTests
```

### 2. Add Dependency

```xml
<dependency>
    <groupId>com.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. Configure

```yaml
snap-agent:
  enabled: true
  llm:
    base-url: https://api.anthropic.com
    api-key: ${LLM_API_KEY}
    model: claude-sonnet-4-6
  jdbc:
    enabled: true          # read-only database queries
  redis:
    enabled: true          # read-only Redis queries
```

### 4. Provide a Read-Only DataSource (if JDBC tools are enabled)

```java
@Bean
public DataSource snapAgentReadOnlyDataSource() {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl("jdbc:mysql://your-db:3306/your_schema");
    ds.setUsername("readonly_user");
    ds.setPassword("readonly_pass");
    ds.setPoolName("snap-agent-jdbc");
    ds.setReadOnly(true);
    return ds;
}
```

### 5. Write Your First Skill

Create `my-skill.md` in your `skills-dir`:

```markdown
---
name: my-skill
description: "Describe what this skill does and when to use it. The LLM uses this to decide when to activate the skill."
---

# Skill Flow

## Step 1: Gather Information
Confirm necessary parameters with the user...

## Step 2: Execute Analysis
Use the query_database tool to query data...
or use your custom tools...

## Step 3: Output Conclusion
...
```

### 6. Launch & Use

Open `http://localhost:8080/snap-agent/`, select a Skill, type your question, and watch the Agent work.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                   Host Spring Boot App                     │
│                                                          │
│   ┌──────────────────────────────────────────────────┐    │
│   │            SnapAgent Framework                  │    │
│   │                                                  │    │
│   │   ┌────────────┐    ┌──────────────────┐        │    │
│   │   │  Web UI    │    │  AgentExecutor   │        │    │
│   │   │  (SPA+SSE) │────│  (Turn Loop)     │        │    │
│   │   └────────────┘    └────────┬─────────┘        │    │
│   │   ┌────────────┐    ┌────────┴─────────┐        │    │
│   │   │ SkillReg   │    │  LLM Client      │        │    │
│   │   │ (.md→Skill)│    │  (Streaming)     │        │    │
│   │   └────────────┘    └──────────────────┘        │    │
│   │   ┌──────────────────────────────────────┐      │    │
│   │   │       ToolDispatcher (SPI)            │      │    │
│   │   │  ┌────────────┐ ┌──────────────┐     │      │    │
│   │   │  │ JDBC Query │ │ Redis Read   │     │      │    │
│   │   │  │ + SqlGuard │ │              │     │      │    │
│   │   │  └────────────┘ └──────────────┘     │      │    │
│   │   │  ┌────────────┐ ┌──────────────┐     │      │    │
│   │   │  │ Code Reader│ │ Log Analyzer │     │      │    │
│   │   │  │ (planned)  │ │ (planned)    │     │      │    │
│   │   │  └────────────┘ └──────────────┘     │      │    │
│   │   │  ┌────────────────────────────────┐  │      │    │
│   │   │  │  Your Custom ToolProvider     │  │      │    │
│   │   │  │  (just @Component)            │  │      │    │
│   │   │  └────────────────────────────────┘  │      │    │
│   │   └──────────────────────────────────────┘      │    │
│   │                                                  │    │
│   │   snap-agent-core (SPI) ────────────────────────│    │
│   └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

**Core Design:**

- **Skill** = Markdown file defining Agent behavior for a specific scenario. YAML frontmatter for metadata, body for steps. The LLM auto-selects the matching Skill based on the user's question.
- **Tool** = `ToolProvider` SPI implementation giving the Agent abilities to interact with external systems. Built-in JDBC/Redis, infinitely extensible.
- **Agent** = `AgentExecutor` multi-turn loop: LLM thinks → calls tools → gets results → thinks more → outputs conclusion. SSE streaming throughout.
- **Framework** = Starter auto-configures everything: Controller, Filter, thread pool, security adapter, cross-pod routing. `enabled=false` = zero impact.

## Key Features

| Feature | Description |
|---------|-------------|
| **Skill-driven** | Markdown defines Agent behavior — no code needed, drop a file and it's live |
| **Tool-extensible** | `ToolProvider` SPI + `@Component` auto-discovery; built-in JDBC/Redis |
| **SSE real-time streaming** | Token-level push of the thinking process — watch the Agent reason step by step |
| **Safety guardrails** | SqlGuard read-only enforcement + rate limiting + audit transcript |
| **Security adapter** | Auto-detects Spring Security / Shiro, or custom `PrincipalResolver` |
| **Zero impact** | `enabled=false` creates no Beans, Filters, or thread pools |
| **Cross-pod routing** | K8s multi-instance SSE relay, auto-discovers the pod holding a task |
| **Chat SPA** | Out-of-the-box Web UI with Markdown rendering, no build tools |

## Configuration Reference

```yaml
snap-agent:
  enabled: true                          # master switch, default false
  base-path: /snap-agent                 # URL prefix
  skills-dir: classpath:/skills/         # Skill directory (classpath or filesystem path)
  llm:
    base-url: https://api.anthropic.com
    api-key: ""                          # recommend env var ${LLM_API_KEY}
    auth-token: ""                       # or Auth Token
    model: claude-sonnet-4-6
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
  agent:
    max-turns: 20                        # max Agent conversation turns
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 1
    max-runs-per-hour: 20
    max-result-rows: 1000                # tool result row limit
  jdbc:
    enabled: true
    datasource-bean-name: snapAgentReadOnlyDataSource
  redis:
    enabled: true
    redis-template-bean-name: redisTemplate
  security:
    framework: auto                      # auto / spring-security / shiro / custom
    audit-log: true
  routing:
    mode: none                           # none / static / k8s-api / headless-dns
```

## Extend: Custom Tools

```java
@Component
public class HttpCallToolProvider implements ToolProvider {
    @Override
    public String name() { return "http_call"; }

    @Override
    public String schema() {
        return "{\"name\":\"http_call\","
             + "\"description\":\"Calls an HTTP endpoint\","
             + "\"input_schema\":{\"type\":\"object\","
             + "\"properties\":{\"url\":{\"type\":\"string\"}},"
             + "\"required\":[\"url\"]}}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        // your logic
        return ToolResult.success("result", 0, 42);
    }
}
```

The tool is auto-discovered by `ToolDispatcher`. The LLM can call it during Skill execution as needed.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/snap-agent/` | Web UI (SPA) |
| GET | `/snap-agent/skills` | List all skills |
| GET | `/snap-agent/models` | List available models |
| GET | `/snap-agent/tools` | List available tools |
| POST | `/snap-agent/runs` | Create an Agent task |
| GET | `/snap-agent/runs/{id}` | Query task status |
| GET | `/snap-agent/runs/{id}/stream` | SSE real-time stream |
| GET | `/snap-agent/runs/{id}/transcript` | Full transcript |
| POST | `/snap-agent/skills/refresh` | Refresh skill directory |
| POST | `/snap-agent/skills/upload` | Upload .md / .zip |

## Roadmap

### v0.1-alpha (current)

- Embedded framework core: AgentExecutor multi-turn loop + LLM streaming + SSE push
- Skill Markdown system: YAML frontmatter + step-based body
- Built-in tools: JDBC read-only queries (SqlGuard) + Redis read-only
- Security adapter: Spring Security / Shiro auto-detection
- Cross-pod SSE relay: K8s API / Headless DNS / Static
- Chat SPA + Markdown rendering

### v0.2 — Framework Enhancement

- MCP protocol: remote tools registered as `mcp__{server}__{tool}`
- Task cancellation: `POST /runs/{id}/cancel`
- Report export: `GET /runs/{id}/report?format=md`
- Skill hot reload: WatchService monitors file changes
- Audit persistence: ring-buffer → optional DB table

### v0.3 — Code Understanding

- **CodeReaderToolProvider**: Agent reads project source code
- **ProjectStructureToolProvider**: scans project structure (modules, packages, key classes)
- **GitLogToolProvider**: reads git log / blame for code history
- Project context injection: auto-scan project structure as system prompt
- Skill auto-generation from code + database analysis

### v0.4 — Operations Diagnostics

- **MetricsToolProvider**: query Prometheus / Grafana metrics
- **LogAnalysisToolProvider**: search ELK / Loki logs, analyze error patterns
- **TraceAnalysisToolProvider**: analyze Jaeger / SkyWalking call chains
- Built-in Skill templates: health-check, slow-query-analysis, error-spike-investigation

### v0.5 — Proactive Monitoring & Bugfix Push

- Anomaly listener: consume Kafka/RabbitMQ events, auto-trigger diagnostic Skills
- Health patrol: scheduled health-check Skills, auto-generate diagnostic reports
- Bugfix push: Agent generates fix suggestions (diff-level), pushes to Issue/tracker
- Alert convergence: aggregate similar anomalies, reduce noise, attach root cause

### v0.6 — Platform

- Spring Boot 3.x support (`jakarta.servlet`)
- OpenAI / other LLM adapters
- Multi-environment datasource switching (sit/uat/prod)
- Task history & search
- Skill marketplace: community-shared Skill templates
- Plugin tool marketplace: ready-to-use ToolProvider extension packs

## Testing

```bash
# Full tests + coverage
mvn clean verify

# Core module only
mvn test -pl snap-agent-core

# SqlGuard tests only
mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=SqlGuardTest
```

## Tech Stack

- Java 8 / Spring Boot 2.5.15 / javax.servlet
- OkHttp 3 (LLM streaming) / Jackson / HikariCP
- JUnit 5 / AssertJ / Mockito / JaCoCo (coverage >= 85%)
- Vanilla JS + SSE (frontend, no build tools)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
