# Deployment

Production deployment guide for SnapAgent.

## Deployment Modes

### Embedded (Recommended)

SnapAgent runs inside your existing Spring Boot application. Zero additional infrastructure.

```yaml
# application.yml
snap-agent:
  enabled: true
  conversation-store: jdbc
  checkpoint:
    store: jdbc
```

### Standalone

Run SnapAgent as a standalone application using the `snap-agent-standalone` module:

```bash
java -jar snap-agent-standalone.jar \
  --snap-agent.enabled=true \
  --snap-agent.llm.api-key=${ANTHROPIC_API_KEY}
```

## Production Configuration

### 1. Enable Security

```yaml
snap-agent:
  security:
    enabled: true
    required-permission: snap-agent:access
    audit-enabled: true
```

Integrates with your existing Spring Security or Shiro configuration. Users without the required permission cannot access SnapAgent endpoints.

### 2. Use Distributed Storage

For multi-instance deployments, use JDBC or Redis backends:

```yaml
snap-agent:
  conversation-store: jdbc     # or redis
  checkpoint:
    store: jdbc                # or redis
```

**JDBC** — Uses your existing `DataSource`. Auto-creates tables on startup.

**Redis** — Uses your existing `StringRedisTemplate`. Lower latency than JDBC.

### 3. Set Budget Limits

```yaml
snap-agent:
  cost:
    enabled: true
    budget:
      daily-limit: 50.00       # USD per day
      per-run-limit: 5.00      # USD per run
```

### 4. Configure LLM Timeouts

```yaml
snap-agent:
  llm:
    timeout-seconds: 120       # Increase for complex tasks
  agent:
    max-iterations: 20         # Cap ReAct loop iterations
```

### 5. Enable Metrics

SnapAgent integrates with Micrometer for observability:

```yaml
snap-agent:
  metrics:
    enabled: true
```

Emitted metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `snap-agent.graph.node.duration` | Timer | Node execution time |
| `snap-agent.llm.tokens` | Counter | Token usage (input/output) |
| `snap-agent.tool.calls` | Counter | Tool invocations |
| `snap-agent.errors` | Counter | Errors by type |

Metrics are exported via your configured Micrometer registry (Prometheus, Datadog, etc.).

### 6. Configure Logging

SnapAgent logs at INFO level for key operations and ERROR for failures:

```yaml
logging:
  level:
    cn.watsontech.snapagent: INFO
```

For debugging:

```yaml
logging:
  level:
    cn.watsontech.snapagent: DEBUG
```

## Kubernetes

### Health Checks

SnapAgent doesn't add custom health indicators. Use your existing Spring Boot Actuator health checks.

### Resource Limits

Recommended starting points:

| Component | CPU | Memory |
|-----------|-----|--------|
| SnapAgent embedded | +0.1 cores | +128 MB |
| Knowledge base (vector search) | +0.2 cores | +256 MB |

### Environment Variables

```yaml
env:
  - name: ANTHROPIC_API_KEY
    valueFrom:
      secretKeyRef:
        name: snap-agent-secrets
        key: anthropic-api-key
  - name: SPRING_DATASOURCE_URL
    value: jdbc:postgresql://postgres:5432/myapp
```

### Multiple Replicas

With JDBC or Redis backends, SnapAgent is stateless and safe to scale horizontally:

```yaml
replicas: 3
```

Each instance shares conversation history and checkpoints via the database.

## Docker

### Embedded in Your Image

No changes needed. SnapAgent is just a Maven dependency.

### Standalone Image

```dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY snap-agent-standalone.jar /app/snap-agent.jar
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "snap-agent.jar"]
```

## Monitoring

### Prometheus

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
```

SnapAgent metrics appear under the `snap_agent_*` prefix.

### Grafana Dashboard

Import the sample dashboard from `docs/grafana/snap-agent.json` or build your own using these panels:

- **Token Usage** — `rate(snap_agent_llm_tokens_total[5m])` by type
- **Tool Calls** — `rate(snap_agent_tool_calls_total[5m])` by tool
- **Node Duration** — `histogram_quantile(0.95, snap_agent_graph_node_duration_seconds_bucket)`
- **Errors** — `rate(snap_agent_errors_total[5m])` by errorType
- **Cost** — Application-level cost tracking via REST API

## Upgrading

### 2.x to 3.x (Spring Boot 2.x → 3.x)

1. Update dependency:
   ```xml
   <artifactId>snap-agent-spring-boot-3x-starter</artifactId>
   ```

2. Ensure Java 17+ runtime

3. Jakarta EE namespace is handled automatically — no code changes needed

### Rolling Upgrade

SnapAgent supports rolling upgrades with distributed backends:

1. Deploy new version to one instance
2. Verify health check passes
3. Deploy to remaining instances

No data migration needed for JDBC/Redis backends.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Skills not loading | Check `snap-agent.builtin-skills-dir` path. Ensure files have `.md` extension. |
| LLM timeout | Increase `snap-agent.llm.timeout-seconds` |
| Permission denied | Check user has `snap-agent:access` permission |
| Budget exceeded | Increase `snap-agent.cost.budget.daily-limit` |
| Conversation lost | Switch from `file` to `jdbc` or `redis` backend |
| High memory usage | Enable conversation summarization: `snap-agent.memory.summarize-enabled: true` |
