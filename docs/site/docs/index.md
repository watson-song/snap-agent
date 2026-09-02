# SnapAgent

**Embedded AI Agent Framework for Spring Boot**

SnapAgent is a lightweight, embeddable AI agent framework that brings intelligent automation to your Java applications with a single Maven dependency.

## Why SnapAgent?

- **Truly Embedded** — One Maven dependency, no separate microservice needed
- **Skill Markdown** — Define agent behaviors in plain Markdown, readable by humans and machines
- **Proactive Patrol** — Not just reactive Q&A: schedule automated inspections and close the loop with issue trackers
- **Java 8+ Compatible** — Works with legacy enterprise systems (Spring Boot 2.x) and modern stacks (Spring Boot 3.x)

## Quick Example

```java
// 1. Add dependency
// <dependency>
//   <groupId>cn.watsontech</groupId>
//   <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
// </dependency>

// 2. Create a Skill (skills/diagnose.md)
// ---
// name: diagnose
// description: Diagnose application errors from logs
// inputs:
//   - key: error
//     label: Error message
//     required: true
// ---
// You are a senior SRE. Given the error message, analyze possible root causes.

// 3. Use it via REST API or the built-in UI
// POST /snap-agent/runs
// { "skillId": "diagnose", "inputs": { "error": "Connection refused..." } }
```

## Features at a Glance

| Feature | Description |
|---------|-------------|
| ReAct Agent | Standard Reasoning + Acting loop with tool integration |
| Skill System | Markdown-defined agent behaviors with hot-reload |
| Tool Framework | `@Tool` annotation, JDBC, Redis, Log, Git, Code tools built-in |
| Conversation Store | File, JDBC, Redis backends for session persistence |
| Knowledge Base | ETL pipeline for document ingestion + vector search |
| Patrol & Alert | Scheduled automated inspections with issue tracker integration |
| Cost Tracking | Token-level cost tracking with budget enforcement |
| Multi-LLM | Anthropic, OpenAI, Bridge, Fallback clients |

## Architecture

```
snap-agent-core          Pure Java interfaces, zero Spring dependency
    ├── graph/           StateGraph, CompiledGraph, GraphExecutor
    ├── llm/             LlmClient SPI
    ├── skill/           SkillMeta, SkillLoader, SkillRegistry
    ├── tool/            @Tool, ToolPlugin SPI, PluginRegistry
    ├── memory/          ChatMemory, Summarizer
    ├── security/        SecurityGateway SPI, AuditStore
    └── patrol/          PatrolScheduler, AnomalyEventListener

snap-agent-spring-boot-2x-starter   Spring Boot 2.x implementation
    ├── autoconfig/      13 auto-configurations
    ├── agent/           AgentService
    ├── llm/             Anthropic, OpenAI, Bridge, Fallback
    ├── tool/            JDBC, Redis, Log, Code, Git, MCP tools
    └── web/             REST controllers, SSE, static UI

snap-agent-spring-boot-3x-starter   Spring Boot 3.x (Jakarta EE 10)
```

## Modules

- **snap-agent-core** — Pure interfaces and abstractions, framework-agnostic
- **snap-agent-spring-boot-2x-starter** — Spring Boot 2.x starter (Java 8+)
- **snap-agent-spring-boot-3x-starter** — Spring Boot 3.x starter (Java 17+, Jakarta EE 10)

## License

Apache 2.0
