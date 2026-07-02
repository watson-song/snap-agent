# SnapAgent

> 嵌入式 AI 技能框架 — 一键为 Spring Boot 应用接入 Agent 能力，用 Markdown 定义技能，用自然语言驱动。

[![Java 8](https://img.shields.io/badge/Java-8-orange.svg)](https://adoptium.net/)
[![Spring Boot 2.5.15](https://img.shields.io/badge/Spring%20Boot-2.5.15-green.svg)](https://spring.io/projects/spring-boot)
[![Version](https://img.shields.io/badge/version-0.1--alpha-blue.svg)]()
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | [中文](README_zh.md)

## 为什么叫 SnapAgent

> Snap = 快照 + 喀嚓（拟声词）

- **Snap** — 像"喀嚓"一声拍照，**一键**嵌入、**即拍即得**。没有脚手架、没有生成器、没有独立服务，加一个依赖就完成接入。
- **Agent** — 核心：LLM 多轮推理 + 工具调用 + 技能驱动。
- 合起来：**SnapAgent** = 即插即用的 AI 技能代理。读音干脆利落，不带任何业务领域色彩。

## 这是什么

SnapAgent 是一个**嵌入式 AI 技能框架**。它让你在任何现有或新的 Spring Boot 项目中，通过添加一个 Maven 依赖 + 几行配置，立刻获得：

- **Agent 对话能力** — 用户用自然语言提问，Agent 理解意图、调用工具、流式输出结果
- **技能系统** — 用 Markdown 编写技能文件，定义 Agent 在特定场景下的行为流程，丢进目录即生效
- **工具生态** — 内置数据库只读查询、Redis 读取等工具；实现 `ToolProvider` 接口即可扩展任意工具
- **开箱即用 UI** — Chat 风格 SPA，SSE 实时流式输出，无需前端构建
- **安全护栏** — `SqlGuard` 强制只读、限流、审计；`SecurityGateway` SPI 对接宿主鉴权

### 不只是查库

"查数据库"只是当前内置的一个工具。框架的设计是**技能驱动 + 工具可扩展**的通用 Agent 平台：

| 场景 | 怎么用 |
|------|--------|
| **业务数据诊断** | 编写 Skill 引导 Agent 查库分析，如"某 SKU 为什么没生成补货策略" |
| **运营诊断** | Skill 定义运营排查流程，Agent 按流程查指标、看趋势、定位异常 |
| **代码分析** | 开发 `CodeReaderToolProvider`，让 Agent 读懂项目代码，回答"这个接口在哪实现" |
| **线上监控** | 开发 `MetricsToolProvider`，Agent 实时查 Prometheus/Grafana 指标，分析健康度 |
| **异常诊断 & Bugfix** | 开发 `LogAnalysisToolProvider`，Agent 分析异常日志，定位根因，推送修复建议 |
| **任何自定义场景** | 实现 `ToolProvider` + 编写 Skill Markdown，Agent 即刻获得新能力 |

## 快速开始

### 1. 安装

**方式 A — 一键安装（从 GitHub Release 下载预构建 JAR）：**

```bash
bash <(curl -sL https://github.com/watson-song/snap-agent/releases/download/v0.1-alpha/install.sh)
```

**方式 B — 从源码构建：**

```bash
git clone https://github.com/watson-song/snap-agent.git && cd snap-agent && mvn clean install -DskipTests
```

### 2. 添加依赖

```xml
<dependency>
    <groupId>com.watsontech.snapagent</groupId>
    <artifactId>snap-agent-spring-boot-2x-starter</artifactId>
    <version>0.1-alpha</version>
</dependency>
```

### 3. 配置

```yaml
snap-agent:
  enabled: true
  llm:
    base-url: https://api.anthropic.com
    api-key: ${LLM_API_KEY}
    model: claude-sonnet-4-6
  jdbc:
    enabled: true          # 数据库只读查询
  redis:
    enabled: true          # Redis 只读查询
```

### 4. 提供只读 DataSource（如启用 JDBC 工具）

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

### 5. 编写第一个 Skill

在 `skills-dir` 目录下创建 `my-skill.md`：

```markdown
---
name: my-skill
description: "描述这个技能做什么、何时使用。LLM 根据这段描述决定何时激活此技能。"
---

# 技能流程

## Step 1: 收集信息
向用户确认必要参数...

## Step 2: 执行分析
使用 query_database 工具查询数据...
或使用你自定义的工具...

## Step 3: 输出结论
...
```

### 6. 启动 & 使用

打开 `http://localhost:8080/snap-agent/`，选择 Skill，输入问题，Agent 开始工作。

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                   宿主 Spring Boot App                     │
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

**核心设计：**

- **Skill（技能）** = Markdown 文件，定义 Agent 在特定场景的行为流程。YAML frontmatter 描述元数据，body 描述步骤。LLM 根据用户问题自动选择匹配的 Skill。
- **Tool（工具）** = `ToolProvider` SPI 实现，给 Agent 提供与外部系统交互的能力。内置 JDBC/Redis，可无限扩展。
- **Agent（执行器）** = `AgentExecutor` 多轮循环：LLM 思考 → 调用工具 → 获取结果 → 继续思考 → 输出结论。全程 SSE 流式推送。
- **Framework（框架）** = Starter 自动装配一切：Controller、Filter、线程池、安全适配、跨 Pod 路由。`enabled=false` 时零影响。

## 核心特性

| 特性 | 说明 |
|------|------|
| **技能驱动** | Markdown 定义 Agent 行为，无需写代码，丢文件即生效 |
| **工具可扩展** | `ToolProvider` SPI + `@Component` 自动发现，内置 JDBC/Redis |
| **SSE 实时流** | token 级推送思考过程，用户看着 Agent 一步步推导 |
| **安全护栏** | SqlGuard 只读强制 + 限流 + 审计 transcript |
| **安全适配** | 自动检测 Spring Security / Shiro，或自定义 `PrincipalResolver` |
| **零影响** | `enabled=false` 时不创建任何 Bean、Filter、线程池 |
| **跨 Pod 路由** | K8s 多实例 SSE 中继，自动发现持有任务的 Pod |
| **Chat SPA** | 开箱即用 Web UI，Markdown 渲染，无构建工具 |

## 配置参考

```yaml
snap-agent:
  enabled: true                          # 总开关，默认 false
  base-path: /snap-agent                 # URL 前缀
  skills-dir: classpath:/skills/         # Skill 目录（classpath 或文件系统路径）
  llm:
    base-url: https://api.anthropic.com
    api-key: ""                          # 推荐用环境变量 ${LLM_API_KEY}
    auth-token: ""                       # 或 Auth Token
    model: claude-sonnet-4-6
    max-tokens: 8192
    timeout-seconds: 120
    streaming: true
  agent:
    max-turns: 20                        # Agent 最大对话轮数
    task-timeout-minutes: 30
    max-concurrent-runs-per-user: 1
    max-runs-per-hour: 20
    max-result-rows: 1000                # 工具结果行数上限
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

## 扩展：自定义工具

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
        // 你的逻辑
        return ToolResult.success("result", 0, 42);
    }
}
```

工具自动被 `ToolDispatcher` 收集，LLM 可在 Skill 执行中按需调用。

## API 端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/snap-agent/` | Web UI (SPA) |
| GET | `/snap-agent/skills` | 列出所有技能 |
| GET | `/snap-agent/models` | 列出可用模型 |
| GET | `/snap-agent/tools` | 列出可用工具 |
| POST | `/snap-agent/runs` | 创建 Agent 任务 |
| GET | `/snap-agent/runs/{id}` | 查询任务状态 |
| GET | `/snap-agent/runs/{id}/stream` | SSE 实时流 |
| GET | `/snap-agent/runs/{id}/transcript` | 完整 transcript |
| POST | `/snap-agent/skills/refresh` | 刷新技能目录 |
| POST | `/snap-agent/skills/upload` | 上传 .md / .zip |

## 路线图

### v0.1-alpha（当前）

- 嵌入式框架核心：AgentExecutor 多轮循环 + LLM 流式 + SSE 推送
- Skill Markdown 系统：YAML frontmatter + 步骤式 body
- 内置工具：JDBC 只读查询 (SqlGuard) + Redis 只读
- 安全适配：Spring Security / Shiro 自动检测
- 跨 Pod SSE 中继：K8s API / Headless DNS / Static
- Chat SPA + Markdown 渲染

### v0.2 — 框架增强

- MCP 协议接入：远程工具以 `mcp__{server}__{tool}` 注册
- 任务取消：`POST /runs/{id}/cancel`
- 报告导出：`GET /runs/{id}/report?format=md`
- Skill 热重载：WatchService 监听文件变更
- 审计持久化：ring-buffer → 可选 DB 表

### v0.3 — 代码理解能力

- **CodeReaderToolProvider**：让 Agent 读取项目源码，回答"这个接口在哪实现"
- **项目上下文注入**：Agent 启动时扫描项目结构，作为系统提示注入 LLM
- **Skill 自动生成**：Agent 分析项目代码 + 数据库结构，自动生成候选 Skill

### v0.4 — 运营诊断能力

- **MetricsToolProvider**：对接 Prometheus / Grafana，Agent 实时查询指标
- **LogAnalysisToolProvider**：对接 ELK / Loki，Agent 分析日志模式、定位异常
- **TraceAnalysisToolProvider**：对接 Jaeger / SkyWalking，Agent 分析调用链瓶颈
- 运营诊断 Skill 模板：通用健康检查、慢查询分析、错误率突增排查

### v0.5 — 主动监控 & 智能推送

- **异常监听**：对接消息队列（Kafka/RabbitMQ），消费异常事件，自动触发诊断
- **健康巡检**：定时执行健康检查 Skill，发现异常自动生成诊断报告
- **Bugfix 推送**：Agent 诊断后结合代码分析，生成修复建议（diff 级别），推送到 Issue/工单系统
- **告警收敛**：同类异常聚合，减少噪声告警，附带根因分析

### v0.6 — 平台化

- Spring Boot 3.x 支持（`jakarta.servlet`）
- OpenAI / 其他 LLM 适配器
- 多环境数据源切换（sit/uat/prod）
- 任务历史 & 搜索
- Skill 市场：社区共享 Skill 模板
- 插件化工具市场：开箱即用的 ToolProvider 扩展包

## 测试

```bash
# 全量测试 + 覆盖率
mvn clean verify

# 仅 core 模块
mvn test -pl snap-agent-core

# 仅 SqlGuard 测试
mvn test -pl snap-agent-spring-boot-2x-starter -Dtest=SqlGuardTest
```

## 技术栈

- Java 8 / Spring Boot 2.5.15 / javax.servlet
- OkHttp 3 (LLM streaming) / Jackson / HikariCP
- JUnit 5 / AssertJ / Mockito / JaCoCo (覆盖率 >= 85%)
- Vanilla JS + SSE (前端，无构建工具)

## License

Apache License 2.0 — 见 [LICENSE](LICENSE)。
