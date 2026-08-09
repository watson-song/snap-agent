---
name: snap-agent-architecture
description: SnapAgent 核心架构设计 — Agent 引擎、Skill 系统、LlmClient SPI、Bridge 桥接
version: 1.0.0
modules:
  - snap-agent-core
  - snap-agent-spring-boot-2x-starter
author: SnapAgent
---

# SnapAgent 核心架构

## 1. 模块划分

```
snap-agent-parent
├── snap-agent-core              # 核心 SPI + 领域模型（无 Spring 依赖）
├── snap-agent-spring-boot-2x-starter  # Spring Boot 2.x 自动装配 + Web 端点
├── snap-agent-client            # 客户端 SDK（可选）
├── snap-agent-demo              # 示例项目（E2E 测试）
├── snap-agent-anchor-demo       # Anchor 功能演示
├── snap-agent-standalone        # 独立部署 JAR（含 Settings Page + LLM Bridge）
└── snap-agent-plugin-archetype  # 插件开发脚手架
```

## 2. 核心 SPI（snap-agent-core）

### LlmClient SPI

```java
public interface LlmClient {
    void stream(LlmRequest req, LlmEventSink events, String taskId);
    default void cancel(String taskId) {}
    default List<String> listModels() { return Collections.emptyList(); }
}
```

**实现类**：
- `AnthropicLlmClient` — Anthropic Messages API
- `OpenAiLlmClient` — OpenAI 兼容 API
- `BridgeLlmClient` — 通过浏览器 Bridge 调用 LLM
- `CostTrackingLlmClient` — 装饰器，统计 token 消耗

### Skill 系统

```
SkillMeta (元数据)
── name / description / version
├── tools: List<String>       // 声明依赖的工具
├── inputs: List<InputSpec>   // 输入参数规格
├── body: String              // Skill body（Markdown）
└── availability: AVAILABLE / UNAVAILABLE
```

**两层模型**：
- `builtin` — classpath 内置 skills（`docs/skills/`）
- `custom` — 上传目录 skills（可覆盖 builtin）

**热重载**：`SkillHotReloader` 监听文件系统变化，自动 refresh

### Agent 引擎

```
AgentService
  ── execute(taskId, skill, task)
        └── ReActGraphFactory.build(skill, task, advisors)
              └── EntryNode → ReActNode[] → ExitNode
```

**Advisors**（上下文增强）：
- `ProjectContextAdvisor` — 注入项目结构信息
- `SkillContextAdvisor` — 注入 skill 元数据
- `LongTermMemoryAdvisor` — 长期记忆注入
- `AnchorOrchestrator` — Anchor 锚点注入（v0.4+）

## 3. Spring Boot 2x 集成（snap-agent-spring-boot-2x-starter）

### 自动配置类

| 配置类 | 职责 |
|--------|------|
| `SnapAgentAutoConfiguration` | 核心 Bean：LlmClient、TaskStore、SkillRegistry、AgentService |
| `WebAutoConfiguration` | Web 层：SnapAgentController、ConversationStore |
| `ToolAutoConfiguration` | 工具注册：JDBC、Redis、Log 工具 |
| `SecurityAutoConfiguration` | 安全：SqlGuard、PrincipalResolver、AuditStore |
| `BridgeAutoConfiguration` | 桥接：IssueBridgeService、BridgeHttpExecutor |
| `LlmBridgeAutoConfiguration` | LLM 桥接：LlmBridgeService、BridgeLlmClient |

### REST 端点

| 端点 | 说明 |
|------|------|
| `GET /snap-agent/info` | 服务信息 + 功能开关 |
| `POST /snap-agent/runs` | 创建 Agent 任务 |
| `GET /snap-agent/runs/{id}` | 查询任务状态 |
| `GET /snap-agent/runs/{id}/stream` | SSE 流式输出 |
| `GET /snap-agent/runs/{id}/transcript` | 完整对话记录 |
| `GET /snap-agent/skills` | Skill 列表 |
| `POST /snap-agent/skills` | 上传 Skill |
| `GET /snap-agent/tools` | 工具列表 |
| `GET /snap-agent/models` | 模型列表 |
| `GET /snap-agent/bridge/llm/stream` | LLM Bridge SSE |
| `POST /snap-agent/bridge/llm/result` | LLM Bridge 结果回传 |

## 4. Bridge 桥接系统

### Issue Bridge（v1.0）
- 代理 Issue Tracker（禅道/Jira）和 VCS（GitLab）HTTP 请求
- 通过浏览器扩展绕过容器网络隔离

### LLM Bridge（v1.0）
- 将 LLM 调用路由到浏览器
- 支持多宿主隔离（按域名存储 LLM 配置）
- 前端 Simulator 模式用于测试

## 5. 配置属性

```yaml
snap-agent:
  enabled: true
  base-path: /snap-agent
  llm:
    api-type: anthropic | openai | bridge
    base-url: https://api.anthropic.com
    api-key: sk-...
    auth-token: 01414185
    model: claude-sonnet-4-20250514
    max-tokens: 8192
    timeout-seconds: 120
  agent:
    max-turns: 20
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 3
  bridge:
    enabled: false
    request-timeout-ms: 30000
  jdbc:
    enabled: false
    datasource-bean-name: dataSource
  security:
    framework: auto
    audit-log: true
  code-graph:
    enabled: false
    scan-packages:
      - cn.watsontech.snapagent
  knowledge:
    enabled: true
    sources:
      - type: markdown
        dir: classpath:/docs/knowledge/
```
