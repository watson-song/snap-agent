# Getting Started

Get SnapAgent running in your Spring Boot application in under 5 minutes.

## Prerequisites

- Java 8+ (Spring Boot 2.x) or Java 17+ (Spring Boot 3.x)
- Maven or Gradle
- An LLM API key (Anthropic, OpenAI, or compatible)

## 1. Add the Dependency

=== "Spring Boot 2.x (Java 8+)"

    ```xml
    <dependency>
      <groupId>cn.watsontech</groupId>
      <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
      <version>2.0.0</version>
    </dependency>
    ```

=== "Spring Boot 3.x (Java 17+)"

    ```xml
    <dependency>
      <groupId>cn.watsontech</groupId>
      <artifactId>snap-agent-spring-boot-3x-starter</artifactId>
      <version>2.0.0</version>
    </dependency>
    ```

## 2. Configure

Add to your `application.yml`:

```yaml
snap-agent:
  enabled: true
  llm:
    provider: anthropic          # or openai
    api-key: ${ANTHROPIC_API_KEY}
    model: claude-sonnet-4-20250514
```

That's the minimum. SnapAgent auto-configures all other components with sensible defaults.

## 3. Create Your First Skill

Create `docs/skills/hello.md` in your project resources:

```markdown
---
name: hello
description: "A simple greeting skill"
inputs:
  - key: name
    label: Your name
    required: true
---

# Hello Skill

Greet the user warmly. Use their name from the input: {name}.

Keep it brief and friendly.
```

SnapAgent auto-detects skill files and hot-reloads them without restart.

## 4. Run

Start your Spring Boot application. SnapAgent exposes:

- **REST API** at `http://localhost:8080/snap-agent/skills`
- **Built-in UI** at `http://localhost:8080/snap-agent/`

### Try the REST API

```bash
# List available skills
curl http://localhost:8080/snap-agent/skills

# Run a skill
curl -X POST http://localhost:8080/snap-agent/runs \
  -H "Content-Type: application/json" \
  -d '{
    "skillId": "hello",
    "inputs": { "name": "World" }
  }'
```

### Try the Built-in UI

Open `http://localhost:8080/snap-agent/` in your browser. You'll see your skill listed. Click it, fill in the input, and chat.

## 5. What's Next?

- **[Concepts](concepts.md)** — Understand Skills, Tools, Agents, and the ReAct loop
- **[Configuration](configuration.md)** — Full configuration reference
- **[API Reference](api.md)** — All REST endpoints documented
- **[Deployment](deployment.md)** — Production deployment guide

## Integration Examples

### With Existing Spring Security

SnapAgent integrates with your existing authentication. No additional config needed if you already have Spring Security:

```java
// SnapAgent automatically picks up SecurityGateway
// Users must have 'snap-agent:access' permission by default
```

### With JDBC Database

```yaml
snap-agent:
  conversation-store: jdbc   # "file" (default), "jdbc", or "redis"
  jdbc:
    read-only: true           # Safety: SQL tool is read-only by default
```

### With Redis

```yaml
snap-agent:
  conversation-store: redis
  redis:
    host: localhost
    port: 6379
```
